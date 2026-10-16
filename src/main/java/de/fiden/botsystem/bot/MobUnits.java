package de.fiden.botsystem.bot;

import de.fiden.botsystem.BotSystemMod;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.BreedGoal;
import net.minecraft.world.entity.ai.goal.FleeSunGoal;
import net.minecraft.world.entity.ai.goal.FollowParentGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MoveThroughVillageGoal;
import net.minecraft.world.entity.ai.goal.MoveToBlockGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.TemptGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.bukkit.event.entity.EntityTargetEvent.TargetReason;

public final class MobUnits {
    private static MobUnits instance;
    private final Map<UUID, MobUnits.Unit> units = new LinkedHashMap<>();
    private final Long2ObjectOpenHashMap<List<MobUnits.Unit>> grid = new Long2ObjectOpenHashMap<>();
    private long gridTick = Long.MIN_VALUE;

    public MobUnits() {
        instance = this;
    }

    public static String teamOf(Entity entity) {
        if (entity instanceof BotPlayer bot) {
            return bot.teamKey();
        } else {
            MobUnits.Unit unit = unitOf(entity);
            return unit == null ? null : unit.teamKey();
        }
    }

    public static MobUnits.Unit unitOf(Entity entity) {
        return instance != null && entity != null ? instance.units.get(entity.getUUID()) : null;
    }

    public MobUnits.Unit byId(UUID id) {
        return this.units.get(id);
    }

    public Collection<MobUnits.Unit> all() {
        return new ArrayList<>(this.units.values());
    }

    public int size() {
        return this.units.size();
    }

    public boolean isEmpty() {
        return this.units.isEmpty();
    }

    public List<String> teams() {
        List<String> teams = new ArrayList<>();

        for (MobUnits.Unit unit : this.units.values()) {
            if (unit.team != null && teams.stream().noneMatch(t -> t.equalsIgnoreCase(unit.team))) {
                teams.add(unit.team);
            }
        }

        return teams;
    }

    private void refreshGrid(long tick) {
        if (tick != this.gridTick) {
            this.gridTick = tick;
            this.grid.clear();

            for (MobUnits.Unit unit : this.units.values()) {
                if (unit.mob.isAlive()) {
                    long cell = (long)Math.floorDiv(unit.mob.getBlockX(), 16) << 32 ^ Math.floorDiv(unit.mob.getBlockZ(), 16) & 4294967295L;
                    this.grid.computeIfAbsent(cell, key -> new ArrayList<>()).add(unit);
                }
            }
        }
    }

    public MobUnits.Unit nearest(ServerLevel world, Vec3 pos, double radius, double bestSq, MobUnits.Unit self, Predicate<MobUnits.Unit> wanted) {
        this.refreshGrid(world.getServer().getTickCount());
        int cx = Math.floorDiv((int)Math.floor(pos.x), 16);
        int cz = Math.floorDiv((int)Math.floor(pos.z), 16);
        int maxRing = (int)Math.ceil(radius / 16.0);
        double best = Math.min(bestSq, radius * radius);
        MobUnits.Unit found = null;
        String own = self == null ? null : self.teamKey();
        boolean skipMates = self != null && own != null && !self.freeForAll && self.targetIds.isEmpty();

        for (int ring = 0; ring <= maxRing; ring++) {
            double ringMin = Math.max(0.0, (ring - 1) * 16.0);
            if (ringMin * ringMin > best) {
                break;
            }

            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                        List<MobUnits.Unit> cell = this.grid.get((long)(cx + dx) << 32 ^ cz + dz & 4294967295L);
                        if (cell != null) {
                            for (MobUnits.Unit unit : cell) {
                                if (unit != self && (!skipMates || !own.equals(unit.teamKey()))) {
                                    double distance = unit.mob.position().distanceToSqr(pos);
                                    if (!(distance >= best) && unit.mob.level() == world && unit.mob.isAlive() && wanted.test(unit)) {
                                        best = distance;
                                        found = unit;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return found;
    }

    public void ring(ServerLevel world, Vec3 pos, int ring, Consumer<MobUnits.Unit> consumer) {
        this.refreshGrid(world.getServer().getTickCount());
        int cx = Math.floorDiv((int)Math.floor(pos.x), 16);
        int cz = Math.floorDiv((int)Math.floor(pos.z), 16);

        for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                    List<MobUnits.Unit> cell = this.grid.get((long)(cx + dx) << 32 ^ cz + dz & 4294967295L);
                    if (cell != null) {
                        for (MobUnits.Unit unit : cell) {
                            if (unit.mob.level() == world && unit.mob.isAlive()) {
                                consumer.accept(unit);
                            }
                        }
                    }
                }
            }
        }
    }

    public List<MobUnits.Unit> near(ServerLevel world, Vec3 pos, double radius) {
        this.refreshGrid(world.getServer().getTickCount());
        List<MobUnits.Unit> found = new ArrayList<>();
        double limit = radius * radius;
        int reach = (int)Math.ceil(radius / 16.0);
        int cx = Math.floorDiv((int)Math.floor(pos.x), 16);
        int cz = Math.floorDiv((int)Math.floor(pos.z), 16);

        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                List<MobUnits.Unit> cell = this.grid.get((long)(cx + dx) << 32 ^ cz + dz & 4294967295L);
                if (cell != null) {
                    for (MobUnits.Unit unit : cell) {
                        if (unit.mob.level() == world && unit.mob.isAlive() && unit.mob.position().distanceToSqr(pos) <= limit) {
                            found.add(unit);
                        }
                    }
                }
            }
        }

        return found;
    }

