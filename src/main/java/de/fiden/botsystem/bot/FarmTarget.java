package de.fiden.botsystem.bot;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public record FarmTarget(Block block, EntityType<?> entity) {
    private static final Map<String, EntityType<?>> DROPS = Map.ofEntries(
        Map.entry("beef", EntityType.COW),
        Map.entry("rindfleisch", EntityType.COW),
        Map.entry("leather", EntityType.COW),
        Map.entry("leder", EntityType.COW),
        Map.entry("milk", EntityType.COW),
        Map.entry("porkchop", EntityType.PIG),
        Map.entry("schweinefleisch", EntityType.PIG),
        Map.entry("chicken", EntityType.CHICKEN),
        Map.entry("haehnchen", EntityType.CHICKEN),
        Map.entry("feather", EntityType.CHICKEN),
        Map.entry("feder", EntityType.CHICKEN),
        Map.entry("mutton", EntityType.SHEEP),
        Map.entry("hammel", EntityType.SHEEP),
        Map.entry("wool", EntityType.SHEEP),
        Map.entry("wolle", EntityType.SHEEP),
        Map.entry("rabbit", EntityType.RABBIT),
        Map.entry("bone", EntityType.SKELETON),
        Map.entry("knochen", EntityType.SKELETON),
        Map.entry("arrow", EntityType.SKELETON),
        Map.entry("string", EntityType.SPIDER),
        Map.entry("faden", EntityType.SPIDER),
        Map.entry("spider_eye", EntityType.SPIDER),
        Map.entry("gunpowder", EntityType.CREEPER),
        Map.entry("schwarzpulver", EntityType.CREEPER),
        Map.entry("rotten_flesh", EntityType.ZOMBIE),
        Map.entry("ender_pearl", EntityType.ENDERMAN),
        Map.entry("slime_ball", EntityType.SLIME),
        Map.entry("ink_sac", EntityType.SQUID),
        Map.entry("blaze_rod", EntityType.BLAZE),
        Map.entry("ghast_tear", EntityType.GHAST),
        Map.entry("phantom_membrane", EntityType.PHANTOM)
    );

    public static FarmTarget parse(String input) {
        String raw = input.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace("minecraft:", "");
        if (raw.isEmpty()) {
            return null;
        }

        String singular = raw.endsWith("s") && raw.length() > 3 ? raw.substring(0, raw.length() - 1) : raw;

        for (String candidate : new String[]{raw, singular}) {
            EntityType<?> drop = DROPS.get(candidate);
            if (drop != null) {
                return new FarmTarget(null, drop);
            }
        }

        for (String candidate : new String[]{raw, singular}) {
            Identifier id = Identifier.tryParse(candidate);
            if (id != null) {
                Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(id);
                if (block.isPresent() && block.get() != Blocks.AIR) {
                    return new FarmTarget(block.get(), null);
                }
            }
        }

        for (String candidate : new String[]{raw, singular}) {
            Identifier id = Identifier.tryParse(candidate);
            if (id != null) {
                Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
                if (type.isPresent() && type.get().getCategory() != MobCategory.MISC) {
                    return new FarmTarget(null, type.get());
                }
            }
        }

        return null;
    }

    public String label() {
        return this.block != null ? BuiltInRegistries.BLOCK.getKey(this.block).getPath() : BuiltInRegistries.ENTITY_TYPE.getKey(this.entity).getPath();
    }
}
