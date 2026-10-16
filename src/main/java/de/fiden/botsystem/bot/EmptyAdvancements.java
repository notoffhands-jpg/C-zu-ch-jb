package de.fiden.botsystem.bot;

import java.lang.reflect.Field;
import java.util.HashMap;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;

/**
 * One shared, empty advancement tracker for every bot (what Citizens does for its NPCs).
 *
 * A real one loads the player's advancement file and registers a listener for each of the ~1500
 * advancements - about half of the time it took to spawn a bot - and afterwards every trigger
 * (moving, hitting, picking things up ...) checks those listeners. Bots never earn advancements,
 * so they get this one: nothing loaded, nothing listened to, nothing saved.
 */
final class EmptyAdvancements extends PlayerAdvancements {
    static final EmptyAdvancements INSTANCE = create();

    @SuppressWarnings("unused")
    private EmptyAdvancements() {
        super(null, null, null, null, null); // never called - created without a constructor below
    }

    private static EmptyAdvancements create() {
        try {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe)unsafeField.get(null);
            EmptyAdvancements empty = (EmptyAdvancements)unsafe.allocateInstance(EmptyAdvancements.class);
            // Paper looks listeners up in this map on every trigger: it has to exist (and stay empty).
            Field criteria = PlayerAdvancements.class.getDeclaredField("criterionData");
            criteria.setAccessible(true);
            criteria.set(empty, new HashMap<>());
            return empty;
        } catch (ReflectiveOperationException | RuntimeException error) {
            de.fiden.botsystem.BotSystemMod.LOGGER.warn("Leere Bot-Advancements nicht moeglich ({}) - Bots laden normale.", error.toString());
            return null;
        }
    }

    @Override
    public void setPlayer(ServerPlayer player) {
    }

    @Override
    public void stopListening() {
    }

    @Override
    public void reload(ServerAdvancementManager manager) {
    }

    @Override
    public void save() {
    }

    @Override
    public boolean award(AdvancementHolder advancement, String criterion) {
        return false;
    }

    @Override
    public boolean revoke(AdvancementHolder advancement, String criterion) {
        return false;
    }

    @Override
    public void flushDirty(ServerPlayer player, boolean showAdvancements) {
    }

    @Override
    public void setSelectedTab(AdvancementHolder advancement) {
    }

    @Override
    public AdvancementProgress getOrStartProgress(AdvancementHolder advancement) {
        AdvancementProgress progress = new AdvancementProgress();
        progress.update(advancement.value().requirements());
        return progress;
    }
}
