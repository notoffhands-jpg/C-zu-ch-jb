package de.fiden.botsystem.ai;

import de.fiden.botsystem.bot.BotPlayer;
import de.fiden.botsystem.bot.Ground;
import de.fiden.botsystem.bot.MobUnits;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * /bot armymarch: a parade formation. The bots stand in an evenly spaced block (columns x ranks)
 * and the whole block moves as one rigid body along a straight line - same speed, same heading,
 * nobody turning or wandering. Bots and mob units march together. The block never waits: whoever
 * falls behind finds its way back with real pathfinding at a run and drops back into step.
 */
public final class ArmyDrill {
    public enum Phase { FORMING, READY, MARCHING, HALTED }

    public final List<BotPlayer> members = new ArrayList<>();
    public final List<MobUnits.Unit> mobs = new ArrayList<>();
    /** Places in the block (fixed at the start, so nobody's spot moves when someone drops out). */
    public final int count;
    public final Vec3 start;
    public final Vec3 goal;
    /** Unit vector of the march direction (horizontal) and the bots' left side. */
    public final double forwardX;
    public final double forwardZ;
    public final float yaw;
    public final int columns;
    public final int ranks;
    public final double spacingSide;
    public final double spacingBack;
    public final double speed;
    /** Sprinting march (pace sprint / sprintjump) and synchronized jumps (sprintjump). */
    public final boolean sprint;
    public final boolean hop;
    public final double length;
    public double progress;
    public Phase phase = Phase.FORMING;
    public long phaseTick;

    public ArmyDrill(List<BotPlayer> bots, List<MobUnits.Unit> units, Vec3 start, Vec3 goal, int columns, int ranks, double spacingSide, double spacingBack, double speed, long tick) {
        this.members.addAll(bots);
        this.mobs.addAll(units);
        this.count = bots.size() + units.size();
        this.start = start;
        this.goal = goal;
        double dx = goal.x - start.x;
        double dz = goal.z - start.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        this.forwardX = distance < 1.0E-4 ? 0.0 : dx / distance;
        this.forwardZ = distance < 1.0E-4 ? 1.0 : dz / distance;
        this.yaw = (float)Math.toDegrees(Math.atan2(-this.forwardX, this.forwardZ));
        this.length = distance;
        this.columns = columns;
        this.ranks = ranks;
        this.spacingSide = spacingSide;
        this.spacingBack = spacingBack;
        String pace = de.fiden.botsystem.bot.BotDefaults.get().movePace == null ? "walk" : de.fiden.botsystem.bot.BotDefaults.get().movePace;
        this.sprint = pace.equals("sprint") || pace.equals("sprintjump");
        this.hop = pace.equals("sprintjump");
        // Walk 0.19 b/t (just under full walking speed, room to correct); sprint 0.26; sprint-jump 0.30.
        this.speed = speed * (this.hop ? 1.55 : (this.sprint ? 1.35 : 1.0));
        this.phaseTick = tick;
        for (int i = 0; i < this.members.size(); i++) {
            this.members.get(i).drill = this;
        }

        for (MobUnits.Unit unit : this.mobs) {
            unit.drill = this;
            unit.mob.getNavigation().stop();
            unit.mob.setTarget(null, org.bukkit.event.entity.EntityTargetEvent.TargetReason.CUSTOM);
        }

        this.assign();
    }

    /**
     * Gives out the places closest-pair-first over the whole group (not front-to-back), so
     * nobody has to cross the block through everyone else to reach its spot. While forming
     * this runs again every 2 s: two members blocking each other just swap places.
     */
    public void assign() {
        List<net.minecraft.world.entity.LivingEntity> who = new ArrayList<>(this.members);
        for (MobUnits.Unit unit : this.mobs) {
            who.add(unit.mob);
        }

        int places = this.count;
        List<long[]> pairs = new ArrayList<>(who.size() * places);
        Vec3[] spots = new Vec3[places];
        for (int p = 0; p < places; p++) {
            spots[p] = this.slotAt(p);
        }

        for (int w = 0; w < who.size(); w++) {
            net.minecraft.world.entity.LivingEntity member = who.get(w);
            for (int p = 0; p < places; p++) {
                double dx = spots[p].x - member.getX();
                double dz = spots[p].z - member.getZ();
                pairs.add(new long[]{(long)((dx * dx + dz * dz) * 1000.0), w, p});
            }
        }

        pairs.sort((a, b) -> Long.compare(a[0], b[0]));
        boolean[] memberDone = new boolean[who.size()];
        boolean[] placeDone = new boolean[places];
        int left = who.size();
        for (long[] pair : pairs) {
            int w = (int)pair[1];
            int p = (int)pair[2];
            if (memberDone[w] || placeDone[p]) {
                continue;
            }

            memberDone[w] = true;
            placeDone[p] = true;
            net.minecraft.world.entity.LivingEntity member = who.get(w);
            if (member instanceof BotPlayer bot) {
                bot.drillSlot = p;
            } else {
                MobUnits.Unit unit = MobUnits.unitOf(member);
                if (unit != null) {
                    unit.drillSlot = p;
                }
            }

            if (--left == 0) {
                break;
            }
        }
    }

