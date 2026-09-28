package techit.lagmonitor;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Uploads report files to a GitHub repository with the contents API, one new file per report
 * (reports/<date>/<file>). Runs on its own thread; the server thread never waits for it.
 *
 * Nothing is lost if GitHub is down, rate-limits us, or the server crashes before an upload
 * finishes: uploaded file names are remembered in uploaded.txt, and at startup and after every
 * periodic report any report still on disk and not uploaded is queued again.
 *
 * The token is only ever sent to api.github.com and never written to logs or reports.
 */
final class Uploader {
    /** GitHub's contents API is meant for files up to 1 MB; larger reports are uploaded shortened. */
    private static final int MAX_UPLOAD_BYTES = 900 * 1024;

    private final Settings settings;
    private final File dir;
    private final File uploadedList;
    private final Set<String> uploadedNames = Collections.synchronizedSet(new HashSet<String>());
    private final Set<String> queuedNames = Collections.synchronizedSet(new HashSet<String>());
    private final LinkedBlockingQueue<File> queue = new LinkedBlockingQueue<File>(1000);
    private Thread thread;
    private volatile boolean running;
    private volatile boolean disabled;
    private volatile int uploaded;
    /** Files whose upload failed and that haven't been uploaded since. */
    private final Set<String> failedNames = Collections.synchronizedSet(new HashSet<String>());
    private volatile String lastError = "";

    Uploader(Settings settings, File dir) {
        this.settings = settings;
        this.dir = dir;
        this.uploadedList = new File(dir, "uploaded.txt");
        if (settings.uploadRepository.indexOf('/') <= 0 || settings.uploadToken.length() == 0) {
            disabled = true;
            lastError = "upload is enabled but repository or token is missing in the config";
            Monitor.log(lastError);
        }
    }

    void start() {
        if (disabled) {
            return;
        }
        loadUploadedList();
        running = true;
        thread = new Thread(new Runnable() {
            public void run() {
                queueMissing();
                loop();
            }
        }, "Lag Monitor uploader");
        thread.setDaemon(true);
        thread.start();
    }

    void add(File f) {
        if (disabled || uploadedNames.contains(f.getName()) || !queuedNames.add(f.getName())) {
            return;
        }
        if (!queue.offer(f)) {
            queuedNames.remove(f.getName());
        }
    }

    /** Queues every report on disk that hasn't been uploaded yet (after a crash, an outage or a restart). */
    void queueMissing() {
        if (disabled) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        Arrays.sort(files);
        for (File f : files) {
            String n = f.getName();
            if (!f.isFile() || !n.endsWith(".txt") || n.startsWith("started-") || n.equals("uploaded.txt")) {
                continue;
            }
            if (n.startsWith("report-") && !settings.uploadPeriodicReports) {
                continue;
            }
            add(f);
        }
    }

    /** A crash is being written: nothing to do, the uploader thread keeps going until stop(). */
    void flushSoon() {
    }

    /** Waits up to the given time for waiting uploads to finish (server stopping or crashing). */
    void stop(long waitMillis) {
        long end = System.currentTimeMillis() + waitMillis;
        while (!disabled && !queue.isEmpty() && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                break;
            }
        }
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    String status() {
        if (disabled) {
            return "off (" + lastError + ")";
        }
        return uploaded + " uploaded, " + queue.size() + " waiting, " + failedNames.size() + " failed (retried later)"
            + (lastError.length() > 0 ? ", last error: " + lastError : "");
    }

    private void loop() {
        while (running && !disabled) {
            File f;
            try {
                f = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                if (!running) {
                    return;
                }
                continue;
            }
            if (f == null) {
                continue;
            }
            long[] waits = {0L, 5000L, 30000L, 120000L};
            boolean done = false;
            for (int attempt = 0; attempt < waits.length && !done && !disabled && running; attempt++) {
                if (waits[attempt] > 0) {
                    sleep(waits[attempt]);
                }
                done = uploadOne(f);
            }
            queuedNames.remove(f.getName());
            if (done) {
                failedNames.remove(f.getName());
            } else {
                // Stays on disk and not in uploaded.txt, so queueMissing() tries it again later.
                failedNames.add(f.getName());
            }
        }
    }