    public List<MobUnits.Unit> spawn(ServerLevel world, EntityType<?> type, int count, String team, Vec3 centre) {
        List<MobUnits.Unit> spawned = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            double angle = i * Math.PI * (3.0 - Math.sqrt(5.0));
            double outer = count == 1 ? 1.5 : Math.max(2.5, Math.sqrt(count) * 1.4);
            double radius = count == 1 ? 1.5 : outer * Math.sqrt((i + 0.5) / count);
            Vec3 spot = Ground.find(world, centre.x + Math.cos(angle) * radius, centre.z + Math.sin(angle) * radius, centre.y);
            if (!(type.create(world, EntitySpawnReason.COMMAND) instanceof Mob mob)) {
                return null;
            }

            mob.snapTo(spot.x, spot.y, spot.z, world.random.nextFloat() * 360.0F, 0.0F);
            mob.finalizeSpawn(world, world.getCurrentDifficultyAt(mob.blockPosition()), EntitySpawnReason.COMMAND, null);
            mob.setPersistenceRequired();
            Nms.targetSelector(mob).removeAllGoals(goal -> true);
            Nms.goalSelector(mob)
                .removeAllGoals(
                    goal -> goal instanceof LookAtPlayerGoal
                        || goal instanceof AvoidEntityGoal
                        || goal instanceof MoveThroughVillageGoal
                        || goal instanceof MoveToBlockGoal
                );
            Nms.goalSelector(mob)
                .removeAllGoals(
                    goal -> goal instanceof PanicGoal
                        || goal instanceof AvoidEntityGoal
                        || goal instanceof TemptGoal
                        || goal instanceof BreedGoal
                        || goal instanceof FollowParentGoal
                        || goal instanceof RandomStrollGoal
                        || goal instanceof FleeSunGoal
                );
            if (mob instanceof net.minecraft.world.entity.npc.villager.AbstractVillager) {
                // Villagers walk by their brain (work, gossip, wander): as units they go where they're
                // sent instead - it made them crawl and wobble.
                mob.getBrain().removeAllBehaviors();
                // ...and no sensors: each villager scanning 16 blocks for every living thing and
                // baby around it is a big cost in crowds of hundreds.
                try {
                    java.lang.reflect.Field sensors = net.minecraft.world.entity.ai.Brain.class.getDeclaredField("sensors");
                    sensors.setAccessible(true);
                    ((java.util.Map<?, ?>)sensors.get(mob.getBrain())).clear();
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }

            AttributeInstance follow = mob.getAttribute(Attributes.FOLLOW_RANGE);
            if (follow != null && follow.getBaseValue() < 40.0) {
                follow.setBaseValue(40.0);
            }

            if (mob instanceof AbstractPiglin piglin) {
                piglin.setImmuneToZombification(true);
            } else if (mob instanceof Hoglin hoglin) {
                hoglin.setImmuneToZombification(true);
            }

            if (mob.getType().is(EntityTypeTags.UNDEAD) && mob.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
                mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
                mob.setDropChance(EquipmentSlot.HEAD, 0.0F);
            }

            if (world.addFreshEntity(mob)) {
                MobUnits.Unit unit = new MobUnits.Unit(mob, team);
                // Spread the units' thinking over the ticks instead of all at once (lag spikes).
                unit.nextThink = world.getServer().getTickCount() + Math.floorMod(mob.getId(), 10);
                AttributeInstance attack = mob.getAttribute(Attributes.ATTACK_DAMAGE);
                boolean hasAttackAi = Nms.goalSelector(mob)
                    .getAvailableGoals()
                    .stream()
                    .anyMatch(goal -> goal.getGoal().getClass().getSimpleName().matches(".*(Attack|Shoot|Fireball|Spit|Charge|Ram|Explode).*"));
                // Mobs with their own attack AI (pillagers, zombies, skeletons ...) fight the vanilla
                // way once they have a target - vanilla speed, crossbow loading, animations.
                // Only mobs without one (villagers, animals ...) get the custom attack.
                unit.customRanged = !hasAttackAi && mob instanceof RangedAttackMob;
                unit.customMelee = !hasAttackAi && !unit.customRanged && !(mob instanceof Creeper);
                unit.meleeDamage = attack != null && attack.getValue() > 0.0
                    ? (float)attack.getValue()
                    : Mth.clamp(Math.round(mob.getMaxHealth() / 6.0F), 1, 8);
                this.units.put(mob.getUUID(), unit);
                spawned.add(unit);
            }
        }

        return spawned;
    }

