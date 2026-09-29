package techit.lagmonitor.ccprofiler;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;

/**
 * Swaps ComputerCraft's ComputerThread (and its two anonymous Runnables) for the profiling version,
 * but only when the server is started with -Dcc.profileSeconds=N (N > 0), and only when all three
 * classes are exactly the ComputerCraft 1.63 ones this was written for (checked by SHA-256). In
 * every other case nothing is changed. ComputerThread$Task is identical in both and isn't touched.
 */
public class Transformer implements IClassTransformer {
    private static final String PACKAGE = "dan200.computercraft.core.computer.";
    private static final String[] NAMES = {"ComputerThread", "ComputerThread$1", "ComputerThread$1$1"};
    /** SHA-256 of the classes in ComputerCraft1.63+tomo1.jar (the same in TechIt-ng's patched jar). */
    private static final String[] STOCK_SHA256 = {
        "74f70b774adc915f0507873719bbe109fd2019bc86a76ed994b0e8f9fb31e38b",
        "8052c0894c4e30a6eb91c2503a6356ade72ecdae07e25efaafef39ba864f93d7",
        "a13d359537eb790e73c9a48928fedd56e9840eee295eef0bb76e44190d441647",
    };
    private static final String REPLACEMENTS = "/techit/lagmonitor/ccprofiler/replacement/";

    private static Boolean s_active;

    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !transformedName.startsWith(PACKAGE + "ComputerThread")) {
            return bytes;
        }
        int index = -1;
        for (int i = 0; i < NAMES.length; i++) {
            if (transformedName.equals(PACKAGE + NAMES[i])) {
                index = i;
            }
        }
        if (index < 0 || !active()) {
            return bytes;
        }
        try {
            return readResource(REPLACEMENTS + NAMES[index] + ".bin");
        } catch (Exception e) {
            // Can't happen with a complete jar; if it does, the classes would no longer match.
            throw new RuntimeException("Lag Monitor CC Profiler: missing replacement for " + transformedName, e);
        }
    }

    /** Decided once, when ComputerThread is first loaded: replace all three classes or none. */
    private static synchronized boolean active() {
        if (s_active != null) {
            return s_active.booleanValue();
        }
        s_active = Boolean.FALSE;
        if (Long.getLong("cc.profileSeconds", 0L) <= 0) {
            return false;
        }
        try {
            for (int i = 0; i < NAMES.length; i++) {
                byte[] original = Launch.classLoader.getClassBytes(PACKAGE + NAMES[i]);
                if (original == null || !STOCK_SHA256[i].equals(sha256(original))) {
                    log("not installed: this ComputerCraft isn't the 1.63 build it was made for (" + NAMES[i] + " differs). ComputerCraft runs unchanged.");
                    return false;
                }
            }
        } catch (Exception e) {
            log("not installed: " + e + ". ComputerCraft runs unchanged.");
            return false;
        }
        s_active = Boolean.TRUE;
        log("installed: a ComputerCraft thread report every " + Long.getLong("cc.profileSeconds") + " s");
        return true;
    }

    private static void log(String message) {
        System.out.println("[Lag Monitor CC Profiler] " + message);
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static byte[] readResource(String path) throws Exception {
        InputStream in = Transformer.class.getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException(path + " not found");
        }
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
