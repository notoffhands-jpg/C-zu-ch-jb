package de.fiden.botsystem.bot;

import com.google.gson.JsonElement;
import com.mojang.datafixers.DataFixer;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.lang.reflect.Field;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.player.Player;

/**
 * One shared, empty statistics tracker for every bot (like {@link EmptyAdvancements}): no stats
 * file read per spawned bot, and the five time statistics every player bumps each tick cost
 * nothing.
 */
final class EmptyStats extends ServerStatsCounter {
    static final EmptyStats INSTANCE = create();

    @SuppressWarnings("unused")
    private EmptyStats() {
        super(null, null); // never called - created without a constructor below
    }

    private static EmptyStats create() {
        try {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe)unsafeField.get(null);
            EmptyStats empty = (EmptyStats)unsafe.allocateInstance(EmptyStats.class);
            Field stats = StatsCounter.class.getDeclaredField("stats");
            stats.setAccessible(true);
            Object2IntOpenHashMap<Stat<?>> none = new Object2IntOpenHashMap<>();
            none.defaultReturnValue(0);
            stats.set(empty, none);
            return empty;
        } catch (ReflectiveOperationException | RuntimeException error) {
            de.fiden.botsystem.BotSystemMod.LOGGER.warn("Leere Bot-Statistik nicht moeglich ({}) - Bots laden normale.", error.toString());
            return null;
        }
    }

    @Override
    public void increment(Player player, Stat<?> stat, int amount) {
    }

    @Override
    public void setValue(Player player, Stat<?> stat, int value) {
    }

    @Override
    public void save() {
    }

    @Override
    public void parse(DataFixer fixer, JsonElement json) {
    }

    @Override
    public void markAllDirty() {
    }

    @Override
    public void sendStats(ServerPlayer player) {
    }
}