    public void remove(MobUnits.Unit unit) {
        this.units.remove(unit.mob.getUUID());
        UnitPush.release(unit);
        unit.mob.discard();
    }

    public int removeAll() {
        int count = this.units.size();

        for (MobUnits.Unit unit : this.all()) {
            this.remove(unit);
        }

        return count;
    }

    /** Mobs targeting each guarded owner (looked up once per owner per tick, shared by all guards). */
    private final Map<UUID, List<LivingEntity>> ownerThreats = new java.util.HashMap<>();
    private long ownerThreatsTick = Long.MIN_VALUE;

    private List<LivingEntity> threatsTo(ServerPlayer owner, long tick) {
        if (this.ownerThreatsTick != tick) {
            this.ownerThreatsTick = tick;
            this.ownerThreats.clear();
        }

        return this.ownerThreats.computeIfAbsent(owner.getUUID(), id -> {
            List<LivingEntity> found = new ArrayList<>();
            for (Mob other : owner.level().getEntitiesOfClass(Mob.class, owner.getBoundingBox().inflate(12.0), m -> m.isAlive() && m.getTarget() == owner)) {
                MobUnits.Unit own = unitOf(other);
                if (own == null || !id.equals(own.followId)) {
                    found.add(other);
                }
            }

            return found;
        });
    }

    /** How many units currently target each entity (rebuilt once per tick). */
    private final java.util.IdentityHashMap<LivingEntity, Integer> attackers = new java.util.IdentityHashMap<>();

    private int attackersOf(LivingEntity target) {
        return this.attackers.getOrDefault(target, 0);
    }

    /** Speed factor for chasing: about a walking player's pace (~0.25 b/t) whatever the mob. */
    private static double chaseSpeed(Mob mob) {
        AttributeInstance speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        double base = speed != null && speed.getValue() > 0.01 ? speed.getValue() : 0.25;
        return Mth.clamp(0.25 / (base * 0.6), 0.6, 1.6);
    }

