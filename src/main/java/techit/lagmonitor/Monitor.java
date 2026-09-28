package techit.lagmonitor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * The running monitor: tick timing (on the server thread, kept tiny), a sampler thread that
 * snapshots the server thread's stack while it runs a tick, and a writer thread for report files.
 * Nothing slow ever happens on the server thread except when writing a crash snapshot.
 */
final class Monitor {
    private static volatile Monitor s_current;

    static Monitor current() {
        return s_current;
    }

    static void setCurrent(Monitor m) {
        s_current = m;
    }

    final Settings settings;
    final File dir;
    private final Uploader uploader;
    private final ExecutorService writer;
    private Thread sampler;
    private volatile boolean running;

    // ---- the tick in progress (written by the server thread, read by the sampler) ----
    private volatile Thread serverThread;
    private volatile boolean inTick;
    private volatile long tickStartNanos;
    private volatile long tickNumber;
    private final List<StackTraceElement[]> tickSamples = new ArrayList<StackTraceElement[]>();
    private volatile long pendingStallMillis;
    private volatile boolean pendingCrash;
    private boolean crashWaitLogged;

    // ---- the current report window, guarded by this ----
    private Window window;
    private long lastTickEndNanos;
    private volatile long lastTickAtNanos;
    private volatile long startedNanos;
    /** The last 1,200 tick durations (about a minute) in microseconds, for crash snapshots. */
    private final int[] history = new int[1200];
    private int historyPos;
    private int historyCount;
    private long spikeHourStart;
    private int spikesThisHour;

    // ---- freezes (sampler thread only) ----
    private long freezeTick = -1;
    private long lastFreezeDump;

    private String lastReportText = "(no report yet)";
    private String lastReportFile = "";
    private long nextReportNanos;

