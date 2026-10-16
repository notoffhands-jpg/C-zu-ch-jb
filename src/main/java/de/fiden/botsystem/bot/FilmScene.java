package de.fiden.botsystem.bot;

import de.fiden.botsystem.BotSystemMod;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public final class FilmScene {
    private static int scriptedDepth;
    private static boolean tracking;
    private static final Set<UUID> spawned = new LinkedHashSet<>();
    private static final Map<UUID, FilmScene.BotState> touched = new HashMap<>();
    private static final Map<UUID, FilmScene.UnitState> touchedUnits = new HashMap<>();
    private static Map<UUID, FilmScene.BotState> before;
    private static Map<UUID, FilmScene.UnitState> beforeUnits;

    private FilmScene() {
    }

    public static boolean enabled() {
        return BotDefaults.get().filmReset;
    }

    public static boolean scripted() {
        return scriptedDepth > 0;
    }

    public static boolean tracking() {
        return tracking;
    }

    public static void begin() {
        if (enabled()) {
            if (!tracking) {
                BotSystemMod.LOGGER.info("[film] Film-Szene beginnt - Bot-Aenderungen durch Film-Befehle werden beim Stopp zurueckgenommen.");
            }

            tracking = true;
        }
    }

    public static void beginCommand() {
        if (enabled()) {
            scriptedDepth++;
            if (scriptedDepth == 1 && BotSystemMod.bots() != null) {
                tracking = true;
                before = new HashMap<>();

                for (BotPlayer bot : BotSystemMod.bots().all()) {
                    before.put(bot.getUUID(), FilmScene.BotState.of(bot));
                }

                beforeUnits = new HashMap<>();
                if (BotSystemMod.mobs() != null) {
                    for (MobUnits.Unit unit : BotSystemMod.mobs().all()) {
                        beforeUnits.put(unit.mob.getUUID(), FilmScene.UnitState.of(unit));
                    }
                }
            }
        }
    }

    public static void endCommand() {
        if (scriptedDepth != 0) {
            scriptedDepth--;
            if (scriptedDepth <= 0 && before != null && BotSystemMod.bots() != null) {
                for (BotPlayer bot : BotSystemMod.bots().all()) {
                    UUID id = bot.getUUID();
                    FilmScene.BotState old = before.get(id);
                    if (old == null) {
                        spawned.add(id);
                    } else if (!touched.containsKey(id) && !spawned.contains(id) && !old.equals(FilmScene.BotState.of(bot))) {
                        touched.put(id, old);
                    }
                }

                if (BotSystemMod.mobs() != null) {
                    for (MobUnits.Unit unit : BotSystemMod.mobs().all()) {
                        UUID id = unit.mob.getUUID();
                        FilmScene.UnitState old = beforeUnits.get(id);
                        if (old == null) {
                            spawned.add(id);
                        } else if (!touchedUnits.containsKey(id) && !spawned.contains(id) && !old.equals(FilmScene.UnitState.of(unit))) {
                            touchedUnits.put(id, old);
                        }
                    }
                }

                before = null;
                beforeUnits = null;
            }
        }
    }

    public static void tick() {
        if (scriptedDepth > 0) {
            scriptedDepth = 0;
            before = null;
            beforeUnits = null;
        }
    }

    public static void restore() {
        if (tracking || !spawned.isEmpty() || !touched.isEmpty() || !touchedUnits.isEmpty()) {
            tracking = false;
            Bots bots = BotSystemMod.bots();
            MobUnits mobs = BotSystemMod.mobs();
            int removed = 0;
            int reset = 0;
            if (bots != null) {
                for (UUID id : new ArrayList<>(spawned)) {
                    BotPlayer bot = bots.byId(id);
                    if (bot != null) {
                        bots.remove(bot);
                    }

                    if (mobs != null) {
                        MobUnits.Unit unit = mobs.byId(id);
                        if (unit != null) {
                            mobs.remove(unit);
                        }
                    }

                    removed++;
                }

                for (Entry<UUID, FilmScene.BotState> entry : touched.entrySet()) {
                    BotPlayer found = bots.byId(entry.getKey());
                    if (found != null && found.isAlive()) {
                        entry.getValue().applyTo(found);
                        reset++;
                    }
                }

                if (mobs != null) {
                    for (Entry<UUID, FilmScene.UnitState> entry : touchedUnits.entrySet()) {
                        MobUnits.Unit unit = mobs.byId(entry.getKey());
                        if (unit != null && unit.mob.isAlive()) {
                            entry.getValue().applyTo(unit);
                            reset++;
                        }
                    }
                }
            }

            spawned.clear();
            touched.clear();
            touchedUnits.clear();
            if (removed + reset > 0) {
                BotSystemMod.LOGGER.info("[film] Szene zurueckgesetzt: {} vom Film gespawnte Bots/Mobs entfernt, {} zurueck an ihren Platz.", removed, reset);
            }
        }
    }

    record BotState(
        ServerLevel world,
        Vec3 pos,
        float yaw,
        float pitch,
        String team,
        boolean fightMode,
        int enemies,
        boolean marching,
        Vec3 walkGoal,
        Vec3 post,
        List<Vec3> patrol,
        double[] patrolArea,
        UUID followTarget,
        double[] stayArea,
        Vec3 lookAtPos,
        UUID lookAtId,
        UUID searchId,
        Stance stance,
        boolean farming,
        Vec3 returnTo
    ) {
        static FilmScene.BotState of(BotPlayer bot) {
            return new FilmScene.BotState(
                bot.level(),
                bot.position(),
                bot.getYRot(),
                bot.getXRot(),
                bot.team,
                bot.fightMode,
                bot.targetIds.size() + bot.enemyTeams.size() + bot.hostileTeams.size(),
                bot.marching,
                bot.walkGoal,
                bot.post,
                bot.patrol == null ? null : new ArrayList<>(bot.patrol),
                bot.patrolArea == null ? null : (double[])bot.patrolArea.clone(),
                bot.followTarget,
                bot.stayArea == null ? null : (double[])bot.stayArea.clone(),
                bot.lookAtPos,
                bot.lookAtId,
                bot.searchId,
                bot.stance,
                bot.isFarming(),
                bot.returnTo
            );
        }

        @Override
        public boolean equals(Object other) {
            return !(other instanceof FilmScene.BotState o)
                ? false
                : this.world == o.world
                    && this.pos.distanceToSqr(o.pos) < 4.0
                    && Objects.equals(this.team, o.team)
                    && this.fightMode == o.fightMode
                    && this.enemies == o.enemies
                    && this.marching == o.marching
                    && Objects.equals(this.walkGoal, o.walkGoal)
                    && Objects.equals(this.post, o.post)
                    && Objects.equals(this.patrol, o.patrol)
                    && Arrays.equals(this.patrolArea, o.patrolArea)
                    && Objects.equals(this.followTarget, o.followTarget)
                    && Arrays.equals(this.stayArea, o.stayArea)
                    && Objects.equals(this.lookAtPos, o.lookAtPos)
                    && Objects.equals(this.lookAtId, o.lookAtId)
                    && Objects.equals(this.searchId, o.searchId)
                    && this.stance == o.stance
                    && this.farming == o.farming;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.team, this.walkGoal, this.post);
        }

        void applyTo(BotPlayer bot) {
            bot.clearFight();
            bot.fightMode = false;
            bot.walkGroup = null;
            bot.detour = null;
            bot.path = null;
            bot.stuckStrikes = 0;
            bot.responseTeam = null;
            bot.savedWalkGoal = null;
            bot.guardHold = false;
            bot.stopFarming();
            bot.setMoveInput(0.0F, 0.0F);
            bot.setSprinting(false);
            bot.team = this.team;
            bot.marching = this.marching;
            bot.walkGoal = this.walkGoal;
            bot.walkDeadline = this.walkGoal == null ? 0L : bot.level().getServer().getTickCount() + 12000L;
            bot.post = this.post;
            bot.patrol = this.patrol == null ? null : new ArrayList<>(this.patrol);
            bot.patrolArea = this.patrolArea == null ? null : (double[])this.patrolArea.clone();
            bot.followTarget = this.followTarget;
            bot.stayArea = this.stayArea == null ? null : (double[])this.stayArea.clone();
            bot.lookAtPos = this.lookAtPos;
            bot.lookAtId = this.lookAtId;
            bot.searchId = this.searchId;
            bot.searchSpotted = false;
            bot.stance = this.stance;
            bot.returnTo = this.returnTo;
            bot.setHealth(bot.getMaxHealth());
            bot.teleportTo(this.world, this.pos.x, this.pos.y, this.pos.z, Collections.emptySet(), this.yaw, this.pitch, false);
        }
    }

    record UnitState(
        ServerLevel world,
        Vec3 pos,
        float yaw,
        String team,
        boolean marching,
        Vec3 goal,
        Vec3 post,
        double[] patrolArea,
        UUID followId,
        Stance stance,
        int enemies
    ) {
        static FilmScene.UnitState of(MobUnits.Unit unit) {
            return new FilmScene.UnitState(
                (ServerLevel)unit.mob.level(),
                unit.mob.position(),
                unit.mob.getYRot(),
                unit.team,
                unit.marching,
                unit.goal,
                unit.post,
                unit.patrolArea == null ? null : (double[])unit.patrolArea.clone(),
                unit.followId,
                unit.stance,
                unit.targetIds.size() + unit.enemyTeams.size() + unit.hostileTeams.size()
            );
        }

        @Override
        public boolean equals(Object other) {
            return !(other instanceof FilmScene.UnitState o)
                ? false
                : this.world == o.world
                    && this.pos.distanceToSqr(o.pos) < 4.0
                    && Objects.equals(this.team, o.team)
                    && this.marching == o.marching
                    && Objects.equals(this.goal, o.goal)
                    && Objects.equals(this.post, o.post)
                    && Arrays.equals(this.patrolArea, o.patrolArea)
                    && Objects.equals(this.followId, o.followId)
                    && this.stance == o.stance
                    && this.enemies == o.enemies;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.team, this.goal, this.post);
        }

        void applyTo(MobUnits.Unit unit) {
            unit.clearFight();
            unit.team = this.team;
            unit.marching = this.marching;
            unit.goal = this.goal;
            unit.post = this.post;
            unit.patrolArea = this.patrolArea == null ? null : (double[])this.patrolArea.clone();
            unit.followId = this.followId;
            unit.stance = this.stance;
            unit.responseTeam = null;
            unit.mob.getNavigation().stop();
            unit.mob.setHealth(unit.mob.getMaxHealth());
            unit.mob.snapTo(this.pos.x, this.pos.y, this.pos.z, this.yaw, 0.0F);
        }
    }
}
