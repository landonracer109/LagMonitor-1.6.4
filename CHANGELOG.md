# Changelog

## 1.2.0 (2026-09-29)

- **Player section in the reports:** each player's ping as the server measures it, and the packets
  and bytes waiting to be sent to them.
- **Fixed:** when a tick crashed, the crashed server waiting for `stop` was reported as a freeze
  (with thread dumps every 30 s) and sampled as a tick. It's now logged once, like a crash outside
  a tick already was.
- **Tested on Cauldron.** Note: Cauldron can't run mod commands sent over RCON (its own bug); use
  them in game or in the console.
- The CC Profiler add-on is unchanged.

## 1.1.0 (2026-09-29)

- **New optional add-on, LagMonitor-CCProfiler:** a report on ComputerCraft 1.63's computer thread
  (how busy it is, queue waits, dropped events, and the computers using it most), in the server log
  and in Lag Monitor's reports. Server only. It works with the normal ComputerCraft jar, and only
  does anything when the server is started with `-Dcc.profileSeconds=N`. See the README.

## 1.0.0 (2026-09-29)

First version.

- **Tick timing:** TPS, tick-time percentiles and slow ticks.
- **Sampling of the server thread,** broken down by part of the tick (including world saving),
  block entity type, entity type, mod tick handler, package and method.
- **Reports:**
  - periodic, and one requested with `/lagmonitor report`;
  - spike;
  - freeze: a tick running too long, with a thread dump;
  - stall: no tick starting while the server thread is busy elsewhere, with a thread dump;
  - crash: a snapshot, plus a section in Minecraft's crash report;
  - final, when the server stops.
- **Also in the reports:** memory and garbage-collection stats, per-dimension world counts, and the
  ComputerCraft thread's report when ComputerCraft-1.63-fixes provides it.
- **Single-player pauses** are recognised, shown separately and left out of TPS. A crashed
  dedicated server waiting for `stop` isn't reported as a stall.
- **Optional GitHub upload,** with retries, rate-limit handling, and automatic upload of anything
  missed (after an outage, a crash or a restart).
- **`/lagmonitor` command** for operators, from in game, the console or RCON. Test commands
  (`simulate`) only with `general.allowTestCommands=true`.
- **Never takes the server down:** errors in Lag Monitor are logged once and ignored, and if it
  can't start, the server starts without it.

Tested in single player and on a dedicated server (see README, "Testing so far").