    public void tick(MinecraftServer server, Function<MobUnits.Unit, LivingEntity> targetFinder, long tick) {
        if (BotSystemMod.bots() != null) {
            UnitPush.tick(this.units.values(), BotSystemMod.bots().all());
        }

        this.attackers.clear();
        for (MobUnits.Unit unit : this.units.values()) {
            LivingEntity target = unit.mob.getTarget();
            if (target != null) {
                this.attackers.merge(target, 1, Integer::sum);
            }
        }

        for (MobUnits.Unit unit : this.all()) {
            Mob mob = unit.mob;
            ServerLevel world = (ServerLevel)mob.level();
            if (!mob.isRemoved() && !mob.isDeadOrDying()) {
                if (mob.isInWater()
                    && (mob.getFluidHeight(FluidTags.WATER) > mob.getFluidJumpThreshold() || mob.isUnderWater())
                    && world.random.nextFloat() < 0.8F) {
                    mob.getJumpControl().jump();
                }

                if (unit.lookAtId != null && (tick & 1L) == 0L && mob.getTarget() == null) {
                    Entity watched = world.getEntity(unit.lookAtId);
                    if (watched != null) {
                        mob.getLookControl().setLookAt(watched, 30.0F, 30.0F);
                    }
                }

                if (unit.drill != null) {
                    continue; // the army march steers it (ArmyDrill.steerMobs)
                }

                if (tick >= unit.nextThink) {
                    unit.nextThink = tick + 10L;
                    if (unit.immortal) {
                        mob.setHealth(mob.getMaxHealth());
                    }

                    LivingEntity current = mob.getTarget();
                    if (current != null && (!current.isAlive() || current.isRemoved() || !unit.targets(current) && !unit.targetIds.contains(current.getUUID()))
                        )
                     {
                        mob.setTarget(null, TargetReason.CUSTOM);
                        current = null;
                    }

                    if (unit.responseTeam != null) {
                        if (current != null && unit.responseTeam.equals(teamOf(current))) {
                            unit.responseUntil = Math.max(unit.responseUntil, tick + 300L);
                        } else if (current == null && tick >= unit.responseUntil) {
                            unit.enemyTeams.remove(unit.responseTeam);
                            unit.responseTeam = null;
                            unit.approach = null;
                            unit.fightMode = !unit.enemyTeams.isEmpty() || !unit.targetIds.isEmpty();
                        }
                    }

                    ServerPlayer owner = unit.followId == null ? null : server.getPlayerList().getPlayer(unit.followId);
                    if (owner != null && owner.isRemoved()) {
                        owner = null;
                    }

                    if (unit.followId != null) {
                        // Never against the owner: drop it as a target and calm vanilla anger (wolves).
                        unit.targetIds.remove(unit.followId);
                        if (current != null && current.getUUID().equals(unit.followId)) {
                            mob.setTarget(null, TargetReason.CUSTOM);
                            current = null;
                            unit.approach = null;
                        }

                        if (mob instanceof net.minecraft.world.entity.NeutralMob neutral && unit.followId.equals(neutral.getPersistentAngerTarget())) {
                            neutral.stopBeingAngry();
                        }

                        if (mob.getLastHurtByMob() != null && mob.getLastHurtByMob().getUUID().equals(unit.followId)) {
                            mob.setLastHurtByMob(null);
                        }
                    }

                    if (owner != null && unit.guardMode) {
                        for (LivingEntity other : new LivingEntity[]{owner.getLastHurtByMob(), owner.getLastHurtMob()}) {
                            if ((!(other instanceof ServerPlayer) || other instanceof BotPlayer || !BotDefaults.get().ignorePlayers)
                                && other != null
                                && other != owner
                                && other != mob
                                && other.isAlive()
                                && !unit.sameTeam(other)
                                && mob.distanceToSqr(other) < 2304.0) {
                                unit.targetIds.add(other.getUUID());
                            }
                        }

                        // Don't wait for the first hit: whatever is already after the owner (a mob
                        // targeting them within 12 blocks) is a threat to the guards now.
                        for (LivingEntity threat : this.threatsTo(owner, tick)) {
                            if (threat != mob && !unit.sameTeam(threat)) {
                                unit.targetIds.add(threat.getUUID());
                            }
                        }
                    }

                    if (unit.stance == Stance.PASSIVE && current != null && current != mob.getLastHurtByMob()) {
                        mob.setTarget(null, TargetReason.CUSTOM);
                        current = null;
                        unit.approach = null;
                    }

                    if (unit.stance == Stance.DEFENSIVE && current != null) {
                        Vec3 hold = unit.holdPoint();
                        if (current.position().distanceToSqr(hold) > 196.0 || mob.position().distanceToSqr(hold) > 256.0) {
                            mob.setTarget(null, TargetReason.CUSTOM);
                            current = null;
                            unit.approach = null;
                            mob.getNavigation().stop();
                        }
                    }

                    boolean recentlyHit = mob.getLastHurtByMob() != null && mob.tickCount - mob.getLastHurtByMobTimestamp() < 60;
                    boolean ordered = unit.goal != null && tick < unit.orderUntil && !unit.marching;
                    // Stay within 12 blocks of the owner - 24 while fighting, so a guard can actually go
                    // after a threat (pillagers need to get within 8 blocks of it to shoot).
                    boolean leashed = owner != null && unit.patrolArea == null && mob.distanceToSqr(owner) > (current != null && current.isAlive() ? 576.0 : 144.0);
                    if ((ordered || leashed) && !recentlyHit) {
                        if (current != null) {
                            mob.setTarget(null, TargetReason.CUSTOM);
                            current = null;
                        }

                        unit.approach = null;
                    } else if ((unit.fightMode || !unit.hostileTeams.isEmpty() || !unit.targetIds.isEmpty() || BotDefaults.get().autoWar)
                        && unit.stance != Stance.PASSIVE) {
                        LivingEntity found = targetFinder.apply(unit);
                        if (found != null && unit.stance == Stance.DEFENSIVE && found.position().distanceToSqr(unit.holdPoint()) > 144.0) {
                            found = null;
                        }

                        if (found != null && !unit.targetIds.contains(found.getUUID())) {
                            double limit = unit.marching && unit.goal != null ? 16.0 : (unit.post != null && unit.sightRange > 0.0 ? unit.sightRange : 0.0);
                            if (limit > 0.0 && mob.distanceToSqr(found) > limit * limit) {
                                found = null;
                            }
                        }

                        if (found != null && (current == null || mob.distanceToSqr(found) < mob.distanceToSqr(current) * 0.5)) {
                            unit.approach = found;
                            if (mob.distanceToSqr(found) <= (mob instanceof RangedAttackMob ? 400.0 : 144.0)) {
                                mob.setTarget(found, TargetReason.CUSTOM);
                                current = found;
                                unit.fightMode = true;
                            } else if (current != null) {
                                mob.setTarget(null, TargetReason.CUSTOM);
                                current = null;
                            }
                        } else if (found == null) {
                            unit.approach = null;
                        }
                    }

                    LivingEntity prey = current != null ? current : (unit.approach != null && unit.approach.isAlive() ? unit.approach : null);
                    if (current != null && !unit.customRanged && !unit.customMelee && mob.distanceToSqr(current) > 81.0) {
                        // Vanilla attack AI fights it - just bring the mob into its range first, each
                        // from its own side.
                        unit.nextThink = tick + 5L;
                        this.approach(unit, spreadSpot(mob, current, mob instanceof RangedAttackMob ? 7.0 : 1.5));
                    }

                    if (prey != null && unit.customRanged) {
                        unit.nextThink = tick + 4L;
                        mob.getLookControl().setLookAt(prey, 30.0F, 30.0F);
                        double gapSq = mob.distanceToSqr(prey);
                        if (gapSq < 400.0 && mob.hasLineOfSight(prey)) {
                            // Hold a shooting distance (7-12 blocks), each on its own side, instead
                            // of the whole group standing in one heap.
                            Vec3 spot = spreadSpot(mob, prey, gapSq < 49.0 ? 9.0 : Math.min(12.0, Math.sqrt(gapSq)));
                            if (gapSq < 49.0 || spot.distanceToSqr(mob.position()) > 9.0) {
                                mob.getNavigation().moveTo(spot.x, spot.y, spot.z, walkSpeed(mob));
                            } else {
                                mob.getNavigation().stop();
                            }

                            if (tick >= unit.nextAttack) {
                                unit.nextAttack = tick + 26L + world.random.nextInt(10);
                                loadCrossbow(mob);
                                ((RangedAttackMob)mob).performRangedAttack(prey, 1.0F);
                            }
                        } else {
                            this.approach(unit, spreadSpot(mob, prey, 10.0));
                        }
                    } else if (prey != null && unit.customMelee) {
                        // Mobs without their own attack AI (villagers, animals ...): chase and hit like a
                        // fighter - re-steered every 2 ticks up close, vanilla melee range, a proper pace.
                        double distSq = mob.distanceToSqr(prey);
                        unit.nextThink = tick + (distSq < 144.0 ? 2L : 6L);
                        mob.getLookControl().setLookAt(prey, 30.0F, 30.0F);
                        double dx = prey.getX() - mob.getX();
                        double dz = prey.getZ() - mob.getZ();
                        if (mob.isWithinMeleeAttackRange(prey) && mob.getSensing().hasLineOfSight(prey)) {
                            mob.getNavigation().stop();
                            mob.getMoveControl().setWantedPosition(prey.getX(), prey.getY(), prey.getZ(), chaseSpeed(mob) * 0.4);
                            hopIfBlocked(mob);
                            if (tick >= unit.nextAttack) {
                                unit.nextAttack = tick + 18L + world.random.nextInt(5);
                                mob.swing(InteractionHand.MAIN_HAND);
                                AttributeInstance attackNow = mob.getAttribute(Attributes.ATTACK_DAMAGE);
                                float hit = attackNow != null && attackNow.getValue() > 0.0 ? (float)attackNow.getValue() : unit.meleeDamage;
                                if (prey.hurtServer(world, world.damageSources().mobAttack(mob), hit)) {
                                    prey.knockback(0.4, -dx, -dz);
                                }
                            }
                        } else if (distSq > 256.0) {
                            this.approach(unit, prey.position(), chaseSpeed(mob));
                        } else {
                            // Alone or in pairs: straight at it. Three or more on one target: each
                            // takes its own side so they surround it instead of shoving.
                            Vec3 aim = this.attackersOf(prey) >= 3 ? spreadSpot(mob, prey, 1.3) : prey.position();
                            if (distSq < 64.0 && mob.getSensing().hasLineOfSight(prey)) {
                                mob.getNavigation().stop();
                                mob.getMoveControl().setWantedPosition(aim.x, aim.y, aim.z, chaseSpeed(mob));
                                hopIfBlocked(mob);
                            } else if (tick >= unit.nextChasePath || mob.getNavigation().isDone()) {
                                unit.chaseTarget = aim;
                                unit.nextChasePath = tick + 10L;
                                mob.getNavigation().moveTo(aim.x, aim.y, aim.z, chaseSpeed(mob));
                            }
                        }
                    } else if (current == null) {
                        if (prey != null) {
                            this.approach(unit, prey.position());
                        } else if (owner != null && unit.patrolArea == null) {
                            double gap = mob.distanceToSqr(owner);
                            if (gap > 1600.0 && mob.level() == owner.level()) {
                                Vec3 spot = Ground.find(
                                    world, owner.getX() + world.random.nextInt(5) - 2.0, owner.getZ() + world.random.nextInt(5) - 2.0, owner.getY()
                                );
                                mob.snapTo(spot.x, spot.y, spot.z, mob.getYRot(), mob.getXRot());
                                mob.getNavigation().stop();
                            } else {
                                // Guard/follow from an own spot 3.5-6 blocks around the owner, not
                                // pressed against them from every side.
                                Vec3 spot = guardSpot(mob, owner);
                                double off = mob.distanceToSqr(spot);
                                if (off > 6.25) {
                                    // Fell behind (more than ~10 blocks): hurry like a sprinting player.
                                    this.approach(unit, spot, walkSpeed(mob) * (gap > 100.0 ? 1.35 : 1.0));
                                } else if (!mob.getNavigation().isDone()) {
                                    mob.getNavigation().stop();
                                } else if (world.random.nextInt(4) == 0) {
                                    mob.getLookControl().setLookAt(owner, 20.0F, 20.0F);
                                }
                            }
                        } else {
                            if (unit.goal == null
                                && unit.post == null
                                && unit.patrolArea == null
                                && unit.stance == Stance.DEFENSIVE
                                && unit.stanceAnchor != null) {
                                double ax = unit.stanceAnchor.x - mob.getX();
                                double az = unit.stanceAnchor.z - mob.getZ();
                                if (ax * ax + az * az > 9.0 && ax * ax + az * az < 4096.0) {
                                    this.approach(unit, unit.stanceAnchor);
                                    continue;
                                }
                            }

                            if (unit.goal == null && unit.patrolArea != null && tick >= unit.nextPatrolPick) {
                                unit.goal = Ground.randomDryNear(world, unit.patrolArea, mob.getX(), mob.getZ(), 30.0, mob.getY(), ThreadLocalRandom.current());
                                if (unit.goal == null) {
                                    unit.nextPatrolPick = tick + 40L;
                                    continue;
                                }

                                unit.goalDeadline = tick + 1200L;
                                unit.nextPatrolPick = tick + 1800L;
                            }

                            if (unit.goal != null) {
                                double dx = unit.goal.x - mob.getX();
                                double dz = unit.goal.z - mob.getZ();
                                if (!(dx * dx + dz * dz < 2.25) && tick <= unit.goalDeadline) {
                                    this.approach(unit, unit.goal);
                                } else {
                                    unit.goal = null;
                                    unit.marching = false;
                                    unit.nextPatrolPick = tick + 100L + world.random.nextInt(300);
                                    mob.getNavigation().stop();
                                }
                            } else if (unit.post != null) {
                                double dx = unit.post.x - mob.getX();
                                double dz = unit.post.z - mob.getZ();
                                if (dx * dx + dz * dz > 4.0) {
                                    this.approach(unit, unit.post);
                                }
                            }
                        }
                    }
                }
            } else {
                this.units.remove(mob.getUUID());
                UnitPush.release(unit);
                if (mob.isDeadOrDying() && BotSystemMod.bots() != null) {
                    BotSystemMod.bots().recordDeath(mob, mob.getLastDamageSource());
                }
            }
        }
    }