    /** Returns true when the file is uploaded (or already there, or gone from disk). */
    private boolean uploadOne(File f) {
        if (!f.exists()) {
            return true;
        }
        HttpURLConnection c = null;
        try {
            byte[] data = readAll(f);
            if (data.length > MAX_UPLOAD_BYTES) {
                byte[] note = ("\n\n[Lag Monitor: shortened for upload, " + data.length + " bytes in total. The full file is on the server.]\n").getBytes("UTF-8");
                byte[] shorter = new byte[MAX_UPLOAD_BYTES + note.length];
                System.arraycopy(data, 0, shorter, 0, MAX_UPLOAD_BYTES);
                System.arraycopy(note, 0, shorter, MAX_UPLOAD_BYTES, note.length);
                data = shorter;
            }
            String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date(f.lastModified()));
            String path = (settings.uploadFolder.length() > 0 ? settings.uploadFolder + "/" : "") + date + "/" + f.getName();
            URL url = new URL("https://api.github.com/repos/" + settings.uploadRepository + "/contents/" + path);
            String body = "{\"message\":\"Lag Monitor: " + f.getName() + "\",\"branch\":\"" + json(settings.uploadBranch)
                + "\",\"content\":\"" + javax.xml.bind.DatatypeConverter.printBase64Binary(data) + "\"}";
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("PUT");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "token " + settings.uploadToken);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "LagMonitor/" + LagMonitor.VERSION);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            OutputStream out = c.getOutputStream();
            out.write(body.getBytes("UTF-8"));
            out.close();
            int code = c.getResponseCode();
            if (code == 200 || code == 201 || code == 422) {
                // 422: a file with that name is already there (uploaded before)
                markUploaded(f.getName());
                if (code != 422) {
                    uploaded++;
                }
                lastError = "";
                return true;
            }
            String retryAfter = c.getHeaderField("Retry-After");
            String remaining = c.getHeaderField("X-RateLimit-Remaining");
            if (code == 429 || (code == 403 && (retryAfter != null || "0".equals(remaining)))) {
                // Rate limited: wait as asked (at most 10 minutes) and try again later.
                long wait = 60000L;
                try {
                    if (retryAfter != null) {
                        wait = Math.min(600000L, Math.max(1000L, Long.parseLong(retryAfter.trim()) * 1000L));
                    }
                } catch (NumberFormatException e) {
                    // keep the default
                }
                lastError = "GitHub rate limit, waiting " + wait / 1000 + " s";
                sleep(wait);
                return false;
            }
            lastError = "GitHub answered " + code + " for " + f.getName();
            if (code == 401 || code == 403 || code == 404) {
                disabled = true;
                lastError += " (check the token, its expiry date and the repository name; uploads stopped until restart,"
                    + " reports are still written on the server and are uploaded after the restart)";
                Monitor.log(lastError);
                return false;
            }
            return false;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            return false;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private void loadUploadedList() {
        if (!uploadedList.exists()) {
            return;
        }
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(uploadedList), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    // Only remember files still on disk: older ones were deleted and can't come back.
                    if (line.trim().length() > 0 && new File(dir, line.trim()).exists()) {
                        uploadedNames.add(line.trim());
                    }
                }
            } finally {
                r.close();
            }
            Writer w = new OutputStreamWriter(new FileOutputStream(uploadedList, false), "UTF-8");
            try {
                synchronized (uploadedNames) {
                    for (String n : uploadedNames) {
                        w.write(n + "\n");
                    }
                }
            } finally {
                w.close();
            }
        } catch (IOException e) {
            Monitor.log("could not read " + uploadedList + ": " + e);
        }
    }

    private synchronized void markUploaded(String name) {
        if (!uploadedNames.add(name)) {
            return;
        }
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(uploadedList, true), "UTF-8");
            try {
                w.write(name + "\n");
            } finally {
                w.close();
            }
        } catch (IOException e) {
            Monitor.log("could not update " + uploadedList + ": " + e);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // stopping
        }
    }

    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static byte[] readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
