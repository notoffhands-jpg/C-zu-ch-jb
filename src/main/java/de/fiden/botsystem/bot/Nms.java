package de.fiden.botsystem.bot;

import ca.spottedleaf.moonrise.common.misc.NearbyPlayers;
import ca.spottedleaf.moonrise.common.misc.SingleUserAreaMap;
import com.mojang.authlib.GameProfile;
import de.fiden.botsystem.BotSystemMod;
import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ChunkMap.TrackedEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.player.Player;

public final class Nms {
    private static final Field GOAL_SELECTOR = field(Mob.class, "goalSelector");
    private static final Field TARGET_SELECTOR = field(Mob.class, "targetSelector");
    private static final Field GAME_PROFILE = field(Player.class, "gameProfile");
    private static final Field NEARBY_PLAYERS_MAP = field(NearbyPlayers.class, "players");
    private static final Field TRACKED_RANGE = field(TrackedEntity.class, "range");

    private Nms() {
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException error) {
            throw new IllegalStateException("NMS-Feld fehlt: " + type.getSimpleName() + "." + name, error);
        }
    }

    public static GoalSelector goalSelector(Mob mob) {
        try {
            return (GoalSelector)GOAL_SELECTOR.get(mob);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException(error);
        }
    }

    public static GoalSelector targetSelector(Mob mob) {
        try {
            return (GoalSelector)TARGET_SELECTOR.get(mob);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException(error);
        }
    }

    public static void hideFromNearbyPlayers(ServerLevel level, ServerPlayer player) {
        try {
            NearbyPlayers nearby = level.moonrise$getNearbyPlayers();
            Map<?, ?> map = (Map<?, ?>)NEARBY_PLAYERS_MAP.get(nearby);
            if (map.get(player) instanceof Object[] array) {
                for (Object tracker : array) {
                    if (tracker instanceof SingleUserAreaMap<?> area) {
                        area.remove();
                    }
                }
            }
        } catch (IllegalAccessException | RuntimeException error) {
            BotSystemMod.LOGGER.warn("Bot nicht aus den Nachbar-Karten genommen: {}", error.toString());
        }
    }

    public static void setTrackingRange(ServerPlayer bot) {
        int distance = BotDefaults.get().trackDistance;
        int range = distance <= 0 ? 512 : distance;
        TrackedEntity tracked = bot.level().getChunkSource().chunkMap.entityMap.get(bot.getId());
        if (tracked != null) {
            try {
                TRACKED_RANGE.setInt(tracked, range);
            } catch (IllegalAccessException | RuntimeException error) {
                BotSystemMod.LOGGER.warn("Tracker-Reichweite fuer {} nicht setzbar: {}", bot.getGameProfile().name(), error.toString());
            }
        }
    }

    public static void setGameProfile(Player player, GameProfile profile) {
        try {
            GAME_PROFILE.set(player, profile);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException(error);
        }
    }
}
