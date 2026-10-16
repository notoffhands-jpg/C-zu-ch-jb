#!/usr/bin/env bash
# Builds the plugin without Gradle, against the Paper server's own libraries.
# Output: build/libs-fixed/botsystem-paper-3.0.0.jar
#   ./build-local.sh          build only
#   ./build-local.sh test     build + copy into the test server's plugins folder
set -e
cd "$(dirname "$0")"
SERVER="$HOME/MinecraftServer"
TEST="$HOME/.local/share/botsystem-tools/testserver"
OUT=build/local
JAR=build/libs-fixed/botsystem-paper-3.0.0.jar

CP=$(find "$SERVER/libraries" "$SERVER/versions" -name "*.jar" | tr '\n' ':')
rm -rf "$OUT" && mkdir -p "$OUT/META-INF" build/libs-fixed
javac --release 21 -nowarn -encoding UTF-8 -cp "$CP" -d "$OUT" $(find src/main/java -name "*.java")
cp -r src/main/resources/. "$OUT/"
sed -i 's/\${version}/3.0.0/' "$OUT/paper-plugin.yml"
printf 'Manifest-Version: 1.0\npaperweight-mappings-namespace: mojang\n' > "$OUT/META-INF/MANIFEST.MF"
(cd "$OUT" && jar --create --file "../../$JAR" --manifest META-INF/MANIFEST.MF .)
echo "Built $JAR ($(du -h "$JAR" | cut -f1))"

if [ "$1" = "test" ]; then
    cp "$JAR" "$TEST/plugins/botsystem-paper-3.0.0.jar"
    echo "Copied to test server"
fi