    /**
     * Direct steering (MoveControl) only jumps right at its goal, not at a block in the way:
     * hop over a one-block step like a player would (not at walls too high to clear).
     */
    private static void hopIfBlocked(Mob mob) {
        if (mob.horizontalCollision && mob.onGround()) {
            double yaw = Math.toRadians(mob.getYRot());
            net.minecraft.core.BlockPos ahead = net.minecraft.core.BlockPos.containing(mob.getX() - Math.sin(yaw) * 0.8, mob.getY() + 0.2, mob.getZ() + Math.cos(yaw) * 0.8);
            ServerLevel level = (ServerLevel)mob.level();
            boolean headFree = level.getBlockState(ahead.above()).getCollisionShape(level, ahead.above()).isEmpty()
                && level.getBlockState(ahead.above(2)).getCollisionShape(level, ahead.above(2)).isEmpty();
            if (headFree) {
                mob.getJumpControl().jump();
            }
        }
    }

    /** Golden-angle direction per mob: neighbours in a group always get different sides. */
    private static double angleOf(Mob mob) {
        return Math.floorMod(mob.getId(), 1000) * 2.399963229728653;
    }

    /** A point around the target at this distance, on the mob's own side (biased toward where it is). */
    private static Vec3 spreadSpot(Mob mob, LivingEntity target, double distance) {
        double toward = Math.atan2(mob.getZ() - target.getZ(), mob.getX() - target.getX());
        double own = angleOf(mob);
        double angle = toward + Math.sin(own) * 1.1;
        return new Vec3(target.getX() + Math.cos(angle) * distance, target.getY(), target.getZ() + Math.sin(angle) * distance);
    }

