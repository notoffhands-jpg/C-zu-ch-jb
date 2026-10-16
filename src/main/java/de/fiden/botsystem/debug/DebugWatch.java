package de.fiden.botsystem.debug;

import de.fiden.botsystem.bot.BotPlayer;
import de.fiden.botsystem.bot.Bots;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

public final class DebugWatch {
    private final Map<UUID, Set<UUID>> watchers = new HashMap<>();

    public int toggle(ServerPlayer viewer, Iterable<BotPlayer> bots) {
        Set<UUID> watched = this.watchers.computeIfAbsent(viewer.getUUID(), key -> new HashSet<>());
        int changed = 0;

        for (BotPlayer bot : bots) {
            if (!watched.add(bot.getUUID())) {
                watched.remove(bot.getUUID());
            }

            changed++;
        }

        if (watched.isEmpty()) {
            this.watchers.remove(viewer.getUUID());
        }

        return changed;
    }

    public void clear(ServerPlayer viewer) {
        this.watchers.remove(viewer.getUUID());
    }

    public void report(MinecraftServer server, Bots bots, long tick) {
        if (!this.watchers.isEmpty() && tick % 20L == 0L) {
            this.watchers.entrySet().removeIf(entry -> {
                ServerPlayer viewer = server.getPlayerList().getPlayer(entry.getKey());
                if (viewer == null) {
                    return true;
                }

                for (BotPlayer bot : bots.all()) {
                    if (entry.getValue().contains(bot.getUUID())) {
                        viewer.sendSystemMessage(Component.literal(line(bot, viewer, tick)).withStyle(ChatFormatting.GRAY), false);
                    }
                }

                return false;
            });
        }
    }

    public static String line(BotPlayer bot, ServerPlayer viewer, long tick) {
        StringBuilder text = new StringBuilder(bot.getGameProfile().name()).append(" | ").append(bot.state());
        text.append(String.format(Locale.ROOT, " | hp %.1f", bot.getHealth()));
        text.append(" | fight ").append(bot.fightMode ? "an" : "aus");
        if (bot.guardMode) {
            text.append(" | wache");
        }

        LivingEntity target = bot.currentTarget;
        if (target == null) {
            text.append(" | ziel -");
        } else {
            text.append(" | ziel ").append(target.getName().getString()).append(String.format(Locale.ROOT, " (%.1fm)", bot.distanceTo(target)));
        }

        text.append(" | liste ").append(bot.targetIds.size());
        if (!bot.hostileIds.isEmpty()) {
            text.append(" | feinde ").append(bot.hostileIds.size()).append(" sicht ").append(bot.sightRange > 0.0 ? (int)bot.sightRange : "aus");
        }

        long attackIn = bot.nextAttackTick - tick;
        text.append(" | schlag ").append(attackIn <= 0L ? "bereit" : attackIn + "t");
        text.append(" | ").append(bot.skill.describe());
        if (bot.farmBlock != null || bot.farmEntity != null) {
            text.append(" | farm ").append(bot.farmBlock != null ? "block" : "tier");
        }

        text.append(String.format(Locale.ROOT, " | zu dir %.1fm", bot.distanceTo(viewer)));
        return text.toString();
    }
}