    public Vec3 slot(MobUnits.Unit unit) {
        return this.slotAt(unit.drillSlot);
    }

    /** Where a member stands right now: its column/rank offset from the moving front-line centre. */
    public Vec3 slot(BotPlayer bot) {
        return this.slotAt(bot.drillSlot);
    }

    public Vec3 slotAt(int slotIndex) {
        int index = Math.max(0, slotIndex);
        int rank = index / this.columns;
        int column = index % this.columns;
        // The last rank may be shorter: centre it too.
        int inRank = rank == this.ranks - 1 ? this.count - rank * this.columns : this.columns;
        double side = (column - (inRank - 1) / 2.0) * this.spacingSide;
        double back = rank * this.spacingBack;
        double frontX = this.start.x + this.forwardX * this.progress;
        double frontZ = this.start.z + this.forwardZ * this.progress;
        // Left of the marchers (vanilla: yaw 0 faces +z, its left is +x).
        double leftX = this.forwardZ;
        double leftZ = -this.forwardX;
        return new Vec3(frontX + leftX * side - this.forwardX * back, 0.0, frontZ + leftZ * side - this.forwardZ * back);
    }

    /** Once per tick, before the members think: advance the block if everyone keeps up. */
    public void update(long tick) {
        this.members.removeIf(bot -> bot.isRemoved() || bot.isDeadOrDying() || bot.drill != this);
        this.mobs.removeIf(unit -> unit.mob.isRemoved() || unit.mob.isDeadOrDying() || unit.drill != this);
        if (this.members.isEmpty() && this.mobs.isEmpty()) {
            return;
        }

        double worstAll = 0.0;
        int standingCount = 0;
        int total = this.members.size() + this.mobs.size();
        double[] gaps = new double[total];
        for (int i = 0; i < total; i++) {
            net.minecraft.world.entity.LivingEntity who = i < this.members.size() ? this.members.get(i) : this.mobs.get(i - this.members.size()).mob;
            Vec3 slot = i < this.members.size() ? this.slot(this.members.get(i)) : this.slot(this.mobs.get(i - this.members.size()));
            double dx = slot.x - who.getX();
            double dz = slot.z - who.getZ();
            gaps[i] = Math.sqrt(dx * dx + dz * dz);
            worstAll = Math.max(worstAll, gaps[i]);
            if (who.onGround()) {
                standingCount++;
            }
        }

        // The block follows the bulk of the army, not its single worst member: with many bots on
        // real terrain someone is always a bit off, and waiting for that one made it crawl.
        // Stragglers sprint to catch up (drillStep).
        java.util.Arrays.sort(gaps);
        double worst = gaps[Math.min(gaps.length - 1, (int)Math.floor(gaps.length * 0.8))];
        boolean standing = standingCount >= total * 0.8;

        switch (this.phase) {
            case FORMING -> {
                if ((tick - this.phaseTick) % 40L == 39L) {
                    this.assign();
                }

                if (worst < 0.6 && standing || tick - this.phaseTick > 200L) {
                    de.fiden.botsystem.BotSystemMod.LOGGER.info("[armymarch] formed after " + (tick - this.phaseTick) + " ticks (80% within " + String.format(java.util.Locale.ROOT, "%.2f", worst) + ")");
                    this.phase = Phase.READY;
                    this.phaseTick = tick;
                }
            }
            case READY -> {
                // A short "attention" pause, then everyone steps off on the same tick.
                if (tick - this.phaseTick >= 20L) {
                    this.phase = Phase.MARCHING;
                    this.phaseTick = tick;
                }
            }
            case MARCHING -> {
                // Never waits for stragglers - they path back on their own.
                this.progress = Math.min(this.length, this.progress + this.speed);
                if (this.progress >= this.length && (worst < 0.4 || tick - this.phaseTick > 200L && this.progress >= this.length)) {
                    this.phase = Phase.HALTED;
                    this.phaseTick = tick;
                }
            }
            case HALTED -> {
            }
        }
    }

