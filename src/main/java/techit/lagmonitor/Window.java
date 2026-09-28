package techit.lagmonitor;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything measured during one report window. Guarded by the Monitor. */
final class Window {
    final long startNanos = System.nanoTime();
    final long startMillis = System.currentTimeMillis();

    int ticks;
    long tickNanosTotal;
    long tickNanosMax;
    int ticksOver50;
    int spikes;
    int spikesNotWritten;
    int freezes;
    int stalls;
    /** Time the game was paused (single player only; a dedicated server never pauses). */
    long pausedNanos;
    /** Tick durations in tenths of a millisecond, for percentiles. */
    int[] durations = new int[6000];

    int samples;
    final Map<String, int[]> categories = new HashMap<String, int[]>();
    final Map<String, int[]> blockEntities = new HashMap<String, int[]>();
    final Map<String, int[]> entities = new HashMap<String, int[]>();
    final Map<String, int[]> handlers = new HashMap<String, int[]>();
    final Map<String, int[]> packages = new HashMap<String, int[]>();
    final Map<String, int[]> methods = new HashMap<String, int[]>();

    final Map<String, long[]> gcStart = gcNow();

    void addTick(long nanos, long sinceLastTickEnd) {
        if (ticks == durations.length) {
            int[] bigger = new int[durations.length * 2];
            System.arraycopy(durations, 0, bigger, 0, durations.length);
            durations = bigger;
        }
        durations[ticks++] = (int) Math.min(Integer.MAX_VALUE, nanos / 100000L);
        tickNanosTotal += nanos;
        tickNanosMax = Math.max(tickNanosMax, nanos);
        if (nanos > 50000000L) {
            ticksOver50++;
        }
    }

    private final Set<String> seen = new HashSet<String>();

    void addSample(StackTraceElement[] st, Classify.Result r) {
        samples++;
        count(categories, r.category);
        if (r.detail != null) {
            if (r.category == Classify.BLOCK_ENTITY) {
                count(blockEntities, r.detail);
            } else if (r.category == Classify.ENTITY) {
                count(entities, r.detail);
            } else if (r.category == Classify.HANDLER) {
                count(handlers, r.detail);
            }
        }
        count(methods, st[0].getClassName() + "." + st[0].getMethodName());
        seen.clear();
        for (StackTraceElement e : st) {
            String p = Classify.packageOf(e.getClassName());
            if (seen.add(p)) {
                count(packages, p);
            }
        }
    }

    private static void count(Map<String, int[]> map, String key) {
        int[] c = map.get(key);
        if (c == null) {
            map.put(key, new int[] {1});
        } else {
            c[0]++;
        }
    }

    double seconds() {
        return (System.nanoTime() - startNanos) / 1e9;
    }

    double tps() {
        double s = seconds();
        double running = s - pausedNanos / 1e9;
        return running <= 0 ? 0 : Math.min(20.0, ticks / running);
    }

    double averageMs() {
        return ticks == 0 ? 0 : tickNanosTotal / 1e6 / ticks;
    }

    /** Percentile of tick time in ms, p between 0 and 100. */
    double percentileMs(double p) {
        if (ticks == 0) {
            return 0;
        }
        int[] copy = new int[ticks];
        System.arraycopy(durations, 0, copy, 0, ticks);
        java.util.Arrays.sort(copy);
        int idx = (int) Math.min(ticks - 1, Math.max(0, Math.ceil(p / 100.0 * ticks) - 1));
        return copy[idx] / 10.0;
    }

    String statusLine() {
        return String.format("TPS %.1f over %.0f s, tick avg %.1f ms, max %.0f ms, %d ticks over 50 ms, %d spikes, %d freezes, %d stalls%s",
            tps(), seconds(), averageMs(), tickNanosMax / 1e6, ticksOver50, spikes, freezes, stalls,
            pausedNanos > 0 ? String.format(", paused %.0f s", pausedNanos / 1e9) : "");
    }

    static Map<String, long[]> gcNow() {
        Map<String, long[]> m = new HashMap<String, long[]>();
        try {
            List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
            for (GarbageCollectorMXBean b : beans) {
                m.put(b.getName(), new long[] {b.getCollectionCount(), b.getCollectionTime()});
            }
        } catch (Throwable t) {
            // not available on this JVM
        }
        return m;
    }
}