    /** Where a guard/follower stands around its owner: 3.5-6 blocks out, mostly behind and to the sides. */
    private static Vec3 guardSpot(Mob mob, LivingEntity owner) {
        double yaw = Math.toRadians(owner.getYRot());
        double behind = Math.atan2(-Math.cos(yaw), Math.sin(yaw));
        double angle = behind + Math.sin(angleOf(mob)) * 1.9;
        double radius = 3.5 + Math.floorMod(mob.getId(), 5) * 0.6;
        return new Vec3(owner.getX() + Math.cos(angle) * radius, owner.getY(), owner.getZ() + Math.sin(angle) * radius);
    }

    /** Crossbow mobs only fire a loaded crossbow - load it right before the shot. */
    private static void loadCrossbow(Mob mob) {
        for (InteractionHand hand : InteractionHand.values()) {
            net.minecraft.world.item.ItemStack held = mob.getItemInHand(hand);
            if (held.getItem() instanceof net.minecraft.world.item.CrossbowItem && !net.minecraft.world.item.CrossbowItem.isCharged(held)) {
                held.set(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES,
                    net.minecraft.world.item.component.ChargedProjectiles.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW)));
            }
        }
    }

    private void approach(MobUnits.Unit unit, Vec3 goal) {
        this.approach(unit, goal, walkSpeed(unit.mob));
    }

    /**
     * The speed vanilla moves this kind of mob at with purpose (navigation speed modifier):
     * villagers and other peaceful mobs walk at ~0.6, monsters at 1.0 - no extra boost.
     */
    private static double walkSpeed(Mob mob) {
        // Movement = speed attribute x this factor. Aim for about a walking player (~0.32), so a
        // villager (0.5) isn't racing and a pillager (0.35) or zombie (0.23) isn't crawling.
        AttributeInstance speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        double base = speed != null && speed.getValue() > 0.01 ? speed.getValue() : 0.25;
        return Mth.clamp(0.32 / base, 0.55, 1.35);
    }

    private void approach(MobUnits.Unit unit, Vec3 goal, double speed) {
        Mob mob = unit.mob;
        if (mob.getNavigation().isDone() || unit.pathGoal == null || !(unit.pathGoal.distanceToSqr(goal) < 4.0) || unit.pathSpeed != speed) {
            unit.pathGoal = goal;
            unit.pathSpeed = speed;
            Vec3 pos = mob.position();
            Vec3 flat = new Vec3(goal.x - pos.x, 0.0, goal.z - pos.z);
            double distance = flat.length();
            Vec3 hop = distance > 12.0 ? pos.add(flat.scale(12.0 / distance)) : goal;
            Vec3 ground = distance > 12.0 ? Ground.find((ServerLevel)mob.level(), hop.x, hop.z, pos.y) : hop;
            if (!mob.getNavigation().moveTo(ground.x, ground.y, ground.z, speed) && distance > 3.0) {
                mob.getMoveControl().setWantedPosition(ground.x, ground.y, ground.z, speed);
                hopIfBlocked(mob);
            }
        }
    }

    public List<MobUnits.Unit> byTeam(String team) {
        List<MobUnits.Unit> found = new ArrayList<>();

        for (MobUnits.Unit unit : this.units.values()) {
            if (team.equalsIgnoreCase(unit.team)) {
                found.add(unit);
            }
        }

        return found;
    }

    public static boolean isRealPlayerTarget(LivingEntity entity) {
        return entity instanceof Player && !(entity instanceof BotPlayer);
    }

    public static final class Unit {
        public final Mob mob;
        /** The internal no-collision team it sits in (UnitPush), null = none. */
        public String pushTeam;
        public String team;
        public final Set<String> enemyTeams = new HashSet<>();
        public final Set<String> hostileTeams = new HashSet<>();
        public final Set<UUID> targetIds = new HashSet<>();
        public boolean freeForAll;
        public boolean fightMode;
        public boolean immortal;
        public Vec3 goal;
        public long goalDeadline;
        public long orderUntil;
        public Vec3 pathGoal;
        public double pathSpeed;
        public boolean marching;
        public long nextThink;
        public Vec3 post;
        public LivingEntity approach;
        public UUID followId;
        public boolean guardMode;
        /** /bot armymarch block this unit marches in (null = none), its place and next path refresh. */
        public de.fiden.botsystem.ai.ArmyDrill drill;
        public int drillSlot = -1;
        public long drillRepath;
        /** Closest it got to its march spot lately, and when - no progress for 2 s = lost. */
        public double drillBestGap = Double.MAX_VALUE;
        public long drillProgressTick;
        public boolean customMelee;
        public boolean customRanged;
        public float meleeDamage;
        public long nextAttack;
        public String responseTeam;
        public long responseUntil;
        public Vec3 chaseTarget;
        public long nextChasePath;
        public double sightRange;
        public double[] patrolArea;
        public long nextPatrolPick;
        public UUID lookAtId;
        public Stance stance;
        public Vec3 stanceAnchor;
        private String teamKeySource;
        private String teamKey;

        public Vec3 holdPoint() {
            if (this.post != null) {
                return this.post;
            }

            if (this.stanceAnchor == null) {
                this.stanceAnchor = this.mob.position();
            }

            return this.stanceAnchor;
        }

        Unit(Mob mob, String team) {
            this.sightRange = BotDefaults.get().sightRange;
            this.stance = Stance.AGGRESSIVE;
            this.mob = mob;
            this.team = team;
        }

        public String teamKey() {
            if (this.teamKeySource != this.team) {
                this.teamKeySource = this.team;
                this.teamKey = this.team == null ? null : this.team.toLowerCase(Locale.ROOT);
            }

            return this.teamKey;
        }

        public String name() {
            return this.mob.hasCustomName() ? this.mob.getCustomName().getString() : BuiltInRegistries.ENTITY_TYPE.getKey(this.mob.getType()).getPath();
        }

        public void clearFight() {
            this.enemyTeams.clear();
            this.hostileTeams.clear();
            this.targetIds.clear();
            this.freeForAll = false;
            this.fightMode = false;
            this.approach = null;
            this.mob.setTarget(null, TargetReason.CUSTOM);
        }

        public boolean targets(LivingEntity entity) {
            if (entity != this.mob && entity.isAlive()) {
                if (this.targetIds.contains(entity.getUUID())) {
                    return true;
                }

                String otherTeam = MobUnits.teamOf(entity);
                boolean otherFfa = entity instanceof BotPlayer bot ? bot.freeForAll : MobUnits.unitOf(entity) != null && MobUnits.unitOf(entity).freeForAll;
                return this.freeForAll && otherFfa
                    ? true
                    : otherTeam != null
                        && !Bots.allied(this.teamKey(), otherTeam)
                        && (this.enemyTeams.contains(otherTeam) || Bots.teamRelation(this.teamKey(), otherTeam, true));
            } else {
                return false;
            }
        }

        public boolean strangerTeam(String otherTeam) {
            return BotDefaults.get().autoWar && this.team != null && otherTeam != null && !this.freeForAll && !Bots.allied(this.team, otherTeam);
        }

        public double effectiveSight() {
            return this.sightRange > 0.0 ? this.sightRange : (BotDefaults.get().autoWar ? 24.0 : 0.0);
        }

        public boolean sameTeam(LivingEntity entity) {
            String otherTeam = MobUnits.teamOf(entity);
            return otherTeam != null && (otherTeam.equals(this.teamKey()) || !this.freeForAll && Bots.allied(this.team, otherTeam));
        }

        public boolean knowsHostile(LivingEntity entity) {
            String otherTeam = MobUnits.teamOf(entity);
            return this.targets(entity) || otherTeam != null && (this.hostileTeams.contains(otherTeam) || Bots.teamRelation(this.team, otherTeam, false));
        }

        public String state() {
            if (this.mob.isDeadOrDying()) {
                return "TOT";
            } else if (this.mob.getTarget() != null && this.mob.getTarget().isAlive()) {
                return "KAMPF";
            } else if (this.goal != null) {
                return this.marching ? "MARSCH" : "LAUFEN";
            } else if (this.followId != null) {
                return this.guardMode ? "BEWACHT" : "FOLGT";
            } else if (this.patrolArea != null) {
                return "PATROUILLE";
            } else {
                return !this.hostileTeams.isEmpty() ? "WACHSAM" : "IDLE";
            }
        }
    }
}
