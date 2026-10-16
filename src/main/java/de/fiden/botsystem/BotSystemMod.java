package de.fiden.botsystem;

import com.mojang.logging.LogUtils;
import de.fiden.botsystem.ai.BotBrain;
import de.fiden.botsystem.ai.BotChat;
import de.fiden.botsystem.ai.BotNavigator;
import de.fiden.botsystem.ai.Gym;
import de.fiden.botsystem.ai.PathRecorder;
import de.fiden.botsystem.bot.BotDefaults;
import de.fiden.botsystem.bot.BotPlayer;
import de.fiden.botsystem.bot.BotSaves;
import de.fiden.botsystem.bot.Bots;
import de.fiden.botsystem.bot.FilmScene;
import de.fiden.botsystem.bot.Kits;
import de.fiden.botsystem.bot.MobUnits;
import de.fiden.botsystem.bot.NavMemory;
import de.fiden.botsystem.bot.Presets;
import de.fiden.botsystem.bot.SkillMemory;
import de.fiden.botsystem.debug.DebugWatch;
import de.fiden.botsystem.debug.MoveTrace;
import de.fiden.botsystem.debug.TickProfiler;
import de.fiden.botsystem.net.MapPayloads;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.bukkit.event.entity.EntityTargetEvent.TargetReason;
import org.slf4j.Logger;

public final class BotSystemMod {
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final String BUILD = "2026-10-16a";
    private static Bots bots;
    public static final MoveTrace TRACE = new MoveTrace();
    public static final BotChat CHAT = new BotChat();
    private static BotBrain brain;
    private static Presets presets;
    private static Kits kits;
    private static MobUnits mobs;
    private static final DebugWatch DEBUG = new DebugWatch();
    private static final NavMemory NAV = new NavMemory();
    private static final SkillMemory SKILLS = new SkillMemory();
    private static long autoLoadAt = -1L;
    private static long perfGcCount;
    private static long perfGcMs;
    private static long perfProfileAt;

    public static MobUnits mobs() {
        return mobs;
    }

    public static Bots bots() {
        return bots;
    }

    public static BotBrain brain() {
        return brain;
    }

    public static Kits kits() {
        return kits;
    }

    private static de.fiden.botsystem.bot.BotTypes types;

    public static de.fiden.botsystem.bot.BotTypes types() {
        if (types == null) {
            types = new de.fiden.botsystem.bot.BotTypes();
        }

        return types;
    }

    public static Presets presets() {
        if (presets == null) {
            presets = new Presets();
        }

        return presets;
    }

    public static NavMemory nav() {
        return NAV;
    }

    public static SkillMemory skills() {
        return SKILLS;
    }

    public static DebugWatch debug() {
        return DEBUG;
    }

    public static void start(MinecraftServer server) {
        NAV.load(BotSystemPlugin.configDir().resolve("botsystem-nav.json"));
        SKILLS.load(BotSystemPlugin.configDir().resolve("botsystem-skills.json"));
        Gym.load(BotSystemPlugin.configDir().resolve("botsystem-gym.json"));
        Bots.bootstrap();
        bots = new Bots(server);
        brain = new BotBrain(bots);
        kits = new Kits(server);
        mobs = new MobUnits();
    }

    public static void stop(MinecraftServer server) {
        if (mobs != null) {
            de.fiden.botsystem.bot.UnitPush.releaseAll(mobs.all());
        }

        if (bots != null && BotDefaults.get().autosave) {
            try {
                BotSaves.save(server, bots, mobs, "auto");
            } catch (IOException error) {
                LOGGER.warn("Autosave beim Stoppen fehlgeschlagen: {}", error.toString());
            }
        }

        if (bots != null) {
            bots.removeAll();
        }

        if (mobs != null) {
            mobs.removeAll();
        }
    }

    public static void onJoin(ServerPlayer player) {
        if (!(player instanceof BotPlayer)) {
            MapPayloads.RenderSettings.current().sendTo(player);
            if (BotDefaults.get().minimap) {
                new MapPayloads.MapHud(true).sendTo(player);
            }
        }
    }

