# Lag Monitor (Minecraft 1.6.4)

A **server-only** profiler for Minecraft 1.6.4 / Forge servers. It measures every server tick,
works out what the server spends its time on, and writes plain-text reports. That includes reports
for **lag spikes, freezes and crashes**, written as they happen, so they're there even when nobody
was watching.

Made for the [TechIt-ng](https://github.com/tomodachi94/tech-it) modpack, but it doesn't depend on
any other mod.

- **Only the server needs it.** Players connect exactly as before and don't install anything.
- **It doesn't change the game.** It only watches: no gameplay, world or network changes.
- **It's cheap.** One background thread looks at the server thread 50 times a second while it's
  running a tick. That's well under 1% of a CPU core.
- **Optional:** uploading every report to a (private) GitHub repository, so they can be read
  without access to the server.

## What it records

| | When | File |
|---|---|---|
| **Periodic report** | every 5 minutes | `report-<date>.txt` |
| **Lag spike** | a tick takes over 250 ms (normal is under 50 ms) | `spike-<date>.txt` |
| **Freeze** | a tick has been running for over 10 s. Written again every 30 s while it lasts. | `freeze-<date>.txt` |
| **Stall** | no tick has started for over 10 s, and the server thread is busy somewhere else, for example loading or saving a world. Also repeated every 30 s. | `stall-<date>.txt` |
| **Crash** | Minecraft writes a crash report | `crash-<date>.txt`, plus a "Lag Monitor" line in Minecraft's own crash report |
| **Final report** | the server stops | `report-<date>.txt` |

Files go into `lagmonitor/` in the server folder. Each one is written in full the moment it's made,
so a crash can't lose it. Files older than 14 days are deleted.

### A periodic report

The report sections are:
- **Server ticks:** real TPS, tick times (average, median, 95th and 99th percentile, worst), how
  busy the server thread is, and how many slow ticks, spikes, freezes and stalls there were. In
  single player, time spent in the pause menu (when the game stops ticking) is shown separately and
  left out of TPS. A dedicated server never pauses.
- **Memory:** heap use and garbage-collection runs. A long "MarkSweep" collection freezes the whole
  server.
- **Worlds:** players online, and loaded chunks, entities and block entities per dimension.
- **Where the tick time went:** what the server thread was doing each time it was sampled, split
  into:
  - parts of the tick: block entities, entities, scheduled block updates, chunk loading, chunk
    unloading, world saving, player packets, mod tick handlers;
  - **block entity types:** which kind of machine, pipe, turtle and so on costs the most;
  - entity types;
  - each mod's tick handlers;
  - packages anywhere in the stack, which is roughly "which mod";
  - the exact methods running.
- **ComputerCraft:** CC's work on the server thread (turtles, monitors, computer blocks) shows up
  like any other block entity. CC's own Lua thread is only covered with the optional
  [CC Profiler add-on](#optional-computercraft-thread-report): how busy that thread is, and which
  computers use it. That thread doesn't affect TPS: when it's overloaded, computers get slow while
  the server runs fine.

Here's the start of a real report, from a test world with an Applied Energistics setup:

```
Lag Monitor 1.0.0 - Final report (server stopping) - 2026-09-28 13:16:44
Window: 194 s since 13:13:30

SERVER TICKS (main thread)
  TPS 19.94 (20 is full speed), 3863 ticks
  tick time: average 1.8 ms, median 1.4 ms, 95% under 2.2 ms, 99% under 7.0 ms, max 106 ms
  busy: 4% of the time (a tick has 50 ms; above 100% the server falls behind)
  ticks over 50 ms: 4, spikes over 250 ms: 0, freezes over 10 s: 0

MEMORY
  used 487 MB, allocated 2273 MB, max 3641 MB
  garbage collector PS MarkSweep: 2 runs, 1710 ms in this window
  garbage collector PS Scavenge: 32 runs, 351 ms in this window

WORLDS
  dimension 0: chunks 441, entities 17, block entities 5, players 1

WHERE THE TICK TIME WENT (247 samples taken during ticks, one every 20 ms)
   73.3%  world tick (other)
    7.7%  entities
    2.4%  block entities
  ...
  Block entity types (turtles, machines, pipes, ...), share of all tick samples:
      2.4%  appeng.common.base.AppEngTile
```

### Spike and freeze reports

A **spike report** shows what the server was doing during that one slow tick: a breakdown like the
one above, plus the most common stacks, which point to the mod code responsible.

A **freeze report** is a full thread dump: every thread's stack with the locks it holds or waits
for, the server thread first, and deadlocks marked. Freezes normally leave nothing behind, because
Minecraft 1.6.4 has no watchdog, so this is often the only evidence.

## Installing

1. Put `LagMonitor-<version>.jar` in the **server's** `mods` folder.
2. Start the server. `config/lagmonitor.cfg` is created with the defaults, and reports start
   appearing in `lagmonitor/`.

Nothing is needed on clients. It also works in single player, which is useful for testing.

## Optional: ComputerCraft thread report

ComputerCraft 1.63 runs every computer's Lua code on one thread of its own. When that thread is
overloaded, computers get slow and programs error with "too long without yielding", while TPS stays
at 20. **LagMonitor-CCProfiler** is a small separate jar that measures it:

1. Put `LagMonitor-CCProfiler-<version>.jar` in the **server's** `mods` folder, next to Lag Monitor.
   Players don't need it.
2. Add `-Dcc.profileSeconds=60` to the server's Java arguments, then restart.

Every 60 seconds the server log then gets a `[CC-Profile]` report, and Lag Monitor's reports get a
"ComputerCraft thread" section:

```
COMPUTERCRAFT THREAD
  04:24:26 65s: 3 tasks, thread busy 0.3%, queue wait avg 0.1 ms / max 0 ms, DROPPED (queue full) 0
    #4                              0.3% of period,     3 tasks, avg  64.19 ms, max   189.3 ms
```

- **thread busy:** how much of the time the CC thread was running computers. Near 100%, computers
  wait on each other.
- **queue wait:** how long events (timers, key presses, turtle results) waited before their
  computer ran.
- **dropped:** events thrown away because a computer already had 256 waiting. ComputerCraft does
  this silently.
- **per computer:** the computers that used the most time, by ID and label.

How it works: when the server starts with `-Dcc.profileSeconds`, it swaps ComputerCraft's
`ComputerThread` class for a copy that's the original instruction for instruction, plus timing
calls ([source](ccprofiler/replacement/dan200/computercraft/core/computer/ComputerThread.java)).
Scheduling is unchanged. It only does this for the exact ComputerCraft 1.63 classes it was written
for (checked by SHA-256, the ones in TechIt-ng's jar). Any other version is left alone, with a line
in the log. Without the Java argument, it changes nothing at all.

## Commands

For server operators only (permission level 3):

| Command | What it does |
|---|---|
| `/lagmonitor` | one-line status: TPS, tick times, spikes, freezes, last report, uploads |
| `/lagmonitor report` | writes (and uploads) a report now |
| `/lagmonitor simulate <ms>` | **test only:** stalls the next server tick for that long, to check that spike (`500`) and freeze (`12000`) reports work. Everyone on the server feels it. |
| `/lagmonitor simulate crash` | **test only:** crashes the server on the next tick, to check that the crash snapshot is written and uploaded. |

The `simulate` commands only work with `general.allowTestCommands=true`, which is off by default.
All commands also work from the server console and RCON.

## Configuration (`config/lagmonitor.cfg`)

| Setting | Default | |
|---|---|---|
| `general.enabled` | `true` | switch the whole mod off |
| `general.folder` | `lagmonitor` | where reports go, relative to the server folder |
| `general.reportMinutes` | `5` | minutes between periodic reports |
| `general.sampleMillis` | `20` | how often the server thread is sampled during a tick. Lower gives more detail and a little more overhead. |
| `general.spikeMillis` | `250` | a tick at least this long gets a spike report |
| `general.freezeSeconds` | `10` | a tick running this long counts as a freeze. The same time without any tick starting counts as a stall. |
| `general.keepDays` | `14` | report files older than this are deleted |
| `general.maxSpikeReportsPerHour` | `60` | limit on spike files. Spikes over the limit are still counted in the reports. |
| `general.allowTestCommands` | `false` | allow the `simulate` commands. Leave off on a live server. |
| `upload.*` | off | see below |

## Uploading reports to GitHub (optional)

When this is on, every report is also added to a GitHub repository (`reports/<date>/<file>`), from
a background thread. The server never waits for it.
- Spike, freeze and crash reports are uploaded straight away.
- Periodic reports are uploaded too, unless `upload.periodicReports=false`.
- Nothing is lost if GitHub can't be reached, rate-limits the server, or the server goes down
  mid-upload. Each report stays on disk, and anything not uploaded yet is sent at the next start
  and after every periodic report. `uploaded.txt` in the reports folder records what has been sent.
- When the server stops, it waits up to 15 s to finish uploading.

**Setting it up:**
1. Create a **private** repository for the reports. Reports contain player names and coordinates,
   so don't use a public one.
2. Create a **fine-grained personal access token** (GitHub → Settings → Developer settings):
   - repository access: **only that one repository**;
   - permissions: **Contents: Read and write**, nothing else;
   - an expiry date that suits you.
3. In `config/lagmonitor.cfg` on the server, set:

   ```
   upload {
       B:enabled=true
       S:repository=<owner>/<repository>
       S:token=<the token>
   }
   ```

4. Restart the server. `/lagmonitor` shows how many reports were uploaded, and the last error if
   any.

Anyone who can read the repository can then read the reports, for example a helper looking into
lag, without access to the server. To give someone access to the private repository, add them in
its **Settings → Collaborators**. The token only ever goes to `api.github.com`. It's never written
to a log or a report.

**When the token expires** (or is revoked, or loses access), GitHub rejects it. Uploads then stop
until the next restart, and `/lagmonitor` and the server log say why. Reports keep being written
on the server. Put a new token in the config and restart; the reports written in the meantime are
uploaded then.

## Reading the reports: what to look at first

1. **TPS and "busy"** in the periodic reports. At 100% busy and TPS under 20, the server thread
   can't keep up, and the rest of the report shows why. If TPS stays at 20 but players still see
   lag, look elsewhere: the ComputerCraft thread section, garbage-collection pauses, or the network.
2. **"Where the tick time went"**, then the **block entity types** table. That usually names the
   mod and the kind of machine directly.
3. **Spike reports:** the most common stack shows the exact code.
4. **Garbage collector** lines: frequent long `MarkSweep` runs mean the server is short of memory,
   and every run is a server-wide freeze.

## How it works

| Piece | Where | |
|---|---|---|
| Tick timing | a Forge server tick handler | records the start and end time of every tick |
| Sampler | background thread | while a tick runs, takes the server thread's stack trace every `sampleMillis` and sorts it by known Minecraft methods. For example, `TileEntity.updateEntity` (SRG `func_70316_g`) marks a block entity. [`Classify.java`](src/main/java/techit/lagmonitor/Classify.java) lists them all. |
| Freezes | same thread | notices a tick that has run too long and takes a thread dump with Java's management API |
| Crashes | FML crash callable | adds a line to crash reports and writes a snapshot |
| Writing | a separate writer thread | files are written there, so the server thread never waits on the disk. The exception is the crash snapshot, which is written on the crashing thread on purpose. |

**Overhead:** measured on the test server, the sampler used 0.2% of one CPU core, while the
writer and uploader threads sleep until there's a report. Each sample briefly pauses the server
thread to read its stack, typically for tens of microseconds, 2 or 3 times per tick. Memory use is a
few hundred KB.

Limits worth knowing:
- The sampler can only look when the JVM lets it. Very short spikes (a few ms) can go unnoticed,
  and a report's percentages are estimates.
- Entity and block entity names are the class that **declares** the update method. A subclass that
  doesn't override it shows up under its parent class, for example `EntityLiving` for many mobs.
- World counts (chunks, entities) are read without stopping the server, so they can be a tick out
  of date.

## Testing so far

Tested in single player on the TechIt-ng pack (Forge 9.11.1.965, Java 8):
- status, spike (`simulate 500`), freeze (`simulate 12000`), requested and final reports;
- block entity and entity breakdowns, with an AE1 setup;
- the crash hook (a deliberate F3+C crash): the "Lag Monitor" line appeared in Minecraft's crash
  report, and a snapshot file was written.

- **GitHub upload** to a private repository with a fine-grained token:
  - spikes arrive within seconds, and requested, periodic and final (shutdown) reports all arrive;
  - it works on the pack's Java 8u51;
  - a token without access to the repository gives a clear "GitHub answered 404" message in
    `/lagmonitor`, and uploads stop instead of retrying.
- **ComputerCraft section:** two computers each waking 20 times a second showed up by label, with
  the CC thread 0.9% busy.
- **Pause handling:** sitting in the pause menu is recognised and left out of TPS, and doesn't
  cause a false stall report.

It also caught a real, repeatable 330–390 ms spike: Forge unloading the Nether shortly after a world
loads and waiting for its save to disk. That's what the "world saving" category was added for.

Tested on a **dedicated server**: the pack's server-side mods on Forge 9.11.1.965 in Docker
(`itzg/minecraft-server:java8`, Java 8u312), with commands sent from RCON:
- a player joins with a client that doesn't have Lag Monitor;
- spike and freeze reports, with the thread dump, uploaded straight away;
- **upload backlog:** with `api.github.com` blocked, a spike report stayed on disk through every
  retry, and was uploaded as soon as GitHub could be reached again;
- **crash:** `simulate crash` wrote the Lag Monitor section of Minecraft's crash report and a
  snapshot file, and the snapshot was uploaded before the server went down. A crashed dedicated
  server keeps running until someone types `stop`, and this is logged once, not reported as a stall;
- overhead measured as above.

It also caught a real 416 ms spike there: a zombie's wander AI pathfinding into an unloaded area,
which made the server generate a new chunk in the middle of the tick.

**CC Profiler add-on:**
- On the dedicated server: with a ComputerCraft jar other than the one it was made for, it logged
  "not installed" and left ComputerCraft alone. With TechIt-ng's jar it installed, and the
  `[CC-Profile]` report appeared in the log and in Lag Monitor's reports.
- Headless, with 24 real ComputerCraft computers (the jar's own Lua, BIOS and ROM) each asking for
  20 events a second, 30 s per run: the same throughput with and without it (318 and 319 events a
  second) and every computer's result correct in both. It reported the thread 99.7% busy, which is
  right for that load.
- Headless, with one computer that never yields (it even catches the first abort) next to 7 normal
  ones: in both, that computer was shut down after the original's 5 + 1.25 + 1.25 s and the other 7
  finished correctly. The report named it: 93.7% of the thread, one task of 7,504 ms, "1 timed out".

## Building

`./build.sh` builds `build/LagMonitor-<version>.jar` with Java 8. It needs three jars in `tools/`,
which aren't in this repository:
- **`ecj.jar`:** the Eclipse compiler, `org.eclipse.jdt:ecj` 3.x.
- **`mc-1.6.4-srg.jar`:** Minecraft 1.6.4 remapped to SRG names.
- **`forge-srg.jar`:** Forge 9.11.1.965 universal, remapped to SRG names.

To make the last two, remap the vanilla and Forge jars with
[SpecialSource](https://github.com/md-5/SpecialSource) and `joined.srg` from Forge's
`deobfuscation_data-1.6.4.lzma`. The same setup is used by
[ComputerCraft-1.63-fixes](https://github.com/landonracer109/ComputerCraft-1.63-fixes/blob/main/BUILDING.md).

The CC Profiler add-on (`build/LagMonitor-CCProfiler-<version>.jar`) is built too when `tools/`
also has:
- **`launchwrapper-1.8.jar`:** Minecraft's launcher library (`net.minecraft:launchwrapper:1.8`).
- **`ComputerCraft1.63+tomo1.jar`:** the ComputerCraft jar from TechIt-ng.

## License

MIT, see [LICENSE](LICENSE).
