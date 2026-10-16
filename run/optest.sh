#!/usr/bin/env bash
# Paper-Testserver starten (BotDev ist in ops.json), Dev-Client als BotDev verbinden, Skript laufen lassen, Server stoppen.
cd /c/Users/notof/BotSystem-Paper/run
"/c/Program Files/Eclipse Adoptium/jdk-25.0.4.7-hotspot/bin/java.exe" -Xmx4G -jar paper.jar --nogui > paper6.log 2>&1 < /dev/null &
SERVER=$!
for i in $(seq 1 60); do grep -q "Done (" paper6.log && break; sleep 2; done
( cd /c/Users/notof/BotSystem-Fabric && ./gradlew.bat runClient -PdevScript=$1 -PdevServer=localhost:25599 --offline -q > "$2" 2>&1 )
taskkill //F //PID $SERVER >/dev/null 2>&1; sleep 2