    public static void tick(MinecraftServer server) {
        if (bots != null) {
            de.fiden.botsystem.command.BotCommands.drainSpawns((long)(Math.max(5.0, BotDefaults.get().spawnBudgetMs) * 1_000_000L));
        }
        FilmScene.tick();
        Bots.tickDrops(server.getTickCount());
        PathRecorder.tick(server);
        de.fiden.botsystem.ai.ActionRecorder.tick(server);
        if (brain != null) {
            brain.tick(server);
            if (mobs != null && !mobs.isEmpty()) {
                mobs.tick(server, brain::findTargetFor, server.getTickCount());
            }

            bots.refreshAnchors(server.getTickCount());
            DEBUG.report(server, bots, server.getTickCount());
            TRACE.tick(server, bots, server.getTickCount());
            autosave(server);
            if (server.getTickCount() % 1200 == 0) {
                NAV.save();
                SKILLS.save();
            }

            perfLog(server);
            CHAT.idle(server, bots, server.getTickCount());
        }
    }

    public static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
        if (bots == null) {
            return true;
        }

        BotPlayer victim = entity instanceof BotPlayer bot ? bot : null;
        if (victim != null && victim.immortal) {
            return false;
        }

        if (victim == null && MobUnits.unitOf(entity) == null || !source.is(DamageTypes.CRAMMING) && !source.is(DamageTypes.IN_WALL)) {
            MobUnits.Unit victimUnit = MobUnits.unitOf(entity);
            if (victimUnit != null && victimUnit.immortal) {
                return false;
            }

            Entity attacker = source.getEntity();
            MobUnits.Unit attackerUnit = attacker instanceof LivingEntity ? MobUnits.unitOf(attacker) : null;
            if (!BotDefaults.get().ignorePlayers
                || !(entity instanceof ServerPlayer)
                || entity instanceof BotPlayer
                || !(attacker instanceof BotPlayer) && attackerUnit == null) {
                if (attackerUnit != null) {
                    if (victim != null && victim.isGuard() && !attackerUnit.targetIds.contains(victim.getUUID())) {
                        return false;
                    }

                    String victimTeam = MobUnits.teamOf(entity);
                    return victimTeam == null || attackerUnit.freeForAll || !Bots.allied(victimTeam, attackerUnit.team);
                } else if (!(attacker instanceof BotPlayer aggressor)) {
                    return true;
                } else if ((victim == null || !aggressor.sameSide(victim)) && (victimUnit == null || !aggressor.sameSide(entity))) {
                    if (victim != null && victim.isGuard() && !aggressor.targetIds.contains(victim.getUUID()) && aggressor.currentTarget != victim) {
                        return false;
                    }

                    if (aggressor.isAlly(entity.getUUID())) {
                        aggressor.targetIds.remove(entity.getUUID());
                        if (entity == aggressor.currentTarget) {
                            aggressor.currentTarget = null;
                        }

                        return false;
                    } else {
                        return aggressor.wantsToHurt(entity);
                    }
                } else {
                    return false;
                }
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    public static void afterDamage(LivingEntity entity, DamageSource source) {
        if (bots != null) {
            if (source.getEntity() instanceof LivingEntity aggressor && aggressor != entity) {
                if (entity instanceof BotPlayer victim && !victim.isAlly(aggressor.getUUID())) {
                    CHAT.onHurt(victim.level().getServer(), victim, aggressor, victim.level().getServer().getTickCount());
                    if (source.getDirectEntity() == aggressor && victim.distanceToSqr(aggressor) < 25.0) {
                        // Melee hit: good players jump the next tick (jump reset - less knockback) and
                        // often change their strafe side.
                        long now = victim.level().getServer().getTickCount();
                        victim.hurtByMeleeTick = now;
                        double skill = victim.skill.skill();
                        double resetChance = Math.max(0.0, Math.min(0.85, (skill - 0.3) * 1.25)) * Math.min(1.2, victim.skill.aggression);
                        victim.jumpResetWanted = java.util.concurrent.ThreadLocalRandom.current().nextDouble() < resetChance;
                        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < 0.25 + skill * 0.35) {
                            victim.strafeDirection = victim.strafeDirection == 0 ? 1 : -victim.strafeDirection;
                        }
                    }
                    boolean sameSide = aggressor instanceof BotPlayer other && victim.sameSide(other);
                    if (!victim.neverFight
                        && !sameSide
                        && !victim.fleeing
                        && (victim.defendSelf || victim.followTarget != null)
                        && BotBrain.isHostileTo(victim, aggressor)) {
                        victim.targetIds.add(aggressor.getUUID());
                        victim.fightMode = true;
                        if (victim.currentTarget == null || victim.currentTarget.isDeadOrDying()) {
                            victim.currentTarget = aggressor;
                            victim.nextTargetTick = 0L;
                            long now = victim.level().getServer().getTickCount();
                            victim.nextAttackTick = Math.max(victim.nextAttackTick, now + victim.skill.reactionTicks(10L));
                        }

                        if (brain != null
                            && (victim.hostileIds.contains(aggressor.getUUID()) || aggressor instanceof BotPlayer enemy && victim.knowsHostile(enemy))) {
                            brain.lookout(victim, Math.max(6.0, victim.sightRange), false);
                        }
                    }

                    String attackerTeam = MobUnits.teamOf(aggressor);
                    if (brain != null
                        && !sameSide
                        && victim.team != null
                        && attackerTeam != null
                        && !victim.freeForAll
                        && !attackerTeam.equals(victim.teamKey())
                        && !Bots.allied(victim.team, attackerTeam)) {
                        brain.alarmWithAllies(victim.teamKey(), attackerTeam, victim.position());
                    }

                    cancelWalk(victim);
                }

                MobUnits.Unit hitUnit = MobUnits.unitOf(entity);
                // A guard/follower never turns on its owner, even when the owner hits it by accident.
                if (hitUnit != null && aggressor.getUUID().equals(hitUnit.followId)) {
                    hitUnit = null;
                }

                if (hitUnit != null && !hitUnit.mob.isDeadOrDying()) {
                    String attackerTeam = MobUnits.teamOf(aggressor);
                    if (attackerTeam == null || !attackerTeam.equals(hitUnit.teamKey()) || hitUnit.freeForAll) {
                        hitUnit.targetIds.add(aggressor.getUUID());
                        hitUnit.fightMode = true;
                        if (hitUnit.mob.getTarget() == null || !hitUnit.mob.getTarget().isAlive()) {
                            hitUnit.mob.setTarget(aggressor, TargetReason.CUSTOM);
                        }

                        if (brain != null && hitUnit.team != null && attackerTeam != null && !hitUnit.freeForAll && !Bots.allied(hitUnit.team, attackerTeam)) {
                            brain.alarmWithAllies(hitUnit.teamKey(), attackerTeam, hitUnit.mob.position());
                        }
                    }
                }

                if (entity instanceof ServerPlayer owner && !(entity instanceof BotPlayer)) {
                    reactFor(owner, aggressor, false);
                }

                // One of my guards got hit (or killed) by an outsider: that attacker is a threat to
                // all my guards, like an attack on me.
                UUID guardOwner = entity instanceof BotPlayer guardBot ? guardBot.followTarget : null;
                MobUnits.Unit guardUnit = MobUnits.unitOf(entity);
                if (guardUnit != null && guardUnit.followId != null) {
                    guardOwner = guardUnit.followId;
                }

                if (guardOwner != null && !aggressor.getUUID().equals(guardOwner)) {
                    MobUnits.Unit attackerUnit = MobUnits.unitOf(aggressor);
                    boolean friendly = aggressor instanceof BotPlayer attackerBot && (guardOwner.equals(attackerBot.followTarget)
                            || entity instanceof BotPlayer victimBot && (victimBot.sameSide(attackerBot) || victimBot.isAlly(attackerBot.getUUID())))
                        || attackerUnit != null && guardOwner.equals(attackerUnit.followId)
                        || entity instanceof BotPlayer allyCheck && allyCheck.isAlly(aggressor.getUUID());
                    ServerPlayer guardedPlayer = entity.level().getServer().getPlayerList().getPlayer(guardOwner);
                    if (!friendly && guardedPlayer != null) {
                        reactFor(guardedPlayer, aggressor, false);
                    }
                }

                if (aggressor instanceof ServerPlayer owner && !(aggressor instanceof BotPlayer)) {
                    reactFor(owner, entity, true);
                }
            }
        }
    }

    private static void reactFor(ServerPlayer owner, LivingEntity target, boolean assist) {
        if (!owner.isSpectator()) {
            UUID ownerId = owner.getUUID();
            UUID targetId = target.getUUID();
            // Mob guards/followers (a wolf guard, ...) do what their owner does: go for whatever
            // the owner hits or whoever hits the owner - villagers and any other mob included.
            if (mobs != null && (assist ? BotDefaults.get().assistOwner : BotDefaults.get().defendOwner)) {
                for (MobUnits.Unit unit : mobs.all()) {
                    if (ownerId.equals(unit.followId)
                        && unit.mob != target
                        && unit.mob.isAlive()
                        && unit.mob.level() == target.level()
                        && unit.mob.distanceToSqr(target) < 48.0 * 48.0
                        && !unit.sameTeam(target)
                        && unit.stance != de.fiden.botsystem.bot.Stance.PASSIVE) {
                        unit.targetIds.add(targetId);
                        unit.fightMode = true;
                        unit.approach = target;
                        if (unit.mob.getTarget() == null || !unit.mob.getTarget().isAlive()) {
                            unit.mob.setTarget(target, TargetReason.CUSTOM);
                        }
                    }
                }
            }

            if (!(target instanceof BotPlayer victimBot && ownerId.equals(victimBot.followTarget))) {
                List<BotPlayer> guards = new ArrayList<>();

                for (BotPlayer bot : bots.all()) {
                    if (ownerId.equals(bot.followTarget)
                        && (assist ? bot.assistOwner : bot.defendOwner)
                        && !bot.isDeadOrDying()
                        && bot != target
                        && !bot.isAlly(targetId)
                        && !(target instanceof BotPlayer other && bot.sameSide(other))) {
                        guards.add(bot);
                    }
                }

                if (!guards.isEmpty()) {
                    String enemyTeam = MobUnits.teamOf(target);
                    int enemies = 1;
                    if (bots != null) {
                        for (BotPlayer other : bots.all()) {
                            if (other != target
                                && !other.isDeadOrDying()
                                && other.team != null
                                && other.teamKey().equals(enemyTeam)
                                && other.distanceToSqr(owner) < 256.0) {
                                enemies++;
                            }
                        }
                    }

                    if (mobs != null && enemyTeam != null) {
                        for (MobUnits.Unit unit : mobs.byTeam(enemyTeam)) {
                            if (unit.mob != target && unit.mob.isAlive() && unit.mob.distanceToSqr(owner) < 256.0) {
                                enemies++;
                            }
                        }
                    }

                    // Guards do what the owner does - all of them that are close join in, not just a pair.
                    int wanted = Math.max(Math.max(2, enemies * 2 + 2), (int)guards.stream().filter(botx -> botx.distanceToSqr(target) < 32.0 * 32.0).count());
                    guards.sort(Comparator.comparingDouble(botx -> botx.distanceToSqr(target)));
                    List<BotPlayer> chosen = new ArrayList<>();

                    for (BotPlayer bot : guards) {
                        if (bot.currentTarget != null
                            && bot.currentTarget.isAlive()
                            && (bot.currentTarget == target || enemyTeam != null && enemyTeam.equals(MobUnits.teamOf(bot.currentTarget)))) {
                            chosen.add(bot);
                        }
                    }

                    for (BotPlayer bot : guards) {
                        if (chosen.size() >= wanted) {
                            break;
                        }

                        if (!chosen.contains(bot)) {
                            chosen.add(bot);
                        }
                    }

                    guards = chosen;
                    List<BotPlayer> free = new ArrayList<>();

                    for (BotPlayer bot : guards) {
                        bot.targetIds.add(targetId);
                        bot.fightMode = true;
                        bot.nextTargetTick = 0L;
                        LivingEntity current = bot.currentTarget;
                        if (current == null || !current.isAlive() || current == target) {
                            free.add(bot);
                        }
                    }

                    for (BotPlayer bot : free.isEmpty() ? guards.subList(0, Math.max(1, guards.size() / 2)) : free) {
                        bot.currentTarget = target;
                        bot.nextTargetTick = 0L;
                    }

                    for (BotPlayer bot : guards) {
                        BotBrain.returnFire(bots, bot, target);
                    }
                }
            }
        }
    }

    private static void autosave(MinecraftServer server) {
        long tick = server.getTickCount();
        if (autoLoadAt < 0L) {
            autoLoadAt = tick + 40L;
        }

        BotDefaults defaults = BotDefaults.get();
        if (defaults.autosave) {
            if (tick == autoLoadAt && bots.all().isEmpty() && Files.exists(BotSaves.file(server, "auto"))) {
                try {
                    int count = BotSaves.load(server, bots, mobs, "auto");
                    LOGGER.info("Autosave geladen: {} Bots/Mobs", count);
                    server.getPlayerList()
                        .broadcastSystemMessage(
                            Component.literal("[BotSystem] Autosave geladen: " + count + " Bots/Mobs.").withStyle(ChatFormatting.GRAY), false
                        );
                } catch (IOException error) {
                    LOGGER.warn("Autosave nicht geladen: {}", error.toString());
                }
            } else {
                if (tick > autoLoadAt && tick % 1200L == 0L && (!bots.all().isEmpty() || mobs != null && !mobs.isEmpty())) {
                    try {
                        BotSaves.save(server, bots, mobs, "auto");
                    } catch (IOException error) {
                        LOGGER.warn("Autosave fehlgeschlagen: {}", error.toString());
                    }
                }
            }
        }
    }

    private static void perfLog(MinecraftServer server) {
        long tick = server.getTickCount();
        if (tick % 200L == 0L && bots != null && (!bots.all().isEmpty() || mobs != null && !mobs.isEmpty())) {
            double[] timings = brain.takeTimings();
            BotNavigator navigator = brain.navigator();
            int paths = navigator.pathsComputed.getAndSet(0);
            double pathMs = navigator.pathNanos.getAndSet(0L) / 1000000.0;
            double pathCpu = navigator.pathCpuNanos.getAndSet(0L) / 1000000.0;
            long gcCount = 0L;
            long gcMs = 0L;

            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                gcCount += Math.max(0L, gc.getCollectionCount());
                gcMs += Math.max(0L, gc.getCollectionTime());
            }

            int moving = 0;

            for (BotPlayer bot : bots.all()) {
                if (bot.walkGoal != null || bot.fightMode && bot.currentTarget != null) {
                    moving++;
                }
            }

            double mspt = server.getAverageTickTimeNanos() / 1000000.0;
            LOGGER.info(
                String.format(
                    Locale.ROOT,
                    "[perf] server %.1f ms/tick | bots %d (%d aktiv) mobs %d | ki %.2f ms avg, %.1f ms max | pfade %d (%.1f ms/pfad, cpu %.1f), queue %d | gc %d x %d ms | heap %d/%d MB",
                    mspt,
                    bots.all().size(),
                    moving,
                    mobs == null ? 0 : mobs.size(),
                    timings[0],
                    timings[1],
                    paths,
                    paths == 0 ? 0.0 : pathMs / paths,
                    paths == 0 ? 0.0 : pathCpu / paths,
                    navigator.queued.get(),
                    gcCount - perfGcCount,
                    gcMs - perfGcMs,
                    Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory() >> 20,
                    Runtime.getRuntime().maxMemory() >> 20
                )
            );
            StringBuilder habits = new StringBuilder();
            for (int i = 0; i < BotBrain.HABITS.length; i++) {
                habits.append(i == 0 ? "" : ", ").append(BotBrain.HABIT_NAMES[i]).append(' ').append(BotBrain.HABITS[i]);
                BotBrain.HABITS[i] = 0;
            }
            LOGGER.info("[perf] kampf: {} | idle verschoben {} | lite-ticks {}", habits, brain.deferred, BotPlayer.liteSkips);
            brain.deferred = 0;
            BotPlayer.liteSkips = 0;
            perfGcCount = gcCount;
            perfGcMs = gcMs;
            if (mspt > 35.0 && tick >= perfProfileAt && !TickProfiler.isRunning()) {
                perfProfileAt = tick + 2400L;
                TickProfiler.start(server.getRunningThread(), 3, line -> LOGGER.info("[perf-profil] {}", line));
            }
        }
    }

    private static void cancelWalk(BotPlayer attacked) {
        if (!attacked.marching && attacked.post == null && (attacked.walkGoal == null || attacked.level().getServer().getTickCount() >= attacked.orderUntil)) {
            attacked.walkGoal = null;
            attacked.walkGroup = null;
        }
    }

    public static MinecraftServer serverOf(BotPlayer bot) {
        return bot.level().getServer();
    }
}
