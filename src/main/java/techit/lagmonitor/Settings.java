package techit.lagmonitor;

import java.io.File;

import net.minecraftforge.common.Configuration;

/** config/lagmonitor.cfg */
final class Settings {
    boolean enabled;
    String folder;
    int reportMinutes;
    int sampleMillis;
    int spikeMillis;
    int freezeSeconds;
    int keepDays;
    int maxSpikeReportsPerHour;
    boolean allowTestCommands;

    boolean uploadEnabled;
    String uploadRepository;
    String uploadBranch;
    String uploadFolder;
    String uploadToken;
    boolean uploadPeriodicReports;

    static Settings load(File file) {
        Configuration c = new Configuration(file);
        c.load();
        Settings s = new Settings();
        s.enabled = c.get("general", "enabled", true, "Set to false to switch Lag Monitor off completely.").getBoolean(true);
        s.folder = c.get("general", "folder", "lagmonitor", "Folder (relative to the server folder) where reports are written.").getString();
        s.reportMinutes = Math.max(1, c.get("general", "reportMinutes", 5, "Minutes between periodic reports.").getInt(5));
        s.sampleMillis = Math.max(5, c.get("general", "sampleMillis", 20,
            "How often (ms) the server thread is sampled while it runs a tick. Lower = more detail, more overhead.").getInt(20));
        s.spikeMillis = Math.max(60, c.get("general", "spikeMillis", 250, "A tick taking at least this many ms gets its own spike report.").getInt(250));
        s.freezeSeconds = Math.max(2, c.get("general", "freezeSeconds", 10,
            "A tick running this long counts as a freeze: every thread's stack is written out (and again every 30 s while it lasts).").getInt(10));
        s.keepDays = Math.max(1, c.get("general", "keepDays", 14, "Delete report files older than this many days.").getInt(14));
        s.maxSpikeReportsPerHour = Math.max(1, c.get("general", "maxSpikeReportsPerHour", 60,
            "At most this many spike reports are written per hour (the rest are only counted).").getInt(60));
        s.allowTestCommands = c.get("general", "allowTestCommands", false,
            "Allow '/lagmonitor simulate <ms>', which deliberately stalls the server to test spike and freeze reports. Leave off on a live server.").getBoolean(false);

        s.uploadEnabled = c.get("upload", "enabled", false,
            "Upload reports to a GitHub repository (use a private repo: reports contain player names and coordinates).").getBoolean(false);
        s.uploadRepository = c.get("upload", "repository", "", "owner/name of the GitHub repository, e.g. someone/server-reports").getString().trim();
        s.uploadBranch = c.get("upload", "branch", "main", "Branch to upload to.").getString().trim();
        s.uploadFolder = c.get("upload", "folder", "reports", "Folder inside the repository.").getString().trim();
        s.uploadToken = c.get("upload", "token", "",
            "A GitHub fine-grained token with Contents: read and write on that one repository only. Never shared or logged.").getString().trim();
        s.uploadPeriodicReports = c.get("upload", "periodicReports", true,
            "Also upload the periodic reports (spike, freeze and crash reports are always uploaded when upload is enabled).").getBoolean(true);
        if (c.hasChanged()) {
            c.save();
        }
        return s;
    }
}
