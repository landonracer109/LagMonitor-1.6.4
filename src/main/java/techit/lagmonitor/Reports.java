package techit.lagmonitor;

import java.io.File;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builds the text of reports. Runs on the writer thread (or the crashing thread). */
final class Reports {
    private Reports() {
    }

    static String reportText(String title, String when, Window w, Settings s) {
        StringBuilder sb = new StringBuilder();
        sb.append("Lag Monitor ").append(LagMonitor.VERSION).append(" - ").append(title).append(" - ").append(when).append('\n');
        sb.append(String.format("Window: %.0f s since %s%n%n", w.seconds(), new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(w.startMillis))));

        sb.append("SERVER TICKS (main thread)\n");
        sb.append(String.format("  TPS %.2f (20 is full speed), %d ticks%n", w.tps(), w.ticks));
        sb.append(String.format("  tick time: average %.1f ms, median %.1f ms, 95%% under %.1f ms, 99%% under %.1f ms, max %.0f ms%n",
            w.averageMs(), w.percentileMs(50), w.percentileMs(95), w.percentileMs(99), w.tickNanosMax / 1e6));
        double runningSeconds = w.seconds() - w.pausedNanos / 1e9;
        sb.append(String.format("  busy: %.0f%% of the time (a tick has 50 ms; above 100%% the server falls behind)%n",
            runningSeconds <= 0 ? 0 : w.tickNanosTotal / 1e9 / runningSeconds * 100));
        sb.append(String.format("  ticks over 50 ms: %d, spikes over %d ms: %d%s, freezes over %d s: %d, stalls outside a tick: %d%n",
            w.ticksOver50, s.spikeMillis, w.spikes, w.spikesNotWritten > 0 ? " (" + w.spikesNotWritten + " not written, hourly limit)" : "",
            s.freezeSeconds, w.freezes, w.stalls));
        if (w.pausedNanos > 0) {
            sb.append(String.format("  game paused for %.0f s (single player only; left out of TPS and busy)%n", w.pausedNanos / 1e9));
        }
        sb.append('\n');

        sb.append(memoryText(w)).append('\n');
        sb.append(worldText()).append('\n');

