#!/usr/bin/env bash
# Builds build/LagMonitor-<version>.jar. Needs Java 8 and, in tools/ (not in the repo):
#   ecj.jar           Eclipse Java compiler (targets Java 6 like the rest of 1.6.4)
#   mc-1.6.4-srg.jar  Minecraft 1.6.4 with SRG names   } see README, "Building"
#   forge-srg.jar     Forge 9.11.1.965 universal, SRG  }
set -euo pipefail
cd "$(dirname "$0")"
T=tools
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac
VERSION=$(sed -n 's/.*VERSION = "\(.*\)";.*/\1/p' src/main/java/techit/lagmonitor/LagMonitor.java)
rm -rf build && mkdir -p build/classes build/tools
java -jar "$T/ecj.jar" -1.6 -nowarn -encoding UTF-8 -cp "$T/mc-1.6.4-srg.jar${SEP}$T/forge-srg.jar" -d build/classes $(find src/main/java -name '*.java')
cp src/main/resources/mcmod.info build/classes/
java -jar "$T/ecj.jar" -1.6 -nowarn -d build/tools buildtools/MakeJar.java
java -cp build/tools MakeJar "build/LagMonitor-$VERSION.jar" build/classes > /dev/null
echo "built build/LagMonitor-$VERSION.jar"

# The optional CC Profiler add-on. Also needs, in tools/:
#   launchwrapper-1.8.jar           (Minecraft's launcher library, for the class transformer)
#   ComputerCraft1.63+tomo1.jar     (the ComputerCraft jar it was written for)
CC="$T/ComputerCraft1.63+tomo1.jar"
if [ -f "$CC" ] && [ -f "$T/launchwrapper-1.8.jar" ]; then
    mkdir -p build/cc/classes build/cc/replacement
    # The replacement ComputerThread and the profiler it calls, compiled together against ComputerCraft
    java -jar "$T/ecj.jar" -1.6 -nowarn -encoding UTF-8 -cp "$CC" -d build/cc/replacement \
        ccprofiler/replacement/dan200/computercraft/core/computer/ComputerThread.java \
        ccprofiler/src/techit/lagmonitor/ccprofiler/CCProfiler.java
    java -jar "$T/ecj.jar" -1.6 -nowarn -encoding UTF-8 \
        -cp "$CC${SEP}$T/forge-srg.jar${SEP}$T/launchwrapper-1.8.jar${SEP}build/cc/replacement" \
        -d build/cc/classes $(find ccprofiler/src -name '*.java')
    # Only the three swapped classes go in, as resources (never loaded directly). The Task interface
    # is ComputerCraft's own.
    R=build/cc/classes/techit/lagmonitor/ccprofiler/replacement
    mkdir -p "$R"
    for c in 'ComputerThread' 'ComputerThread$1' 'ComputerThread$1$1'; do
        cp "build/cc/replacement/dan200/computercraft/core/computer/$c.class" "$R/$c.bin"
    done
    java -cp build/tools MakeJar "build/LagMonitor-CCProfiler-$VERSION.jar" \
        "--attr=FMLCorePlugin: techit.lagmonitor.ccprofiler.LoadingPlugin" build/cc/classes > /dev/null
    echo "built build/LagMonitor-CCProfiler-$VERSION.jar"
else
    echo "skipped the CC Profiler add-on (needs $CC and $T/launchwrapper-1.8.jar)"
fi
