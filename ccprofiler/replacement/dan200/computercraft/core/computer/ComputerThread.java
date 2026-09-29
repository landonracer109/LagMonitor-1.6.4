package dan200.computercraft.core.computer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.WeakHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import techit.lagmonitor.ccprofiler.CCProfiler;

/**
 * ComputerCraft 1.63's ComputerThread, with the Lag Monitor CC Profiler's measurements added.
 *
 * Only loaded when -Dcc.profileSeconds is set (see Transformer). The scheduling is the original's,
 * instruction for instruction: one task at a time, round-robin across computers' queues, each task
 * on a new thread that gets 5 seconds before the computer is aborted (soft, then hard, then the
 * thread is stopped). The only additions are the four CCProfiler calls, marked "profiler", and
 * getLastProfileReport().
 */
public class ComputerThread {
    private static Object m_lock = new Object();
    private static Thread m_thread = null;
    private static WeakHashMap<Object, LinkedBlockingQueue<Task>> m_computerTasks = new WeakHashMap<Object, LinkedBlockingQueue<Task>>();
    private static ArrayList<LinkedBlockingQueue<Task>> m_computerTasksActive = new ArrayList<LinkedBlockingQueue<Task>>();
    private static ArrayList<LinkedBlockingQueue<Task>> m_computerTasksPending = new ArrayList<LinkedBlockingQueue<Task>>();
    private static Object m_defaultQueue = new Object();
    private static Object m_monitor = new Object();
    private static boolean m_busy = false;
    private static boolean m_running = false;
    private static boolean m_stopped = false;

    public static interface Task {
        public Computer getOwner();

        public void execute();
    }

    /**
     * The latest profiler report (with the time it was made), or null if none has been made yet.
     * Lag Monitor includes it in its reports.
     */
    public static String getLastProfileReport() {
        return CCProfiler.getLastReport();
    }

    public static void start() {
        synchronized (m_lock) {
            if (m_running) {
                m_stopped = false;
                return;
            }
            m_thread = new Thread(new Runnable() {
                @SuppressWarnings("deprecation")
                public void run() {
                    while (true) {
                        synchronized (m_computerTasksPending) {
                            if (!m_computerTasksPending.isEmpty()) {
                                Iterator<LinkedBlockingQueue<Task>> it = m_computerTasksPending.iterator();
                                while (it.hasNext()) {
                                    LinkedBlockingQueue<Task> queue = it.next();
                                    if (!m_computerTasksActive.contains(queue)) {
                                        m_computerTasksActive.add(queue);
                                    }
                                    it.remove();
                                }
                            }
                        }
                        Iterator<LinkedBlockingQueue<Task>> it = m_computerTasksActive.iterator();
                        while (it.hasNext()) {
                            LinkedBlockingQueue<Task> queue = it.next();
                            if (queue == null || queue.isEmpty()) {
                                continue;
                            }
                            synchronized (m_lock) {
                                if (m_stopped) {
                                    m_running = false;
                                    m_thread = null;
                                    return;
                                }
                            }
                            try {
                                final Task task = queue.take();
                                m_busy = true;
                                long started = System.nanoTime(); // profiler
                                Thread worker = new Thread(new Runnable() {
                                    public void run() {
                                        try {
                                            task.execute();
                                        } catch (Throwable e) {
                                            System.out.println("computercraft: Error running task.");
                                            e.printStackTrace();
                                        }
                                    }
                                });
                                worker.start();
                                worker.join(5000L);
                                boolean timedOut = worker.isAlive(); // profiler
                                if (worker.isAlive()) {
                                    Computer computer = task.getOwner();
                                    if (computer != null) {
                                        computer.abort(false);
                                        worker.join(1250L);
                                        if (worker.isAlive()) {
                                            computer.abort(true);
                                            worker.join(1250L);
                                        }
                                    }
                                    if (worker.isAlive()) {
                                        worker.interrupt();
                                        worker.stop();
                                    }
                                }
                                CCProfiler.taskDone(task, started, System.nanoTime(), timedOut); // profiler
                            } catch (InterruptedException e) {
                                // as the original: move on to the next queue
                                m_busy = false;
                                continue;
                            } finally {
                                m_busy = false;
                            }
                            synchronized (queue) {
                                if (queue.isEmpty()) {
                                    it.remove();
                                }
                            }
                        }
                        while (m_computerTasksActive.isEmpty() && m_computerTasksPending.isEmpty()) {
                            synchronized (m_monitor) {
                                try {
                                    m_monitor.wait();
                                } catch (InterruptedException e) {
                                    // as the original: check again
                                }
                            }
                        }
                    }
                }
            });
            m_thread.start();
            m_running = true;
        }
    }

    public static void stop() {
        synchronized (m_lock) {
            if (m_running) {
                m_stopped = true;
                m_thread.interrupt();
            }
        }
    }

    public static void queueTask(Task _task, Computer computer) {
        Object queueObject = computer;
        if (queueObject == null) {
            queueObject = m_defaultQueue;
        }
        LinkedBlockingQueue<Task> queue = m_computerTasks.get(queueObject);
        if (queue == null) {
            queue = new LinkedBlockingQueue<Task>(256);
            m_computerTasks.put(queueObject, queue);
        }
        synchronized (m_computerTasksPending) {
            if (!queue.offer(CCProfiler.queued(_task))) { // profiler: remembers when it was queued
                CCProfiler.dropped(); // profiler: the original drops it silently when 256 are queued
            }
            if (!m_computerTasksPending.contains(queue)) {
                m_computerTasksPending.add(queue);
            }
        }
        synchronized (m_monitor) {
            m_monitor.notify();
        }
    }
}
