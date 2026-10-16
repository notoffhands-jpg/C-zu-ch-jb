#!/usr/bin/env bash
# Builds ArmyFPS with plain javac against Minecraft 1.21.11 (intermediary names) and the game's
# own libraries from the Prism instance - the same way BatchSplit was built, no Gradle.
#   bash build.sh            build only -> build/armyfps-<version>.jar
#   bash build.sh install    build, move any old armyfps jar from the mods folder to old/, copy the new one in
set -euo pipefail
cd "$(dirname "$0")"

VERSION=2.0.0
PRISM="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"
LIBS="$PRISM/libraries"
MODS="$PRISM/instances/1.21.11(1)/minecraft/mods"
MC=$(find "$HOME/.gradle/caches/fabric-loom/minecraftMaven" -name 'minecraft-merged-intermediary-1.21.11*.jar' 2>/dev/null | head -1)
[ -n "$MC" ] || { echo "Minecraft 1.21.11 intermediary jar not found in ~/.gradle/caches/fabric-loom"; exit 1; }

# Minecraft first, then every library of the instance (Mixin, Fabric Loader, JOML, Brigadier ...),
# but not the obfuscated vanilla client jar.
CP="$MC:$(find "$LIBS" -name '*.jar' -not -path '*/com/mojang/minecraft/*' | tr '\n' ':')"

rm -rf build
mkdir -p build/classes
javac --release 21 -proc:none -nowarn -encoding UTF-8 -cp "$CP" -d build/classes $(find src -name '*.java')
cp res/* build/classes/
JAR="build/armyfps-$VERSION.jar"
(cd build/classes && jar --create --file "../armyfps-$VERSION.jar" .)
echo "Built $JAR"

if [ "${1:-}" = "install" ]; then
    mkdir -p old
    for old in "$MODS"/armyfps-*.jar; do
        [ -e "$old" ] && mv -v "$old" old/
    done
    cp -v "$JAR" "$MODS/"
    echo "Installed. Restart Minecraft."
fi
