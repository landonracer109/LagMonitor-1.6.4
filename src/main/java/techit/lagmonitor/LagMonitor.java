package techit.lagmonitor;

import java.io.File;
import java.util.EnumSet;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.ITickHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.TickType;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.network.NetworkMod;
import cpw.mods.fml.common.registry.TickRegistry;
import cpw.mods.fml.relauncher.Side;

/**
 * Lag Monitor: a server-only profiler for Minecraft 1.6.4 servers.
 *
 * Times every server tick, samples what the server thread is doing, and writes reports to
 * lagmonitor/ in the server folder: a periodic report, a report for every lag spike, a thread dump
 * when the server freezes, and a snapshot when it crashes. Optionally uploads them to a GitHub
 * repository. Players don't need it: clients can connect without it.
 */
@Mod(modid = LagMonitor.MODID, name = "Lag Monitor", version = LagMonitor.VERSION)
@NetworkMod(clientSideRequired = false, serverSideRequired = false)
public class LagMonitor {
    public static final String MODID = "lagmonitor";
    public static final String VERSION = "1.0.0";

    private Settings settings;
    private Monitor monitor;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        settings = Settings.load(event.getSuggestedConfigurationFile());
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        if (!settings.enabled) {
            return;
        }
        TickRegistry.registerTickHandler(new ServerTicks(), Side.SERVER);
        FMLCommonHandler.instance().registerCrashCallable(new CrashSection());
    }

    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        if (!settings.enabled) {
            return;
        }
        try {
            monitor = new Monitor(settings, new File(settings.folder));
            Monitor.setCurrent(monitor);
            monitor.start();
            event.registerServerCommand(new CommandLagMonitor(settings.allowTestCommands));
        } catch (Throwable t) {
            // Never stop the server from starting: run without Lag Monitor instead.
            Monitor.setCurrent(null);
            monitor = null;
            Monitor.log("could not start, running without it: " + t);
            t.printStackTrace();
        }
    }

    @EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        Monitor m = monitor;
        monitor = null;
        Monitor.setCurrent(null);
        if (m != null) {
            try {
                m.stop();
            } catch (Throwable t) {
                Monitor.log("error while stopping (ignored): " + t);
            }
        }
    }

    /**
     * Server tick start and end, on the server thread. Kept as cheap as possible, and nothing that
     * goes wrong in Lag Monitor may ever take the server down: errors are logged (once) and ignored.
     */
    private static final class ServerTicks implements ITickHandler {
        private boolean reported;

        public void tickStart(EnumSet<TickType> type, Object... tickData) {
            Monitor current = Monitor.current();
            if (current != null && current.takeCrashRequest()) {
                // Deliberately outside the try below: this is the test crash an operator asked for.
                throw new RuntimeException("Lag Monitor test crash (/lagmonitor simulate crash)");
            }
            try {
                Monitor m = Monitor.current();
                if (m != null) {
                    m.tickStart();
                }
            } catch (Throwable t) {
                report(t);
            }
        }

        public void tickEnd(EnumSet<TickType> type, Object... tickData) {
            try {
                Monitor m = Monitor.current();
                if (m != null) {
                    m.tickEnd();
                }
            } catch (Throwable t) {
                report(t);
            }
        }

        private void report(Throwable t) {
            if (!reported) {
                reported = true;
                Monitor.log("error in tick timing (ignored, server unaffected): " + t);
                t.printStackTrace();
            }
        }

        public EnumSet<TickType> ticks() {
            return EnumSet.of(TickType.SERVER);
        }

        public String getLabel() {
            return "Lag Monitor";
        }
    }

    /** Adds a short "Lag Monitor" section to Minecraft crash reports and saves a snapshot file. */
    private static final class CrashSection implements cpw.mods.fml.common.ICrashCallable {
        public String call() throws Exception {
            Monitor m = Monitor.current();
            if (m == null) {
                return "not running";
            }
            return m.crashSummary();
        }

        public String getLabel() {
            return "Lag Monitor";
        }
    }
}
