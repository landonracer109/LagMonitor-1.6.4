package techit.lagmonitor;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatMessageComponent;

/**
 * /lagmonitor                  status
 * /lagmonitor report           write (and upload) a report now
 * /lagmonitor simulate <ms>    TEST ONLY: stall the next server tick, to check spike and freeze
 *                              reports. Only when general.allowTestCommands=true in the config.
 * /lagmonitor simulate crash   TEST ONLY: crash the server on the next tick, to check crash reports.
 *
 * Operators only (permission level 3).
 */
final class CommandLagMonitor extends CommandBase {
    private final boolean allowTest;

    CommandLagMonitor(boolean allowTest) {
        this.allowTest = allowTest;
    }

    public String func_71517_b() { // getCommandName
        return "lagmonitor";
    }

    public String func_71518_a(ICommandSender sender) { // getCommandUsage
        return allowTest ? "/lagmonitor [report | simulate <ms> | simulate crash]" : "/lagmonitor [report]";
    }

    public int func_82362_a() { // getRequiredPermissionLevel
        return 3;
    }

    public void func_71515_b(ICommandSender sender, String[] args) { // processCommand
        Monitor m = Monitor.current();
        if (m == null) {
            say(sender, "Lag Monitor is not running.");
            return;
        }
        if (args.length == 0) {
            say(sender, "Lag Monitor: " + m.status());
        } else if ("report".equals(args[0])) {
            m.periodicReport("Report requested by " + sender.func_70005_c_());
            say(sender, "Lag Monitor: writing a report to " + m.dir.getPath() + ".");
        } else if ("simulate".equals(args[0]) && !allowTest) {
            say(sender, "Lag Monitor: test commands are off (general.allowTestCommands in config/lagmonitor.cfg).");
        } else if ("simulate".equals(args[0]) && args.length == 2 && "crash".equals(args[1])) {
            m.requestCrash();
            say(sender, "Lag Monitor: the next server tick will crash the server (test).");
        } else if ("simulate".equals(args[0]) && args.length == 2) {
            long ms;
            try {
                ms = Math.min(60000L, Math.max(0L, Long.parseLong(args[1])));
            } catch (NumberFormatException e) {
                say(sender, "Usage: /lagmonitor simulate <milliseconds>");
                return;
            }
            m.requestStall(ms);
            say(sender, "Lag Monitor: the next server tick will stall for " + ms + " ms (test).");
        } else {
            say(sender, "Usage: " + func_71518_a(sender));
        }
    }

    // Commands are sorted by name. CommandBase's own compareTo only exists under its SRG name at
    // compile time, so it is spelled out here.
    public int compareTo(Object other) {
        return func_71517_b().compareTo(((net.minecraft.command.ICommand) other).func_71517_b());
    }

    private static void say(ICommandSender sender, String text) {
        sender.func_70006_a(ChatMessageComponent.func_111066_d(text)); // sendChatToPlayer(createFromText)
    }
}