    Monitor(Settings settings, File dir) {
        this.settings = settings;
        this.dir = dir;
        this.uploader = settings.uploadEnabled ? new Uploader(settings, dir) : null;
        this.writer = Executors.newSingleThreadExecutor(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "Lag Monitor writer");
                t.setDaemon(true);
                return t;
            }
        });
    }

    void start() {
        dir.mkdirs();
        synchronized (this) {
            window = new Window();
        }
        nextReportNanos = System.nanoTime() + settings.reportMinutes * 60000000000L;
        startedNanos = System.nanoTime();
        running = true;
        if (uploader != null) {
            uploader.start();
        }
        sampler = new Thread(new Runnable() {
            public void run() {
                sampleLoop();
            }
        }, "Lag Monitor sampler");
        sampler.setDaemon(true);
        // Above normal, so it still gets to look when the server thread is hogging a core
        sampler.setPriority(Thread.NORM_PRIORITY + 2);
        sampler.start();
        writer.submit(new Runnable() {
            public void run() {
                Reports.deleteOld(dir, settings.keepDays);
                writeFile("started", "Lag Monitor " + LagMonitor.VERSION + " started at " + now() + "\n" + Reports.settingsText(settings), false);
            }
        });
        log("started; reports in " + dir.getAbsolutePath() + (uploader != null ? ", uploading to " + settings.uploadRepository : ""));
    }

    void stop() {
        running = false;
        if (sampler != null) {
            sampler.interrupt();
        }
        final Window w;
        synchronized (this) {
            w = window;
            window = new Window();
        }
        writer.submit(new Runnable() {
            public void run() {
                writeReport(w, "Final report (server stopping)");
            }
        });
        writer.shutdown();
        try {
            writer.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (uploader != null) {
            uploader.stop(15000L);
        }
    }

    // ---- server thread ----

    void tickStart() {
        if (serverThread != Thread.currentThread()) {
            serverThread = Thread.currentThread();
        }
        synchronized (tickSamples) {
            tickSamples.clear();
        }
        tickNumber++;
        tickStartNanos = System.nanoTime();
        inTick = true;
        long stall = pendingStallMillis;
        if (stall > 0) {
            pendingStallMillis = 0;
            simulatedStall(stall);
        }
    }

    /**
     * TEST ONLY (/lagmonitor simulate): stalls the next server tick. The command may run on another
     * thread (the server console's RCON thread), so it only asks, and the server thread stalls here.
     */
    void requestStall(long millis) {
        pendingStallMillis = millis;
    }

    /** TEST ONLY (/lagmonitor simulate crash): the next server tick throws, which crashes the server. */
    void requestCrash() {
        pendingCrash = true;
    }

    boolean takeCrashRequest() {
        if (!pendingCrash) {
            return false;
        }
        pendingCrash = false;
        return true;
    }

    private static void simulatedStall(long millis) {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            // busy wait on purpose, so the sampler sees this method
            Math.sqrt(System.nanoTime());
        }
    }

    void tickEnd() {
        long end = System.nanoTime();
        inTick = false;
        long duration = end - tickStartNanos;
        boolean spike = duration >= settings.spikeMillis * 1000000L;
        boolean writeSpike = false;
        synchronized (this) {
            window.addTick(duration, lastTickEndNanos == 0 ? 0 : end - lastTickEndNanos);
            lastTickEndNanos = end;
            lastTickAtNanos = end;
            history[historyPos] = (int) Math.min(Integer.MAX_VALUE, duration / 1000L);
            historyPos = (historyPos + 1) % history.length;
            historyCount = Math.min(historyCount + 1, history.length);
            if (spike) {
                if (end - spikeHourStart > 3600000000000L) {
                    spikeHourStart = end;
                    spikesThisHour = 0;
                }
                window.spikes++;
                if (spikesThisHour < settings.maxSpikeReportsPerHour) {
                    spikesThisHour++;
                    writeSpike = true;
                } else {
                    window.spikesNotWritten++;
                }
            }
        }
        if (writeSpike) {
            final List<StackTraceElement[]> samples;
            synchronized (tickSamples) {
                samples = new ArrayList<StackTraceElement[]>(tickSamples);
            }
            final long ms = duration / 1000000L;
            final long tick = tickNumber;
            final String when = now();
            writer.submit(new Runnable() {
                public void run() {
                    writeFile("spike", Reports.spikeText(when, tick, ms, samples, settings.sampleMillis), true);
                }
            });
        }
    }

    // ---- sampler thread ----

    private void sampleLoop() {
        Classify.Result result = new Classify.Result();
        while (running) {
            try {
                Thread.sleep(settings.sampleMillis);
            } catch (InterruptedException e) {
                if (!running) {
                    return;
                }
            }
            try {
                Thread server = serverThread;
                if (server != null && inTick) {
                    long tick = tickNumber;
                    StackTraceElement[] st = server.getStackTrace();
                    if (inTick && tick == tickNumber && st.length > 0) {
                        Classify.classify(st, result);
                        synchronized (this) {
                            window.addSample(st, result);
                        }
                        synchronized (tickSamples) {
                            if (tickSamples.size() < 1000) {
                                tickSamples.add(st);
                            }
                        }
                    }
                    checkFreeze(tick);
                } else if (!inTick) {
                    checkBetweenTicks();
                }
                if (System.nanoTime() >= nextReportNanos) {
                    nextReportNanos = System.nanoTime() + settings.reportMinutes * 60000000000L;
                    periodicReport("Periodic report");
                }
            } catch (Throwable t) {
                log("sampler error: " + t);
            }
        }
    }

    private void checkFreeze(long tick) {
        long runningFor = System.nanoTime() - tickStartNanos;
        if (!inTick || tick != tickNumber || runningFor < settings.freezeSeconds * 1000000000L) {
            return;
        }
        long now = System.nanoTime();
        if (freezeTick == tick && now - lastFreezeDump < 30000000000L) {
            return;
        }
        final boolean first = freezeTick != tick;
        freezeTick = tick;
        lastFreezeDump = now;
        synchronized (this) {
            if (first) {
                window.freezes++;
            }
        }
        final String dump = Reports.threadDump(serverThread);
        final long seconds = runningFor / 1000000000L;
        final String when = now();
        writer.submit(new Runnable() {
            public void run() {
                writeFile("freeze", "Server FROZEN at " + when + ": the current tick has been running for " + seconds + " s"
                    + (first ? "" : " (still frozen; written again every 30 s while it lasts)") + ".\n\n" + dump, true);
            }
        });
        log("server frozen for " + seconds + " s, thread dump written");
    }

    // ---- time between ticks: a paused single-player game, or a server stuck outside a tick ----

    private long lastBetweenCheck;
    private long stallDumpAt;
    private boolean inPause;

    /**
     * No tick is running. If none has started for freezeSeconds, either the game is paused
     * (single player: the server thread just sleeps in its main loop) or the server is stuck
     * somewhere outside a tick, e.g. loading or saving a world. The first is counted as paused
     * time; the second gets a "stall" report with a thread dump, like a freeze.
     */
    private void checkBetweenTicks() {
        long now = System.nanoTime();
        long since = now - Math.max(lastTickAtNanos, startedNanos);
        long elapsed = lastBetweenCheck == 0 ? 0 : now - lastBetweenCheck;
        lastBetweenCheck = now;
        if (since < settings.freezeSeconds * 1000000000L) {
            stallDumpAt = 0;
            inPause = false;
            return;
        }
        Thread server = serverThread != null ? serverThread : findServerThread();
        if (server == null) {
            return;
        }
        long tickBefore = tickNumber;
        StackTraceElement[] st = server.getStackTrace();
        // A tick may have started while the stack was being taken (the first tick after a pause):
        // that isn't a stall.
        if (inTick || tickNumber != tickBefore || isInTickMethod(st)) {
            return;
        }
        if (isWaitingAfterCrash(st)) {
            // After a crash the dedicated server keeps running, waiting for "stop" on the console
            // (finalTick). That isn't a stall; the crash report already says what happened.
            if (!crashWaitLogged) {
                crashWaitLogged = true;
                log("the server has crashed and is waiting to be stopped (type stop in the console, or restart it)");
            }
            return;
        }
        if (isIdleInMainLoop(st)) {
            synchronized (this) {
                // The first time a pause is recognised, count it from when the ticks stopped (or
                // from the start of this report window, if that was later), not from now.
                window.pausedNanos += inPause ? elapsed : Math.min(since, now - window.startNanos);
            }
            inPause = true;
            return;
        }
        inPause = false;
        if (stallDumpAt != 0 && now - stallDumpAt < 30000000000L) {
            return;
        }
        final boolean first = stallDumpAt == 0;
        stallDumpAt = now;
        synchronized (this) {
            if (first) {
                window.stalls++;
            }
        }
        final String dump = Reports.threadDump(server);
        final long seconds = since / 1000000000L;
        final String when = now();
        writer.submit(new Runnable() {
            public void run() {
                writeFile("stall", "Server STALLED at " + when + ": no tick has started for " + seconds
                    + " s, and the server thread is busy outside a tick (for example loading or saving a world)"
                    + (first ? "" : " (still stalled; written again every 30 s while it lasts)") + ".\n\n" + dump, true);
            }
        });
        log("server stalled outside a tick for " + seconds + " s, thread dump written");
    }

    /** The server thread sleeping in MinecraftServer.run between ticks (what a paused single-player game does). */
    /** The server thread is in MinecraftServer.finalTick, where a crashed server waits to be stopped. */
    private static boolean isWaitingAfterCrash(StackTraceElement[] st) {
        for (StackTraceElement e : st) {
            if ("func_71228_a".equals(e.getMethodName()) && e.getClassName().startsWith("net.minecraft.server.")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIdleInMainLoop(StackTraceElement[] st) {
        if (st.length < 2 || !"sleep".equals(st[0].getMethodName()) || !"java.lang.Thread".equals(st[0].getClassName())) {
            return false;
        }
        for (int i = 1; i < Math.min(st.length, 4); i++) {
            if (st[i].getClassName().startsWith("net.minecraft.server.") && "run".equals(st[i].getMethodName())) {
                return true;
            }
        }
        return false;
    }

    /** Inside MinecraftServer.tick (SRG func_71217_p), i.e. a tick is running even if its start wasn't seen yet. */
    private static boolean isInTickMethod(StackTraceElement[] st) {
        for (StackTraceElement e : st) {
            if ("func_71217_p".equals(e.getMethodName()) && e.getClassName().startsWith("net.minecraft.server.")) {
                return true;
            }
        }
        return false;
    }

    private static Thread findServerThread() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("Server thread".equals(t.getName())) {
                return t;
            }
        }
        return null;
    }

    // ---- reports ----

    void periodicReport(final String title) {
        final Window w;
        synchronized (this) {
            w = window;
            window = new Window();
        }
        writer.submit(new Runnable() {
            public void run() {
                writeReport(w, title);
                Reports.deleteOld(dir, settings.keepDays);
                if (uploader != null) {
                    uploader.queueMissing();
                }
            }
        });
    }

    private void writeReport(Window w, String title) {
        String text = Reports.reportText(title, now(), w, settings);
        lastReportText = text;
        File f = writeFile("report", text, settings.uploadPeriodicReports);
        if (f != null) {
            lastReportFile = f.getName();
        }
    }

    /** Status line for the command. */
    synchronized String status() {
        return window.statusLine() + " | last report: " + (lastReportFile.length() > 0 ? lastReportFile : "none yet")
            + (uploader != null ? " | uploads: " + uploader.status() : "");
    }

    /** Called while Minecraft writes a crash report (on the crashing thread). Must never throw. */
    String crashSummary() {
        try {
            StringBuilder sb = new StringBuilder();
            synchronized (this) {
                sb.append(window.statusLine()).append("; last ").append(historyCount).append(" ticks (ms, newest last):");
                int n = Math.min(historyCount, 40);
                for (int i = n; i > 0; i--) {
                    int idx = (historyPos - i + history.length) % history.length;
                    sb.append(' ').append(history[idx] / 1000);
                }
            }
            String summary = sb.toString();
            StringBuilder file = new StringBuilder();
            file.append("Minecraft is writing a crash report at ").append(now()).append(".\n\n");
            file.append("Lag Monitor at the time of the crash:\n  ").append(summary).append("\n\n");
            file.append("Server thread stack now:\n");
            Thread server = serverThread;
            if (server != null) {
                // Leave out the frames of Minecraft writing this crash report (and of Lag Monitor
                // being asked for its part), so the stack starts where the server actually was.
                StackTraceElement[] st = server.getStackTrace();
                int from = 0;
                for (int i = 0; i < st.length; i++) {
                    String c = st[i].getClassName();
                    if (c.startsWith("techit.lagmonitor.") || c.startsWith("net.minecraft.crash.") || c.equals("java.lang.Thread")
                        || (c.equals("cpw.mods.fml.common.FMLCommonHandler") && "enhanceCrashReport".equals(st[i].getMethodName()))) {
                        from = i + 1;
                    }
                }
                if (from >= st.length) {
                    from = 0;
                }
                if (from > 0) {
                    file.append("    (").append(from).append(" frames of the crash report being written left out)\n");
                }
                for (int i = from; i < st.length; i++) {
                    file.append("    at ").append(st[i]).append('\n');
                }
            }
            synchronized (this) {
                file.append("\nCurrent (partial) report window:\n").append(Reports.reportText("Partial report", now(), window, settings));
            }
            file.append("\nPrevious report:\n").append(lastReportText);
            writeFile("crash", file.toString(), true);
            if (uploader != null) {
                uploader.flushSoon();
            }
            return summary;
        } catch (Throwable t) {
            return "error while summarising: " + t;
        }
    }

    // ---- files ----

    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
    private static final SimpleDateFormat NOW = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    static String now() {
        synchronized (NOW) {
            return NOW.format(new Date());
        }
    }

    File writeFile(String kind, String text, boolean upload) {
        String stamp;
        synchronized (STAMP) {
            stamp = STAMP.format(new Date());
        }
        File f = new File(dir, kind + "-" + stamp + ".txt");
        for (int i = 2; f.exists(); i++) {
            f = new File(dir, kind + "-" + stamp + "-" + i + ".txt");
        }
        Writer w = null;
        try {
            dir.mkdirs();
            w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            w.write(text);
            w.flush();
        } catch (IOException e) {
            log("could not write " + f + ": " + e);
            return null;
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (IOException e) {
                    // already reported if it mattered
                }
            }
        }
        if (upload && uploader != null) {
            uploader.add(f);
        }
        return f;
    }

    static void log(String message) {
        System.out.println("[Lag Monitor] " + message);
    }
}
