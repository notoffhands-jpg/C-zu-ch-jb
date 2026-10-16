#!/usr/bin/env bash
# Records 25 s of the running game (Java Flight Recorder, "profile" settings) while you turn the
# camera at the army, collects the render-related settings, and pushes it all to the branch
# "devref-stutter" (never main) for analysis. Read-only on the game; only writes the recording
# into the instance folder and a temporary folder.
#
# Start the game, stand in front of the army, then run:  bash tools/record-stutter.sh
set -euo pipefail

REMOTE="https://github.com/notoffhands-jpg/C-zu-ch-jb.git"
INSTANCE="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/1.21.11(1)"
GAME="$INSTANCE/minecraft"
SECONDS_TO_RECORD=25
JFR="$GAME/turn.jfr"

# The game's JVM: Prism's launcher entry point - never the Paper server, which also runs Java.
PID=$(jcmd -l 2>/dev/null | grep -i 'prismlauncher\|EntryPoint\|KnotClient' | grep -vi 'paper' | awk '{print $1}' | head -1 || true)
if [ -z "$PID" ]; then
    PID=$(pgrep -f 'org.prismlauncher.EntryPoint' | head -1 || true)
fi
if [ -z "$PID" ]; then
    echo "Game not found. Is Minecraft running?"
    echo "If it is: Prism > instance > Edit > Settings > Java arguments, add for one start:"
    echo "  -XX:StartFlightRecording=delay=60s,duration=${SECONDS_TO_RECORD}s,settings=profile,filename=turn.jfr"
    echo "then turn the camera at the army from 60 s after the game starts, and run this script again with: NORECORD=1 bash tools/record-stutter.sh"
    [ "${NORECORD:-}" = "1" ] || exit 1
fi

if [ "${NORECORD:-}" != "1" ]; then
    rm -f "$JFR"
    if ! jcmd "$PID" JFR.start name=armyturn settings=profile duration=${SECONDS_TO_RECORD}s filename="$JFR"; then
        echo "Could not start the recording in the game (Flatpak sandbox?). Use the Java-argument way:"
        echo "  Prism > instance > Edit > Settings > Java arguments, add for one start:"
        echo "  -XX:StartFlightRecording=delay=60s,duration=${SECONDS_TO_RECORD}s,settings=profile,filename=turn.jfr"
        echo "  Turn the camera at the army from 60 s after the game starts, then run: NORECORD=1 bash tools/record-stutter.sh"
        exit 1
    fi
    for i in 3 2 1; do echo "Start turning in $i ..."; sleep 1; done
    echo ">>> TURN THE CAMERA back and forth at the army now, for ${SECONDS_TO_RECORD} seconds <<<"
    sleep $((SECONDS_TO_RECORD + 3))
    echo "Recording finished."
fi
for i in $(seq 1 20); do [ -s "$JFR" ] && break; sleep 1; done
[ -s "$JFR" ] || { echo "No recording at $JFR - paste this output to Claude."; exit 1; }

OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
cp "$JFR" "$OUT/turn.jfr"

# Settings that decide frame pacing and entity work.
cp "$GAME/options.txt" "$OUT/" 2>/dev/null || true
mkdir -p "$OUT/config"
for f in sodium-options.json sodium-extra-options.json sodium-extra.properties entityculling.json moreculling.toml dynamic_fps.json armyfps.properties; do
    cp "$GAME/config/$f" "$OUT/config/" 2>/dev/null || true
done
cp "$INSTANCE/instance.cfg" "$OUT/" 2>/dev/null || true
ls -1 "$GAME/mods" > "$OUT/mods.txt" 2>/dev/null || true
grep -i 'opengl\|GL_\|renderer\|vendor\|driver\|sodium\|workaround\|nvidia\|backend\|java version\|armyfps' "$GAME/logs/latest.log" | head -200 > "$OUT/log-render-lines.txt" 2>/dev/null || true
nvidia-smi > "$OUT/nvidia-smi.txt" 2>&1 || true
{ echo "__GL_THREADED_OPTIMIZATIONS=${__GL_THREADED_OPTIMIZATIONS:-unset}"; echo "__GL_MaxFramesAllowed=${__GL_MaxFramesAllowed:-unset}"; } > "$OUT/env.txt"

cd "$OUT"
git init -q
git add -A
git commit -qm "Stutter recording $(date '+%Y-%m-%d %H:%M')"
git push -f "$REMOTE" HEAD:devref-stutter
echo "Done: pushed to branch devref-stutter. Tell Claude."