        sb.append(String.format("WHERE THE TICK TIME WENT (%d samples taken during ticks, one every %d ms)%n", w.samples, s.sampleMillis));
        if (w.samples == 0) {
            sb.append("  (no samples)\n\n");
        } else {
            table(sb, w.categories, w.samples, 20, "");
            sb.append("\n  Block entity types (turtles, machines, pipes, ...), share of all tick samples:\n");
            table(sb, w.blockEntities, w.samples, 25, "  ");
            sb.append("\n  Entity types:\n");
            table(sb, w.entities, w.samples, 15, "  ");
            sb.append("\n  Mod tick handlers:\n");
            table(sb, w.handlers, w.samples, 15, "  ");
            sb.append("\n  Packages anywhere in the stack (a package is counted once per sample):\n");
            table(sb, w.packages, w.samples, 25, "  ");
            sb.append("\n  Methods running at the moment of the sample:\n");
            table(sb, w.methods, w.samples, 20, "  ");
            sb.append('\n');
        }
        sb.append(computerCraftText());
        return sb.toString();
    }

    private static void table(StringBuilder sb, Map<String, int[]> map, int total, int max, String indent) {
        if (map.isEmpty()) {
            sb.append(indent).append("  (none)\n");
            return;
        }
        List<Map.Entry<String, int[]>> list = new ArrayList<Map.Entry<String, int[]>>(map.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<String, int[]>>() {
            public int compare(Map.Entry<String, int[]> a, Map.Entry<String, int[]> b) {
                return b.getValue()[0] - a.getValue()[0];
            }
        });
        int shown = 0;
        for (Map.Entry<String, int[]> e : list) {
            if (shown++ >= max) {
                sb.append(indent).append(String.format("  ... %d more%n", list.size() - max));
                break;
            }
            sb.append(indent).append(String.format("  %5.1f%%  %s%n", 100.0 * e.getValue()[0] / total, e.getKey()));
        }
    }

    private static String memoryText(Window w) {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        StringBuilder sb = new StringBuilder("MEMORY\n");
        sb.append(String.format("  used %d MB, allocated %d MB, max %d MB%n", used >> 20, rt.totalMemory() >> 20, rt.maxMemory() >> 20));
        Map<String, long[]> now = Window.gcNow();
        for (Map.Entry<String, long[]> e : now.entrySet()) {
            long[] start = w.gcStart.get(e.getKey());
            long count = e.getValue()[0] - (start != null ? start[0] : 0);
            long ms = e.getValue()[1] - (start != null ? start[1] : 0);
            sb.append(String.format("  garbage collector %s: %d runs, %d ms in this window%n", e.getKey(), count, ms));
        }
        sb.append(String.format("  threads: %d%n", Thread.activeCount()));
        return sb.toString();
    }

    /** Players, and loaded chunks, entities and block entities per dimension (read without stopping the server). */
    private static String worldText() {
        StringBuilder sb = new StringBuilder("WORLDS\n");
        try {
            Class<?> serverClass = Class.forName("net.minecraft.server.MinecraftServer");
            Object server = serverClass.getMethod("func_71276_C").invoke(null);
            if (server != null) {
                sb.append("  players online: ").append(serverClass.getMethod("func_71233_x").invoke(server)).append('\n');
            }
            Object[] worlds = (Object[]) Class.forName("net.minecraftforge.common.DimensionManager").getMethod("getWorlds").invoke(null);
            for (Object world : worlds) {
                sb.append("  dimension ").append(dimensionOf(world))
                    .append(": chunks ").append(chunks(world))
                    .append(", entities ").append(listSize(world, "field_72996_f"))
                    .append(", block entities ").append(listSize(world, "field_73009_h"))
                    .append(", players ").append(listSize(world, "field_73010_i")).append('\n');
            }
        } catch (Throwable t) {
            sb.append("  unavailable: ").append(t).append('\n');
        }
        return sb.toString();
    }

    private static String dimensionOf(Object world) {
        try {
            Object provider = field(world.getClass(), "field_73011_w").get(world);
            return String.valueOf(field(provider.getClass(), "field_76574_g").get(provider));
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String chunks(Object world) {
        try {
            Object provider = field(world.getClass(), "field_73059_b").get(world);
            Method m = provider.getClass().getMethod("func_73152_e");
            return String.valueOf(m.invoke(provider));
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String listSize(Object world, String name) {
        try {
            Object list = field(world.getClass(), name).get(world);
            return String.valueOf(((List<?>) list).size());
        } catch (Throwable t) {
            return "?";
        }
    }

    private static final Map<String, Field> FIELDS = new HashMap<String, Field>();

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        String key = c.getName() + "." + name;
        synchronized (FIELDS) {
            Field f = FIELDS.get(key);
            if (f != null) {
                return f;
            }
            for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                try {
                    f = k.getDeclaredField(name);
                    f.setAccessible(true);
                    FIELDS.put(key, f);
                    return f;
                } catch (NoSuchFieldException e) {
                    // try the superclass
                }
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** The ComputerCraft thread's own report, if the ComputerCraft jar provides one. */
    private static String computerCraftText() {
        StringBuilder sb = new StringBuilder("COMPUTERCRAFT THREAD\n");
        try {
            Class<?> ct = Class.forName("dan200.computercraft.core.computer.ComputerThread");
            Method m = ct.getMethod("getLastProfileReport");
            Object text = m.invoke(null);
            sb.append(text == null ? "  (no report yet)\n" : indent(String.valueOf(text)));
        } catch (ClassNotFoundException e) {
            sb.append("  ComputerCraft not installed\n");
        } catch (NoSuchMethodException e) {
            sb.append("  not available: needs the ComputerCraft-1.63-fixes jar (fixes-2 or later) with -Dcc.profileSeconds=N\n");
        } catch (Throwable t) {
            sb.append("  unavailable: ").append(t).append('\n');
        }
        return sb.toString();
    }

    private static String indent(String s) {
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n")) {
            sb.append("  ").append(line).append('\n');
        }
        return sb.toString();
    }

    static String spikeText(String when, long tick, long ms, List<StackTraceElement[]> samples, int sampleMillis) {
        StringBuilder sb = new StringBuilder();
        sb.append("Lag spike at ").append(when).append(": tick ").append(tick).append(" took ").append(ms).append(" ms (a normal tick is under 50 ms)\n");
        sb.append(samples.size()).append(" samples of the server thread were taken during it (one every ").append(sampleMillis).append(" ms).\n\n");
        if (samples.isEmpty()) {
            sb.append("No samples: the tick was too short to catch, or the sampler didn't get CPU time.\n");
            return sb.toString();
        }
        Map<String, int[]> what = new HashMap<String, int[]>();
        Map<String, int[]> stacks = new HashMap<String, int[]>();
        Map<String, StackTraceElement[]> example = new HashMap<String, StackTraceElement[]>();
        Classify.Result r = new Classify.Result();
        for (StackTraceElement[] st : samples) {
            Classify.classify(st, r);
            String key = r.category + (r.detail != null ? ": " + r.detail : "");
            int[] c = what.get(key);
            if (c == null) {
                what.put(key, new int[] {1});
            } else {
                c[0]++;
            }
            // group stacks by their innermost 12 frames
            StringBuilder k = new StringBuilder();
            for (int i = 0; i < Math.min(12, st.length); i++) {
                k.append(st[i]).append('|');
            }
            String sk = k.toString();
            int[] n = stacks.get(sk);
            if (n == null) {
                stacks.put(sk, new int[] {1});
                example.put(sk, st);
            } else {
                n[0]++;
            }
        }
        sb.append("What the tick was doing:\n");
        table(sb, what, samples.size(), 20, "");
        List<Map.Entry<String, int[]>> list = new ArrayList<Map.Entry<String, int[]>>(stacks.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<String, int[]>>() {
            public int compare(Map.Entry<String, int[]> a, Map.Entry<String, int[]> b) {
                return b.getValue()[0] - a.getValue()[0];
            }
        });
        int shown = 0;
        for (Map.Entry<String, int[]> e : list) {
            if (shown++ >= 5) {
                break;
            }
            sb.append(String.format("%nStack seen in %d of %d samples:%n", e.getValue()[0], samples.size()));
            StackTraceElement[] st = example.get(e.getKey());
            for (int i = 0; i < Math.min(st.length, 60); i++) {
                sb.append("    at ").append(st[i]).append('\n');
            }
            if (st.length > 60) {
                sb.append("    ... ").append(st.length - 60).append(" more\n");
            }
        }
        return sb.toString();
    }

    /** Every thread's full stack, with locks, marking the server thread first. */
    static String threadDump(Thread server) {
        StringBuilder sb = new StringBuilder();
        try {
            ThreadMXBean mx = ManagementFactory.getThreadMXBean();
            long[] deadlocked = null;
            try {
                deadlocked = mx.findDeadlockedThreads();
            } catch (Throwable t) {
                // not supported
            }
            if (deadlocked != null) {
                sb.append("DEADLOCK between threads: ");
                for (long id : deadlocked) {
                    sb.append(id).append(' ');
                }
                sb.append("\n\n");
            }
            ThreadInfo[] infos = mx.dumpAllThreads(mx.isObjectMonitorUsageSupported(), mx.isSynchronizerUsageSupported());
            List<ThreadInfo> ordered = new ArrayList<ThreadInfo>();
            for (ThreadInfo ti : infos) {
                if (server != null && ti.getThreadId() == server.getId()) {
                    ordered.add(0, ti);
                } else {
                    ordered.add(ti);
                }
            }
            for (ThreadInfo ti : ordered) {
                sb.append('"').append(ti.getThreadName()).append("\" id=").append(ti.getThreadId()).append(' ').append(ti.getThreadState());
                if (server != null && ti.getThreadId() == server.getId()) {
                    sb.append("   <== SERVER THREAD");
                }
                if (ti.getLockName() != null) {
                    sb.append("\n    waiting for ").append(ti.getLockName());
                    if (ti.getLockOwnerName() != null) {
                        sb.append(" held by \"").append(ti.getLockOwnerName()).append("\" id=").append(ti.getLockOwnerId());
                    }
                }
                sb.append('\n');
                StackTraceElement[] st = ti.getStackTrace();
                MonitorInfo[] monitors = ti.getLockedMonitors();
                for (int i = 0; i < st.length; i++) {
                    sb.append("    at ").append(st[i]).append('\n');
                    if (monitors != null) {
                        for (MonitorInfo mi : monitors) {
                            if (mi.getLockedStackDepth() == i) {
                                sb.append("      - locked ").append(mi).append('\n');
                            }
                        }
                    }
                }
                LockInfo[] syncs = ti.getLockedSynchronizers();
                if (syncs != null && syncs.length > 0) {
                    sb.append("    holds:");
                    for (LockInfo li : syncs) {
                        sb.append(' ').append(li);
                    }
                    sb.append('\n');
                }
                sb.append('\n');
            }
        } catch (Throwable t) {
            sb.append("thread dump failed: ").append(t).append('\n');
        }
        return sb.toString();
    }

    static String settingsText(Settings s) {
        return String.format("reportMinutes=%d sampleMillis=%d spikeMillis=%d freezeSeconds=%d keepDays=%d upload=%s%n",
            s.reportMinutes, s.sampleMillis, s.spikeMillis, s.freezeSeconds, s.keepDays,
            s.uploadEnabled ? s.uploadRepository + "/" + s.uploadFolder : "off");
    }

    /** Deletes report files older than the given number of days. */
    static void deleteOld(File dir, int days) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - days * 86400000L;
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".txt") && f.lastModified() < cutoff) {
                f.delete();
            }
        }
    }
}
