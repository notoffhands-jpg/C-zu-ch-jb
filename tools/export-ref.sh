#!/usr/bin/env bash
# Collects what ArmyFPS development needs from this PC and pushes it to the branch "devref"
# (never main): Yarn mappings, the BatchSplit source, bytecode listings of the renderer classes
# ArmyFPS hooks, and the mod list. Read-only on everything it collects; it only writes to a
# temporary folder and pushes that folder.
set -euo pipefail

REMOTE="https://github.com/notoffhands-jpg/C-zu-ch-jb.git"
INSTANCE="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/1.21.11(1)/minecraft"
BATCHSPLIT="$HOME/Desktop/BatchSplit"
LOOM="$HOME/.gradle/caches/fabric-loom"

MC=$(find "$LOOM/minecraftMaven" -name 'minecraft-merged-intermediary-1.21.11*.jar' 2>/dev/null | head -1)
MAPPINGS=$(find "$LOOM" -maxdepth 3 -path '*yarn*1.21.11*' -name mappings.tiny 2>/dev/null | head -1)
[ -n "$MC" ] || { echo "Minecraft intermediary jar not found under $LOOM/minecraftMaven"; exit 1; }
[ -n "$MAPPINGS" ] || { echo "Yarn mappings.tiny for 1.21.11 not found under $LOOM"; exit 1; }
echo "Minecraft: $MC"
echo "Mappings:  $MAPPINGS"

OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/javap"

# 1. Mappings (Yarn is CC0), gzipped.
gzip -c "$MAPPINGS" > "$OUT/mappings.tiny.gz"

# 2. Renderer classes by Yarn name -> intermediary name, then their bytecode listing.
awk -F'\t' '
    NR == 1 { for (i = 4; i <= NF; i++) { if ($i == "intermediary") inter = i - 2; if ($i == "named") named = i - 2 } next }
    $1 == "c" { print $inter "\t" $named }
' "$MAPPINGS" | grep -E $'\tnet/minecraft/client/(model/ModelPart|render/entity/equipment/|render/entity/(LivingEntityRenderer|PlayerEntityRenderer|EntityRenderer|EntityRenderManager|EntityRenderDispatcher)(\\$|$)|render/entity/feature/(ArmorFeatureRenderer|HeldItemFeatureRenderer|FeatureRenderer|PlayerHeldItemFeatureRenderer)(\\$|$)|render/entity/model/(BipedEntityModel|PlayerEntityModel|EntityModel)(\\$|$)|render/entity/state/(EntityRenderState|LivingEntityRenderState|BipedEntityRenderState|PlayerEntityRenderState)(\\$|$)|render/item/(ItemRenderer|ItemRenderState)(\\$|$)|render/command/|render/(SpriteTexturedVertexConsumer|VertexConsumers|VertexConsumerProvider|BufferBuilder|WorldRenderer|RenderLayer|OverlayTexture)(\\$|$)|font/TextRenderer(\\$|$))' \
    > "$OUT/classes.tsv" || true
echo "Renderer classes found: $(wc -l < "$OUT/classes.tsv")"
while IFS=$'\t' read -r inter named; do
    file="$OUT/javap/$(echo "$named" | tr '/$' '._').txt"
    { echo "// $named = $inter"; javap -c -p -cp "$MC" "${inter//\//.}" 2>&1; } > "$file"
done < "$OUT/classes.tsv"

# 3. BatchSplit (own code): proven 1.21.11 mixin targets.
if [ -d "$BATCHSPLIT" ]; then
    mkdir -p "$OUT/batchsplit"
    cp -r "$BATCHSPLIT/src" "$BATCHSPLIT/res" "$OUT/batchsplit/" 2>/dev/null || true
    cp "$BATCHSPLIT"/*.sh "$OUT/batchsplit/" 2>/dev/null || true
fi

# 4. What runs in the game: mods and library versions.
ls -1 "$INSTANCE/mods" > "$OUT/mods.txt" 2>/dev/null || echo "mods folder not found" > "$OUT/mods.txt"
ls -1 "$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/libraries/net/fabricmc/fabric-loader" > "$OUT/fabric-loader.txt" 2>/dev/null || true

cd "$OUT"
git init -q
git add -A
git commit -qm "Reference files for ArmyFPS development"
git push -f "$REMOTE" HEAD:devref
echo "Done: pushed to branch devref."
