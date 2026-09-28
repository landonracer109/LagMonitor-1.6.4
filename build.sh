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
