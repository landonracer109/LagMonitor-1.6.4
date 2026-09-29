package techit.lagmonitor.ccprofiler;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import dan200.computercraft.core.computer.Computer;
import dan200.computercraft.core.computer.ComputerThread;

/**
 * Measures ComputerCraft's computer thread, called from the replacement ComputerThread.
 *
 * Every cc.profileSeconds seconds it makes a report: how busy the thread was, how long tasks waited
 * in the queue, tasks dropped because a computer's queue was full, and the computers that used the
 * most time. It's printed to the server log ("[CC-Profile]" lines) and kept for Lag Monitor.
 *
 * Everything except dropped() and queued() runs on the computer thread only.
 */
public final class CCProfiler {
    private static final long INTERVAL_NANOS = Math.max(1L, Long.getLong("cc.profileSeconds", 60L)) * 1000000000L;

    private CCProfiler() {
    }

    /** A queued task with the time it was queued. */
    public static final class TimedTask implements ComputerThread.Task {
        final ComputerThread.Task inner;
        final long queuedAt = System.nanoTime();

        TimedTask(ComputerThread.Task inner) {
            this.inner = inner;
        }

        public Computer getOwner() {
            return inner.getOwner();
        }

        public void execute() {
            inner.execute();
        }
    }

    private static final class ComputerStats {
        String name;
        int tasks;
        long runNanos;
        long maxRunNanos;
        int timeouts;
    }

    private static final Map<Integer, ComputerStats> s_stats = new HashMap<Integer, ComputerStats>();
    private static long s_periodStart = System.nanoTime();
    private static int s_tasks;
    private static long s_runNanos;
    private static long s_waitNanos;
    private static long s_maxWaitNanos;
    private static final AtomicInteger s_dropped = new AtomicInteger();
    private static volatile String s_lastReport;

    public static ComputerThread.Task queued(ComputerThread.Task task) {
        return new TimedTask(task);
    }

    public static void dropped() {
        s_dropped.incrementAndGet();
    }

    public static String getLastReport() {
        return s_lastReport;
    }

    public static void taskDone(ComputerThread.Task task, long started, long ended, boolean timedOut) {
        try {
            long runNanos = ended - started;
            long waitNanos = task instanceof TimedTask ? started - ((TimedTask) task).queuedAt : 0L;
            Computer owner = task.getOwner();
            int id = owner == null ? -1 : owner.getID();
            ComputerStats stats = s_stats.get(id);
            if (stats == null) {
                stats = new ComputerStats();
                s_stats.put(id, stats);
            }
            if (owner != null) {
                String label = owner.getLabel();
                stats.name = "#" + id + (label != null ? " \"" + label + "\"" : "");
            } else {
                stats.name = "(no computer)";
            }
            stats.tasks++;
            stats.runNanos += runNanos;
            stats.maxRunNanos = Math.max(stats.maxRunNanos, runNanos);
            if (timedOut) {
                stats.timeouts++;
            }
            s_tasks++;
            s_runNanos += runNanos;
            s_waitNanos += waitNanos;
            s_maxWaitNanos = Math.max(s_maxWaitNanos, waitNanos);
            maybeReport(ended);
        } catch (Throwable t) {
            // Never let the profiler break the computer thread.
        }
    }

    private static void maybeReport(long now) {
        long period = now - s_periodStart;
        if (period < INTERVAL_NANOS) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[CC-Profile] %.0fs: %d tasks, thread busy %.1f%%, queue wait avg %.1f ms / max %.0f ms, DROPPED (queue full) %d%n",
            period / 1e9, s_tasks, 100.0 * s_runNanos / period,
            s_tasks == 0 ? 0.0 : s_waitNanos / 1e6 / s_tasks, s_maxWaitNanos / 1e6, s_dropped.getAndSet(0)));
        ArrayList<ComputerStats> list = new ArrayList<ComputerStats>(s_stats.values());
        Collections.sort(list, new Comparator<ComputerStats>() {
            public int compare(ComputerStats a, ComputerStats b) {
                return a.runNanos < b.runNanos ? 1 : (a.runNanos > b.runNanos ? -1 : 0);
            }
        });
        int shown = 0;
        for (ComputerStats s : list) {
            // every computer that used at least 0.5% of the period, at least 15, at most 40
            if (shown >= 40 || (shown >= 15 && s.runNanos * 200 < period)) {
                break;
            }
            sb.append(String.format("[CC-Profile]   %-28s %6.1f%% of period, %5d tasks, avg %6.2f ms, max %7.1f ms%s%n",
                s.name, 100.0 * s.runNanos / period, s.tasks, s.runNanos / 1e6 / s.tasks, s.maxRunNanos / 1e6,
                s.timeouts > 0 ? ", " + s.timeouts + " timed out" : ""));
            shown++;
        }
        String report = sb.toString();
        s_lastReport = new SimpleDateFormat("HH:mm:ss").format(new Date()) + " " + report.replace("[CC-Profile] ", "");
        System.out.print(report);
        s_stats.clear();
        s_periodStart = now;
        s_tasks = 0;
        s_runNanos = 0;
        s_waitNanos = 0;
        s_maxWaitNanos = 0;
    }
}