    /** A halted block keeps standing at attention until its members get other orders. */
    public boolean finished(long tick) {
        return false;
    }

    public void dismiss() {
        for (BotPlayer bot : this.members) {
            if (bot.drill == this) {
                bot.drill = null;
                bot.drillSlot = -1;
            }
        }

        for (MobUnits.Unit unit : this.mobs) {
            if (unit.drill == this) {
                unit.drill = null;
                unit.drillSlot = -1;
            }
        }

        this.members.clear();
        this.mobs.clear();
    }

    /** Speed factor that makes this mob cover `perTick` blocks per tick (measured: ~0.6 x attribute). */
    private static double modifierFor(Mob mob, double perTick) {
        AttributeInstance speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        double base = speed != null && speed.getValue() > 0.01 ? speed.getValue() : 0.25;
        return Mth.clamp(perTick / (base * 0.6), 0.05, 3.0);
    }

    /**
     * Once per tick for the mob members: in step - steer exactly at the moving spot, facing the
     * march direction; fallen behind (more than 2.5 blocks) - real pathfinding toward a point a
     * bit ahead of the spot, faster than the block, until back in step.
     */
    public void steerMobs(long tick) {
        boolean moving = this.phase == Phase.MARCHING && this.progress < this.length;
        for (MobUnits.Unit unit : this.mobs) {
            Mob mob = unit.mob;
            Vec3 slot = this.slot(unit);
            double dx = slot.x - mob.getX();
            double dz = slot.z - mob.getZ();
            double gap = Math.sqrt(dx * dx + dz * dz);
            if (gap < 1.0 || gap < unit.drillBestGap - 0.3) {
                unit.drillBestGap = gap;
                unit.drillProgressTick = tick;
            }

            // Bumps, steps, a jump: stay in the march and step/jump over it. Only when really lost
            // (far off, or not getting closer for 2 s) take the pathfinding.
            if (gap > 6.0 || gap > 1.5 && tick - unit.drillProgressTick > 20L) {
                if (tick >= unit.drillRepath || mob.getNavigation().isDone()) {
                    unit.drillRepath = tick + 10L;
                    double lead = moving ? Math.min(4.0, gap * 0.5) : 0.0;
                    Vec3 ahead = Ground.find((ServerLevel)mob.level(), slot.x + this.forwardX * lead, slot.z + this.forwardZ * lead, mob.getY());
                    // Catch up at a believable run (capped), not a superhuman dash.
                    double hurry = Math.min(gap > 6.0 ? 1.6 : 1.3, modifierFor(mob, moving ? this.speed * (gap > 6.0 ? 1.7 : 1.4) + 0.05 : 0.18));
                    boolean found = mob.getNavigation().moveTo(ahead.x, ahead.y, ahead.z, hurry);
                    if (!found) {
                        // No path found: head straight for it.
                        mob.getMoveControl().setWantedPosition(ahead.x, ahead.y, ahead.z, hurry);
                    }
                }

                continue;
            }

            mob.getNavigation().stop();
            // Close in firmly even on the last half block (mobs have more drift than bots).
            double pull = gap < 0.05 ? 0.0 : 0.35 + 0.04 / Math.max(0.2, gap);
            double vx = (moving ? this.forwardX * this.speed : 0.0) + dx * pull;
            double vz = (moving ? this.forwardZ * this.speed : 0.0) + dz * pull;
            double v = Math.sqrt(vx * vx + vz * vz);
            if (v > 0.012 && (moving || gap > 0.15)) {
                if (mob.horizontalCollision && mob.onGround()) {
                    if (moving && BotBrain.blockedHigh(mob, this.forwardX, this.forwardZ)) {
                        // Too high to jump: sidestep around it, then back into line.
                        double side = Math.floorMod(unit.drillSlot, 2) == 0 ? 1.0 : -1.0;
                        vx = this.forwardX * 0.05 + this.forwardZ * side * 0.25;
                        vz = this.forwardZ * 0.05 - this.forwardX * side * 0.25;
                        v = Math.sqrt(vx * vx + vz * vz);
                    } else {
                        mob.getJumpControl().jump();
                    }
                }

                mob.getMoveControl().setWantedPosition(mob.getX() + vx / v * 2.0, mob.getY(), mob.getZ() + vz / v * 2.0, Math.min(1.4, modifierFor(mob, v)));
            } else {
                mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0.0);
            }

            if (!moving && gap <= 0.15) {
                mob.setYRot(this.yaw);
                mob.setYHeadRot(this.yaw);
                mob.setYBodyRot(this.yaw);
            }
        }
    }
}
