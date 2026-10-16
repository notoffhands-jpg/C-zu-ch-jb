package de.fiden.botsystem.ai;

import de.fiden.botsystem.BotSystemMod;
import de.fiden.botsystem.bot.Actors;
import de.fiden.botsystem.bot.BotDefaults;
import de.fiden.botsystem.bot.BotPlayer;
import de.fiden.botsystem.bot.BotProfile;
import de.fiden.botsystem.bot.Bots;
import de.fiden.botsystem.bot.Ground;
import de.fiden.botsystem.bot.MobUnits;
import de.fiden.botsystem.bot.NavMemory;
import de.fiden.botsystem.bot.Stance;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.Plane;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class BotBrain {
    private static final double REACH = 3.0;
    private static final double PURSUIT_RADIUS = 512.0;
    private static final double NEARBY_RADIUS = 48.0;
    private static final double MARCH_ENGAGE = 16.0;
    private static final double MARCH_CHASE = 32.0;
    private final Bots bots;
    private final BotNavigator navigator = new BotNavigator();
    private final Map<Long, BotBrain.SharedPath> sharedPaths = new HashMap<>();
    private final Map<Long, List<BotPlayer>> grid = new HashMap<>();
    private final Map<String, List<BotPlayer>> byTeam = new HashMap<>();
    private static final int CELL = 32;
    private final Map<UUID, Integer> targetLoad = new HashMap<>();
    private final Map<Long, List<BotPlayer>> fineGrid = new HashMap<>();
    private long brainNanos;
    /** Idle bots that waited a tick because of the time budget (reported in [perf]). */
    public int deferred;
    private long brainMaxNanos;
    private int brainTicks;
    private final IdentityHashMap<LivingEntity, List<BotPlayer>> ringCache = new IdentityHashMap<>();
    private long ringTick = Long.MIN_VALUE;
    private static final int ALARM_COUNT = 3;
    private static final Vec3 REC_DONE = new Vec3(0.0, -9999.0, 0.0);
    private final IdentityHashMap<RecordedPath, List<BotPlayer>> recFollowers = new IdentityHashMap<>();
    private long recFollowersTick = Long.MIN_VALUE;
    private final Map<Long, BotBrain.SharedLong> sharedLong = new HashMap<>();
    private long sharedLongSweep;
    private final Map<UUID, BotBrain.Crowd> crowds = new HashMap<>();
    private long marchSlotsTick = Long.MIN_VALUE;
    private static final boolean DEBUG_NAV = System.getProperty("botsystem.debugnav") != null;
    /** How often the newer combat habits kicked in (shown in the [perf] log line). */
    public static final int[] HABITS = new int[8];
    public static final String[] HABIT_NAMES = {"jumpreset", "hitselect", "kante", "ueberzahl", "vorhalt", "netz", "gapple", "essen"};

    private static long shareKey(Vec3 start, Vec3 goal) {
        long sx = Math.floorDiv((long)Math.floor(start.x), 8L) & 65535L;
        long sz = Math.floorDiv((long)Math.floor(start.z), 8L) & 65535L;
        long gx = Math.floorDiv((long)Math.floor(goal.x), 8L) & 65535L;
        long gz = Math.floorDiv((long)Math.floor(goal.z), 8L) & 65535L;
        return sx << 48 | sz << 32 | gx << 16 | gz;
    }

    private boolean unitSpots(MobUnits.Unit unit, LivingEntity other) {
        String team = MobUnits.teamOf(other);
        if (team != null && (unit.hostileTeams.contains(team) || unit.strangerTeam(team))) {
            if (unit.fightMode && unit.hostileTeams.contains(team)) {
                return true;
            }

            double sight = unit.effectiveSight();
            return sight > 0.0 && unit.mob.distanceToSqr(other) <= sight * sight && unit.mob.hasLineOfSight(other);
        } else {
            return false;
        }
    }

    public LivingEntity findTargetFor(MobUnits.Unit unit) {
        ServerLevel world = (ServerLevel)unit.mob.level();
        Vec3 pos = unit.mob.position();
        LivingEntity best = null;
        double bestDistance = 1024.0;
        int cells = 1;
        int cx = Math.floorDiv(unit.mob.getBlockX(), 32);
        int cz = Math.floorDiv(unit.mob.getBlockZ(), 32);

        for (int dx = -cells; dx <= cells; dx++) {
            for (int dz = -cells; dz <= cells; dz++) {
                List<BotPlayer> cell = this.grid.get(ChunkPos.asLong(cx + dx, cz + dz));
                if (cell != null) {
                    for (BotPlayer bot : cell) {
                        if ((!bot.isGuard() || unit.targetIds.contains(bot.getUUID()))
                            && bot.level() == world
                            && !bot.isDeadOrDying()
                            && (unit.targets(bot) || this.unitSpots(unit, bot))) {
                            double distance = bot.distanceToSqr(pos);
                            if (distance < bestDistance) {
                                bestDistance = distance;
                                best = bot;
                            }
                        }
                    }
                }
            }
        }

        MobUnits.Unit nearestUnit = this.bots
            .mobs()
            .nearest(world, pos, 32.0, bestDistance, unit, other -> unit.targets(other.mob) || this.unitSpots(unit, other.mob));
        if (nearestUnit != null) {
            bestDistance = nearestUnit.mob.distanceToSqr(pos);
            best = nearestUnit.mob;
        }

        for (ServerPlayer player : Bots.realPlayers(world.players())) {
            if (!BotDefaults.get().ignorePlayers && unit.targetIds.contains(player.getUUID()) && player.isAlive() && !player.isSpectator()) {
                double distance = player.distanceToSqr(pos);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = player;
                }
            }
        }

        // Anything else the unit was told to go for (what its owner hit: villagers, animals, mobs).
        for (UUID id : unit.targetIds) {
            if (world.getEntity(id) instanceof LivingEntity living
                && !(living instanceof ServerPlayer)
                && living.isAlive()
                && living != unit.mob
                && MobUnits.unitOf(living) == null) {
                double distance = living.distanceToSqr(pos);
                if (distance < bestDistance || best == null && distance < 2304.0) {
                    bestDistance = distance;
                    best = living;
                }
            }
        }

        if (best == null && unit.fightMode && (!unit.enemyTeams.isEmpty() || !unit.targetIds.isEmpty())) {
            bestDistance = 262144.0;

            for (String team : unit.enemyTeams) {
                List<BotPlayer> members = this.byTeam.get(team);
                if (members != null && !members.isEmpty()) {
                    int offset = (unit.mob.tickCount / 20 + unit.mob.getId()) % members.size();

                    for (int i = 0; i < Math.min(48, members.size()); i++) {
                        BotPlayer other = members.get((offset + i) % members.size());
                        if (!other.isGuard() && other.level() == world && other.isAlive() && !other.isDeadOrDying()) {
                            double distance = other.distanceToSqr(pos);
                            if (distance < bestDistance) {
                                bestDistance = distance;
                                best = other;
                            }
                        }
                    }
                }

                for (MobUnits.Unit other : this.bots.mobs().byTeam(team)) {
                    if (other != unit && other.mob.level() == world && other.mob.isAlive()) {
                        double distance = other.mob.distanceToSqr(pos);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = other.mob;
                        }
                    }
                }
            }

            int checked = 0;

            for (UUID id : unit.targetIds) {
                if (++checked > 64) {
                    break;
                }

                if (world.getEntity(id) instanceof LivingEntity living && living.isAlive() && living != unit.mob) {
                    double distance = living.distanceToSqr(pos);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = living;
                    }
                }
            }
        }

        return best;
    }

    private int load(LivingEntity target) {
        return this.targetLoad.getOrDefault(target.getUUID(), 0);
    }

    private void rebuildGrid() {
        this.grid.values().forEach(List::clear);
        this.byTeam.values().forEach(List::clear);
        this.targetLoad.clear();

        for (BotPlayer bot : this.bots.all()) {
            if (!bot.isRemoved() && bot.currentTarget != null && bot.currentTarget.isAlive()) {
                this.targetLoad.merge(bot.currentTarget.getUUID(), 1, Integer::sum);
            }

            if (!bot.isRemoved()) {
                long key = ChunkPos.asLong(Math.floorDiv(bot.getBlockX(), 32), Math.floorDiv(bot.getBlockZ(), 32));
                this.grid.computeIfAbsent(key, k -> new ArrayList<>()).add(bot);
                if (bot.team != null) {
                    this.byTeam.computeIfAbsent(bot.teamKey(), k -> new ArrayList<>()).add(bot);
                }
            }
        }
    }

    private void rebuildFineGrid() {
        this.fineGrid.values().forEach(List::clear);
        if (!(BotDefaults.get().separation <= 0.0)) {
            for (BotPlayer bot : this.bots.all()) {
                if (!bot.isRemoved() && !bot.isDeadOrDying()) {
                    long key = ChunkPos.asLong(Mth.floor(bot.getX() / SEP_CELL), Mth.floor(bot.getZ() / SEP_CELL));
                    this.fineGrid.computeIfAbsent(key, k -> new ArrayList<>()).add(bot);
                }
            }
        }
    }

    /**
     * Bots push each other apart here, on the crowd grid, once per pair with vanilla's push
     * formula - instead of every bot asking the world for everything touching it each tick (in a
     * swarm of 2000 that query walked hundreds of bots per bot).
     */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<List<BotPlayer>> pushGrid = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private final List<List<BotPlayer>> pushSpare = new ArrayList<>();
    private static final double PUSH_CELL = 0.6;

    private void pushBots(long tick) {
        if (!BotDefaults.get().fastMobPush) {
            return;
        }

        // Own grid with player-width cells: only bots within 0.6 blocks can touch, so the 3x3
        // cells around hold just the few that matter even in a packed swarm.
        for (List<BotPlayer> list : this.pushGrid.values()) {
            list.clear();
            this.pushSpare.add(list);
        }
        this.pushGrid.clear();
        for (BotPlayer bot : this.bots.all()) {
            if (!bot.isRemoved() && !bot.isDeadOrDying() && !bot.isPassenger() && bot.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) {
                long key = ChunkPos.asLong(Mth.floor(bot.getX() / PUSH_CELL), Mth.floor(bot.getZ() / PUSH_CELL));
                List<BotPlayer> cell = this.pushGrid.get(key);
                if (cell == null) {
                    cell = this.pushSpare.isEmpty() ? new ArrayList<>(2) : this.pushSpare.remove(this.pushSpare.size() - 1);
                    this.pushGrid.put(key, cell);
                }
                cell.add(bot);
                bot.gridPushTick = tick;
            }
        }

        for (BotPlayer bot : this.bots.all()) {
            if (bot.gridPushTick != tick) {
                continue;
            }

            int cx = Mth.floor(bot.getX() / PUSH_CELL);
            int cz = Mth.floor(bot.getZ() / PUSH_CELL);
            int pushes = 0;
            for (int dx = -1; dx <= 1 && pushes < 8; dx++) {
                for (int dz = -1; dz <= 1 && pushes < 8; dz++) {
                    List<BotPlayer> cell = this.pushGrid.get(ChunkPos.asLong(cx + dx, cz + dz));
                    if (cell == null) {
                        continue;
                    }

                    for (int i = 0; i < cell.size() && pushes < 8; i++) {
                        BotPlayer other = cell.get(i);
                        if (other.getId() <= bot.getId() || other.level() != bot.level()) {
                            continue;
                        }

                        double ax = other.getX() - bot.getX();
                        double az = other.getZ() - bot.getZ();
                        // Player boxes are 0.6 wide and 1.8 tall: touching = closer than that.
                        if (Math.abs(ax) >= 0.6 || Math.abs(az) >= 0.6 || Math.abs(other.getY() - bot.getY()) >= 1.8) {
                            continue;
                        }

                        double max = Math.max(Math.abs(ax), Math.abs(az));
                        if (max < 0.01) {
                            double angle = (bot.getId() * 31 + other.getId()) * 0.7;
                            ax = Math.cos(angle) * 0.02;
                            az = Math.sin(angle) * 0.02;
                            max = 0.02;
                        }

                        max = Math.sqrt(max);
                        double scale = Math.min(1.0, 1.0 / max) * 0.1 / max; // vanilla's 0.05, both sides' share
                        double px = ax * scale;
                        double pz = az * scale;
                        bot.push(-px, 0.0, -pz);
                        other.push(px, 0.0, pz);
                        pushes++;
                    }
                }
            }
        }
    }

    private int thinkEvery(BotPlayer bot) {
        if (!BotDefaults.get().liteIdle || bot.hurtTime > 0 || bot.isInWater() || !bot.onGround() || bot.miningPos != null || bot.bridgeDir != null) {
            return 1;
        }

        LivingEntity target = bot.currentTarget;
        if (target == null || !bot.fightMode || target.level() != bot.level()) {
            return 1;
        }

        double gap = bot.distanceToSqr(target);
        return gap > 2304.0 ? 4 : (gap > 400.0 ? 2 : 1);
    }

    /** Separation cells: as wide as the push reaches (2.2), so the 3x3 cells around cover it. */
    private static final double SEP_CELL = 2.2;

    private Vec3 separation(BotPlayer bot) {
        int cx = Mth.floor(bot.getX() / SEP_CELL);
        int cz = Mth.floor(bot.getZ() / SEP_CELL);
        double sx = 0.0;
        double sz = 0.0;
        boolean any = false;
        int felt = 0;

        for (int dx = -1; dx <= 1 && felt < 16; dx++) {
            for (int dz = -1; dz <= 1 && felt < 16; dz++) {
                List<BotPlayer> cell = this.fineGrid.get(ChunkPos.asLong(cx + dx, cz + dz));
                if (cell != null) {
                    // In a packed crowd the force maxes out long before every neighbour is counted.
                    for (int index = 0; index < cell.size() && felt < 16; index++) {
                        BotPlayer other = cell.get(index);
                        if (other != bot && other.level() == bot.level() && !(Math.abs(other.getY() - bot.getY()) > 1.6)) {
                            double ax = bot.getX() - other.getX();
                            double az = bot.getZ() - other.getZ();
                            double squared = ax * ax + az * az;
                            if (!(squared >= 4.84)) {
                                if (squared < 1.0E-4) {
                                    double angle = Math.floorMod(bot.getId() * 37, 360) * Math.PI / 180.0;
                                    ax = Math.cos(angle);
                                    az = Math.sin(angle);
                                    squared = 0.01;
                                }

                                double distance = Math.sqrt(squared);
                                double force = (2.2 - distance) * 0.095;
                                if (distance < 0.66) {
                                    force += (0.66 - distance) * 0.55;
                                }

                                double length = Math.sqrt(ax * ax + az * az);
                                sx += ax / length * force;
                                sz += az / length * force;
                                any = true;
                                felt++;
                            }
                        }
                    }
                }
            }
        }

        bot.crowded = felt >= 8;
        if (!any) {
            return null;
        }

        double length = Math.sqrt(sx * sx + sz * sz);
        if (length > 0.35) {
            sx = sx / length * 0.35;
            sz = sz / length * 0.35;
        }

        return new Vec3(sx, 0.0, sz);
    }

    private void forEachNearby(BotPlayer bot, double radius, Consumer<BotPlayer> consumer) {
        int cells = (int)Math.ceil(radius / 32.0);
        int cx = Math.floorDiv(bot.getBlockX(), 32);
        int cz = Math.floorDiv(bot.getBlockZ(), 32);
        double limit = radius * radius;

        for (int dx = -cells; dx <= cells; dx++) {
            for (int dz = -cells; dz <= cells; dz++) {
                List<BotPlayer> cell = this.grid.get(ChunkPos.asLong(cx + dx, cz + dz));
                if (cell != null) {
                    for (BotPlayer other : cell) {
                        if (other != bot && other.level() == bot.level() && bot.distanceToSqr(other) <= limit) {
                            consumer.accept(other);
                        }
                    }
                }
            }
        }
    }

    public BotNavigator navigator() {
        return this.navigator;
    }

    public double[] takeTimings() {
        double avg = this.brainTicks == 0 ? 0.0 : this.brainNanos / 1000000.0 / this.brainTicks;
        double max = this.brainMaxNanos / 1000000.0;
        this.brainNanos = 0L;
        this.brainMaxNanos = 0L;
        this.brainTicks = 0;
        return new double[]{avg, max};
    }

    public BotBrain(Bots bots) {
        this.bots = bots;
    }

    public void tick(MinecraftServer server) {
        String cageLine = ExitSense.tickCage(this.bots, server.getTickCount());
        if (cageLine != null) {
            BotSystemMod.LOGGER.info("[cage] {}", cageLine);

            for (ServerPlayer player : Bots.realPlayers(server.getPlayerList().getPlayers())) {
                player.sendSystemMessage(Component.literal("[BotSystem] " + cageLine).withStyle(ChatFormatting.GRAY));
            }
        }

        long gymTick = server.getTickCount();
        String shuffleLine = Gym.tickShuffle(server, this.bots, gymTick);
        if (shuffleLine != null) {
            BotSystemMod.LOGGER.info("[gym] {}", shuffleLine);

            for (ServerPlayer player : Bots.realPlayers(server.getPlayerList().getPlayers())) {
                player.sendSystemMessage(Component.literal("[BotSystem] " + shuffleLine).withStyle(ChatFormatting.GRAY));
            }
        }

        if (Gym.dueReport(gymTick)) {
            int active = 0;

            for (BotPlayer bot : this.bots.all()) {
                if (bot.gymActive) {
                    active++;
                }
            }

            if (active > 0) {
                String line = Gym.report(active);
                BotSystemMod.LOGGER.info("[gym] {}", line);

                for (ServerPlayer player : Bots.realPlayers(server.getPlayerList().getPlayers())) {
                    player.sendSystemMessage(Component.literal("[BotSystem] " + line).withStyle(ChatFormatting.GRAY));
                }

                BotSystemMod.skills().save();
            }
        }

        long tick = server.getTickCount();
        long start = System.nanoTime();
        if (tick % 100L == 0L) {
            this.sharedPaths.entrySet().removeIf(entry -> tick - entry.getValue().tick() > 60L);
        }

        this.bots.sweep(tick);
        for (ArmyDrill drill : new ArrayList<>(this.drills)) {
            drill.update(tick);
            drill.steerMobs(tick);
            if (drill.members.isEmpty() && drill.mobs.isEmpty() || drill.finished(tick)) {
                drill.dismiss();
                this.drills.remove(drill);
            }
        }

        this.rebuildGrid();
        this.rebuildFineGrid();
        this.pushBots(tick);
        if (tick % 20L == 0L) {
            this.balanceGuards(server);
        }

        // Time budget: past it, idle bots (no target, no order) wait for the next tick; anyone
        // fighting or walking always thinks. Keeps a spike from snowballing with huge armies.
        long budget = (long)(BotDefaults.get().aiBudgetMs * 1000000.0);
        for (BotPlayer bot : this.bots.all()) {
            if (!bot.isRemoved() && !bot.isDeadOrDying() && bot.removeAtTick < 0L) {
                if (bot.replay != null) {
                    continue; // playing an action recording: no own decisions
                }

                if (budget > 0L
                    && System.nanoTime() - start > budget
                    && bot.currentTarget == null
                    && bot.walkGoal == null
                    && bot.followTarget == null
                    && bot.patrol == null
                    && bot.miningPos == null
                    && tick - bot.lastThinkTick < 4L) {
                    this.deferred++;
                    continue;
                }

                // Level of detail: chasing a target that is still far away doesn't need a decision
                // every tick - the move input simply carries on in between. Close fights, fresh
                // hits and everything else think every tick.
                int every = this.thinkEvery(bot);
                if (every > 1 && (tick + bot.getId()) % every != 0) {
                    try {
                        bot.lavaGuard();
                    } catch (RuntimeException ignored) {
                    }
                    continue;
                }

                bot.lastThinkTick = tick;
                try {
                    this.think(bot, tick);
                    bot.lavaGuard();
                } catch (RuntimeException error) {
                    server.getPlayerList();
                    Component.literal("[BotSystem] " + bot.getGameProfile().name() + ": " + error);
                    boolean var10002 = false;
                    bot.setMoveInput(0.0F, 0.0F);
                }
            }
        }

        long spent = System.nanoTime() - start;
        this.brainNanos += spent;
        this.brainMaxNanos = Math.max(this.brainMaxNanos, spent);
        this.brainTicks++;
    }

    private boolean waterClutch(BotPlayer bot, long tick) {
        ServerLevel world = bot.level();
        if (bot.clutchWater != null) {
            if (tick - bot.clutchTick > 60L) {
                bot.clutchWater = null;
                return false;
            }

            if ((bot.onGround() || bot.isInWater()) && tick - bot.clutchTick >= 6L) {
                int bucket = -1;

                for (int slot = 0; slot < 36; slot++) {
                    if (bot.getInventory().getItem(slot).is(Items.BUCKET)) {
                        bucket = slot;
                        break;
                    }
                }

                if (bucket >= 0 && world.getFluidState(bot.clutchWater).is(FluidTags.WATER) && world.getFluidState(bot.clutchWater).isSource()) {
                    bot.setXRot(80.0F);
                    bot.getInventory().setSelectedSlot(bucket);
                    world.setBlock(bot.clutchWater, Blocks.AIR.defaultBlockState(), 3);
                    bot.getInventory().setItem(bucket, new ItemStack(Items.WATER_BUCKET));
                    bot.swing(InteractionHand.MAIN_HAND);
                    if (bot.clutchPrevSlot >= 0) {
                        bot.getInventory().setSelectedSlot(bot.clutchPrevSlot);
                    }
                }

                bot.clutchWater = null;
                bot.clutchPrevSlot = -1;
            }

            return false;
        } else if (!bot.onGround() && !bot.isInWater() && !(bot.getDeltaMovement().y > -0.3) && !bot.isFallFlying()) {
            int bucket = -1;

            for (int slot = 0; slot < 36; slot++) {
                if (bot.getInventory().getItem(slot).is(Items.WATER_BUCKET)) {
                    bucket = slot;
                    break;
                }
            }

            if (bucket < 0) {
                return false;
            }

            if (bucket >= 9) {
                ItemStack swap = bot.getInventory().getItem(8);
                bot.getInventory().setItem(8, bot.getInventory().getItem(bucket));
                bot.getInventory().setItem(bucket, swap);
                bucket = 8;
            }

            int x = Mth.floor(bot.getX());
            int z = Mth.floor(bot.getZ());
            int top = Mth.floor(bot.getY());
            BlockPos ground = null;

            for (int y = top; y > top - 48; y--) {
                BlockPos at = new BlockPos(x, y, z);
                if (!world.getFluidState(at).isEmpty()) {
                    return false;
                }

                if (!world.getBlockState(at).getCollisionShape(world, at).isEmpty()) {
                    ground = at;
                    break;
                }
            }

            if (ground == null) {
                return false;
            }

            double below = bot.getY() - (ground.getY() + 1.0);
            double total = bot.fallDistance + below;
            double damage = Math.max(0.0, total - 3.0);
            if (damage < Math.min(bot.getHealth() - 1.0, bot.getHealth() * 0.5)) {
                return false;
            }

            double window = Math.max(2.5, -bot.getDeltaMovement().y * 2.2 + 1.0);
            bot.setXRot(88.0F);
            if (below > window) {
                return true;
            }

            BlockPos place = ground.above();
            if (!world.getBlockState(place).canBeReplaced()) {
                return true;
            }

            bot.clutchPrevSlot = bot.getInventory().getSelectedSlot();
            bot.getInventory().setSelectedSlot(bucket);
            if (((BucketItem)Items.WATER_BUCKET).emptyContents(bot, world, place, null)) {
                bot.getInventory().setItem(bucket, new ItemStack(Items.BUCKET));
                bot.swing(InteractionHand.MAIN_HAND);
                bot.clutchWater = place;
                bot.clutchTick = tick;
            } else {
                bot.getInventory().setSelectedSlot(bot.clutchPrevSlot);
            }

            return true;
        } else {
            return false;
        }
    }

    public final List<ArmyDrill> drills = new ArrayList<>();

    /** Walking speed (blocks/tick) at full input on the ground - drill inputs are scaled by it. */
    private static final double WALK_SPEED = 0.2157;

    /**
     * One tick of parade marching: far from the own slot while forming -> walk there; otherwise
     * face the exact march heading and hold the slot of the moving block with precise input
     * (same speed as everyone, no turning, no fidgeting, no fighting).
     */
    private boolean drillStep(BotPlayer bot, long tick) {
        ArmyDrill drill = bot.drill;
        if (drill.phase == ArmyDrill.Phase.HALTED
            && (bot.walkGoal != null || bot.fightMode || bot.followTarget != null || bot.emoteKind != 0 || bot.post != null)) {
            // Arrived and standing at attention - a new order (goto, attack, follow, emote ...) dismisses.
            drill.members.remove(bot);
            bot.drill = null;
            bot.drillSlot = -1;
            return false;
        }

        Vec3 slot = drill.slot(bot);
        double dx = slot.x - bot.getX();
        double dz = slot.z - bot.getZ();
        double gap = Math.sqrt(dx * dx + dz * dz);
        if (bot.isUsingItem()) {
            bot.stopUsingItem();
        }

        bot.setShiftKeyDown(false);
        bot.setSprinting(false);
        if (gap < 1.0 || gap < bot.drillBestGap - 0.3) {
            bot.drillBestGap = gap;
            bot.drillProgressTick = tick;
        }

        if (gap > 6.0 || gap > 1.5 && tick - bot.drillProgressTick > 20L) {
            // Really lost (far off, or stuck for 2 s): real pathfinding at a run, aiming a bit ahead
            // of the moving spot. Small bumps are stepped/jumped in the march instead.
            boolean marchingNow = drill.phase == ArmyDrill.Phase.MARCHING && drill.progress < drill.length;
            double lead = marchingNow ? Math.min(4.0, gap * 0.5) : 0.0;
            Vec3 spot = Ground.find(bot.level(), slot.x + drill.forwardX * lead, slot.z + drill.forwardZ * lead, bot.getY());
            Vec3 target = spot != null ? spot : new Vec3(slot.x, bot.getY(), slot.z);
            if (drill.phase == ArmyDrill.Phase.FORMING) {
                // Lining up from far away: the normal walk (long routes, doors ...).
                if (bot.walkGoal == null || bot.walkGoal.distanceToSqr(target) > 9.0) {
                    bot.walkGoal = target;
                    bot.walkDeadline = tick + 1200L;
                    bot.walkGroup = null;
                    bot.marching = false;
                    bot.path = null;
                }

                this.walk(bot, tick);
            } else {
                // Lost while marching: the chase steering (short paths around the obstacle, keeps
                // re-aiming at the moving spot) - the walk system was too cautious to catch up.
                bot.walkGoal = null;
                if (bot.chaseGoal == null || bot.chaseGoal.distanceToSqr(target) > 9.0) {
                    bot.chaseGoal = target;
                }

                this.moveTo(bot, bot.chaseGoal, tick);
            }
            // After walk(): it sets its own pace; a straggler sprints to catch up with the block.
            bot.setSprinting((marchingNow || gap > 6.0) && !bot.isInWater());
            return true;
        }

        bot.walkGoal = null;
        bot.path = null;
        boolean straggling = gap > 1.2;
        bot.setSprinting((drill.sprint && drill.phase == ArmyDrill.Phase.MARCHING || straggling) && !bot.isInWater());
        bot.setYRot(drill.yaw);
        bot.setYHeadRot(drill.yaw);
        bot.setYBodyRot(drill.yaw);
        bot.setXRot(0.0F);
        boolean moving = drill.phase == ArmyDrill.Phase.MARCHING && drill.progress < drill.length;
        double vx = (moving ? drill.forwardX * drill.speed : 0.0) + dx * 0.3;
        double vz = (moving ? drill.forwardZ * drill.speed : 0.0) + dz * 0.3;
        double top = bot.isSprinting() ? WALK_SPEED * 1.3 : WALK_SPEED;
        double forward = (vx * drill.forwardX + vz * drill.forwardZ) / top;
        double left = (vx * drill.forwardZ - vz * drill.forwardX) / top;
        double size = Math.sqrt(forward * forward + left * left);
        if (size > 1.0) {
            forward /= size;
            left /= size;
        }

        if (!moving && gap < 0.06) {
            forward = 0.0;
            left = 0.0;
        }

        boolean wall = moving && bot.horizontalCollision && blockedHigh(bot, drill.forwardX, drill.forwardZ);
        if (wall) {
            // Something too high to jump in the way (a post, a tree): step around it to the side
            // instead of pushing into it.
            left = (Math.floorMod(bot.drillSlot, 2) == 0 ? 1.0 : -1.0);
            forward = 0.4;
        }

        bot.setMoveInput((float)forward, (float)left);
        // Sprint-jump pace: the whole block jumps on the same tick.
        boolean hopNow = moving && drill.hop && (tick - drill.phaseTick) % 12L == 0L;
        bot.wantJump(bot.onGround() && (bot.horizontalCollision && !wall || hopNow));
        return true;
    }

    /** The two blocks in front (feet + head) are both solid: can't be stepped or jumped over. */
    static boolean blockedHigh(net.minecraft.world.entity.LivingEntity who, double fx, double fz) {
        net.minecraft.core.BlockPos ahead = net.minecraft.core.BlockPos.containing(who.getX() + fx * 0.8, who.getY() + 0.2, who.getZ() + fz * 0.8);
        ServerLevel level = (ServerLevel)who.level();
        return !level.getBlockState(ahead.above()).getCollisionShape(level, ahead.above()).isEmpty()
            && !level.getBlockState(ahead).getCollisionShape(level, ahead).isEmpty();
    }

    /** Stand still exactly on the post, facing postYaw (parade-straight lines when occupying). */
    private void holdPost(BotPlayer bot, double dx, double dz) {
        float yaw = bot.postYaw;
        bot.setYRot(this.approach(bot.getYRot(), yaw, 12.0F));
        bot.setYHeadRot(bot.getYRot());
        bot.setYBodyRot(bot.getYRot());
        bot.setXRot(this.approach(bot.getXRot(), 0.0F, 6.0F));
        bot.setSprinting(false);
        bot.setShiftKeyDown(false);
        double rad = Math.toRadians(bot.getYRot());
        double fx = -Math.sin(rad);
        double fz = Math.cos(rad);
        double gap = Math.sqrt(dx * dx + dz * dz);
        if (gap < 0.06) {
            bot.setMoveInput(0.0F, 0.0F);
        } else {
            double forward = (dx * fx + dz * fz) * 0.3 / WALK_SPEED;
            double left = (dx * fz - dz * fx) * 0.3 / WALK_SPEED;
            bot.setMoveInput((float)Mth.clamp(forward, -1.0, 1.0), (float)Mth.clamp(left, -1.0, 1.0));
        }

        bot.wantJump(false);
    }

    private void think(BotPlayer bot, long tick) {
        if (bot.drill != null && this.drillStep(bot, tick)) {
            return;
        }

        if (!this.waterClutch(bot, tick)) {
            if (bot.healNow && tick > bot.nextHealPlan - 60L) {
                bot.healNow = false;
            }

            if (tick % 20L == 0L && !bot.gymActive && bot.trainRunsLeft <= 0) {
                AttributeInstance speed = bot.getAttribute(Attributes.MOVEMENT_SPEED);
                if (speed != null && speed.getBaseValue() > 0.1001) {
                    speed.setBaseValue(0.1);
                }
            }

            if (bot.bridgeUntil != 0L && tick > bot.bridgeUntil) {
                bot.bridgeUntil = 0L;
                bot.setShiftKeyDown(false);
            }

            if (bot.fidgetKind != 0 && (bot.walkGoal != null || bot.fightMode || bot.isFarming())) {
                this.endFidget(bot);
            }

            if (bot.emoteKind == 0 || !this.emote(bot, tick)) {
                if (bot.escape == null || bot.fightMode || !this.explore(bot, tick)) {
                    boolean guard = false;
                    double sight = bot.effectiveSight();
                    if (sight > 0.0
                        && tick >= bot.ceasefireUntil
                        && !bot.neverFight
                        && bot.stance != Stance.PASSIVE
                        && tick % 10L == 0L
                        && (!bot.hostileIds.isEmpty() || !bot.hostileTeams.isEmpty() || BotDefaults.get().autoWar && bot.team != null)) {
                        this.lookout(bot, sight, true);
                    }

                    if (bot.miningPos == null && this.escapeWeb(bot, tick)) {
                        return;
                    }

                    if (bot.miningPos != null && this.continueMining(bot, tick)) {
                        return;
                    }

                    if (bot.bridgeDir != null && this.continueBridge(bot, tick)) {
                        return;
                    }

                    if (!this.drink(bot, tick)) {
                        if (!this.eat(bot, tick)) {
                            if (!bot.fleeing || !this.flee(bot, tick)) {
                                if (bot.searchId != null && (tick + bot.getId()) % 5L == 0L) {
                                    this.search(bot, tick);
                                }

                                boolean ordered = bot.walkGoal != null && tick < bot.orderUntil;
                                boolean enemyClose = bot.currentTarget != null && bot.currentTarget.isAlive() && bot.distanceToSqr(bot.currentTarget) < 49.0;
                                if (!ordered || enemyClose || !this.walk(bot, tick)) {
                                    if (!bot.fightMode || !this.fight(bot, tick)) {
                                        if (bot.walkGoal == null || !this.walk(bot, tick)) {
                                            if (!bot.isFarming() || !this.farm(bot, tick)) {
                                                if (bot.followTarget == null || bot.guardHold || !this.follow(bot, tick)) {
                                                    double defend = BotDefaults.get().defendRadius;
                                                    if (defend > 0.0
                                                        && bot.defendOwner
                                                        && !guard
                                                        && !bot.neverFight
                                                        && bot.stance != Stance.PASSIVE
                                                        && tick % 10L == bot.getId() % 10L
                                                        && bot.team != null) {
                                                        LivingEntity threat = this.threatNearTeammates(bot, defend);
                                                        // Defensive bots only join in if the fight is within their hold radius -
                                                        // never get pulled away from their post to help a distant ally.
                                                        if (threat != null
                                                            && (bot.stance != Stance.DEFENSIVE || this.withinHold(bot, threat.position(), this.leash(bot)))) {
                                                            if (bot.returnTo == null) {
                                                                bot.returnTo = bot.post != null ? bot.post : bot.position();
                                                            }

                                                            bot.targetIds.add(threat.getUUID());
                                                            bot.currentTarget = threat;
                                                            bot.fightMode = true;
                                                            bot.nextTargetTick = 0L;
                                                            return;
                                                        }
                                                    }

                                                    if (bot.returnTo != null) {
                                                        double rx = bot.returnTo.x - bot.getX();
                                                        double rz = bot.returnTo.z - bot.getZ();
                                                        if (rx * rx + rz * rz > 2.25) {
                                                            bot.walkGoal = bot.returnTo;
                                                            bot.walkDeadline = tick + 2400L;
                                                            bot.walkGroup = null;
                                                            bot.marching = false;
                                                            return;
                                                        }

                                                        bot.returnTo = null;
                                                    }

                                                    if (bot.stayArea != null
                                                        && (!insideArea(bot.stayArea, bot.getX(), bot.getZ(), 1.0) || outsideLevels(bot.stayArea, bot.getY()))
                                                        && tick >= bot.giveUpUntil) {
                                                        double[] a = bot.stayArea;
                                                        double tx = Math.max(a[0] + 2.0, Math.min(a[2] - 2.0, bot.getX()));
                                                        double tz = Math.max(a[1] + 2.0, Math.min(a[3] - 2.0, bot.getZ()));
                                                        Vec3 back = Ground.hasHeight(a) ? Ground.nearestSpot(bot.level(), a, bot.position()) : null;
                                                        bot.walkGoal = back != null ? back : Ground.find(bot.level(), tx, tz, bot.getY());
                                                        bot.walkDeadline = tick + 1200L;
                                                        bot.walkGroup = null;
                                                        bot.marching = false;
                                                    } else {
                                                        if (bot.stance == Stance.DEFENSIVE
                                                            && bot.post == null
                                                            && bot.patrolArea == null
                                                            && bot.patrol == null
                                                            && tick >= bot.giveUpUntil) {
                                                            if (bot.stanceAnchor != null && !(bot.stanceAnchor.distanceToSqr(bot.position()) > 4096.0)) {
                                                                if (bot.stanceAnchor.distanceToSqr(bot.position()) > 9.0) {
                                                                    bot.walkGoal = bot.stanceAnchor;
                                                                    bot.walkDeadline = tick + 1200L;
                                                                    bot.walkGroup = null;
                                                                    bot.marching = false;
                                                                    return;
                                                                }
                                                            } else {
                                                                bot.stanceAnchor = bot.position();
                                                            }
                                                        }

                                                        if (bot.patrolArea != null && tick >= bot.giveUpUntil) {
                                                            if (tick >= bot.nextPatrolPick) {
                                                                Vec3 spot = Ground.hasHeight(bot.patrolArea)
                                                                    ? Ground.patrolSpot(
                                                                        bot.level(), bot.patrolArea, bot.position(), ThreadLocalRandom.current()
                                                                    )
                                                                    : Ground.randomDryNear(
                                                                        bot.level(),
                                                                        bot.patrolArea,
                                                                        bot.getX(),
                                                                        bot.getZ(),
                                                                        32.0,
                                                                        bot.getY(),
                                                                        ThreadLocalRandom.current()
                                                                    );
                                                                if (spot == null) {
                                                                    bot.nextPatrolPick = tick + 40L;
                                                                    this.stand(bot);
                                                                } else {
                                                                    bot.walkGoal = spot;
                                                                    bot.walkDeadline = tick + 1200L;
                                                                    bot.walkGroup = null;
                                                                    bot.marching = false;
                                                                    bot.nextPatrolPick = tick
                                                                        + 100L
                                                                        + ThreadLocalRandom.current().nextLong(0L, 300L)
                                                                        + (this.navigator.queued.get() > 120 ? 100L : 0L);
                                                                }
                                                            } else {
                                                                this.stand(bot);
                                                                this.lookAround(bot);
                                                                this.glance(bot, tick);
                                                                this.fidget(bot, tick);
                                                            }
                                                        } else {
                                                            if (bot.post != null && tick >= bot.giveUpUntil) {
                                                                double dx = bot.post.x - bot.getX();
                                                                double dz = bot.post.z - bot.getZ();
                                                                if (dx * dx + dz * dz > 1.44) {
                                                                    bot.walkGoal = bot.post;
                                                                    bot.walkDeadline = tick + 12000L;
                                                                    bot.walkGroup = null;
                                                                    bot.marching = false;
                                                                    return;
                                                                }

                                                                if (!Float.isNaN(bot.postYaw)) {
                                                                    // Occupy: stand exactly on the grid spot, everyone facing the same way.
                                                                    this.holdPost(bot, dx, dz);
                                                                    return;
                                                                }
                                                            } else if (bot.patrol != null && !bot.patrol.isEmpty() && tick >= bot.giveUpUntil) {
                                                                bot.walkGoal = bot.patrol.get(Math.min(bot.patrolIndex, bot.patrol.size() - 1));
                                                                bot.walkDeadline = tick + 12000L;
                                                                bot.walkGroup = null;
                                                                bot.marching = false;
                                                                return;
                                                            }

                                                            if (!bot.gymActive || !this.gym(bot, tick)) {
                                                                if (bot.trainRunsLeft <= 0 || !this.train(bot, tick)) {
                                                                    if (!bot.lifeMode || !this.civilian(bot, tick)) {
                                                                        bot.setMoveInput(0.0F, 0.0F);
                                                                        bot.wantJump(false);
                                                                        bot.setSprinting(false);
                                                                        this.lookAround(bot);
                                                                        this.glance(bot, tick);
                                                                        this.fidget(bot, tick);
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** A point 20 blocks away along the bot's fixed compass heading, at eye height. */
    private static Vec3 headingPoint(BotPlayer bot) {
        double rad = Math.toRadians(bot.lookYaw);
        return bot.getEyePosition().add(-Math.sin(rad) * 20.0, 0.0, Math.cos(rad) * 20.0);
    }

    /** Ends an emote cleanly: shield down and the off-hand item back. */
    private void endEmote(BotPlayer bot) {
        bot.emoteKind = 0;
        bot.emoteLook = null;
        bot.setShiftKeyDown(false);
        if (bot.emoteOffhand != null) {
            if (bot.isUsingItem()) {
                bot.stopUsingItem();
            }

            bot.setItemInHand(InteractionHand.OFF_HAND, bot.emoteOffhand);
            bot.emoteOffhand = null;
        }
    }

    private boolean emote(BotPlayer bot, long tick) {
        if (tick >= bot.emoteUntil) {
            this.endEmote(bot);
            return false;
        }

        if (tick < bot.emoteStart) {
            this.stand(bot);
            return true;
        }

        long age = tick - bot.emoteStart;
        this.stand(bot);
        if (bot.emoteLook != null) {
            this.face(bot, bot.emoteLook, 14.0F);
            bot.setYBodyRot(bot.getYRot());
        } else if (!Float.isNaN(bot.lookYaw)) {
            this.face(bot, headingPoint(bot), 14.0F);
            bot.setYBodyRot(bot.getYRot());
        }

        switch (bot.emoteKind) {
            case 9: {
                // Shield up, standing firm.
                if (!bot.getOffhandItem().is(Items.SHIELD)) {
                    bot.emoteOffhand = bot.getOffhandItem().copy();
                    bot.setItemInHand(InteractionHand.OFF_HAND, new net.minecraft.world.item.ItemStack(Items.SHIELD));
                }

                if (!bot.isUsingItem()) {
                    bot.startUsingItem(InteractionHand.OFF_HAND);
                }

                bot.setShiftKeyDown(age % 60L < 40L);
                bot.setXRot(this.approach(bot.getXRot(), 4.0F, 3.0F));
                break;
            }
            case 10: {
                // Aura farming: dead calm, chin up, a slow look off to the side and back,
                // barely any movement - and a faint shimmer around.
                float side = (float)(Math.sin(age * 0.018) * 38.0);
                bot.setYHeadRot(bot.getYRot() + side);
                bot.setXRot(this.approach(bot.getXRot(), (float)(-12.0 + Math.sin(age * 0.013) * 4.0), 1.2F));
                bot.setShiftKeyDown(false);
                if (age % 8L == 0L) {
                    double angle = age * 0.35;
                    bot.level().sendParticles(net.minecraft.core.particles.ParticleTypes.ENCHANT,
                        bot.getX() + Math.cos(angle) * 0.8, bot.getY() + 0.3 + (age % 32L) / 16.0, bot.getZ() + Math.sin(angle) * 0.8,
                        2, 0.05, 0.1, 0.05, 0.02);
                }

                if (age % 120L == 60L) {
                    bot.level().sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD, bot.getX(), bot.getY() + 1.0, bot.getZ(), 6, 0.4, 0.6, 0.4, 0.01);
                }
                break;
            }
            case 11: {
                // Dance: bob with sneaks, sway the body side to side, now and then a hop.
                bot.setShiftKeyDown(age % 10L < 5L);
                float sway = (float)(Math.sin(age * 0.3) * 35.0);
                bot.setYBodyRot(bot.getYRot() + sway);
                bot.setYHeadRot(bot.getYRot() - sway * 0.5F);
                if (age % 40L == 20L) {
                    bot.wantJump(bot.onGround());
                }

                if (age % 7L == 0L) {
                    bot.swing(age % 14L == 0L ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
                }
                break;
            }
            case 12: {
                // Salute: straight up, eyes ahead, one sharp arm raise at the start and end.
                bot.setShiftKeyDown(false);
                bot.setXRot(this.approach(bot.getXRot(), -6.0F, 5.0F));
                if (age == 2L || bot.emoteUntil - tick == 6L) {
                    bot.swing(InteractionHand.MAIN_HAND, true);
                }
                break;
            }
            case 13: {
                // Flex: look down at the arm, show it off.
                bot.setXRot(this.approach(bot.getXRot(), 38.0F, 4.0F));
                bot.setYHeadRot(bot.getYRot() + 32.0F);
                if (age % 18L == 0L) {
                    bot.swing(InteractionHand.MAIN_HAND, true);
                }
                break;
            }
            case 14: {
                // Thinking: head down and to the side, slow.
                bot.setXRot(this.approach(bot.getXRot(), 22.0F, 1.5F));
                bot.setYHeadRot(bot.getYRot() + (float)(28.0 + Math.sin(age * 0.05) * 8.0));
                break;
            }
            case 15:
                bot.setShiftKeyDown(age % 4L < 2L);
                break;
            case 1:
                bot.wantJump(bot.onGround() && age % 12L < 2L);
                if (age % 6L == 0L) {
                    bot.swing(InteractionHand.MAIN_HAND);
                }

                bot.setXRot(this.approach(bot.getXRot(), -22.0F, 6.0F));
                break;
            case 2:
                bot.setXRot((float)(18.0 * Math.sin(age * 0.32)));
                break;
            case 3:
                float base = bot.getYRot();
                bot.setYHeadRot((float)(base + 24.0 * Math.sin(age * 0.42)));
                break;
            case 4:
                if (age % 4L == 0L) {
                    bot.swing(InteractionHand.MAIN_HAND);
                }

                bot.setYHeadRot((float)(bot.getYRot() + 7.0 * Math.sin(age * 0.5)));
                break;
            case 5:
                bot.setShiftKeyDown(true);
                bot.setXRot(this.approach(bot.getXRot(), age < 30L ? 55.0F : 0.0F, 5.0F));
                break;
            case 6:
                if (age == 0L) {
                    bot.swing(InteractionHand.MAIN_HAND);
                }
                break;
            case 7:
                if (age % 3L == 0L) {
                    bot.swing(age % 6L == 0L ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
                }
                break;
            default:
                bot.setXRot((float)(12.0 * Math.sin(age * 0.55)));
                bot.wantJump(bot.onGround() && age % 20L == 0L);
        }

        return true;
    }

    private boolean civilian(BotPlayer bot, long tick) {
        if (bot.lifeCentre == null) {
            bot.lifeCentre = bot.position();
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (tick < bot.lifePauseUntil) {
            BotPlayer partner = bot.lifePartner == null ? null : this.bots.byId(bot.lifePartner);
            if (partner != null && partner.level() == bot.level() && !partner.isDeadOrDying() && bot.distanceToSqr(partner) < 64.0) {
                this.face(bot, partner.getEyePosition(), 7.0F);
                bot.setYBodyRot(bot.getYRot());
                if (random.nextInt(140) == 0) {
                    bot.emoteKind = 2;
                    bot.emoteStart = tick;
                    bot.emoteUntil = tick + 25L;
                }
            } else {
                this.glance(bot, tick);
                this.fidget(bot, tick);
            }

            this.stand(bot);
            return true;
        } else {
            if (tick < bot.nextLifePick) {
                this.glance(bot, tick);
                this.fidget(bot, tick);
                this.stand(bot);
                return true;
            }

            bot.nextLifePick = tick + 120L + random.nextLong(0L, 400L);
            BotPlayer neighbour = null;
            double best = 49.0;

            for (BotPlayer other : this.bots.all()) {
                if (other != bot && other.lifeMode && other.level() == bot.level() && !other.isDeadOrDying() && other.walkGoal == null) {
                    double distance = bot.distanceToSqr(other);
                    if (distance < best) {
                        best = distance;
                        neighbour = other;
                    }
                }
            }

            if (neighbour != null && random.nextInt(100) < 45) {
                long talk = 80L + random.nextLong(0L, 260L);
                bot.lifePartner = neighbour.getUUID();
                bot.lifePauseUntil = tick + talk;
                if (neighbour.lifePauseUntil < tick + 40L) {
                    neighbour.lifePartner = bot.getUUID();
                    neighbour.lifePauseUntil = tick + talk;
                    neighbour.nextLifePick = tick + talk;
                }

                return true;
            } else {
                double radius = Math.max(4.0, bot.lifeRadius);
                double[] area = new double[]{bot.lifeCentre.x - radius, bot.lifeCentre.z - radius, bot.lifeCentre.x + radius, bot.lifeCentre.z + radius};
                Vec3 spot = Ground.randomDryNear(bot.level(), area, bot.getX(), bot.getZ(), Math.min(radius, 14.0), bot.getY(), random);
                if (spot == null) {
                    return true;
                }

                bot.lifePartner = null;
                bot.walkGoal = spot;
                bot.walkDeadline = tick + 1200L;
                bot.walkGroup = null;
                bot.marching = false;
                bot.nextLifePick = tick + 200L + random.nextLong(0L, 500L);
                return true;
            }
        }
    }

    private void fidget(BotPlayer bot, long tick) {
        if (BotDefaults.get().humanMovement && BotDefaults.get().idleFidget) {
            if (tick < bot.fidgetUntil) {
                switch (bot.fidgetKind) {
                    case 1:
                        bot.setShiftKeyDown(true);
                        break;
                    case 2:
                        bot.wantJump(bot.onGround() && tick == bot.fidgetUntil - 1L);
                    case 3:
                    default:
                        break;
                    case 4:
                        if (bot.fidgetSpot != null) {
                            double dx = bot.fidgetSpot.x - bot.getX();
                            double dz = bot.fidgetSpot.z - bot.getZ();
                            if (dx * dx + dz * dz > 0.16) {
                                this.steerTowards(bot, bot.fidgetSpot, tick, 0.35F, 0.0F);
                            } else {
                                bot.setMoveInput(0.0F, 0.0F);
                            }
                        }
                        break;
                    case 5:
                        if (bot.fidgetSpot != null) {
                            this.face(bot, bot.fidgetSpot, 8.0F);
                            bot.setYBodyRot(bot.getYRot());
                        }
                }
            } else {
                this.endFidget(bot);
                if (tick >= bot.nextFidget) {
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    bot.nextFidget = tick + 160L + random.nextLong(0L, (long)(1400.0 * (1.15 - bot.skill.fidgetiness)));
                    if (!bot.fightMode && !bot.isUsingItem()) {
                        int pick = random.nextInt(100);
                        if (pick < 18) {
                            bot.fidgetKind = 1;
                            bot.fidgetUntil = tick + 4L + random.nextLong(0L, 14L);
                        } else if (pick < 24) {
                            bot.fidgetKind = 2;
                            bot.fidgetUntil = tick + 2L;
                        } else if (pick < 50 && !bot.getInventory().getItem((bot.getInventory().getSelectedSlot() + 1) % 9).isEmpty()) {
                            bot.fidgetKind = 3;
                            bot.fidgetSlot = bot.getInventory().getSelectedSlot();
                            bot.getInventory().setSelectedSlot((bot.fidgetSlot + 1 + random.nextInt(3)) % 9);
                            bot.fidgetUntil = tick + 30L + random.nextLong(0L, 120L);
                        } else if (pick < 72 && bot.post == null && bot.followTarget == null) {
                            double angle = random.nextDouble() * Math.PI * 2.0;
                            double distance = 1.0 + random.nextDouble() * 2.0;
                            Vec3 spot = Ground.findDry(
                                bot.level(), bot.getX() + Math.cos(angle) * distance, bot.getZ() + Math.sin(angle) * distance, bot.getY()
                            );
                            if (spot != null
                                && Math.abs(spot.y - bot.getY()) < 1.5
                                && this.lineWalkable(bot, bot.position(), new Vec3(spot.x, bot.getY(), spot.z))) {
                                bot.fidgetKind = 4;
                                bot.fidgetSpot = spot;
                                bot.fidgetUntil = tick + 20L + (long)(distance * 12.0);
                            }
                        } else if (pick < 90) {
                            LivingEntity[] someone = new LivingEntity[]{null};

                            for (ServerPlayer player : Bots.realPlayers(bot.level().players())) {
                                if (player.distanceToSqr(bot) < 400.0 && player != bot) {
                                    someone[0] = player;
                                    break;
                                }
                            }

                            if (someone[0] == null) {
                                this.forEachNearby(bot, 10.0, other -> {
                                    if (someone[0] == null && other != bot && (other.zza != 0.0F || random.nextInt(4) == 0)) {
                                        someone[0] = other;
                                    }
                                });
                            }

                            if (someone[0] != null) {
                                bot.fidgetKind = 5;
                                bot.fidgetSpot = someone[0].getEyePosition();
                                bot.fidgetUntil = tick + 15L + random.nextLong(0L, 40L);
                            }
                        } else {
                            bot.swing(InteractionHand.MAIN_HAND, true);
                            bot.fidgetKind = 6;
                            bot.fidgetUntil = tick + 3L;
                        }
                    }
                }
            }
        } else {
            if (bot.fidgetKind != 0) {
                this.endFidget(bot);
            }
        }
    }

    private boolean drink(BotPlayer bot, long tick) {
        if (bot.splashSlot >= 0) {
            return this.continueSplash(bot, tick);
        } else if (bot.drinkSlot >= 0) {
            if (bot.isUsingItem() && bot.getInventory().getSelectedSlot() == bot.drinkSlot) {
                LivingEntity target = bot.currentTarget;
                if (target != null && target.isAlive() && bot.distanceToSqr(target) < 36.0) {
                    this.face(bot, target.getEyePosition(), 20.0F);
                    bot.setMoveInput(-0.8F, bot.strafeDirection * 0.3F);
                    bot.setSprinting(false);
                } else {
                    bot.setMoveInput(0.0F, 0.0F);
                }

                return true;
            } else {
                ItemStack left = bot.getInventory().getItem(bot.drinkSlot);
                if (left.is(Items.GLASS_BOTTLE)) {
                    bot.getInventory().setItem(bot.drinkSlot, ItemStack.EMPTY);
                }

                bot.getInventory().setSelectedSlot(bot.drinkPreviousSlot);
                this.swapBack(bot);
                bot.drinkSlot = -1;
                bot.nextWeaponCheck = 0L;
                return false;
            }
        } else if (BotDefaults.get().potions
            && (tick >= bot.nextDrinkCheck || bot.isOnFire() && !bot.hasEffect(MobEffects.FIRE_RESISTANCE) && (tick + bot.getId()) % 4L == 0L)
            && !bot.isDeadOrDying()
            && !bot.isInWater()
            && bot.eatSlot < 0) {
            bot.nextDrinkCheck = tick + 10L + ThreadLocalRandom.current().nextLong(0L, 10L);
            LivingEntity target = bot.currentTarget;
            boolean threatened = target != null && target.isAlive();
            double distance = threatened ? Math.sqrt(bot.distanceToSqr(target)) : 0.0;
            int healing = -1;
            int strength = -1;
            int swiftness = -1;
            int fireRes = -1;
            int buff = -1;
            int splashHeal = -1;
            int splashHarm = -1;
            int splashStrength = -1;
            int splashSpeed = -1;
            int splashFireRes = -1;
            int splashBuff = -1;

            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = bot.getInventory().getItem(slot);
                boolean splash = stack.is(Items.SPLASH_POTION);
                if (stack.is(Items.POTION) || splash) {
                    PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
                    if (contents != null) {
                        for (MobEffectInstance effect : contents.getAllEffects()) {
                            if (splash) {
                                if (effect.getEffect().value() == MobEffects.INSTANT_HEALTH.value() && splashHeal < 0) {
                                    splashHeal = slot;
                                } else if (effect.getEffect().value() == MobEffects.INSTANT_DAMAGE.value() && splashHarm < 0) {
                                    splashHarm = slot;
                                } else if (effect.getEffect().value() == MobEffects.STRENGTH.value() && splashStrength < 0) {
                                    splashStrength = slot;
                                } else if (effect.getEffect().value() == MobEffects.SPEED.value() && splashSpeed < 0) {
                                    splashSpeed = slot;
                                } else if (effect.getEffect().value() == MobEffects.FIRE_RESISTANCE.value() && splashFireRes < 0) {
                                    splashFireRes = slot;
                                } else if (splashBuff < 0
                                    && effect.getEffect().value().isBeneficial()
                                    && !effect.getEffect().value().isInstantenous()
                                    && runningOut(bot, effect.getEffect())) {
                                    splashBuff = slot;
                                }
                            } else if (effect.getEffect().value() == MobEffects.INSTANT_HEALTH.value() && healing < 0) {
                                healing = slot;
                            } else if (effect.getEffect().value() == MobEffects.STRENGTH.value() && strength < 0) {
                                strength = slot;
                            } else if (effect.getEffect().value() == MobEffects.SPEED.value() && swiftness < 0) {
                                swiftness = slot;
                            } else if (effect.getEffect().value() == MobEffects.FIRE_RESISTANCE.value() && fireRes < 0) {
                                fireRes = slot;
                            } else if (buff < 0
                                && effect.getEffect().value().isBeneficial()
                                && !effect.getEffect().value().isInstantenous()
                                && runningOut(bot, effect.getEffect())) {
                                // Any other lasting buff (regeneration, resistance, ...) the bot doesn't have yet.
                                buff = slot;
                            }
                        }
                    }
                }
            }

            if (splashHeal < 0 || !(bot.getHealth() <= 8.0F) || (!threatened || !(distance < 6.0)) && !(bot.getHealth() <= 5.0F)) {
                if (splashHarm < 0
                    || !threatened
                    || !(distance > 2.5)
                    || !(distance < 9.0)
                    || !(target.getHealth() <= 10.0F)
                    || target instanceof BotPlayer ally && bot.sameSide(ally)) {
                    int chosen = -1;
                    if (healing < 0 || !bot.healNow && (!(bot.getHealth() <= 8.0F) || threatened && !(distance > 3.5)) && !(bot.getHealth() <= 5.0F)) {
                        // Buffs go up right before a fight and get re-potted as they run out.
                        // Splash when the enemy is outside the splash radius (it would get the buff too), otherwise drink.
                        boolean safe = threatened && safeToDrink(bot, distance);
                        boolean splashOk = !threatened || distance > 4.5;
                        boolean engaging = threatened && distance < 24.0;
                        boolean needFireRes = runningOut(bot, MobEffects.FIRE_RESISTANCE);
                        int pick = -1;
                        if (needFireRes && (bot.isOnFire() || bot.isInLava())) {
                            // Burning: put it out right now, splashing even with the enemy close if there's nothing to drink.
                            pick = pickBuff(fireRes, splashFireRes, true, splashOk || fireRes < 0);
                        } else if (engaging && runningOut(bot, MobEffects.STRENGTH)) {
                            pick = pickBuff(strength, splashStrength, safe, splashOk);
                        }

                        if (pick < 0 && threatened && distance < 60.0 && runningOut(bot, MobEffects.SPEED)) {
                            pick = pickBuff(swiftness, splashSpeed, safe, splashOk);
                        }

                        if (pick < 0 && engaging && needFireRes) {
                            pick = pickBuff(fireRes, splashFireRes, safe, splashOk);
                        }

                        if (pick < 0 && engaging) {
                            pick = pickBuff(buff, splashBuff, safe, splashOk);
                        }

                        if (pick >= SPLASH) {
                            return this.startSplash(bot, pick - SPLASH, tick);
                        }

                        chosen = pick;
                    } else {
                        chosen = healing;
                    }

                    if (chosen < 0) {
                        return false;
                    } else {
                        // Lower a drawn bow / shield first: vanilla ignores startUsingItem while
                        // something else is in use, the potion was never drunk and the bow draw
                        // got cancelled every second - archers barely shot.
                        if (bot.isUsingItem()) {
                            bot.stopUsingItem();
                            bot.drawStartTick = -1L;
                        }

                        bot.drinkPreviousSlot = bot.getInventory().getSelectedSlot();
                        chosen = this.toHotbar(bot, chosen);
                        bot.drinkSlot = chosen;
                        bot.getInventory().setSelectedSlot(chosen);
                        bot.startUsingItem(InteractionHand.MAIN_HAND);
                        if (!bot.isUsingItem()) {
                            bot.drinkSlot = -1;
                            bot.getInventory().setSelectedSlot(bot.drinkPreviousSlot);
                            this.swapBack(bot);
                            return false;
                        } else {
                            return true;
                        }
                    }
                } else {
                    return this.throwPotion(bot, splashHarm, target);
                }
            } else {
                return this.startSplash(bot, splashHeal, tick);
            }
        } else {
            return false;
        }
    }

    private static final int SPLASH = 1000;

    /** Drink slot, SPLASH + splash slot, or -1. Splashing is instant, so it wins when allowed. */
    private static int pickBuff(int drink, int splash, boolean canDrink, boolean canSplash) {
        if (splash >= 0 && canSplash) {
            return SPLASH + splash;
        } else {
            return drink >= 0 && canDrink ? drink : -1;
        }
    }

    /** Begin a self-splash: look down over a few ticks like a player would, then throw. */
    private boolean startSplash(BotPlayer bot, int slot, long tick) {
        bot.splashSlot = slot;
        bot.splashThrowAt = tick + 2L + ThreadLocalRandom.current().nextLong(0L, 3L);
        return this.continueSplash(bot, tick);
    }

    private boolean continueSplash(BotPlayer bot, long tick) {
        if (bot.isDeadOrDying()) {
            bot.splashSlot = -1;
            return false;
        }

        LivingEntity target = bot.currentTarget;
        if (target != null && target.isAlive() && bot.distanceToSqr(target) < 36.0) {
            bot.setMoveInput(-0.6F, bot.strafeDirection * 0.3F);
        } else {
            bot.setMoveInput(0.0F, 0.0F);
        }

        bot.setXRot(this.approach(bot.getXRot(), 88.0F, 28.0F));
        boolean lookingDown = bot.getXRot() >= 80.0F;
        if (tick < bot.splashThrowAt || !lookingDown && tick < bot.splashThrowAt + 10L) {
            return true;
        }

        int slot = bot.splashSlot;
        bot.splashSlot = -1;
        if (!bot.getInventory().getItem(slot).is(Items.SPLASH_POTION)) {
            return false;
        }

        int hotbar = this.toHotbar(bot, slot);
        int previous = bot.getInventory().getSelectedSlot();
        bot.getInventory().setSelectedSlot(hotbar);
        bot.getInventory().getItem(hotbar).use(bot.level(), bot, InteractionHand.MAIN_HAND);
        bot.swing(InteractionHand.MAIN_HAND);
        bot.getInventory().setSelectedSlot(previous);
        this.swapBack(bot);
        bot.nextDrinkCheck = tick + 10L;
        bot.nextWeaponCheck = 0L;
        return false;
    }

    /** Space needed to drink safely: at full health almost point-blank, the lower the health the more distance. */
    private static boolean safeToDrink(BotPlayer bot, double distance) {
        float health = bot.getHealth() / bot.getMaxHealth();
        double minGap = health >= 0.95F ? 1.0 : (health >= 0.7F ? 2.0 : (health >= 0.4F ? 3.0 : 4.5));
        return distance >= minGap;
    }

    /** Missing, or about to expire (drinking takes 32 ticks, so start before it ends). */
    private static boolean runningOut(BotPlayer bot, Holder<MobEffect> effect) {
        MobEffectInstance active = bot.getEffect(effect);
        return active == null || !active.isInfiniteDuration() && active.getDuration() < 40;
    }

    private int toHotbar(BotPlayer bot, int slot) {
        if (slot < 9) {
            return slot;
        }

        bot.drinkSwapSlot = slot;
        bot.drinkSwapStack = bot.getInventory().getItem(8);
        bot.getInventory().setItem(8, bot.getInventory().getItem(slot));
        bot.getInventory().setItem(slot, ItemStack.EMPTY);
        return 8;
    }

    private void swapBack(BotPlayer bot) {
        if (bot.drinkSwapSlot >= 0) {
            ItemStack now = bot.getInventory().getItem(8);
            bot.getInventory().setItem(8, bot.drinkSwapStack);
            if (!now.isEmpty() && !now.is(Items.GLASS_BOTTLE)) {
                bot.getInventory().setItem(bot.drinkSwapSlot, now);
            }

            bot.drinkSwapSlot = -1;
            bot.drinkSwapStack = ItemStack.EMPTY;
        }
    }

    private boolean throwPotion(BotPlayer bot, int slot, LivingEntity target) {
        int hotbar = this.toHotbar(bot, slot);
        int previous = bot.getInventory().getSelectedSlot();
        float yaw = bot.getYRot();
        float pitch = bot.getXRot();
        if (target == null) {
            bot.setXRot(85.0F);
        } else {
            Vec3 eye = bot.getEyePosition();
            Vec3 aim = target.position().add(target.getDeltaMovement().scale(6.0));
            double dx = aim.x - eye.x;
            double dz = aim.z - eye.z;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            bot.setYRot((float)(Mth.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F);
            bot.setYHeadRot(bot.getYRot());
            bot.setXRot((float)Mth.clamp(-horizontal * 4.0 + 8.0, -40.0, 20.0));
        }

        bot.getInventory().setSelectedSlot(hotbar);
        ItemStack stack = bot.getInventory().getItem(hotbar);
        stack.use(bot.level(), bot, InteractionHand.MAIN_HAND);
        bot.swing(InteractionHand.MAIN_HAND);
        bot.getInventory().setSelectedSlot(previous);
        this.swapBack(bot);
        if (target != null) {
            bot.setYRot(yaw);
            bot.setYHeadRot(yaw);
        }

        bot.setXRot(pitch);
        bot.nextDrinkCheck += 20L;
        return false;
    }

    private boolean hasHealItem(BotPlayer bot) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = bot.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                if (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE) || slot < 9 && stack.get(DataComponents.FOOD) != null) {
                    return true;
                }

                PotionContents contents = stack.is(Items.POTION) ? stack.get(DataComponents.POTION_CONTENTS) : null;
                if (contents != null) {
                    for (MobEffectInstance effect : contents.getAllEffects()) {
                        if (effect.getEffect().value() == MobEffects.INSTANT_HEALTH.value()) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    private boolean eat(BotPlayer bot, long tick) {
        if (bot.eatSlot >= 0) {
            if (bot.isUsingItem() && bot.getInventory().getSelectedSlot() == bot.eatSlot) {
                LivingEntity target = bot.currentTarget;
                if (target != null && target.isAlive() && bot.distanceToSqr(target) < 25.0) {
                    this.face(bot, target.getEyePosition(), 20.0F);
                    bot.setMoveInput(-0.8F, bot.strafeDirection * 0.3F);
                    bot.setSprinting(false);
                } else {
                    bot.setMoveInput(0.0F, 0.0F);
                }

                return true;
            } else {
                if (bot.getFoodData().getFoodLevel() >= 20 && !bot.eatWasGolden) {
                    bot.heal(4.0F);
                }

                bot.getInventory().setSelectedSlot(bot.eatPreviousSlot);
                bot.eatSlot = -1;
                bot.nextWeaponCheck = 0L;
                this.swapBack(bot); // an apple fetched from the backpack: slot 9 gets its item back
                return false;
            }
        } else if (tick >= bot.nextEatCheck && !(bot.getHealth() >= 11.0F) && !bot.isDeadOrDying() && !bot.isInWater()) {
            bot.nextEatCheck = tick + 30L;
            LivingEntity target = bot.currentTarget;
            if (target != null && target.isAlive() && bot.distanceToSqr(target) < 36.0 && bot.getHealth() > 5.0F && !bot.healNow) {
                return false;
            }

            // Like a player: in a fight a golden apple (the enchanted one only when it's critical),
            // never a second one while the first one's regeneration/absorption still runs; steak
            // only out of a fight (bots are always full, so food heals them only a little).
            int golden = -1;
            int enchanted = -1;
            int food = -1;

            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = bot.getInventory().getItem(slot);
                if (!stack.isEmpty()) {
                    if (stack.is(Items.GOLDEN_APPLE)) {
                        if (golden < 0 || golden >= 9 && slot < 9) {
                            golden = slot;
                        }
                    } else if (stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
                        if (enchanted < 0 || enchanted >= 9 && slot < 9) {
                            enchanted = slot;
                        }
                    } else if (slot < 9 && stack.get(DataComponents.FOOD) != null && food < 0) {
                        food = slot;
                    }
                }
            }

            boolean inFight = target != null && target.isAlive() && bot.distanceToSqr(target) < 400.0
                || bot.getLastHurtByMob() != null && bot.getLastHurtByMobTimestamp() > bot.tickCount - 100;
            boolean buffed = bot.hasEffect(MobEffects.REGENERATION) || bot.getAbsorptionAmount() > 0.0F;
            float health = bot.getHealth();
            int chosen = -1;
            if (inFight) {
                if (health <= 6.0F && enchanted >= 0) {
                    chosen = enchanted;
                } else if (!buffed || health <= 6.0F) {
                    chosen = golden >= 0 ? golden : (health <= 8.0F ? enchanted : -1);
                }

                if (chosen < 0 && golden < 0 && enchanted < 0) {
                    chosen = food;
                }
            } else {
                chosen = food >= 0 ? food : (golden >= 0 ? golden : (health <= 6.0F ? enchanted : -1));
            }

            if (chosen >= 9) {
                this.swapBack(bot);
                chosen = this.toHotbar(bot, chosen);
            }

            if (chosen < 0) {
                return false;
            } else {
                if (bot.isUsingItem()) {
                    bot.stopUsingItem();
                    bot.drawStartTick = -1L;
                }

                bot.eatPreviousSlot = bot.getInventory().getSelectedSlot();
                bot.eatSlot = chosen;
                bot.eatWasGolden = chosen >= 0 && (bot.getInventory().getItem(chosen).is(Items.GOLDEN_APPLE) || bot.getInventory().getItem(chosen).is(Items.ENCHANTED_GOLDEN_APPLE));
                HABITS[bot.eatWasGolden ? 6 : 7]++;
                bot.getFoodData().setFoodLevel(14);
                bot.getInventory().setSelectedSlot(chosen);
                bot.startUsingItem(InteractionHand.MAIN_HAND);
                if (!bot.isUsingItem()) {
                    bot.eatSlot = -1;
                    bot.getInventory().setSelectedSlot(bot.eatPreviousSlot);
                    this.swapBack(bot);
                    return false;
                } else {
                    return true;
                }
            }
        } else {
            return false;
        }
    }

    private void search(BotPlayer bot, long tick) {
        LivingEntity target = !(bot.level().getEntity(bot.searchId) instanceof LivingEntity living && living.isAlive())
                || living instanceof ServerPlayer player && player.isSpectator()
            ? null
            : living;
        if (target == null) {
            bot.searchSpotted = false;
            bot.fightMode = false;
            if (bot.searchLastSeen != null) {
                this.searchArea(bot, bot.searchLastSeen);
            }
        } else {
            boolean sees = this.inViewCone(bot, target, 70.0) && bot.distanceToSqr(target) <= 2304.0 && bot.hasLineOfSight(target);
            if (sees) {
                bot.searchLastSeen = target.position();
                bot.searchLastSeenTick = tick;
                if (!bot.searchSpotted) {
                    bot.searchSpotted = true;
                    bot.targetIds.add(target.getUUID());
                    bot.currentTarget = target;
                    bot.fightMode = true;
                    bot.patrolArea = null;
                    bot.walkGoal = null;
                    bot.nextTargetTick = 0L;
                    bot.nextAttackTick = Math.max(bot.nextAttackTick, tick + bot.skill.reactionTicks(8L));
                }
            } else {
                if (bot.searchSpotted && tick - bot.searchLastSeenTick > 200L) {
                    if (tick - bot.searchLastSeenTick > 2400L) {
                        bot.searchSpotted = false;
                        bot.targetIds.remove(target.getUUID());
                        if (bot.currentTarget == target) {
                            bot.currentTarget = null;
                        }

                        bot.fightMode = false;
                        this.searchArea(bot, bot.searchLastSeen != null ? bot.searchLastSeen : bot.position());
                    } else if (bot.currentTarget == null || !bot.currentTarget.isAlive() || bot.distanceToSqr(target) > 1600.0) {
                        if (bot.walkGoal == null && bot.searchLastSeen != null && bot.position().distanceToSqr(bot.searchLastSeen) > 9.0) {
                            bot.walkGoal = bot.searchLastSeen;
                            bot.walkDeadline = tick + 1200L;
                            bot.walkGroup = null;
                            bot.marching = false;
                        } else if (bot.walkGoal == null && bot.patrolArea == null) {
                            double[] area = new double[]{
                                bot.searchLastSeen.x - 25.0, bot.searchLastSeen.z - 25.0, bot.searchLastSeen.x + 25.0, bot.searchLastSeen.z + 25.0
                            };
                            bot.patrolArea = area;
                            bot.nextPatrolPick = 0L;
                        }
                    }
                } else if (!bot.searchSpotted && bot.patrolArea == null) {
                    this.searchArea(bot, bot.searchLastSeen != null ? bot.searchLastSeen : bot.position());
                }
            }
        }
    }

    private void searchArea(BotPlayer bot, Vec3 centre) {
        bot.patrolArea = new double[]{centre.x - 60.0, centre.z - 60.0, centre.x + 60.0, centre.z + 60.0};
        bot.nextPatrolPick = 0L;
        bot.post = null;
        bot.patrol = null;
    }

    private boolean inViewCone(BotPlayer bot, LivingEntity other, double halfAngle) {
        Vec3 look = bot.getViewVector(1.0F);
        Vec3 to = other.getEyePosition().subtract(bot.getEyePosition());
        double length = to.length();
        if (length < 1.5) {
            return true;
        }

        double cos = look.dot(to) / length;
        return cos >= Math.cos(Math.toRadians(halfAngle));
    }

    private void endFidget(BotPlayer bot) {
        if (bot.fidgetKind != 0) {
            if (bot.fidgetKind == 1) {
                bot.setShiftKeyDown(false);
            }

            if (bot.fidgetKind == 3 && bot.fidgetSlot >= 0) {
                bot.getInventory().setSelectedSlot(bot.fidgetSlot);
                bot.fidgetSlot = -1;
            }

            bot.fidgetKind = 0;
            bot.fidgetSpot = null;
            bot.fidgetUntil = 0L;
            bot.setMoveInput(0.0F, 0.0F);
        }
    }

    private void glance(BotPlayer bot, long tick) {
        if (BotDefaults.get().humanMovement && bot.lookAtId == null && bot.lookAtTeam == null && bot.lookAtPos == null) {
            if (tick % 10L == bot.getId() % 10L) {
                for (ServerPlayer player : Bots.realPlayers(bot.level().players())) {
                    double distance = player.distanceToSqr(bot);
                    if (distance < 49.0 && player.getDeltaMovement().horizontalDistanceSqr() > 0.003 && ThreadLocalRandom.current().nextInt(3) != 0) {
                        bot.glanceAt = player.getUUID();
                        bot.glanceAtUntil = tick + 20L + ThreadLocalRandom.current().nextLong(0L, 40L);
                        break;
                    }
                }
            }

            if (tick < bot.glanceAtUntil && bot.glanceAt != null && bot.level().getEntity(bot.glanceAt) instanceof LivingEntity living && living.isAlive()) {
                this.face(bot, living.getEyePosition(), 9.0F);
                bot.setYBodyRot(bot.getYRot());
            } else {
                if (tick >= bot.nextGlance) {
                    bot.nextGlance = tick + 60L + ThreadLocalRandom.current().nextLong(0L, 140L);
                    bot.glanceYaw = Mth.wrapDegrees(bot.getYRot() + ThreadLocalRandom.current().nextFloat() * 120.0F - 60.0F);
                }

                double drift = BotDefaults.get().idleHeadDrift;
                float noise = drift <= 0.0
                    ? 0.0F
                    : (float)(Math.sin((tick + bot.getId() * 37L) * 0.011) * drift + Math.sin((tick + bot.getId() * 91L) * 0.037) * drift * 0.35);
                float pitchNoise = drift <= 0.0 ? 0.0F : (float)(Math.sin((tick + bot.getId() * 53L) * 0.008) * drift * 0.8);
                float yaw = Mth.wrapDegrees(this.approach(bot.getYRot(), bot.glanceYaw + noise, 3.0F));
                bot.setYRot(yaw);
                bot.setYHeadRot(yaw);
                bot.setXRot(this.approach(bot.getXRot(), pitchNoise, 2.0F));
            }
        }
    }

    private LivingEntity threatNearTeammates(BotPlayer bot, double radius) {
        LivingEntity[] found = new LivingEntity[]{null};
        this.forEachNearby(
            bot,
            radius,
            mate -> {
                if (found[0] == null
                    && mate.fightMode
                    && mate.currentTarget != null
                    && mate.currentTarget.isAlive()
                    && bot.sameSide(mate)
                    && mate.returnTo == null
                    && mate.distanceToSqr(mate.currentTarget) < 64.0
                    && mate.currentTarget.distanceToSqr(bot) <= radius * radius
                    && (mate.farmEntity == null || mate.currentTarget.getType() != mate.farmEntity)
                    && !bot.sameSide(mate.currentTarget)
                    && !bot.isAlly(mate.currentTarget.getUUID())
                    && !(mate.currentTarget instanceof BotPlayer guard && guard.isGuard())) {
                    found[0] = mate.currentTarget;
                }
            }
        );
        return found[0];
    }

    private void lookAround(BotPlayer bot) {
        if (!Float.isNaN(bot.lookYaw)) {
            bot.lookAtPos = headingPoint(bot);
        }

        if (bot.lookAtPos != null) {
            this.face(bot, bot.lookAtPos, 10.0F);
            bot.setYBodyRot(bot.getYRot());
        } else {
            LivingEntity target = this.lookTarget(bot);
            if (target != null) {
                this.face(bot, target.getEyePosition(), 10.0F);
                bot.setYBodyRot(bot.getYRot());
            }
        }
    }

    private LivingEntity lookTarget(BotPlayer bot) {
        if (bot.lookAtId == null) {
            if (bot.lookAtTeam != null) {
                BotPlayer nearest = null;
                double best = Double.MAX_VALUE;

                for (BotPlayer other : this.bots.all()) {
                    if (other != bot && bot.lookAtTeam.equalsIgnoreCase(other.team) && !other.isDeadOrDying() && other.level() == bot.level()) {
                        double distance = bot.distanceToSqr(other);
                        if (distance < best) {
                            best = distance;
                            nearest = other;
                        }
                    }
                }

                return nearest;
            } else {
                return null;
            }
        } else {
            Entity entity = bot.level().getEntity(bot.lookAtId);
            return entity instanceof LivingEntity living && living.isAlive() && entity != bot ? living : null;
        }
    }

    private boolean fight(BotPlayer bot, long tick) {
        if (bot.neverFight) {
            // Absolute override: whatever set fightMode/currentTarget, drop it and never swing.
            bot.fightMode = false;
            bot.currentTarget = null;
            return false;
        }

        LivingEntity target = bot.currentTarget;
        if (target instanceof ServerPlayer hunted
            && hunted.isAlive()
            && hunted.level() != bot.level()
            && bot.targetIds.contains(hunted.getUUID())) {
            // Angry follow: the target went to another dimension - chase it through the portal.
            return this.chasePortal(bot, tick, hunted.level().dimension());
        }

        if (bot.huntType != null && (tick + bot.getId()) % 20L == 0L) {
            double range = bot.huntRange;
            for (Entity mob : this.huntables(bot.level(), bot.huntType, tick)) {
                if (mob.isAlive() && bot.distanceToSqr(mob) <= range * range) {
                    bot.targetIds.add(mob.getUUID());
                }
            }
        }

        if (!this.validTarget(bot, target) || tick >= bot.nextTargetTick) {
            LivingEntity picked = this.selectTarget(bot, tick);
            if (picked == null && this.validTarget(bot, target)) {
                // The far scan only runs every 20 ticks; don't drop a still-valid target on the
                // re-picks in between - that made armies chase for half a second, stop for half a second.
                picked = target;
            }

            if (picked != null && picked != target) {
                bot.nextAttackTick = Math.max(bot.nextAttackTick, tick + bot.skill.reactionTicks(8L));
            }

            if (picked != target) {
                bot.chaseGoal = null;
            }

            target = picked;
            bot.currentTarget = target;
            bot.nextTargetTick = tick + (picked == null ? 10L : (bot.distanceToSqr(picked) > 36.0 ? 10L : 30L));
        }

        if (bot.responseTeam != null) {
            if (target != null && bot.responseTeam.equals(MobUnits.teamOf(target))) {
                bot.responseUntil = Math.max(bot.responseUntil, tick + 300L);
            } else if (target == null && tick >= bot.responseUntil) {
                this.endResponse(bot);
                return false;
            }
        }

        if (target == null) {
            this.pruneDeadTargets(bot);
            if (!bot.hasEnemies()
                && !bot.nearbyTargets
                && bot.huntType == null // hunting stays on: the hunt scan runs in here
                && (bot.followTarget != null || bot.isFarming() || !bot.hostileIds.isEmpty() || !bot.hostileTeams.isEmpty())) {
                bot.fightMode = false;
            }

            if (bot.freeForAll || !bot.enemyTeams.isEmpty()) {
                bot.nextTargetTick = tick + 20L;
            }

            if (bot.fightMode
                && (bot.freeForAll || !bot.enemyTeams.isEmpty() || bot.nearbyTargets || bot.huntType != null)
                && bot.walkGoal == null
                && tick >= bot.orderUntil
                && bot.post == null
                && bot.patrolArea == null
                && bot.followTarget == null
                && bot.patrol == null
                && bot.stayArea == null
                && bot.stance == Stance.AGGRESSIVE
                && tick >= bot.nextHuntPick
                && tick >= bot.giveUpUntil) {
                Vec3 hint = this.huntHint(bot);
                Vec3 spot;
                if (hint != null) {
                    // Enemies known: head straight for them (up to 48 blocks per leg) and re-aim often,
                    // instead of a random spot plus a 5-15 s wait - that was the zig-zag stop-and-go.
                    double hx = hint.x - bot.getX();
                    double hz = hint.z - bot.getZ();
                    double length = Math.max(0.001, Math.sqrt(hx * hx + hz * hz));
                    double step = Math.min(length * 0.85, 48.0);
                    bot.nextHuntPick = tick + 40L;
                    spot = Ground.find(bot.level(), bot.getX() + hx / length * step, bot.getZ() + hz / length * step, bot.getY());
                } else {
                    bot.nextHuntPick = tick + 100L + ThreadLocalRandom.current().nextLong(0L, 200L);
                    double cx = bot.getX();
                    double cz = bot.getZ();
                    double[] area = new double[]{cx - 40.0, cz - 40.0, cx + 40.0, cz + 40.0};
                    spot = Ground.randomDryNear(bot.level(), area, cx, cz, 35.0, bot.getY(), ThreadLocalRandom.current());
                }
                if (spot != null) {
                    bot.walkGoal = spot;
                    bot.walkDeadline = tick + 1200L;
                    bot.walkGroup = null;
                    bot.marching = false;
                }
            }

            return false;
        } else {
            double skill = bot.skill.skill();
            double distance = bot.distanceTo(target);
            if (distance <= 3.0) {
                bot.lastTargetDistance = distance;
            }

            this.targetVelocity(bot, target, tick);
            if (bot.jumpResetWanted) {
                if (tick - bot.hurtByMeleeTick > 3L) {
                    bot.jumpResetWanted = false;
                } else if (bot.onGround() && !bot.isUsingItem() && !bot.isInWater() && distance < 5.0) {
                    // Jump reset: jump straight into the knockback while sprinting at the enemy -
                    // the jump's forward push eats part of it, like good PvP players do.
                    bot.jumpResetWanted = false;
                    this.face(bot, target.getEyePosition(), 45.0F);
                    bot.setSprinting(true);
                    bot.setMoveInput(1.0F, 0.0F);
                    bot.wantJump(true);
                    HABITS[0]++;
                    return true;
                }
            }

            if (tick >= bot.nextWeaponCheck) {
                bot.nextWeaponCheck = tick + 10L;
                this.chooseWeapon(bot, distance, target);
            }

            if (this.dodgeMace(bot, target, tick)) {
                return true;
            }

            // Everyone (melee and archers): shut in a cave with the target up on the surface or
            // flying - dig out first instead of wandering around underground.
            if (BotDefaults.get().dig && this.surface(bot, target, tick)) {
                return true;
            }

            boolean archer = bot.getMainHandItem().is(Items.BOW) && !bot.getProjectile(bot.getMainHandItem()).isEmpty();
            if (tick < bot.retreatUntil) {
                bot.setSprinting(!bot.isInWater());
                if (bot.retreatUntil - tick <= 30L && !(distance < 2.5)) {
                    Vec3 away = bot.position().add(bot.position().subtract(target.position()).normalize().scale(8.0));
                    this.lookAlong(bot, away);
                    this.steerTowards(bot, away, tick, 1.0F, 0.0F);
                } else {
                    this.face(bot, target.getEyePosition(), 16.0F);
                    this.combatMove(bot, -1.0F, bot.strafeDirection * 0.3F);
                }

                bot.wantJump(bot.horizontalCollision && bot.onGround());
                if (!bot.healAfterRetreat || !(distance > 6.5) && bot.retreatUntil - tick >= 4L) {
                    if (!bot.healAfterRetreat && !bot.oddsRetreat && (bot.getHealth() > bot.skill.retreatHealth + 4.0 || distance > 12.0)) {
                        bot.retreatUntil = 0L;
                    }

                    return true;
                } else {
                    bot.healAfterRetreat = false;
                    bot.healNow = true;
                    bot.nextEatCheck = 0L;
                    bot.nextDrinkCheck = 0L;
                    bot.retreatUntil = 0L;
                    return true;
                }
            } else {
                bot.oddsRetreat = false;
                // Cautious bots (low aggression) back off to heal earlier, aggressive ones push on.
                double caution = Mth.clamp(1.6 - 0.6 * bot.skill.aggression, 0.6, 1.3);
                if (bot.getHealth() <= Math.max(7.0 * caution, bot.skill.retreatHealth)
                    && (distance < 6.0 || archer && distance < 24.0)
                    && tick >= bot.nextHealPlan
                    && (archer || !bot.isUsingItem())
                    && this.hasHealItem(bot)) {
                    if (bot.isUsingItem()) {
                        bot.stopUsingItem();
                        bot.drawStartTick = -1L;
                    }

                    bot.nextHealPlan = tick + 160L;
                    bot.healAfterRetreat = true;
                    bot.retreatUntil = tick + 35L + ThreadLocalRandom.current().nextLong(0L, 20L);
                    bot.strafeDirection = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
                    return true;
                }

                if (bot.skill.retreatHealth > 0.0
                    && bot.getHealth() <= bot.skill.retreatHealth
                    && distance < 6.0
                    && bot.getLastHurtByMob() != null
                    && bot.stance != Stance.DEFENSIVE
                    && ThreadLocalRandom.current().nextDouble() < 0.3 * caution) {
                    bot.retreatUntil = tick + 40L + ThreadLocalRandom.current().nextLong(0L, 60L);
                    bot.strafeDirection = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
                    return true;
                }

                // Outnumbered and hurt: fall back for a moment instead of dying in a 1-vs-3.
                if (tick >= bot.nextOddsCheck && !archer) {
                    bot.nextOddsCheck = tick + 20L + (bot.getId() & 7);
                    double hurt = bot.getHealth() / Math.max(1.0F, bot.getMaxHealth());
                    if (bot.stance != Stance.DEFENSIVE
                        && bot.post == null
                        && !bot.immortal
                        && distance < 6.0
                        && hurt < Mth.clamp(0.9 - 0.35 * bot.skill.aggression, 0.25, 0.7)) {
                        int[] odds = this.odds(bot);
                        if (odds[0] >= odds[1] + 2 + (int)Math.round(Math.max(0.0, bot.skill.aggression - 1.0) * 3.0)) {
                            bot.oddsRetreat = true;
                            HABITS[3]++;
                            bot.retreatUntil = tick + 40L + ThreadLocalRandom.current().nextLong(0L, 30L);
                            bot.strafeDirection = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
                            return true;
                        }
                    }
                }

                if (archer) {
                    return this.shoot(bot, target, tick, distance);
                }

                if (BotDefaults.get().dig && distance < 12.0 && eyeReach(bot, target) > 3.05 && this.perch(bot, target, tick)) {
                    return true;
                }

                if (BotDefaults.get().dig && distance < 24.0 && this.tunnelToward(bot, target, tick, distance)) {
                    return true;
                }

                // Melee range is measured like vanilla, eye to hitbox - the old centre-to-centre 3.0
                // capped every tier at ~2.7 real reach and kept good players out of swing range.
                if (eyeReach(bot, target) > 3.05) {
                    if (bot.isUsingItem()) {
                        bot.stopUsingItem();
                    }

                    this.throwCobweb(bot, target, tick, distance);

                    // A whole crowd on one target: the outer rings wait in their place around it
                    // (and step up when the front thins out) instead of all squeezing into one heap.
                    if (bot.ringTarget == target && bot.ringCount > 12 && BotDefaults.get().crowdRings) {
                        Vec3 place = this.approachPoint(bot, target, tick);
                        if (bot.ringNumber >= 2) {
                            double gx = place.x - bot.getX();
                            double gz = place.z - bot.getZ();
                            if (gx * gx + gz * gz < 1.0) {
                                this.face(bot, target.getEyePosition(), 12.0F);
                                this.holdStill(bot);
                            } else if (gx * gx + gz * gz < 64.0 || bot.chaseDirect) {
                                this.combatAim(bot, target, tick, distance, skill);
                                bot.setSprinting(gx * gx + gz * gz > 9.0 && !bot.isInWater());
                                this.steerTowards(bot, place, tick, 1.0F, 0.0F);
                            } else {
                                this.moveTo(bot, place, tick);
                            }
                            return true;
                        }
                    }

                    boolean sprint = distance > 2.2 + (1.0 - bot.skill.personality.sprintPreference) * 3.0;
                    bot.setSprinting(sprint && !bot.isInWater());
                    if (distance < 5.0 && bot.hasLineOfSight(target) && !bot.horizontalCollision) {
                        this.combatAim(bot, target, tick, distance, skill);
                        this.steerTowards(bot, this.approachPoint(bot, target, tick), tick, 1.0F, 0.0F);
                        if (bot.sprintJump && bot.isSprinting() && bot.onGround() && distance > 4.2 && tick >= bot.nextHopDecision) {
                            bot.nextHopDecision = tick + 30L;
                            if (ThreadLocalRandom.current().nextDouble() < Math.max(0.5, bot.skill.hopHabit)) {
                                bot.wantJump(true);
                            }
                        }

                        if (bot.skill.critHabit > 0.0 && bot.critRolledFor != bot.nextAttackTick) {
                            bot.critRolledFor = bot.nextAttackTick;
                            bot.critWanted = ThreadLocalRandom.current().nextDouble() < bot.skill.critHabit;
                        }

                        boolean closing = distance < bot.lastTargetDistance - 0.03;
                        boolean levelGround = Math.abs(target.getY() - bot.getY()) < 1.0;
                        if (bot.critWanted
                            && !bot.critPending
                            && distance > 2.4
                            && distance < 3.3
                            && bot.onGround()
                            && closing
                            && levelGround
                            && tick >= bot.nextAttackTick - 8L
                            && tick <= bot.nextAttackTick + 2L) {
                            bot.wantJump(true);
                            bot.critPending = true;
                            bot.critWanted = false;
                        }

                        bot.lastTargetDistance = distance;
                    } else {
                        if (tick >= bot.chaseCheckTick) {
                            bot.chaseCheckTick = tick + 5L;
                            bot.chaseDirect = distance < 40.0
                                && Math.abs(target.getY() - bot.getY()) < 2.5
                                && ExitSense.walkable(bot.level(), bot.position(), target.position());
                        }

                        if (bot.chaseDirect) {
                            this.combatAim(bot, target, tick, distance, skill);
                            this.steerTowards(bot, this.leadPoint(bot, target, tick, distance), tick, 1.0F, 0.0F);
                        } else {
                            // Path to a stable point instead of the target's live position: a goal that
                            // moves every tick threw away each finished path/route and made armies
                            // stop-and-go. Re-aim once the target moved 20% of the gap (min 4 blocks).
                            Vec3 live = target.position();
                            if (distance > 24.0) {
                                // Far away: each bot aims at its own spot beside the target, so an army
                                // advances as a front instead of one queue that keeps blocking itself.
                                double ax = live.x - bot.getX();
                                double az = live.z - bot.getZ();
                                double length = Math.max(0.001, Math.sqrt(ax * ax + az * az));
                                double lateral = (Math.floorMod(bot.getId() * 7, 9) - 4) * 2.2;
                                live = new Vec3(live.x - az / length * lateral, live.y, live.z + ax / length * lateral);
                            }

                            if (bot.chaseGoal == null || bot.chaseGoal.distanceToSqr(live) > Math.max(16.0, distance * distance * 0.04)) {
                                bot.chaseGoal = live;
                            }

                            this.moveTo(bot, bot.chaseGoal, tick);
                        }

                        if (bot.sprintJump
                            && bot.isSprinting()
                            && bot.onGround()
                            && !bot.horizontalCollision
                            && !bot.isInWater()
                            && distance > 5.0
                            && distance < 40.0
                            && (tick & 3L) == 0L
                            && bot.hasLineOfSight(target)) {
                            bot.wantJump(true);
                        }
                    }

                    return true;
                } else {
                    this.combatAim(bot, target, tick, distance, skill);
                    boolean ready = tick >= bot.nextAttackTick && bot.getAttackStrengthScale(0.5F) >= 0.9F;
                    boolean hasShield = bot.getOffhandItem().is(Items.SHIELD);
                    if (hasShield
                        && !ready
                        && tick >= bot.shieldUntil
                        && bot.skill.shieldHabit > 0.0
                        && distance < 3.2
                        && ThreadLocalRandom.current().nextDouble() < bot.skill.shieldHabit * 0.3) {
                        bot.startUsingItem(InteractionHand.OFF_HAND);
                        bot.shieldUntil = tick + 6L + ThreadLocalRandom.current().nextLong(0L, 14L);
                    }

                    // Read the enemy: sprinting in for a hit while our own swing isn't ready -> block it.
                    boolean rushing = target.isSprinting() && distance < bot.lastTargetDistance - 0.05 && distance < 3.4;
                    if (hasShield && !ready && rushing && tick >= bot.shieldUntil && tick >= bot.nextReadBlock && !bot.isUsingItem()
                        && bot.nextAttackTick - tick > 4L
                        && ThreadLocalRandom.current().nextDouble() < 0.15 + skill * 0.3) {
                        bot.startUsingItem(InteractionHand.OFF_HAND);
                        bot.shieldUntil = tick + 4L + ThreadLocalRandom.current().nextLong(0L, 4L);
                        bot.nextReadBlock = tick + 40L + ThreadLocalRandom.current().nextLong(0L, 40L);
                    }

                    bot.lastTargetDistance = distance;

                    if (bot.isUsingItem() && (ready || tick >= bot.shieldUntil)) {
                        bot.stopUsingItem();
                    }

                    // W-tap: good players re-sprint right after a hit for full knockback on the
                    // next one; a missed reset keeps them walking for a while.
                    long resprint = bot.wtapped ? 4L : 14L;
                    bot.setSprinting(tick - bot.lastHitTick > resprint && distance > 1.4 && !bot.isInWater() && !bot.isUsingItem());
                    this.holdRing(bot, target, tick);
                    if (!ready && !bot.isUsingItem() && bot.swingReach > 0.0 && eyeReach(bot, target) < bot.swingReach - 0.3) {
                        // Too close while the swing recharges: step back to the own reach.
                        this.combatMove(bot, -0.7F, bot.strafeDirection * 0.35F * (float)bot.skill.strafeAmount());
                    }
                    if (bot.skill.critHabit > 0.0
                        && !bot.critPending
                        && bot.onGround()
                        && !bot.isUsingItem()
                        && tick >= bot.nextAttackTick - 6L
                        && tick < bot.nextAttackTick
                        && bot.critRolledFor != bot.nextAttackTick) {
                        bot.critRolledFor = bot.nextAttackTick;
                        if (ThreadLocalRandom.current().nextDouble() < bot.skill.critHabit) {
                            bot.wantJump(true);
                            bot.critPending = true;
                        }
                    }

                    if (!bot.critPending) {
                        // Circling/stepping in close combat: still hop a one-block step in the way.
                        bot.wantJump(bot.horizontalCollision && bot.onGround());
                    }

                    if (ready && !bot.isUsingItem()) {
                        if (bot.critPending && !bot.onGround() && bot.getDeltaMovement().y >= 0.0) {
                            return true;
                        }

                        // Hit select: the target is still in its hit immunity (someone just hit it) -
                        // a swing now does next to nothing and wastes the cooldown. Good players wait
                        // the few ticks; weak ones just click.
                        if (target.invulnerableTime > 10 && skill >= 0.45 && bot.iframeHolds < 10) {
                            if (bot.iframeHolds++ == 0) {
                                HABITS[1]++;
                            }
                            return true;
                        }

                        bot.iframeHolds = 0;

                        if (bot.swingReach <= 0.0) {
                            bot.swingReach = bot.skill.swingReach();
                        }

                        double reachNow = eyeReach(bot, target);
                        // Time the swing like a player: when the gap is closing, swing a moment early
                        // so the hit lands at the intended reach (never beyond 3).
                        double closing = bot.lastReach > 0.0 ? Math.max(0.0, Math.min(0.5, bot.lastReach - reachNow)) : 0.0;
                        bot.lastReach = reachNow;
                        if (reachNow > Math.min(3.0, bot.swingReach + 0.1 + closing * 0.9)) {
                            // Not in this swing's reach yet: close in instead of hitting at the very
                            // edge every time. Weaker players now and then swing too early and whiff.
                            if (ThreadLocalRandom.current().nextDouble() < (1.0 - skill) * 0.04 && reachNow < 3.6) {
                                bot.swing(InteractionHand.MAIN_HAND, true);
                                bot.nextAttackTick = tick + bot.skill.attackCooldown(bot.getMainHandItem());
                                bot.swingReach = bot.skill.swingReach();
                            } else {
                                this.steerTowards(bot, target.position(), tick, 1.0F, bot.strafeDirection * 0.25F);
                            }

                            return true;
                        }

                        bot.critPending = false;
                        if (!bot.onGround() && bot.getDeltaMovement().y < 0.0) {
                            bot.setSprinting(false);
                        }

                        // Like a player's crosshair: a wall in between blocks the hit, and whoever
                        // stands in the line gets it instead.
                        LivingEntity struck = this.firstInLine(bot, target, reachNow);
                        boolean hit = struck != null && ThreadLocalRandom.current().nextDouble() <= bot.skill.accuracy();
                        if (!hit) {
                            ThreadLocalRandom random = ThreadLocalRandom.current();
                            Vec3 toward = target.position().subtract(bot.position());
                            Vec3 flat = new Vec3(toward.x, 0.0, toward.z);
                            flat = flat.lengthSqr() < 0.001 ? new Vec3(0.0, 0.0, 1.0) : flat.normalize();
                            double off = (random.nextBoolean() ? 1.0 : -1.0) * random.nextDouble(0.55, 1.15);
                            this.face(bot, target.getEyePosition().add(-flat.z * off, random.nextDouble(-0.45, 0.4), flat.x * off), 18.0F);
                        } else {
                            this.face(bot, target.getEyePosition(), (float)(30.0 + skill * 15.0));
                        }

                        if (hit) {
                            bot.attack(struck);
                        }
                        if (DEBUG_NAV) {
                            this.navLog(bot, "Schlag " + de.fiden.botsystem.bot.Tiers.label(bot.skill.skill()) + " aus " + String.format(Locale.ROOT, "%.2f", reachNow) + (hit ? " Treffer" : " daneben"));
                        }

                        bot.swing(InteractionHand.MAIN_HAND, true);
                        bot.lastHitTick = tick;
                        bot.nextAttackTick = tick + bot.skill.attackCooldown(bot.getMainHandItem());
                        bot.swingReach = bot.skill.swingReach();
                        bot.wtapped = ThreadLocalRandom.current().nextDouble() < bot.skill.wtapChance();
                    }

                    return true;
                }
            }
        }
    }

    private final Map<ServerLevel, Map<net.minecraft.world.entity.EntityType<?>, List<Entity>>> huntCache = new IdentityHashMap<>();
    private long huntCacheTick = -1000L;

    /**
     * All living entities of a type in a world, collected once a second and shared by every
     * hunting bot - each bot querying a 1024x1024 box every second got expensive with armies.
     */
    private List<Entity> huntables(ServerLevel level, net.minecraft.world.entity.EntityType<?> type, long tick) {
        if (tick - this.huntCacheTick >= 20L) {
            this.huntCacheTick = tick;
            this.huntCache.clear();
        }

        return this.huntCache.computeIfAbsent(level, key -> new IdentityHashMap<>()).computeIfAbsent(type, key -> {
            List<Entity> found = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (entity.getType() == key && entity instanceof LivingEntity && entity.isAlive()) {
                    found.add(entity);
                }
            }

            return found;
        });
    }

    /** Eye to the nearest point of the target's hitbox - what vanilla's reach check measures. */
    private static double eyeReach(BotPlayer bot, LivingEntity target) {
        Vec3 eye = bot.getEyePosition();
        AABB box = target.getBoundingBox();
        double x = Mth.clamp(eye.x, box.minX, box.maxX);
        double y = Mth.clamp(eye.y, box.minY, box.maxY);
        double z = Mth.clamp(eye.z, box.minZ, box.maxZ);
        return eye.distanceTo(new Vec3(x, y, z));
    }

    /**
     * What a swing at the target actually connects with: null when a block is in the way,
     * another living entity when it stands in the line first (unless it's on our side - then
     * the swing just misses), else the target.
     */
    private LivingEntity firstInLine(BotPlayer bot, LivingEntity target, double reach) {
        ServerLevel level = bot.level();
        Vec3 eye = bot.getEyePosition();
        AABB box = target.getBoundingBox();
        Vec3 aim = new Vec3(Mth.clamp(eye.x, box.minX, box.maxX), Mth.clamp(eye.y, box.minY + 0.1, box.maxY - 0.1), Mth.clamp(eye.z, box.minZ, box.maxZ));
        // Aim a little into the body, not at its very surface, so the ray reaches it.
        Vec3 into = aim.add(box.getCenter().subtract(aim).scale(0.3));
        net.minecraft.world.phys.BlockHitResult wall = level.clip(new net.minecraft.world.level.ClipContext(
            eye, into, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, bot));
        if (wall.getType() != net.minecraft.world.phys.HitResult.Type.MISS && wall.getLocation().distanceToSqr(eye) < eye.distanceToSqr(aim) - 0.01) {
            return null;
        }

        LivingEntity first = target;
        double best = eye.distanceToSqr(aim);
        AABB sweep = new AABB(eye, into).inflate(0.5);
        for (Entity other : level.getEntities(bot, sweep, e -> e != target && e instanceof LivingEntity living && living.isAlive() && !e.isSpectator() && e.isPickable())) {
            java.util.Optional<Vec3> crossing = other.getBoundingBox().inflate(other.getPickRadius()).clip(eye, into);
            if (crossing.isPresent()) {
                double distance = eye.distanceToSqr(crossing.get());
                if (distance < best) {
                    best = distance;
                    first = (LivingEntity)other;
                }
            }
        }

        if (first != target && (first instanceof BotPlayer mate && bot.sameSide(mate) || bot.isAlly(first.getUUID()))) {
            return null; // a friend is in the way - the swing goes nowhere useful
        }

        return first;
    }

    /**
     * The enemy is dropping onto the bot with a mace (airborne, falling fast, above and close):
     * shield up if there is one, otherwise sprint sideways out of the landing spot.
     */
    private boolean dodgeMace(BotPlayer bot, LivingEntity target, long tick) {
        if (!target.getMainHandItem().is(Items.MACE) || target.onGround()) {
            return false;
        }

        double fall = target.getY() - target.yo;
        double above = target.getY() - bot.getY();
        double flat = Math.sqrt(bot.distanceToSqr(target.getX(), bot.getY(), target.getZ()));
        if (!(fall < -0.35) || !(above > 1.5) || !(flat < 4.5) || above > 40.0) {
            return false;
        }

        if (bot.getOffhandItem().is(Items.SHIELD)) {
            if (!bot.isUsingItem()) {
                bot.startUsingItem(InteractionHand.OFF_HAND);
            }

            bot.shieldUntil = tick + 12L;
            this.face(bot, target.getEyePosition(), 40.0F);
            bot.setMoveInput(0.0F, 0.0F);
            return true;
        }

        if (bot.isUsingItem()) {
            bot.stopUsingItem();
        }

        // Sideways, away from where it will land.
        Vec3 away = bot.position().subtract(target.getX(), bot.getY(), target.getZ());
        if (away.lengthSqr() < 0.01) {
            away = new Vec3(bot.strafeDirection >= 0 ? 1.0 : -1.0, 0.0, 0.0);
        }

        Vec3 spot = bot.position().add(away.normalize().scale(4.0));
        this.lookAlong(bot, spot);
        this.steerTowards(bot, spot, tick, 1.0F, 0.0F);
        bot.setSprinting(true);
        return true;
    }

    /** A cobweb in the inventory and a target running around 2-6 blocks away: web its feet. */
    private void throwCobweb(BotPlayer bot, LivingEntity target, long tick, double distance) {
        if (tick < bot.nextCobweb || distance < 2.0 || distance > 6.0 || !target.onGround()) {
            return;
        }

        double speed = Math.sqrt((target.getX() - target.xo) * (target.getX() - target.xo) + (target.getZ() - target.zo) * (target.getZ() - target.zo));
        if (speed < 0.1) {
            return;
        }

        Inventory inventory = bot.getInventory();
        int slot = -1;
        for (int i = 0; i < 36; i++) {
            if (inventory.getItem(i).is(Items.COBWEB)) {
                slot = i;
                break;
            }
        }

        if (slot < 0) {
            bot.nextCobweb = tick + 200L;
            return;
        }

        bot.nextCobweb = tick + 60L + ThreadLocalRandom.current().nextLong(0L, 60L);
        ServerLevel level = bot.level();
        // Where it will be a moment from now.
        BlockPos at = BlockPos.containing(target.getX() + (target.getX() - target.xo) * 4.0, target.getY() + 0.2, target.getZ() + (target.getZ() - target.zo) * 4.0);
        if (!level.getBlockState(at).canBeReplaced() || !level.getFluidState(at).isEmpty() || !bot.hasLineOfSight(target)) {
            return;
        }

        this.face(bot, Vec3.atCenterOf(at), 60.0F);
        this.bots().blocks.remember(level, at, tick); // before placing: restoring puts the air back
        level.setBlock(at, Blocks.COBWEB.defaultBlockState(), 3);
        inventory.getItem(slot).shrink(1);
        bot.swing(InteractionHand.MAIN_HAND, true);
    }

    private void combatAim(BotPlayer bot, LivingEntity target, long tick, double distance, double skill) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (bot.aimTarget != target) {
            bot.aimTarget = target;
            bot.aimPoint = null;
        }

        if (tick >= bot.nextLookAway) {
            bot.nextLookAway = tick + random.nextLong(100L, 241L);
            if (random.nextDouble() < 0.035 + (1.0 - skill) * 0.055) {
                bot.lookAwayUntil = tick + random.nextLong(3L, 7L);
            }
        }

        Vec3 desired = target.getEyePosition();
        if (distance > 7.0 && tick < bot.lookAwayUntil) {
            Vec3 side = new Vec3(target.getX() - bot.getX(), 0.0, target.getZ() - bot.getZ());
            if (side.lengthSqr() > 0.001) {
                side = side.normalize();
                desired = bot.getEyePosition()
                    .add(new Vec3(-side.z, 0.0, side.x).scale(bot.skill.pathBias >= 0.0 ? 4.0 : -4.0))
                    .add(0.0, Math.sin((tick + Math.floorMod(bot.getId(), 97)) * 0.11) * 0.18, 0.0);
            }
        }

        if (tick >= bot.nextAimTick || bot.aimPoint == null) {
            Vec3 tracked = bot.aimPoint != null ? bot.aimPoint : bot.getEyePosition().add(bot.getLookAngle().scale(Math.min(4.0, distance)));
            double phase = (tick + Math.floorMod(bot.getId(), 360)) * 0.045;
            double drift = 0.015 + (1.0 - skill) * 0.08;
            tracked = tracked.add(desired.subtract(tracked).scale(0.28 + skill * 0.42))
                .add(Math.sin(phase) * drift, Math.cos(phase * 0.73) * drift * 0.45, Math.sin(phase * 0.61 + 1.4) * drift);
            bot.aimPoint = tracked;
            bot.nextAimTick = tick + 1L + Math.round((1.0 - skill) * 2.0);
        }

        this.face(bot, bot.aimPoint, (float)(4.5 + skill * 7.0) * 1.6F);
    }

    /** The target's smoothed movement per tick, from its server positions (works for real players too). */
    private Vec3 targetVelocity(BotPlayer bot, LivingEntity target, long tick) {
        if (bot.trackedTarget != target || bot.trackedPos == null || tick - bot.trackedTick > 10L) {
            bot.trackedTarget = target;
            bot.trackedPos = target.position();
            bot.trackedTick = tick;
            bot.trackedVel = Vec3.ZERO;
            return Vec3.ZERO;
        }

        if (tick > bot.trackedTick) {
            Vec3 now = target.position();
            Vec3 step = now.subtract(bot.trackedPos).scale(1.0 / (tick - bot.trackedTick));
            if (step.horizontalDistanceSqr() > 1.0) {
                step = Vec3.ZERO; // teleport or a knockback spike - not where it is heading
            }

            bot.trackedVel = bot.trackedVel.scale(0.6).add(step.scale(0.4));
            bot.trackedPos = now;
            bot.trackedTick = tick;
        }

        return bot.trackedVel;
    }

    /** Where a moving target will be by the time we get there: chase ahead of it, not its back. */
    private Vec3 leadPoint(BotPlayer bot, LivingEntity target, long tick, double distance) {
        Vec3 velocity = this.targetVelocity(bot, target, tick);
        if (velocity.horizontalDistanceSqr() < 0.0025) {
            return target.position();
        }

        double ticks = Math.min(16.0, distance / 0.28) * (0.35 + bot.skill.skill() * 0.45);
        HABITS[4]++;
        return target.position().add(velocity.x * ticks, 0.0, velocity.z * ticks);
    }

    /** Running away from us (not just strafing). */
    private boolean targetFleeing(BotPlayer bot, LivingEntity target, long tick) {
        Vec3 velocity = this.targetVelocity(bot, target, tick);
        double speed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (speed < 0.12) {
            return false;
        }

        double dx = target.getX() - bot.getX();
        double dz = target.getZ() - bot.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        return length > 0.01 && (velocity.x * dx + velocity.z * dz) / (speed * length) > 0.5;
    }

    /**
     * Combat footwork with a look at the ground: backing off or strafing never walks the bot off a
     * drop of more than 3 blocks or into lava/fire - it tries the other side, else stands.
     */
    private void combatMove(BotPlayer bot, float forward, float strafe) {
        if (bot.onGround() && (forward < 0.0F || strafe != 0.0F) && this.footworkDanger(bot, forward, strafe)) {
            HABITS[2]++;
            if (strafe != 0.0F && !this.footworkDanger(bot, Math.max(0.0F, forward), -strafe)) {
                strafe = -strafe;
                bot.strafeDirection = -bot.strafeDirection;
                forward = Math.max(0.0F, forward);
            } else if (!this.footworkDanger(bot, Math.max(0.0F, forward), 0.0F)) {
                strafe = 0.0F;
                forward = Math.max(0.0F, forward);
            } else {
                forward = 0.0F;
                strafe = 0.0F;
            }
        }

        bot.setMoveInput(forward, strafe);
    }

    private boolean footworkDanger(BotPlayer bot, float forward, float strafe) {
        if (forward == 0.0F && strafe == 0.0F) {
            return false;
        }

        // Same rotation vanilla uses for move input (Entity.getInputVector).
        double yaw = Math.toRadians(bot.getYRot());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        double dx = strafe * cos - forward * sin;
        double dz = forward * cos + strafe * sin;
        double length = Math.sqrt(dx * dx + dz * dz);
        ServerLevel level = bot.level();
        MutableBlockPos at = new MutableBlockPos();
        for (double reach : new double[]{0.6, 1.1}) {
            int x = Mth.floor(bot.getX() + dx / length * reach);
            int z = Mth.floor(bot.getZ() + dz / length * reach);
            int y = Mth.floor(bot.getY() + 0.2);
            for (int up = 0; up <= 1; up++) {
                at.set(x, y + up, z);
                BlockState state = level.getBlockState(at);
                if (state.getFluidState().is(FluidTags.LAVA) || state.is(BlockTags.FIRE) || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH)) {
                    return true;
                }
            }

            at.set(x, y, z);
            if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()) {
                continue; // a wall: walking into it is harmless
            }

            boolean floor = false;
            for (int down = 1; down <= 4 && !floor; down++) {
                at.set(x, y - down, z);
                BlockState below = level.getBlockState(at);
                if (below.getFluidState().is(FluidTags.LAVA) || below.is(BlockTags.FIRE) || below.is(Blocks.MAGMA_BLOCK)) {
                    return true;
                }
                floor = !below.getCollisionShape(level, at).isEmpty() || below.getFluidState().is(FluidTags.WATER);
            }

            if (!floor) {
                return true;
            }
        }

        return false;
    }

    /** Enemies after this bot within 7 blocks vs. fighting friends within 10. */
    private int[] odds(BotPlayer bot) {
        int[] counts = new int[2];
        this.forEachNearby(bot, 10.0, other -> {
            if (other.isDeadOrDying()) {
                return;
            }
            if (bot.sameSide(other)) {
                if (other.fightMode) {
                    counts[1]++;
                }
            } else if (other.currentTarget == bot && bot.distanceToSqr(other) < 49.0) {
                counts[0]++;
            }
        });
        if (this.bots.mobs() != null && !this.bots.mobs().isEmpty()) {
            for (MobUnits.Unit unit : this.bots.mobs().near(bot.level(), bot.position(), 7.0)) {
                if (unit.mob.getTarget() == bot) {
                    counts[0]++;
                }
            }
        }
        return counts;
    }

    private Vec3 approachPoint(BotPlayer bot, LivingEntity target, long tick) {
        if (this.ringTick != tick) {
            this.ringTick = tick;
            this.ringCache.clear();

            for (BotPlayer other : this.bots.all()) {
                if (other.fightMode && other.currentTarget != null && other.currentTarget.isAlive() && !other.isDeadOrDying()) {
                    this.ringCache.computeIfAbsent(other.currentTarget, key -> new ArrayList<>()).add(other);
                }
            }

            for (Entry<LivingEntity, List<BotPlayer>> entry : this.ringCache.entrySet()) {
                List<BotPlayer> list = entry.getValue();
                if (list.size() > 24) {
                    // Big crowd: the nearest form the front ring (distance in 2-block steps, so the
                    // order doesn't flicker while everyone shuffles about).
                    LivingEntity centre = entry.getKey();
                    list.sort(Comparator.<BotPlayer>comparingInt(other -> (int)(Math.sqrt(other.distanceToSqr(centre)) / 2.0)).thenComparingInt(Entity::getId));
                } else {
                    list.sort(Comparator.comparingInt(Entity::getId));
                }

                LivingEntity ringTarget = list.get(0).currentTarget;
                for (int i = 0; i < list.size(); i++) {
                    list.get(i).ringIndex = i;
                    list.get(i).ringCount = list.size();
                    list.get(i).ringTarget = ringTarget;
                }
            }
        }

        // (a list lookup per call was 2000 x 2000 checks a tick with a whole swarm on one target)
        int count = bot.ringTarget == target ? Math.max(1, bot.ringCount) : 1;
        int base = count == 1 ? 0 : bot.ringIndex;
        int rotation = count > 6 && count <= 24 ? (int)(tick / 140L % count) : 0;
        int index = Math.floorMod(base + rotation, count);
        boolean mobTargetRing = !(target instanceof ServerPlayer);
        int ring;
        int members;
        int slot;
        if (count <= 24) {
            ring = index / 6;
            members = Math.min(6, count - ring * 6);
            slot = index % 6;
        } else {
            // Big crowds: rings filled by their circumference (a real crowd), not 6 per ring -
            // 2000 attackers made rings 400 blocks wide.
            ring = 0;
            int first = 0;
            int capacity = 6;
            while (true) {
                double radius = 2.15 + ring * (mobTargetRing ? 1.0 : 1.3);
                capacity = Math.max(6, (int)(Math.PI * 2 * radius / 1.15));
                if (index < first + capacity) {
                    break;
                }
                first += capacity;
                ring++;
            }
            slot = index - first;
            members = Math.min(capacity, count - first);
        }
        bot.ringNumber = ring;
        double angle = count > 24
            // keep its own bearing: just move in or out to the ring, never around through the crowd
            ? Math.atan2(bot.getZ() - target.getZ(), bot.getX() - target.getX())
            : (Math.PI * 2) * slot / Math.max(1, members) + bot.skill.pathBias * 0.16;
        boolean targetInAir = !target.onGround() && target.getY() > bot.getY() + 1.25;
        if (targetInAir) {
            angle += ((bot.getId() & 1) == 0 ? 1.0 : -1.0) * (tick / 10L % 180L) * Math.PI * 2.0 / 180.0;
        } else if (count == 1) {
            angle = Math.atan2(bot.getZ() - target.getZ(), bot.getX() - target.getX()) + bot.skill.pathBias * 0.12;
        }

        boolean mobTarget = !(target instanceof ServerPlayer);
        // Stand where this swing's reach ends (eye to hitbox), like a player spacing a fight -
        // not pressed against the enemy: that made every tier hit from about 2 blocks.
        if (bot.swingReach <= 0.0) {
            bot.swingReach = bot.skill.swingReach();
        }

        double spacing = bot.swingReach + target.getBbWidth() / 2.0 - 0.1;
        double radius = (targetInAir ? 1.1 : Math.max(spacing, mobTarget ? 1.25 : (count == 1 ? 1.7 : 2.15)))
            + ring * (mobTarget ? 1.0 : 1.3)
            + (ring > 0 ? Math.floorMod(bot.getId(), 3) * 0.1 : 0.0);
        return new Vec3(target.getX() + Math.cos(angle) * radius, targetInAir ? bot.getY() : target.getY(), target.getZ() + Math.sin(angle) * radius);
    }

    private void holdRing(BotPlayer bot, LivingEntity target, long tick) {
        Vec3 spot = this.approachPoint(bot, target, tick);
        double dx = spot.x - bot.getX();
        double dz = spot.z - bot.getZ();
        double yawRad = Math.toRadians(bot.getYRot());
        double facingX = -Math.sin(yawRad);
        double facingZ = Math.cos(yawRad);
        double gap = Math.sqrt(dx * dx + dz * dz);
        if (gap > 0.7) {
            double along = (dx * facingX + dz * facingZ) / gap;
            double across = (dx * facingZ - dz * facingX) / gap;
            float scale = (float)Math.min(1.0, gap / 1.5 + 0.3);
            this.combatMove(bot, (float)Mth.clamp(along, -1.0, 1.0) * scale, (float)Mth.clamp(across, -1.0, 1.0) * scale);
        } else {
            int phase = Math.floorMod((int)tick + bot.getId(), 60);
            if (phase >= 12 && phase <= 34) {
                float direction = Math.floorMod((int)(tick / 120L) + bot.getId(), 2) == 0 ? 1.0F : -1.0F;
                this.combatMove(bot, 0.0F, direction * (float)((0.55 + bot.skill.personality.strafePreference * 0.35) * (0.45 + bot.skill.strafeAmount() * 0.7)));
            } else {
                bot.setMoveInput(0.0F, 0.0F);
            }
        }
    }

    private void chooseWeapon(BotPlayer bot, double distance, LivingEntity target) {
        int bow = -1;
        int melee = -1;
        int meleeScore = -1;

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = bot.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                if (stack.is(Items.BOW)) {
                    if (!bot.getProjectile(stack).isEmpty()) {
                        bow = slot;
                    }
                } else {
                    int score = stack.is(ItemTags.SWORDS)
                        ? 4
                        : (
                            stack.is(Items.MACE)
                                ? 4
                                : (
                                    stack.is(ItemTags.AXES)
                                        ? 3
                                        : (
                                            stack.is(Items.TRIDENT)
                                                ? 3
                                                : (stack.is(ItemTags.PICKAXES) ? 1 : (stack.is(ItemTags.SHOVELS) ? 1 : (stack.is(ItemTags.HOES) ? 1 : 0)))
                                        )
                                )
                        );
                    if (score > meleeScore) {
                        meleeScore = score;
                        melee = slot;
                    }
                }
            }
        }

        int axe = -1;
        if (target == null || !target.isBlocking()) {
            bot.stunDecision = 0;
        } else if (bot.stunDecision == 0) {
            // Once per raised shield: does this player go for the axe stun? (tier table "stun")
            bot.stunDecision = ThreadLocalRandom.current().nextDouble() < bot.skill.stunChance() ? 1 : -1;
        }

        if (target != null && target.isBlocking() && distance < 4.0 && bot.stunDecision > 0) {
            for (int slot = 0; slot < 9; slot++) {
                if (bot.getInventory().getItem(slot).is(ItemTags.AXES)) {
                    axe = slot;
                    break;
                }
            }
        }

        int wanted;
        if (axe >= 0 && bow < 0) {
            wanted = axe;
        } else if (bow >= 0) {
            wanted = bow;
        } else if (melee >= 0) {
            wanted = melee;
        } else {
            wanted = bow >= 0 ? bow : 0;
        }

        if (bot.getInventory().getSelectedSlot() != wanted) {
            if (bot.isUsingItem()) {
                bot.stopUsingItem();
            }

            bot.getInventory().setSelectedSlot(wanted);
            bot.drawStartTick = -1L;
        }
    }

    private boolean shoot(BotPlayer bot, LivingEntity target, long tick, double distance) {
        double skill = bot.skill.skill();
        boolean dragon = target instanceof EnderDragon;
        if (bot.hasLineOfSight(target)) {
            bot.lastSawTarget = tick;
        }

        // A short gap in sight (someone walking past, a leaf) doesn't make an archer lower the bow.
        boolean sees = tick - bot.lastSawTarget < 15L;
        boolean hold = BotDefaults.get().archersHold || bot.stance == Stance.DEFENSIVE;
        double range = dragon ? 60.0 : 32.0;
        if (distance <= range && sees) {
            Vec3 aim = this.arrowAim(bot, target, skill, tick);
            this.face(bot, aim, (float)(8.0 + skill * 14.0));
            bot.setSprinting(false);
            this.archerFootwork(bot, target, tick, distance, hold);
            if (!bot.isUsingItem()) {
                if (tick >= bot.nextAttackTick) {
                    bot.startUsingItem(InteractionHand.MAIN_HAND);
                    bot.drawStartTick = tick;
                }

                return true;
            } else {
                if (bot.drawStartTick < 0L) {
                    bot.drawStartTick = tick; // drawn by something else - count from now
                }

                // 20 ticks = full power (arrow speed 3); arrowAim assumes that.
                long charge = 20L + Math.round((1.0 - skill) * 8.0);
                if (tick - bot.drawStartTick >= charge) {
                    this.face(bot, aim, 180.0F);
                    bot.releaseUsingItem();
                    bot.drawStartTick = -1L;
                    bot.nextAttackTick = tick + 4L + Math.round((1.0 - skill) * 16.0);
                }

                return true;
            }
        } else {
            if (bot.isUsingItem() && !sees) {
                bot.stopUsingItem();
                bot.drawStartTick = -1L;
            }

            this.face(bot, target.getEyePosition(), 20.0F);
            if (hold && sees) {
                // In sight but out of range: keep moving, just not toward the enemy.
                this.archerFootwork(bot, target, tick, 12.0, true);
                bot.setSprinting(false);
                return true;
            } else if (hold && distance < 48.0) {
                // No sight: sidestep to find a shooting lane instead of standing still.
                if (tick >= bot.strafeUntil || bot.horizontalCollision) {
                    bot.strafeDirection = bot.horizontalCollision ? -bot.strafeDirection : (ThreadLocalRandom.current().nextBoolean() ? 1 : -1);
                    if (bot.strafeDirection == 0) {
                        bot.strafeDirection = 1;
                    }

                    bot.strafeUntil = tick + 25L + ThreadLocalRandom.current().nextLong(0L, 20L);
                }

                bot.setMoveInput(0.0F, bot.strafeDirection * 0.7F);
                bot.wantJump(bot.horizontalCollision && bot.onGround());
                bot.setSprinting(false);
                return true;
            } else if (hold) {
                this.stand(bot);
                return true;
            } else {
                bot.setSprinting(distance > 12.0 && !bot.isInWater());
                this.moveTo(bot, target.position(), tick);
                return true;
            }
        }
    }

    /**
     * Archer movement while shooting: always on the move (strafing, changing sides, stepping
     * back when the enemy gets close), never walking up to it - unless not holding and far away.
     */
    private void archerFootwork(BotPlayer bot, LivingEntity target, long tick, double distance, boolean hold) {
        if (tick >= bot.strafeUntil || bot.horizontalCollision && bot.onGround() && tick % 10L == 0L) {
            int previous = bot.strafeDirection;
            bot.strafeDirection = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
            if (bot.horizontalCollision) {
                bot.strafeDirection = previous == 0 ? 1 : -previous;
            }

            bot.strafeUntil = tick + ThreadLocalRandom.current().nextLong(20L, 50L);
        }

        float side = bot.strafeDirection * (bot.isUsingItem() ? 0.55F : 0.8F);
        float forward;
        if (distance < 6.0) {
            forward = -0.9F;
        } else if (distance < 10.0) {
            forward = -0.4F;
        } else if (distance > 24.0 && !hold) {
            forward = 0.6F;
        } else {
            forward = 0.0F;
        }

        // Don't back off a ledge or into lava.
        if (forward < 0.0F && this.dropAhead(bot, bot.getLookAngle().scale(-1.0)) > 3) {
            forward = 0.0F;
        }

        bot.setMoveInput(forward, side);
        bot.wantJump(bot.horizontalCollision && bot.onGround());
    }

    /**
     * Where to point the bow so a full-power arrow hits: the middle of the body (the ender
     * dragon's body part, not its eye), led by the target's real movement, lifted by the actual
     * arrow drop from a simulated flight (speed 3, drag 0.99, gravity 0.05). Plus skill-based error.
     */
    private Vec3 arrowAim(BotPlayer bot, LivingEntity target, double skill, long tick) {
        Entity part = target;
        if (target instanceof EnderDragon dragon) {
            EnderDragonPart[] parts = dragon.getSubEntities();
            if (parts.length > 2) {
                part = parts[2]; // body
            }
        }

        Vec3 moved = new Vec3(target.getX() - target.xo, target.getY() - target.yo, target.getZ() - target.zo);
        double verticalLead = target.onGround() ? 0.0 : (target instanceof EnderDragon || target.isFallFlying() ? 1.0 : 0.3);
        Vec3 centre = part.getBoundingBox().getCenter();
        Vec3 eye = bot.getEyePosition().add(0.0, -0.1, 0.0);
        Vec3 goal = centre;
        double lift = 0.0;
        for (int round = 0; round < 3; round++) {
            Vec3 aimAt = goal.add(0.0, lift, 0.0);
            int ticks = this.arrowFlight(eye, aimAt, goal);
            if (ticks < 0) {
                break;
            }

            double miss = this.arrowHeightAt(eye, aimAt, goal) - goal.y;
            lift -= miss;
            goal = centre.add(moved.x * ticks, moved.y * ticks * verticalLead, moved.z * ticks);
        }

        Vec3 aim = goal.add(0.0, lift, 0.0);
        double error = (1.0 - skill) * 0.9;
        if (error > 0.0) {
            long seed = tick / 10L + bot.getId() * 31L;
            java.util.Random random = new java.util.Random(seed);
            aim = aim.add((random.nextDouble() - 0.5) * error, (random.nextDouble() - 0.5) * error * 0.6, (random.nextDouble() - 0.5) * error);
        }

        return aim;
    }

    /** Ticks a full-power arrow shot from eye toward aimAt needs to cover the horizontal gap to goal (-1: never). */
    private int arrowFlight(Vec3 eye, Vec3 aimAt, Vec3 goal) {
        Vec3 velocity = aimAt.subtract(eye).normalize().scale(3.0);
        double gap = Math.sqrt((goal.x - eye.x) * (goal.x - eye.x) + (goal.z - eye.z) * (goal.z - eye.z));
        double covered = 0.0;
        double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        for (int ticks = 1; ticks <= 120; ticks++) {
            covered += horizontal;
            horizontal *= 0.99;
            if (covered >= gap) {
                return ticks;
            }
        }

        return -1;
    }

    /** Height of that arrow when it reaches the horizontal distance of goal. */
    private double arrowHeightAt(Vec3 eye, Vec3 aimAt, Vec3 goal) {
        Vec3 velocity = aimAt.subtract(eye).normalize().scale(3.0);
        double gap = Math.sqrt((goal.x - eye.x) * (goal.x - eye.x) + (goal.z - eye.z) * (goal.z - eye.z));
        double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        double vy = velocity.y;
        double covered = 0.0;
        double y = eye.y;
        for (int ticks = 1; ticks <= 120; ticks++) {
            if (covered + horizontal >= gap) {
                double part = horizontal <= 1.0E-6 ? 1.0 : (gap - covered) / horizontal;
                return y + vy * part;
            }

            covered += horizontal;
            y += vy;
            horizontal *= 0.99;
            vy = vy * 0.99 - 0.05;
        }

        return y;
    }

    private void addMobCandidates(BotPlayer bot, ServerLevel world, List<LivingEntity> candidates) {
        Vec3 pos = bot.position();
        double[] best = new double[]{Double.MAX_VALUE};

        for (LivingEntity candidate : candidates) {
            best[0] = Math.min(best[0], bot.distanceToSqr(candidate));
        }

        IdentityHashMap<String, Boolean> teams = new IdentityHashMap<>();

        for (int ring = 0; ring <= 3; ring++) {
            double reach = best[0] == Double.MAX_VALUE ? 48.0 : Math.min(48.0, Math.sqrt(best[0]) * 1.5 + 3.0);
            if (Math.max(0.0, (ring - 1) * 16.0) > reach) {
                break;
            }

            this.bots.mobs().ring(world, pos, ring, unit -> {
                double distance = unit.mob.distanceToSqr(pos);
                double limit = best[0] == Double.MAX_VALUE ? 48.0 : Math.min(48.0, Math.sqrt(best[0]) * 1.5 + 3.0);
                if (!(distance > limit * limit) && this.unitWanted(bot, unit, teams) && this.validTarget(bot, unit.mob)) {
                    candidates.add(unit.mob);
                    if (distance < best[0]) {
                        best[0] = distance;
                    }
                }
            });
        }
    }

    private boolean unitWanted(BotPlayer bot, MobUnits.Unit unit, IdentityHashMap<String, Boolean> teams) {
        if (!bot.targetIds.isEmpty() && bot.targetIds.contains(unit.mob.getUUID())) {
            return true;
        }

        if (bot.freeForAll && unit.freeForAll) {
            return true;
        }

        if (unit.team == null) {
            return false;
        }

        String key = unit.teamKey();
        Boolean wanted = teams.get(key);
        if (wanted == null) {
            wanted = bot.enemyTeams.contains(key) || Bots.teamRelation(bot.team, key, true);
            teams.put(key, wanted);
        }

        return wanted;
    }

    private boolean validTarget(BotPlayer bot, LivingEntity target) {
        if (target != null && target != bot && !target.isRemoved() && !target.isDeadOrDying() && target.isAlive()) {
            if (target.level() == bot.level() && !bot.isAlly(target.getUUID())) {
                if (target instanceof ServerPlayer player) {
                    if (player.isSpectator() || !(player instanceof BotPlayer) && BotDefaults.get().ignorePlayers) {
                        return false;
                    }

                    if (player.isCreative() && !bot.targetIds.contains(player.getUUID())) {
                        return false;
                    }
                }

                if (bot.sameSide(target) || Bots.allied(bot.team, MobUnits.teamOf(target))) {
                    return false;
                } else if (target instanceof BotPlayer other && other.isGuard() && !bot.targetIds.contains(other.getUUID())) {
                    return false;
                } else if (bot.stance == Stance.DEFENSIVE && !this.withinHold(bot, target.position(), this.leash(bot))) {
                    return false;
                } else if (bot.stayArea != null && !insideArea(bot.stayArea, target.getX(), target.getZ(), 8.0)) {
                    return false;
                } else if (bot.marching && bot.walkGoal != null) {
                    boolean current = target == bot.currentTarget;
                    double reach = current ? 32.0 : 16.0;
                    return bot.distanceToSqr(target) <= reach * reach && (current || bot.hasLineOfSight(target));
                } else {
                    boolean explicit = bot.targetIds.contains(target.getUUID()) || bot.targets(target);
                    double limit = explicit ? 512.0 : 48.0;
                    return bot.distanceToSqr(target) <= limit * limit;
                }
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    private Vec3 huntHint(BotPlayer bot) {
        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;
        if (bot.huntType != null) {
            // Hunting: head for the nearest mob of that kind even beyond the hunt radius - a
            // hunter searches, it doesn't stand around waiting for one to wander close.
            long now = bot.level().getServer().getTickCount();
            for (Entity mob : this.huntables(bot.level(), bot.huntType, now)) {
                double distance = mob.distanceToSqr(bot);
                if (mob.isAlive() && distance < bestDistance) {
                    bestDistance = distance;
                    best = mob.position();
                }
            }
        }

        for (String team : bot.enemyTeams) {
            List<BotPlayer> members = this.byTeam.get(team);
            if (members != null) {
                int offset = ThreadLocalRandom.current().nextInt(Math.max(1, members.size()));

                for (int i = 0; i < Math.min(24, members.size()); i++) {
                    BotPlayer other = members.get((offset + i) % members.size());
                    if (!other.isDeadOrDying() && other.level() == bot.level() && !other.isGuard()) {
                        double distance = other.distanceToSqr(bot);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = other.position();
                        }
                    }
                }
            }
        }

        if (bot.nearbyTargets) {
            for (ServerPlayer player : Bots.realPlayers(bot.level().players())) {
                if (this.validTarget(bot, player)) {
                    double distance = player.distanceToSqr(bot);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = player.position();
                    }
                }
            }
        }

        return best;
    }

    private static boolean outsideLevels(double[] area, double y) {
        return area.length >= 6 && (y < Math.min(area[4], area[5]) - 0.5 || y > Math.max(area[4], area[5]) + 0.6);
    }

    private boolean relaxed(BotPlayer bot, long tick) {
        if (!bot.fightMode && tick >= bot.orderUntil && bot.walkGroup == null) {
            double[] area = bot.patrolArea != null ? bot.patrolArea : bot.stayArea;
            return area != null && outsideLevels(area, bot.getY()) ? false : bot.patrolArea != null || bot.stayArea != null || bot.lifeMode || bot.post != null;
        } else {
            return false;
        }
    }

    private int dropAhead(BotPlayer bot, Vec3 direction) {
        ServerLevel world = bot.level();
        Vec3 pos = bot.position();
        int x = Mth.floor(pos.x + direction.x * 0.8);
        int z = Mth.floor(pos.z + direction.z * 0.8);
        int feet = Mth.floor(pos.y + 0.01);
        MutableBlockPos at = new MutableBlockPos();
        if (!world.getBlockState(at.set(x, feet, z)).getCollisionShape(world, at).isEmpty()) {
            return 0;
        }

        for (int down = 1; down <= 5; down++) {
            at.set(x, feet - down, z);
            BlockState state = world.getBlockState(at);
            if (!state.getCollisionShape(world, at).isEmpty() || !world.getFluidState(at).isEmpty() || state.is(BlockTags.CLIMBABLE)) {
                return down - 1;
            }
        }

        return 6;
    }

    private static boolean insideArea(double[] area, double x, double z, double margin) {
        return x >= area[0] - margin && x <= area[2] + margin && z >= area[1] - margin && z <= area[3] + margin;
    }

    private double leash(BotPlayer bot) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = bot.getInventory().getItem(slot);
            if (stack.is(Items.BOW) && !bot.getProjectile(stack).isEmpty()) {
                return 26.0;
            }
        }

        return 12.0;
    }

    private boolean withinHold(BotPlayer bot, Vec3 pos, double leash) {
        if (bot.patrolArea != null) {
            double[] a = bot.patrolArea;
            double dx = Math.max(0.0, Math.max(a[0] - pos.x, pos.x - a[2]));
            double dz = Math.max(0.0, Math.max(a[1] - pos.z, pos.z - a[3]));
            return dx * dx + dz * dz <= leash * leash;
        }

        Vec3 hold = bot.post != null ? bot.post : bot.stanceAnchor;
        if (hold == null) {
            hold = bot.stanceAnchor = bot.position();
        }

        double dx = hold.x - pos.x;
        double dz = hold.z - pos.z;
        return dx * dx + dz * dz <= leash * leash;
    }

    private LivingEntity selectTarget(BotPlayer bot, long tick) {
        if (tick < bot.ceasefireUntil) {
            LivingEntity attacker = bot.getLastHurtByMob();
            return attacker != null && attacker == bot.currentTarget && this.validTarget(bot, attacker) ? attacker : null;
        }

        if (bot.followTarget != null) {
            LivingEntity best = null;
            double bestDistance = Double.MAX_VALUE;

            for (UUID id : bot.targetIds) {
                if (bot.level().getEntity(id) instanceof LivingEntity living && this.validTarget(bot, living)) {
                    double distance = bot.distanceToSqr(living);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = living;
                    }
                }
            }

            if (best != null) {
                return best;
            }

            ServerPlayer owner = bot.level().getServer().getPlayerList().getPlayer(bot.followTarget);
            Vec3 anchor = owner != null && !bot.guardHold ? owner.position() : bot.position();
            double[] bestGuard = new double[]{Double.MAX_VALUE};
            LivingEntity[] pick = new LivingEntity[]{null};
            this.forEachNearby(
                bot,
                24.0,
                other -> {
                    if ((bot.targets(other) || bot.knowsHostile(other) && bot.hasLineOfSight(other))
                        && this.validTarget(bot, other)
                        && other.position().distanceToSqr(anchor) <= 256.0) {
                        double distancex = bot.distanceToSqr(other);
                        if (distancex < bestGuard[0]) {
                            bestGuard[0] = distancex;
                            pick[0] = other;
                        }
                    }
                }
            );
            if (pick[0] == null && this.bots.mobs() != null) {
                for (MobUnits.Unit unit : this.bots.mobs().near(bot.level(), bot.position(), 24.0)) {
                    if ((bot.targets(unit.mob) || bot.knowsHostile(unit.mob) && bot.hasLineOfSight(unit.mob))
                        && this.validTarget(bot, unit.mob)
                        && unit.mob.position().distanceToSqr(anchor) <= 256.0) {
                        double distance = bot.distanceToSqr(unit.mob);
                        if (distance < bestGuard[0]) {
                            bestGuard[0] = distance;
                            pick[0] = unit.mob;
                        }
                    }
                }
            }

            return pick[0];
        } else {
            if (bot.stance == Stance.PASSIVE) {
                LivingEntity attacker = bot.getLastHurtByMob();
                return attacker != null && attacker == bot.currentTarget && this.validTarget(bot, attacker) ? attacker : null;
            }

            LivingEntity[] best = new LivingEntity[]{null};
            double[] bestDistance = new double[]{Double.MAX_VALUE};
            ServerLevel world = bot.level();
            List<LivingEntity> candidates = new ArrayList<>();

            for (ServerPlayer player : Bots.realPlayers(world.players())) {
                boolean wanted = bot.targetIds.contains(player.getUUID()) || bot.nearbyTargets;
                if (wanted && this.validTarget(bot, player)) {
                    candidates.add(player);
                }
            }

            if (bot.hasEnemies()) {
                boolean onlyListed = bot.enemyTeams.isEmpty() && !bot.freeForAll && !Bots.hasTeamEnemies(bot.team);
                if (onlyListed) {
                    // Only named targets (an attack on one player, say): look them up directly -
                    // scanning all 2000 team mates around for enemies was the swarm's biggest cost.
                    int listed = 0;
                    for (UUID id : bot.targetIds) {
                        if (++listed > 64) {
                            break;
                        }
                        if (world.getEntity(id) instanceof LivingEntity living
                            && !(living instanceof ServerPlayer player && !(player instanceof BotPlayer))
                            && bot.distanceToSqr(living) <= 2304.0
                            && this.validTarget(bot, living)) {
                            candidates.add(living);
                        }
                    }
                } else {
                    String ownTeam = bot.team;
                    this.forEachNearby(bot, 48.0, other -> {
                        // Team mates first and cheaply: they are most of a crowd.
                        if (ownTeam != null && ownTeam.equals(other.team) && !bot.freeForAll && !bot.targetIds.contains(other.getUUID())) {
                            return;
                        }
                        if (candidates.size() < 200 && bot.targets(other) && this.validTarget(bot, other)) {
                            candidates.add(other);
                        }
                    });
                }
                if (Actors.enabled()) {
                    for (LivingEntity actor : Actors.near(world, bot.position(), 48.0)) {
                        if (bot.targetIds.contains(actor.getUUID()) && this.validTarget(bot, actor)) {
                            candidates.add(actor);
                        }
                    }
                }

                if (!onlyListed && this.bots.mobs() != null && !this.bots.mobs().isEmpty()) {
                    this.addMobCandidates(bot, world, candidates);
                }
            }

            if (!candidates.isEmpty()) {
                double nearest = Double.MAX_VALUE;

                for (LivingEntity candidate : candidates) {
                    nearest = Math.min(nearest, bot.distanceToSqr(candidate));
                }

                double reach = Math.sqrt(nearest) * 1.5 + 3.0;
                double limit = reach * reach;

                for (LivingEntity candidate : candidates) {
                    double raw = bot.distanceToSqr(candidate);
                    if (!(raw > limit)) {
                        int others = this.load(candidate) - (candidate == bot.currentTarget ? 1 : 0);
                        boolean onMe = candidate == bot.getLastHurtByMob() && bot.getLastHurtByMobTimestamp() > bot.tickCount - 100
                            || candidate instanceof BotPlayer enemy && enemy.currentTarget == bot
                            || candidate instanceof Mob mob && mob.getTarget() == bot;
                        double health = (candidate.getHealth() + candidate.getAbsorptionAmount()) / Math.max(1.0F, candidate.getMaxHealth());
                        double score = switch (bot.skill.targetPref) {
                            case NEAREST -> raw * (candidate == bot.currentTarget ? 0.85 : 1.0);
                            case WEAKEST -> raw * (0.25 + health) * (1.0 + 0.25 * Math.min(3, Math.max(0, others))) * (candidate == bot.currentTarget ? 0.8 : 1.0);
                            case STRONGEST -> raw * (1.6 - Math.min(1.0, health) * 0.4 - Math.min(20, candidate.getArmorValue()) / 40.0) * (candidate == bot.currentTarget ? 0.8 : 1.0);
                            case THREAT -> raw * (onMe ? 0.2 : 1.0) * (candidate == bot.currentTarget ? 0.8 : 1.0);
                            default -> raw
                                * (candidate.getHealth() < 7.0F ? 0.55 : 1.0)
                                * (1.0 + 0.4 * Math.min(3, Math.max(0, others)))
                                * (onMe ? 0.7 : 1.0)
                                * (candidate == bot.currentTarget ? 0.8 : 1.0);
                        };
                        if (score < bestDistance[0]) {
                            bestDistance[0] = score;
                            best[0] = candidate;
                        }
                    }
                }
            }

            if (best[0] == null && bot.hasEnemies() && tick >= bot.nextFarScanTick) {
                bot.nextFarScanTick = tick + 20L;
                int checked = 0;

                for (UUID id : bot.targetIds) {
                    if (++checked > 64) {
                        break;
                    }

                    if (world.getEntity(id) instanceof LivingEntity living && this.validTarget(bot, living)) {
                        double distance = bot.distanceToSqr(living);
                        if (distance < bestDistance[0]) {
                            bestDistance[0] = distance;
                            best[0] = living;
                        }
                    }
                }

                if (this.bots.mobs() != null) {
                    for (String team : bot.enemyTeams) {
                        for (MobUnits.Unit unit : this.bots.mobs().byTeam(team)) {
                            if (this.validTarget(bot, unit.mob)) {
                                double distance = bot.distanceToSqr(unit.mob);
                                if (distance < bestDistance[0]) {
                                    bestDistance[0] = distance;
                                    best[0] = unit.mob;
                                }
                            }
                        }
                    }
                }

                for (String team : bot.enemyTeams) {
                    List<BotPlayer> members = this.byTeam.get(team);
                    if (members != null && !members.isEmpty()) {
                        int offset = (int)((tick / 20L + bot.getId()) % members.size());

                        for (int i = 0; i < Math.min(48, members.size()); i++) {
                            BotPlayer other = members.get((offset + i) % members.size());
                            if (this.validTarget(bot, other)) {
                                double distance = bot.distanceToSqr(other);
                                if (distance < bestDistance[0]) {
                                    bestDistance[0] = distance;
                                    best[0] = other;
                                }
                            }
                        }
                    }
                }
            }

            return best[0];
        }
    }

    private void pruneDeadTargets(BotPlayer bot) {
        ServerLevel world = bot.level();
        bot.targetIds.removeIf(id -> {
            Entity entity = world.getEntity(id);
            return entity == null || entity.isRemoved() || entity instanceof LivingEntity living && living.isDeadOrDying();
        });
    }

    public void lookout(BotPlayer bot, double range, boolean needSight) {
        boolean[] spotted = new boolean[]{false};
        boolean defensive = bot.stance == Stance.DEFENSIVE;
        double leash = defensive ? this.leash(bot) : 0.0;
        Map<String, Integer> seenPerTeam = new HashMap<>();
        this.forEachNearby(
            bot,
            range,
            other -> {
                if (!other.isGuard()
                    && bot.knowsHostile(other)
                    && other.isAlive()
                    && !bot.targetIds.contains(other.getUUID())
                    && (!needSight || bot.hasLineOfSight(other))
                    && (!defensive || this.withinHold(bot, other.position(), leash))) {
                    bot.targetIds.add(other.getUUID());
                    spotted[0] = true;
                    if (other.team != null) {
                        seenPerTeam.merge(other.teamKey(), 1, Integer::sum);
                    }
                }
            }
        );
        if (this.bots.mobs() != null && !this.bots.mobs().isEmpty()) {
            for (MobUnits.Unit unit : this.bots.mobs().near(bot.level(), bot.position(), range)) {
                if (bot.knowsHostile(unit.mob)
                    && !bot.targetIds.contains(unit.mob.getUUID())
                    && (!needSight || bot.hasLineOfSight(unit.mob))
                    && (!defensive || this.withinHold(bot, unit.mob.position(), leash))) {
                    bot.targetIds.add(unit.mob.getUUID());
                    spotted[0] = true;
                    if (unit.team != null) {
                        seenPerTeam.merge(unit.teamKey(), 1, Integer::sum);
                    }
                }
            }
        }

        if (bot.team != null) {
            for (Entry<String, Integer> entry : seenPerTeam.entrySet()) {
                if (entry.getValue() >= 3 && (bot.hostileTeams.contains(entry.getKey()) || bot.strangerTeam(entry.getKey()))) {
                    this.alarmWithAllies(bot.teamKey(), entry.getKey(), bot.position());
                }
            }
        }

        for (ServerPlayer player : Bots.realPlayers(bot.level().players())) {
            if (bot.hostileIds.contains(player.getUUID())
                && player.isAlive()
                && bot.distanceToSqr(player) <= range * range
                && !bot.targetIds.contains(player.getUUID())
                && (!needSight || bot.hasLineOfSight(player))) {
                bot.targetIds.add(player.getUUID());
                spotted[0] = true;
            }
        }

        if (spotted[0] && !bot.fightMode) {
            bot.fightMode = true;
            bot.nextTargetTick = 0L;
        }
    }

    public void alarmWithAllies(String team, String enemyTeam) {
        this.alarmWithAllies(team, enemyTeam, null);
    }

    public void alarmWithAllies(String team, String enemyTeam, Vec3 incident) {
        int radius = BotDefaults.get().responseRadius;
        if (incident != null && radius > 0) {
            List<String> teams = new ArrayList<>();
            teams.add(team.toLowerCase(Locale.ROOT));
            Set<String> allies = Bots.teamAllies().get(team.toLowerCase(Locale.ROOT));
            if (allies != null) {
                for (String ally : allies) {
                    if (!ally.equalsIgnoreCase(enemyTeam)) {
                        teams.add(ally);
                    }
                }
            }

            int enemies = 0;
            List<BotPlayer> enemyMembers = this.byTeam.get(enemyTeam);
            if (enemyMembers != null) {
                for (BotPlayer enemy : enemyMembers) {
                    if (!enemy.isDeadOrDying() && enemy.position().distanceToSqr(incident) < 1024.0) {
                        enemies++;
                    }
                }
            }

            if (this.bots.mobs() != null) {
                for (MobUnits.Unit unit : this.bots.mobs().byTeam(enemyTeam)) {
                    if (unit.mob.isAlive() && unit.mob.position().distanceToSqr(incident) < 1024.0) {
                        enemies++;
                    }
                }
            }

            int wanted = Math.max(3, enemies * 2 + 2);
            long now = this.bots.server().getTickCount();
            List<BotPlayer> candidates = new ArrayList<>();

            for (String member : teams) {
                List<BotPlayer> list = this.byTeam.get(member);
                if (list != null) {
                    for (BotPlayer bot : list) {
                        if (!bot.isDeadOrDying() && !bot.neverFight && bot.stance != Stance.PASSIVE) {
                            bot.hostileTeams.add(enemyTeam);
                            if (bot.responseTeam != null && bot.responseTeam.equals(enemyTeam)) {
                                bot.responseUntil = now + 600L;
                            } else if (bot.position().distanceToSqr(incident) <= (double)radius * radius
                                && (bot.stayArea == null || insideArea(bot.stayArea, incident.x, incident.z, 8.0))) {
                                candidates.add(bot);
                            }
                        }
                    }

                    if (this.bots.mobs() != null) {
                        for (MobUnits.Unit unit : this.bots.mobs().byTeam(member)) {
                            if (unit.stance != Stance.PASSIVE) {
                                unit.hostileTeams.add(enemyTeam);
                                if (unit.mob.position().distanceToSqr(incident) <= (double)radius * radius) {
                                    unit.enemyTeams.add(enemyTeam);
                                    unit.fightMode = true;
                                    unit.responseTeam = enemyTeam;
                                    unit.responseUntil = now + 600L;
                                }
                            }
                        }
                    }
                }
            }

            candidates.sort(Comparator.comparingDouble(botx -> botx.position().distanceToSqr(incident)));
            int sent = 0;

            for (BotPlayer bot : candidates) {
                if (sent >= wanted) {
                    break;
                }

                if (now >= bot.ceasefireUntil && (bot.walkGoal == null || now >= bot.orderUntil) && !bot.marching) {
                    if (bot.enemyTeams.contains(enemyTeam) && bot.responseTeam == null) {
                        bot.fightMode = true;
                        bot.nextTargetTick = 0L;
                        sent++;
                    } else {
                        if (bot.responseTeam == null) {
                            bot.savedWalkGoal = bot.walkGoal;
                            bot.savedWalkDeadline = bot.walkDeadline;
                            bot.savedMarching = bot.marching;
                            if (bot.returnTo == null && bot.walkGoal == null && bot.post == null && bot.patrolArea == null && bot.followTarget == null) {
                                bot.returnTo = bot.position();
                            }
                        }

                        bot.responseTeam = enemyTeam;
                        bot.responseUntil = now + 600L;
                        bot.enemyTeams.add(enemyTeam);
                        bot.fightMode = true;
                        bot.nextTargetTick = 0L;
                        bot.walkGoal = null;
                        sent++;
                    }
                }
            }
        } else {
            this.alarm(team, enemyTeam);
            Set<String> allies = Bots.teamAllies().get(team.toLowerCase(Locale.ROOT));
            if (allies != null) {
                for (String ally : allies) {
                    if (!ally.equalsIgnoreCase(enemyTeam)) {
                        this.alarm(ally, enemyTeam);
                    }
                }
            }
        }
    }

    private void endResponse(BotPlayer bot) {
        if (bot.responseTeam != null) {
            bot.enemyTeams.remove(bot.responseTeam);
            bot.responseTeam = null;
            bot.fightMode = bot.hasEnemies();
            bot.currentTarget = null;
            if (bot.savedWalkGoal != null) {
                bot.walkGoal = bot.savedWalkGoal;
                bot.walkDeadline = Math.max(bot.savedWalkDeadline, this.bots.server().getTickCount() + 2400L);
                bot.marching = bot.savedMarching;
                bot.savedWalkGoal = null;
            }
        }
    }

    public void alarm(String team, String enemyTeam) {
        List<BotPlayer> members = this.byTeam.get(team);
        if (members != null) {
            for (BotPlayer member : members) {
                if (!member.neverFight && member.stance != Stance.PASSIVE && !member.enemyTeams.contains(enemyTeam) && !member.isDeadOrDying()) {
                    member.enemyTeams.add(enemyTeam);
                    member.fightMode = true;
                    member.nextTargetTick = 0L;
                }
            }
        }

        if (this.bots.mobs() != null) {
            for (MobUnits.Unit unit : this.bots.mobs().byTeam(team)) {
                if (unit.stance != Stance.PASSIVE) {
                    unit.enemyTeams.add(enemyTeam);
                    unit.fightMode = true;
                }
            }
        }
    }

    private void balanceGuards(MinecraftServer server) {
        Map<UUID, List<BotPlayer>> byOwner = new HashMap<>();

        for (BotPlayer bot : this.bots.all()) {
            if (bot.followTarget != null && bot.fightMode && !bot.isDeadOrDying() && !bot.isRemoved()) {
                byOwner.computeIfAbsent(bot.followTarget, key -> new ArrayList<>()).add(bot);
            }
        }

        for (Entry<UUID, List<BotPlayer>> entry : byOwner.entrySet()) {
            List<BotPlayer> guards = entry.getValue();
            Map<UUID, LivingEntity> threats = new LinkedHashMap<>();

            for (BotPlayer guard : guards) {
                ServerLevel world = guard.level();

                for (UUID id : guard.targetIds) {
                    if (!id.equals(entry.getKey())
                        && !threats.containsKey(id)
                        && world.getEntity(id) instanceof LivingEntity living
                        && living.isAlive()
                        && !living.isRemoved()) {
                        threats.put(id, living);
                    }
                }
            }

            if (threats.size() >= 2 && guards.size() >= 2) {
                List<LivingEntity> list = new ArrayList<>(threats.values());
                List<BotPlayer> pool = new ArrayList<>(guards);
                int perThreat = Math.max(1, guards.size() / list.size());

                for (LivingEntity threat : list) {
                    pool.sort(Comparator.comparingDouble(guardx -> guardx.distanceToSqr(threat)));
                    int take = Math.min(perThreat, pool.size());

                    for (int i = 0; i < take; i++) {
                        this.assign(pool.remove(0), threat);
                    }
                }

                for (BotPlayer guard : pool) {
                    LivingEntity nearest = null;
                    double best = Double.MAX_VALUE;

                    for (LivingEntity threat : list) {
                        double distance = guard.distanceToSqr(threat);
                        if (distance < best) {
                            best = distance;
                            nearest = threat;
                        }
                    }

                    this.assign(guard, nearest);
                }
            }
        }
    }

    private void assign(BotPlayer guard, LivingEntity threat) {
        if (threat != null) {
            if (threat != guard.currentTarget) {
                guard.currentTarget = threat;
                guard.nextTargetTick = 0L;
            }

            returnFire(this.bots, guard, threat);
        }
    }

    public static void returnFire(Bots bots, BotPlayer guard, LivingEntity threat) {
        if (threat instanceof BotPlayer enemy
            && enemy != guard
            && !enemy.isAlly(guard.getUUID())
            && !enemy.sameSide(guard)
            && enemy.targetIds.add(guard.getUUID())) {
            enemy.fightMode = true;
            enemy.nextTargetTick = 0L;
        }
    }

    private void learnCrumbs(BotPlayer bot, long tick) {
    }

    private void commitCrumbs(BotPlayer bot, boolean arrived) {
        if (arrived && !bot.trailTainted && !bot.crumbTrail.isEmpty()) {
            String world = NavMemory.worldId(bot.level());

            for (double[] step : bot.crumbTrail) {
                BotSystemMod.nav().link(world, new Vec3(step[0], step[1], step[2]), new Vec3(step[3], step[4], step[5]), step[6], true);
            }
        }

        bot.crumbTrail.clear();
    }

    private void askMemory(BotPlayer bot, Vec3 goal, long tick) {
    }

    private boolean train(BotPlayer bot, long tick) {
        if (bot.trainRunsLeft <= 0 || bot.trainA == null || bot.trainB == null) {
            return false;
        }

        if (bot.walkGoal != null) {
            return false;
        }

        Vec3 target = bot.trainToB ? bot.trainB : bot.trainA;
        double dx = target.x - bot.getX();
        double dz = target.z - bot.getZ();
        if (bot.trainLegStart == 0L && dx * dx + dz * dz < 16.0) {
            bot.trainToB = !bot.trainToB;
            target = bot.trainToB ? bot.trainB : bot.trainA;
        }

        if (bot.trainLegStart > 0L) {
            long time = tick - bot.trainLegStart;
            if (time < 20L) {
                bot.trainToB = !bot.trainToB;
                bot.trainLegStart = tick;
                bot.walkGoal = bot.trainToB ? bot.trainB : bot.trainA;
                bot.walkDeadline = tick + 3600L;
                bot.orderUntil = bot.walkDeadline;
                return true;
            }

            bot.trainTimes.add(time);
            bot.trainRunsLeft--;
            BotSystemMod.LOGGER
                .info(
                    "[train] {} Strecke {} in {} s geschafft ({} Laeufe offen)",
                    bot.getGameProfile().name(),
                    bot.trainToB ? "A->B" : "B->A",
                    String.format(Locale.ROOT, "%.1f", time / 20.0),
                    bot.trainRunsLeft
                );
            bot.trainToB = !bot.trainToB;
            target = bot.trainToB ? bot.trainB : bot.trainA;
            if (bot.trainRunsLeft <= 0) {
                bot.trainLegStart = 0L;
                AttributeInstance done = bot.getAttribute(Attributes.MOVEMENT_SPEED);
                if (done != null) {
                    done.setBaseValue(0.1);
                }

                BotSystemMod.nav().save();
                return false;
            }
        }

        double factor = Math.max(1.0, BotDefaults.get().trainSpeed);
        AttributeInstance speed = bot.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(0.1 * factor);
        }

        bot.hesitateUntil = 0L;
        bot.trainLegStart = tick;
        bot.walkGoal = target;
        bot.walkDeadline = tick + 3600L;
        bot.orderUntil = bot.walkDeadline;
        bot.walkGroup = null;
        bot.marching = false;
        return true;
    }

    private void lapStart(BotPlayer bot, Vec3 goal, long tick) {
        bot.lapStart = -1L;
    }

    private void lapFinish(BotPlayer bot, long tick, boolean reached) {
        if (bot.lapStart >= 0L && bot.lapKey != null) {
            double seconds = (tick - bot.lapStart) / 20.0;
            if (reached && !bot.lapPath.isEmpty()) {
                double[] first = bot.lapPath.get(0);
                double sx = bot.getX() - first[0];
                double sz = bot.getZ() - first[2];
                double speedLimit = 7.0 * (bot.trainRunsLeft <= 0 && !bot.gymActive ? 1.0 : Math.max(1.0, BotDefaults.get().trainSpeed));
                if (Math.sqrt(sx * sx + sz * sz) / Math.max(0.05, seconds) > speedLimit || Math.sqrt(sx * sx + sz * sz) < 4.0) {
                    reached = false;
                }
            }

            if (reached && seconds > 0.5 && (BotDefaults.get().learnAlways || bot.trainRunsLeft > 0 || bot.gymActive)) {
                BotProfile profile = bot.profile();
                boolean better = profile.lap(bot.lapKey, seconds, bot.lapPath);
                BotSystemMod.skills().touch();
                if (bot.gymActive && bot.gymRun) {
                    Gym.lap(bot, seconds, tick);
                }

                if (better && (bot.trainRunsLeft > 0 || DEBUG_NAV)) {
                    BotSystemMod.LOGGER
                        .info(
                            "[train] {} neue Bestzeit {} s auf {} (Runde {}, {} Verbesserungen)",
                            bot.getGameProfile().name(),
                            String.format(Locale.ROOT, "%.1f", seconds),
                            bot.lapKey,
                            profile.laps,
                            profile.improvements
                        );
                }
            }

            bot.lapStart = -1L;
            bot.lapKey = null;
            bot.lapPath.clear();
        }
    }

    private boolean gym(BotPlayer bot, long tick) {
        if (bot.gymUntil >= 0L && tick > bot.gymUntil) {
            this.stopGym(bot);
            return false;
        }

        if (bot.walkGoal != null) {
            return false;
        }

        Vec3 goal = Gym.pick(bot);
        if (goal == null) {
            this.stand(bot);
            return true;
        }

        AttributeInstance speed = bot.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(0.1 * Math.max(1.0, BotDefaults.get().trainSpeed));
        }

        bot.hesitateUntil = 0L;
        bot.walkGoal = goal;
        bot.walkDeadline = tick + 2400L;
        bot.orderUntil = bot.walkDeadline;
        bot.walkGroup = null;
        bot.marching = false;
        return true;
    }

    public void stopGym(BotPlayer bot) {
        bot.gymActive = false;
        bot.gymUntil = -1L;
        AttributeInstance speed = bot.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(0.1);
        }

        BotSystemMod.skills().save();
    }

    private boolean explore(BotPlayer bot, long tick) {
        ExitSense.Episode episode = bot.escape;
        ExitSense.visit(episode, bot.position());
        boolean out = ExitSense.escaped(bot, episode);
        if (!out && tick <= episode.deadline) {
            boolean arrived = episode.target != null
                && (episode.target.x - bot.getX()) * (episode.target.x - bot.getX()) + (episode.target.z - bot.getZ()) * (episode.target.z - bot.getZ()) < 2.25;
            if (episode.target == null || arrived || tick > episode.targetUntil) {
                episode.target = ExitSense.choose(bot.level(), bot, episode);
                episode.targetUntil = tick + 100L;
                if (episode.target == null) {
                    this.stand(bot);
                    return true;
                }
            }

            bot.walkGoal = episode.target;
            bot.walkDeadline = tick + 200L;
            bot.orderUntil = bot.walkDeadline;
            bot.walkGroup = null;
            bot.marching = false;
            this.walk(bot, tick);
            return true;
        } else {
            if (out) {
                ExitSense.rememberExit(bot.position());
            }

            double seconds = ExitSense.finish(episode, out, tick);
            if (episode.training) {
                ExitSense.cageResult(bot, out, seconds);
            }

            this.navLog(bot, (out ? "draussen nach " : "nicht rausgekommen nach ") + String.format(Locale.ROOT, "%.1f", seconds) + " s");
            bot.escape = null;
            bot.walkGoal = episode.goal;
            return false;
        }
    }

    private void openDoorAhead(BotPlayer bot) {
        ServerLevel world = bot.level();
        float yaw = bot.getYRot() * (float) (Math.PI / 180.0);
        double fx = -Mth.sin(yaw);
        double fz = Mth.cos(yaw);

        for (double reach : new double[]{0.6, 1.2}) {
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos pos = BlockPos.containing(bot.getX() + fx * reach, bot.getY() + 0.1 + dy, bot.getZ() + fz * reach);
                BlockState state = world.getBlockState(pos);
                if (state.getBlock() instanceof DoorBlock door) {
                    if (!door.isOpen(state) && door.type().canOpenByHand()) {
                        door.setOpen(bot, world, state, pos, true);
                        bot.swing(InteractionHand.MAIN_HAND);
                    }

                    return;
                }

                if (state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)) {
                    world.setBlock(pos, state.setValue(FenceGateBlock.OPEN, true), 10);
                    bot.swing(InteractionHand.MAIN_HAND);
                    return;
                }
            }
        }
    }

    private boolean flee(BotPlayer bot, long tick) {
        Vec3 pos = bot.position();
        double sx = 0.0;
        double sz = 0.0;
        double nearest = Double.MAX_VALUE;
        int threats = 0;

        for (LivingEntity other : bot.level()
            .getEntitiesOfClass(LivingEntity.class, bot.getBoundingBox().inflate(28.0, 8.0, 28.0), otherx -> otherx != bot && otherx.isAlive())) {
            boolean threat = other == bot.getLastHurtByMob()
                || other instanceof Mob mob && mob.getTarget() == bot
                || other instanceof BotPlayer enemy && (enemy.currentTarget == bot || enemy.fightMode && !enemy.sameSide(bot) && bot.wantsToHurt(other))
                || bot.targetIds.contains(other.getUUID());
            if (threat) {
                double distance = other.distanceToSqr(pos);
                double weight = 1.0 / Math.max(1.0, distance);
                sx += (other.getX() - pos.x) * weight;
                sz += (other.getZ() - pos.z) * weight;
                nearest = Math.min(nearest, distance);
                threats++;
            }
        }

        bot.fightMode = false;
        bot.currentTarget = null;
        if (threats > 0) {
            bot.lastThreatTick = tick;
        }

        if (bot.getHealth() < 14.0F && nearest > 64.0) {
            bot.healNow = true;
            bot.nextEatCheck = Math.min(bot.nextEatCheck, tick);
            bot.nextDrinkCheck = Math.min(bot.nextDrinkCheck, tick);
            bot.nextHealPlan = tick + 100L;
        }

        if (threats == 0) {
            if (tick - bot.lastThreatTick > 60L) {
                bot.walkGoal = null;
                bot.setSprinting(false);
                this.stand(bot);
                return true;
            }
        } else if (tick >= bot.fleeRepick || bot.walkGoal == null) {
            bot.fleeRepick = tick + 20L;
            double length = Math.sqrt(sx * sx + sz * sz);
            double ax = length < 1.0E-4 ? Math.cos(bot.getId()) : -sx / length;
            double az = length < 1.0E-4 ? Math.sin(bot.getId()) : -sz / length;
            Vec3 best = null;
            double bestScore = -Double.MAX_VALUE;

            for (double turn : new double[]{0.0, 0.5, -0.5, 1.0, -1.0, 1.5, -1.5}) {
                double cos = Math.cos(turn);
                double sin = Math.sin(turn);
                double dx = ax * cos - az * sin;
                double dz = ax * sin + az * cos;
                Vec3 ground = Ground.find(bot.level(), pos.x + dx * 18.0, pos.z + dz * 18.0, pos.y);
                if (ground != null && !(Math.abs(ground.y - pos.y) > 6.0)) {
                    double score = -Math.abs(turn) * 4.0 - Math.abs(ground.y - pos.y);
                    if (score > bestScore) {
                        bestScore = score;
                        best = ground;
                    }
                }
            }

            if (best != null) {
                bot.walkGoal = best;
                bot.walkDeadline = tick + 80L;
                bot.orderUntil = bot.walkDeadline;
                bot.walkGroup = null;
                bot.marching = false;
            }
        }

        if (bot.walkGoal == null) {
            this.stand(bot);
            return true;
        }

        bot.setSprinting(!bot.isInWater());
        this.walk(bot, tick);
        if (bot.onGround() && !bot.horizontalCollision && !bot.isInWater() && bot.isSprinting()) {
            bot.wantJump(true);
        }

        return true;
    }

    private boolean walk(BotPlayer bot, long tick) {
        if (bot.portalFrom != null && bot.level().dimension() != bot.portalFrom) {
            // Came out the other side of a portal: the portal goal is stale here.
            this.arrivedThroughPortal(bot);
            if (bot.walkGoal == null) {
                return false;
            }
        }

        if (bot.walkGoalDimension != null) {
            if (bot.level().dimension() != bot.walkGoalDimension) {
                int portal = this.aimAtPortal(bot, tick, bot.walkGoalDimension);
                if (portal == PORTAL_NONE) {
                    bot.setMoveInput(0.0F, 0.0F);
                    return false;
                } else if (portal == PORTAL_HANDLED) {
                    return true;
                }
            } else {
                bot.walkGoalDimension = null;
                if (bot.crossGoal != null) {
                    bot.walkGoal = bot.crossGoal;
                    bot.crossGoal = null;
                }
            }
        }

        Vec3 goal = bot.walkGoal;
        boolean rejoining = false;
        if (bot.recPath != null) {
            if (bot.recGoal != goal) {
                this.endRecorded(bot);
            } else {
                Vec3 next = this.followRecorded(bot, tick);
                if (next == null) {
                    bot.lastWalkTick = tick;
                    return true;
                }

                if (next != REC_DONE) {
                    goal = next;
                    rejoining = true;
                }
            }
        }

        if (bot.walkTracked != goal || tick - bot.lastWalkTick > 20L) {
            this.beginWalk(bot, goal, tick);
        }

        bot.lastWalkTick = tick;
        if (bot.pendingLong != null) {
            if (bot.pendingLong.isDone()) {
                LongRoute.Route route = bot.pendingLong.getNow(null);
                bot.pendingLong = null;
                Vec3 here = bot.position();
                boolean sameGoal = bot.longGoal != null && bot.longGoal.distanceToSqr(goal) < Math.max(16.0, bot.longGoal.distanceToSqr(bot.position()) * 0.04);
                boolean moved = bot.longFrom != null && bot.longFrom.distanceToSqr(here) > 36.0
                    || bot.pillarBaseY != Integer.MIN_VALUE
                    || tick < bot.crossUntil;
                if (!sameGoal || moved) {
                    bot.nextLongPlan = 0L;
                } else if (route == null) {
                    bot.longFailed = true;
                    this.navLog(bot, "Weitplanung: kein Weg");
                } else if (!route.points().isEmpty()) {
                    double straight = Math.sqrt((goal.x - here.x) * (goal.x - here.x) + (goal.z - here.z) * (goal.z - here.z));
                    double wallTop = BotDefaults.get().build ? this.wallOnLine(bot, goal) : NO_WALL;
                    double climb = (wallTop - here.y) * BotDefaults.get().climbPenalty;
                    if (!route.partial() && wallTop > here.y + 1.5 && route.length() > straight * BotDefaults.get().detourLimit + climb) {
                        bot.longFailed = true;
                        bot.preferClimb = true;
                        bot.climbGoal = goal;
                        this.navLog(bot, "Weitplanung: Umweg " + (int)route.length() + " statt " + (int)straight + "+" + (int)climb + " -> klettern");
                    } else {
                        bot.navRoute = new ArrayList<>(route.points());
                        bot.navRouteIndex = 0;
                        bot.navRouteUntil = tick + 30L * (long)route.length() + 600L;
                        bot.longRoute = true;
                        bot.longFailed = false;
                        bot.noWayVerdict = false;
                        bot.preferClimb = false;
                        bot.path = null;
                        bot.pendingPath = null;
                        this.navLog(
                            bot,
                            "Weitplanung: "
                                + route.points().size()
                                + " Punkte, "
                                + (int)route.length()
                                + " Bloecke"
                                + (route.partial() ? " (Teilstrecke)" : "")
                        );
                    }
                }
            } else if (tick - bot.longRequested < 400L) {
                this.lookAlong(bot, goal);
                this.holdStill(bot);
                return true;
            }
        }

        this.learnCrumbs(bot, tick);
        if (tick % 3L == 0L) {
            this.openDoorAhead(bot);
        }

        Vec3 steerGoal = goal;
        if (bot.navRoute != null) {
            while (bot.navRouteIndex + 1 < bot.navRoute.size()) {
                Vec3 here = bot.navRoute.get(bot.navRouteIndex);
                Vec3 next = bot.navRoute.get(bot.navRouteIndex + 1);
                double meToNext = (next.x - bot.getX()) * (next.x - bot.getX()) + (next.z - bot.getZ()) * (next.z - bot.getZ());
                double hereToNext = (next.x - here.x) * (next.x - here.x) + (next.z - here.z) * (next.z - here.z);
                if (!(meToNext < hereToNext) || !(Math.abs(here.y - bot.getY()) < 2.5) || !ExitSense.walkable(bot.level(), bot.position(), next)) {
                    break;
                }

                bot.navRouteIndex++;
            }

            if (tick <= bot.navRouteUntil && bot.navRouteIndex < bot.navRoute.size()) {
                Vec3 waypoint = bot.navRoute.get(bot.navRouteIndex);
                double wx = waypoint.x - bot.getX();
                double wz = waypoint.z - bot.getZ();
                if (wx * wx + wz * wz < 6.25 && Math.abs(waypoint.y - bot.getY()) < 3.0 && ExitSense.walkable(bot.level(), bot.position(), waypoint)) {
                    bot.navRouteIndex++;
                    bot.path = null;
                    bot.pendingPath = null;
                    bot.pathFails = 0;
                }

                if (bot.navRouteIndex < bot.navRoute.size()) {
                    steerGoal = bot.navRoute.get(bot.navRouteIndex);
                } else {
                    this.routeEnded(bot, tick);
                }
            } else {
                this.routeEnded(bot, tick);
            }
        }

        if (BotDefaults.get().humanMovement && tick < bot.hesitateUntil) {
            this.lookAlong(bot, steerGoal);
            this.holdStill(bot);
            return true;
        }

        double dx = goal.x - bot.getX();
        double dz = goal.z - bot.getZ();
        double horizontal = dx * dx + dz * dz;
        StuckReport.check(
            bot,
            goal,
            tick,
            () -> "Pfad "
                + (bot.path == null ? "keiner" : (bot.path.canReach() ? "ganz " : "Teilstueck ") + bot.path.getNodeCount() + " Knoten")
                + (bot.pendingPath != null ? ", Suche laeuft" : "")
                + " | Weitplanung "
                + (
                    bot.pendingLong != null
                        ? "rechnet"
                        : (
                            bot.longRoute && bot.navRoute != null
                                ? "Route " + bot.navRouteIndex + "/" + bot.navRoute.size()
                                : (bot.longFailed ? "kein Weg" : "-")
                        )
                )
                + (bot.pillarBaseY != Integer.MIN_VALUE ? " | baut hoch" : "")
                + (bot.preferClimb ? " | will klettern" : "")
                + (tick < bot.queueUntil ? " | steht an" : "")
        );
        if (horizontal < bot.walkBest - 0.35) {
            bot.walkBest = horizontal;
            bot.walkProgressTick = tick;
        }

        boolean relaxedWalk = !rejoining && bot.recPath == null && this.relaxed(bot, tick) && bot.post == null;
        if (relaxedWalk && (bot.relaxedCheckPos == null || bot.relaxedCheckPos.distanceToSqr(bot.position()) > 2.25)) {
            bot.relaxedCheckPos = bot.position();
            bot.relaxedCheckTick = tick;
        }

        if (relaxedWalk && tick - bot.relaxedCheckTick > 160L && horizontal > 4.0) {
            bot.relaxedCheckPos = null;
            this.navLog(bot, "Streifziel aufgegeben (kein Fortschritt)");
            bot.walkGoal = null;
            this.endWalk(bot);
            bot.nextPatrolPick = tick + 10L + ThreadLocalRandom.current().nextLong(0L, 30L);
            this.stand(bot);
            return true;
        }

        boolean precise = bot.post != null || bot.patrol != null;
        boolean crowded = horizontal < 20.25
            && tick - bot.walkProgressTick > 60L
            && tick > bot.queueUntil + 40L
            && ExitSense.walkable(bot.level(), bot.position(), goal)
            && this.goalOccupied(bot, goal);
        boolean arrived = horizontal < (precise ? 0.36 : 1.0) || crowded;
        if (tick <= bot.walkDeadline && (!arrived || rejoining)) {
            bot.setSprinting(this.wantsSprint(bot, horizontal) && !bot.isInWater());
            boolean lastMetres = horizontal < 9.0 && bot.path != null && bot.path.isDone() || horizontal < 2.25;
            if (lastMetres && ExitSense.walkable(bot.level(), bot.position(), steerGoal)) {
                this.lookAlong(bot, steerGoal);
                this.steerTowards(bot, steerGoal, tick, horizontal < 1.0 ? 0.4F : 0.8F, 0.0F);
                return true;
            }

            if (lastMetres && bot.path != null && bot.path.isDone()) {
                bot.path = null;
            }

            this.moveTo(bot, steerGoal, tick);
            return true;
        } else {
            this.endRecorded(bot);
            this.lapFinish(bot, tick, arrived);
            this.commitCrumbs(bot, arrived);
            bot.walkGoal = null;
            bot.walkGroup = null;
            bot.marching = false;
            this.endWalk(bot);
            if (arrived && bot.post != null && bot.fightMode && bot.hasEnemies()) {
                bot.hostileIds.addAll(bot.targetIds);
                bot.hostileTeams.addAll(bot.enemyTeams);
                bot.targetIds.clear();
                bot.enemyTeams.clear();
                bot.currentTarget = null;
                bot.fightMode = false;
            }

            if (arrived && bot.patrol != null && bot.patrol.size() > 1) {
                if (bot.patrolForward) {
                    if (++bot.patrolIndex >= bot.patrol.size()) {
                        bot.patrolIndex = bot.patrol.size() - 2;
                        bot.patrolForward = false;
                    }
                } else if (--bot.patrolIndex < 0) {
                    bot.patrolIndex = 1;
                    bot.patrolForward = true;
                }
            }

            bot.setMoveInput(0.0F, 0.0F);
            bot.setSprinting(false);
            return false;
        }
    }

    private Vec3 followRecorded(BotPlayer bot, long tick) {
        RecordedPath path = bot.recPath;
        if (!path.hasWidth()) {
            path.measure(bot.level());
        }

        int n = path.size();
        Vec3 pos = bot.position();
        int from = 0;
        int to = n - 2;
        if (bot.recS >= 0.0) {
            int at = path.segmentAt(bot.recS);
            from = Math.max(0, at - 3);
            to = Math.min(n - 2, at + 14);
        }

        double bestCost = Double.MAX_VALUE;
        double bestS = Math.max(0.0, bot.recS);
        double bestH = Double.MAX_VALUE;
        double bestDy = 0.0;

        for (int i = from; i <= to; i++) {
            double ax = path.x[i];
            double az = path.z[i];
            double sx = path.x[i + 1] - ax;
            double sz = path.z[i + 1] - az;
            double len = sx * sx + sz * sz;
            double t = len < 1.0E-6 ? 0.0 : Mth.clamp(((pos.x - ax) * sx + (pos.z - az) * sz) / len, 0.0, 1.0);
            double px = ax + sx * t;
            double pz = az + sz * t;
            double py = path.y[i] + (path.y[i + 1] - path.y[i]) * t;
            double h = (pos.x - px) * (pos.x - px) + (pos.z - pz) * (pos.z - pz);
            double dy = pos.y - py;
            double cost = h + 4.0 * dy * dy;
            if (cost < bestCost) {
                bestCost = cost;
                bestS = path.s[i] + (path.s[i + 1] - path.s[i]) * t;
                bestH = Math.sqrt(h);
                bestDy = dy;
            }
        }

        if (bot.recS < 0.0) {
            Vec3 start = path.point(0);
            double sx = start.x - pos.x;
            double sz = start.z - pos.z;
            if (sx * sx + sz * sz < 144.0 && Math.abs(start.y - pos.y) < 3.0) {
                bestS = 0.0;
                bestH = Math.sqrt(sx * sx + sz * sz);
                bestDy = pos.y - start.y;
            }

            bot.recS = bestS;
            bot.recBest = bestS;
            bot.recProgressTick = tick;
        }

        double room = Math.max(path.leftAt(bestS), path.rightAt(bestS));
        boolean airborne = !bot.onGround() && !bot.isInWater() && !bot.onClimbable();
        boolean onPath = bestH <= room + 1.2 && Math.abs(bestDy) <= (airborne ? 3.0 : 1.3);
        if (onPath) {
            bot.recS = bestS < bot.recS - 2.5 ? bestS : Math.max(bestS, bot.recS - 1.0);
        }

        if (bot.recS > bot.recBest + 0.5) {
            bot.recBest = bot.recS;
            bot.recProgressTick = tick;
        }

        boolean stalled = tick - bot.recProgressTick > 160L;
        if (!onPath || stalled || bot.recRejoin != null) {
            if (bot.recRejoin == null || tick - bot.recRejoinTick > 60L) {
                this.pickRejoin(bot, path, pos, stalled);
                bot.recRejoinTick = tick;
                bot.recProgressTick = tick;
                this.navLog(bot, "Weg " + path.name + ": Wiedereinstieg bei " + (int)bot.recRejoinS + "/" + (int)path.length());
            }

            double rx = bot.recRejoin.x - pos.x;
            double rz = bot.recRejoin.z - pos.z;
            if (!(rx * rx + rz * rz < 2.25) || !(Math.abs(bot.recRejoin.y - pos.y) < 1.2)) {
                this.stopRecordedSneak(bot);
                return bot.recRejoin;
            }

            bot.recS = bot.recRejoinS;
            bot.recBest = bot.recS;
            bot.recProgressTick = tick;
            bot.recRejoin = null;
        }

        if (bot.recS >= path.length() - 0.7) {
            this.endRecorded(bot);
            return REC_DONE;
        }

        double narrow = path.narrowest(bot.recS, bot.recS + 3.0);
        double look = airborne ? 2.4 : (narrow < 1.6 ? 1.1 : 1.8);
        double ts = Math.min(path.length(), bot.recS + look);
        Vec3 dir = path.direction(ts);
        Vec3 left = RecordedPath.leftOf(dir);
        double maxLeft = Math.max(0.0, Math.min(path.leftAt(ts), path.leftAt(bot.recS)) - 0.35);
        double maxRight = Math.max(0.0, Math.min(path.rightAt(ts), path.rightAt(bot.recS)) - 0.35);
        double lane = bot.recLane >= 0.0 ? bot.recLane * maxLeft : bot.recLane * maxRight;
        bot.recLat = bot.recLat + Mth.clamp(lane - bot.recLat, -0.1, 0.1);
        bot.recLat = Mth.clamp(bot.recLat, -maxRight, maxLeft);
        Vec3 target = path.at(ts).add(left.scale(bot.recLat));
        double gap = this.recGapAhead(bot, path, tick);
        float forward = gap < 0.9 ? 0.0F : (gap < 1.7 ? 0.45F : 1.0F);
        if (forward < 0.9F) {
            bot.recProgressTick = Math.max(bot.recProgressTick, tick - 100L);
        }

        int seg = path.segmentAt(bot.recS);
        boolean jumpNow = false;
        boolean runUp = false;

        for (int j = Math.max(0, seg - 1); j <= Math.min(n - 1, seg + 8); j++) {
            if ((path.flags[j] & 2) != 0) {
                double ahead = path.s[j] - bot.recS;
                if ((path.flags[j] & 4) != 0 && ahead > -0.5 && ahead < 4.0) {
                    runUp = true;
                }

                if (j != bot.recJumpDone && ahead < 0.55 && ahead > -0.8 && bot.onGround()) {
                    jumpNow = true;
                    bot.recJumpDone = j;
                }
            }
        }

        int here = path.nearestIndex(bot.recS);
        boolean sneak = (path.flags[here] & 8) != 0 && !runUp;
        this.lookAlong(bot, path.at(bot.recS + 4.5).add(left.scale(bot.recLat)));
        if (narrow >= 1.6) {
            this.glanceWhileWalking(bot, tick);
        }

        this.steerTowards(bot, target, tick, forward, 0.0F);
        if (jumpNow) {
            bot.wantJump(true);
            bot.jumpCooldown = 6;
        }

        double far = path.length() - bot.recS;
        bot.setSprinting(forward > 0.9F && !sneak && !bot.isInWater() && (runUp || this.wantsSprint(bot, far * far)));
        if (sneak != bot.recSneaking) {
            bot.setShiftKeyDown(sneak);
            bot.recSneaking = sneak;
        }

        return null;
    }

    private void pickRejoin(BotPlayer bot, RecordedPath path, Vec3 pos, boolean stalled) {
        int n = path.size();
        int start = Math.max(0, path.segmentAt(Math.max(0.0, bot.recS)) - 40);
        int end = Math.min(n - 1, start + 600);
        double best = Double.MAX_VALUE;
        int pick = Math.min(n - 1, start);

        for (int i = start; i <= end; i++) {
            if ((path.flags[i] & 1) != 0) {
                double dx = path.x[i] - pos.x;
                double dz = path.z[i] - pos.z;
                double dy = path.y[i] - pos.y;
                double behind = Math.max(0.0, bot.recS - path.s[i]);
                double cost = Math.sqrt(dx * dx + dz * dz) + 4.0 * Math.abs(dy) + behind * 0.3;
                if (stalled && path.s[i] <= bot.recS + 1.0) {
                    cost += 20.0;
                }

                if (cost < best) {
                    best = cost;
                    pick = i;
                }
            }
        }

        bot.recRejoin = path.point(pick);
        bot.recRejoinS = path.s[pick];
    }

    private double recGapAhead(BotPlayer bot, RecordedPath path, long tick) {
        if (this.recFollowersTick != tick) {
            this.recFollowersTick = tick;
            this.recFollowers.clear();

            for (BotPlayer other : this.bots.all()) {
                if (other.recPath != null && other.recS >= 0.0 && other.recRejoin == null && !other.isDeadOrDying()) {
                    this.recFollowers.computeIfAbsent(other.recPath, key -> new ArrayList<>()).add(other);
                }
            }

            for (List<BotPlayer> list : this.recFollowers.values()) {
                list.sort((a, b) -> a.recS != b.recS ? Double.compare(a.recS, b.recS) : Integer.compare(b.getId(), a.getId()));
                int i = 0;

                while (i < list.size()) {
                    list.get(i).recRank = i++;
                }
            }
        }

        List<BotPlayer> list = this.recFollowers.get(path);
        if (list != null && bot.recRank >= 0 && bot.recRank < list.size() && list.get(bot.recRank) == bot) {
            for (int k = bot.recRank + 1; k < list.size(); k++) {
                BotPlayer other = list.get(k);
                double ds = other.recS - bot.recS;
                if (ds > 2.0) {
                    break;
                }

                if (Math.abs(other.recLat - bot.recLat) < 0.75 && Math.abs(other.getY() - bot.getY()) < 1.5) {
                    return Math.max(0.0, ds);
                }
            }

            return Double.MAX_VALUE;
        } else {
            return Double.MAX_VALUE;
        }
    }

    private void stopRecordedSneak(BotPlayer bot) {
        if (bot.recSneaking) {
            bot.setShiftKeyDown(false);
            bot.recSneaking = false;
        }
    }

    private void endRecorded(BotPlayer bot) {
        this.stopRecordedSneak(bot);
        bot.recPath = null;
        bot.recGoal = null;
        bot.recRejoin = null;
        bot.recS = -1.0;
        bot.recJumpDone = -1;
    }

    private void beginWalk(BotPlayer bot, Vec3 goal, long tick) {
        bot.walkTracked = goal;
        bot.navRoute = null;
        bot.navRouteIndex = 0;
        bot.pendingLong = null;
        bot.longFailed = false;
        bot.longRoute = false;
        bot.nextLongPlan = 0L;
        bot.noWayVerdict = false;
        bot.preferClimb = false;
        bot.climbGoal = null;
        bot.path = null;
        bot.pendingPath = null;
        bot.pathGoal = null;
        bot.detour = null;
        bot.forceRepath = false;
        bot.avoidTicks = 0;
        bot.queueUntil = 0L;
        bot.stuckTicks = 0;
        bot.progressPos = null;
        bot.crumbTrail.clear();
        bot.trailTainted = false;
        bot.walkBest = Double.MAX_VALUE;
        bot.walkProgressTick = tick;
        bot.lapStart = -1L;
        bot.relaxedCheckPos = null;
    }

    private void endWalk(BotPlayer bot) {
        bot.walkTracked = null;
        bot.navRoute = null;
        bot.longRoute = false;
        bot.pendingLong = null;
        bot.path = null;
        bot.pendingPath = null;
        bot.preferClimb = false;
        bot.detour = null;
    }

    private void routeEnded(BotPlayer bot, long tick) {
        this.navLog(bot, "Route zu Ende (" + (tick > bot.navRouteUntil ? "Zeit" : "letzter Punkt " + bot.navRouteIndex + "/" + bot.navRoute.size()) + ")");
        bot.navRoute = null;
        bot.longRoute = false;
        bot.nextLongPlan = 0L;
    }

    private boolean goalOccupied(BotPlayer bot, Vec3 goal) {
        if (!bot.level()
            .getEntitiesOfClass(
                BotPlayer.class, new AABB(goal.x - 1.4, goal.y - 1.0, goal.z - 1.4, goal.x + 1.4, goal.y + 2.0, goal.z + 1.4), other -> other != bot
            )
            .isEmpty()) {
            return true;
        }

        Vec3 pos = bot.position();
        Vec3 ahead = new Vec3(goal.x - pos.x, 0.0, goal.z - pos.z);
        if (ahead.lengthSqr() < 0.01) {
            return false;
        }

        Vec3 spot = pos.add(ahead.normalize().scale(1.0));
        return !bot.level()
            .getEntitiesOfClass(
                BotPlayer.class,
                new AABB(spot.x - 0.7, pos.y - 0.5, spot.z - 0.7, spot.x + 0.7, pos.y + 2.0, spot.z + 0.7),
                other -> other != bot && other.walkGoal == null
            )
            .isEmpty();
    }

    private boolean wantsSprint(BotPlayer bot, double horizontal) {
        if (bot.fleeing) {
            return true;
        }

        String pace = BotDefaults.get().movePace == null ? "walk" : BotDefaults.get().movePace;

        return switch (pace) {
            case "sprint", "sprintjump" -> horizontal > 16.0;
            case "auto" -> horizontal > 400.0;
            default -> false;
        };
    }

    private void holdStill(BotPlayer bot) {
        bot.setMoveInput(0.0F, 0.0F);
        bot.wantJump(false);
        bot.setSprinting(false);
    }

    /**
     * Not one block closer to the goal for 10 s (walking in circles, pressed against a wall, a
     * short path that keeps failing): find a way around for real - a long route right away,
     * plus a detour to the side; after 20 s also allow digging/building through.
     * True when it took over this tick.
     */
    private boolean watchProgress(BotPlayer bot, Vec3 pos, Vec3 goal, long tick) {
        double flat = Math.sqrt((goal.x - pos.x) * (goal.x - pos.x) + (goal.z - pos.z) * (goal.z - pos.z)) + Math.abs(goal.y - pos.y) * 0.5;
        if (bot.progressGoal == null || bot.progressGoal.distanceToSqr(goal) > 16.0 || flat < bot.progressBest - 1.5 || flat < 3.0) {
            if (bot.progressGoal == null || bot.progressGoal.distanceToSqr(goal) > 16.0) {
                bot.progressEscalations = 0;
            }

            bot.progressGoal = goal;
            bot.progressBest = flat;
            bot.progressBestTick = tick;
            return false;
        }

        if (tick - bot.progressBestTick < 200L || bot.pendingLong != null || bot.bridgeDir != null || bot.miningPos != null
            || bot.pillarBaseY != Integer.MIN_VALUE || tick < bot.queueUntil) {
            return false;
        }

        bot.progressBestTick = tick;
        bot.progressEscalations++;
        this.navLog(bot, "kein Fortschritt seit 10 s -> neuer Weg (Stufe " + bot.progressEscalations + ")");
        bot.path = null;
        bot.pendingPath = null;
        bot.navRoute = null;
        bot.longRoute = false;
        bot.longFailed = false;
        bot.forceRepath = true;
        bot.stuckTicks = 0;
        int side = (bot.progressEscalations & 1) == 0 ? 1 : -1;
        bot.detour = this.pickDetour(bot, pos, goal, 6.0 + bot.progressEscalations * 3.0, side, 2.0);
        bot.detourUntil = tick + 120L;
        if (bot.progressEscalations >= 2) {
            // Walking around didn't work either: let the dig/build fallbacks (tunnel, bridge, pillar) try.
            bot.noWayVerdict = true;
        }

        this.planLong(bot, pos, goal, tick);
        return true;
    }

    private void planLong(BotPlayer bot, Vec3 pos, Vec3 goal, long tick) {
        long key = Math.floorDiv((int)Math.floor(goal.x), 4) * 73856093L
            ^ Math.floorDiv((int)Math.floor(goal.z), 4) * 19349663L
            ^ Math.floorDiv((int)Math.floor(goal.y), 4) * 83492791L
            ^ Math.floorDiv((int)Math.floor(pos.x), 8) * 2654435761L
            ^ Math.floorDiv((int)Math.floor(pos.z), 8) * 40503L
            ^ Math.floorDiv((int)Math.floor(pos.y), 4) * 1181783497L;
        if (tick - this.sharedLongSweep > 200L) {
            this.sharedLongSweep = tick;
            this.sharedLong.values().removeIf(entry -> tick - entry.tick() > 400L);
        }

        BotBrain.SharedLong shared = this.sharedLong.get(key);
        if (shared == null || tick - shared.tick() > 200L || shared.goal().distanceToSqr(goal) > 16.0 || shared.from().distanceToSqr(pos) > 144.0) {
            shared = new BotBrain.SharedLong(
                pos, goal, tick, LongRoute.plan(bot.level(), pos, goal, this.relaxed(bot, tick) ? 3 : (goal.y < pos.y - 3.0 ? 14 : 5))
            );
            this.sharedLong.put(key, shared);
        }

        bot.pendingLong = shared.future();
        bot.longFrom = pos;
        bot.longGoal = goal;
        bot.longRequested = tick;
        bot.nextLongPlan = tick + 200L;
    }

    private void advancePastBehind(BotPlayer bot) {
        if (bot.path != null) {
            Vec3 pos = bot.position();
            int guard = 0;

            while (!bot.path.isDone() && bot.path.getNextNodeIndex() + 1 < bot.path.getNodeCount() && guard++ < 64) {
                Vec3 node = bot.path.getNextEntityPos(bot);
                Vec3 next = bot.path.getEntityPosAtNode(bot, bot.path.getNextNodeIndex() + 1);
                double along = (pos.x - node.x) * (next.x - node.x) + (pos.z - node.z) * (next.z - node.z);
                if (!(along > 0.0) || !(Math.abs(next.y - pos.y) < 1.5) || !this.lineWalkable(bot, pos, next)) {
                    break;
                }

                bot.path.advance();
            }
        }
    }

    private boolean blockedByWalker(BotPlayer bot, Vec3 pos, Vec3 target) {
        Vec3 ahead = new Vec3(target.x - pos.x, 0.0, target.z - pos.z);
        if (ahead.lengthSqr() < 0.01) {
            return false;
        }

        Vec3 spot = pos.add(ahead.normalize().scale(0.9));
        return !bot.level()
            .getEntitiesOfClass(
                BotPlayer.class,
                new AABB(spot.x - 0.5, pos.y - 0.5, spot.z - 0.5, spot.x + 0.5, pos.y + 2.0, spot.z + 0.5),
                other -> other != bot && other.walkGoal != null && other.getDeltaMovement().horizontalDistanceSqr() < 0.004
            )
            .isEmpty();
    }

    private Vec3 pickDetour(BotPlayer bot, Vec3 pos, Vec3 goal, double distance, int preferSign, double forward) {
        Vec3 toGoal = new Vec3(goal.x - pos.x, 0.0, goal.z - pos.z);
        Vec3 dir = toGoal.lengthSqr() < 0.01 ? new Vec3(1.0, 0.0, 0.0) : toGoal.normalize();
        Vec3 side = new Vec3(-dir.z, 0.0, dir.x);
        ServerLevel world = bot.level();
        int sign = preferSign >= 0 ? 1 : -1;

        for (double reach : new double[]{distance, distance * 0.6, 2.0}) {
            for (int s : new int[]{sign, -sign}) {
                Vec3 raw = pos.add(side.scale(s * reach)).add(dir.scale(forward));
                Vec3 spot = Ground.findDry(world, raw.x, raw.z, pos.y);
                if (spot != null && Math.abs(spot.y - pos.y) < 1.5 && ExitSense.walkable(world, pos, spot)) {
                    return spot;
                }
            }
        }

        Vec3 back = pos.subtract(dir.scale(3.0));
        Vec3 spot = Ground.findDry(world, back.x, back.z, pos.y);
        return spot != null && Math.abs(spot.y - pos.y) < 1.5 && ExitSense.walkable(world, pos, spot) ? spot : null;
    }

    private int freeSide(BotPlayer bot, Vec3 direction) {
        Vec3 pos = bot.position();
        if (bot.path != null && !bot.path.isDone() && bot.path.getNextNodeIndex() + 1 < bot.path.getNodeCount()) {
            Vec3 next = bot.path.getEntityPosAtNode(bot, bot.path.getNextNodeIndex() + 1);
            double cross = direction.x * (next.z - pos.z) - direction.z * (next.x - pos.x);
            if (Math.abs(cross) > 0.3) {
                return cross > 0.0 ? 1 : -1;
            }
        }

        Vec3 left = new Vec3(-direction.z, 0.0, direction.x);
        boolean leftFree = this.lineWalkable(bot, pos, pos.add(left.scale(3.0)));
        boolean rightFree = this.lineWalkable(bot, pos, pos.subtract(left.scale(3.0)));
        if (leftFree != rightFree) {
            return leftFree ? 1 : -1;
        } else {
            return (bot.getId() & 1) == 0 ? 1 : -1;
        }
    }

    private boolean follow(BotPlayer bot, long tick) {
        LivingEntity owner = bot.level().getServer().getPlayerList().getPlayer(bot.followTarget);
        if (owner == null && bot.level().getEntity(bot.followTarget) instanceof LivingEntity living) {
            owner = living;
        }

        if (bot.portalFrom != null && bot.level().dimension() != bot.portalFrom) {
            this.arrivedThroughPortal(bot);
        }

        if (owner != null && owner.level() != bot.level() && owner != bot) {
            return this.chasePortal(bot, tick, owner.level().dimension());
        }

        if (owner != null && owner.level() == bot.level() && owner != bot) {
            BotDefaults defaults = BotDefaults.get();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            BotBrain.Crowd crowd = this.crowdOf(owner, tick);
            Vec3 spot = this.crowdSpot(bot, owner, crowd, defaults, tick, random);
            double distance = bot.distanceTo(owner);
            double toSpotSq = bot.position().distanceToSqr(spot.x, bot.getY(), spot.z);
            double toSpot = Math.sqrt(toSpotSq);
            double slack = Math.max(1.0, defaults.followSlack);
            double trigger = crowd.moving ? slack : slack + 2.0;
            boolean shouldMove = toSpot > trigger || distance > bot.followDistance + defaults.followSpread;
            if (shouldMove && bot.guardMoveAt == 0L) {
                int row = bot.crowdSlot < 0 ? 0 : bot.crowdSlot / Math.max(1, crowd.columns);
                long stagger = defaults.followStagger <= 0 ? 0L : random.nextLong(0L, defaults.followStagger + 1L) + row * 3L;
                bot.guardMoveAt = tick + (toSpot > slack * 3.0 ? 0L : stagger);
            }

            if (!shouldMove && toSpot < 1.6) {
                bot.guardMoveAt = 0L;
            }

            if (bot.guardMoveAt != 0L && tick >= bot.guardMoveAt && toSpot > 1.2) {
                this.endFidget(bot);
                double catchUp = Math.max(2.0, defaults.followCatchUp);
                boolean behind = toSpot > catchUp;
                bot.setSprinting(defaults.followSprint && behind && !bot.isInWater());
                if (behind) {
                    this.moveTo(bot, owner.position(), tick);
                    return true;
                }

                if (bot.stuckTicks < 12) {
                    this.lookAlong(bot, spot);
                    this.steerTowards(bot, spot, tick, toSpot < 2.5 ? 0.5F : 1.0F, 0.0F);
                    if (bot.position().x == bot.lastPos.x && bot.position().z == bot.lastPos.z) {
                        bot.stuckTicks++;
                    } else {
                        bot.stuckTicks = 0;
                        bot.lastPos = bot.position();
                    }

                    return true;
                } else {
                    this.moveTo(bot, spot, tick);
                    return true;
                }
            } else {
                bot.setSprinting(false);
                bot.setMoveInput(0.0F, 0.0F);
                bot.wantJump(false);
                if (bot.lookAtId != null || bot.lookAtTeam != null || bot.lookAtPos != null) {
                    this.lookAround(bot);
                    return true;
                } else if (tick < bot.crowdLookUntil) {
                    this.face(bot, owner.getEyePosition(), 8.0F);
                    bot.setYBodyRot(bot.getYRot());
                    return true;
                } else if (defaults.followLook > 0 && tick % 20L == bot.getId() % 20L && random.nextInt(100) < defaults.followLook) {
                    bot.crowdLookUntil = tick + 15L + random.nextLong(0L, 35L);
                    return true;
                } else {
                    this.glance(bot, tick);
                    this.fidget(bot, tick);
                    return true;
                }
            }
        } else {
            bot.setMoveInput(0.0F, 0.0F);
            return false;
        }
    }

    /**
     * Owner went through a portal and is now in another dimension. Instead of walking to
     * their raw coordinates in the wrong world, walk into the nearest nether portal - vanilla
     * then teleports the bot like a player, and follow() picks the owner up on the other side.
     */
    private boolean chasePortal(BotPlayer bot, long tick, ResourceKey<Level> toward) {
        int portal = this.aimAtPortal(bot, tick, toward);
        if (portal == PORTAL_NONE) {
            bot.setMoveInput(0.0F, 0.0F);
            return false;
        }

        return portal == PORTAL_HANDLED || this.walk(bot, tick);
    }

    private static final int PORTAL_NONE = 0;
    private static final int PORTAL_WALK = 1;
    private static final int PORTAL_HANDLED = 2;

    /**
     * Steers a bot into the nearest nether portal. PORTAL_WALK: walkGoal now points at the
     * portal, path there normally. PORTAL_HANDLED: stepping into it or already standing inside
     * (vanilla's portal timer does the teleport, like for a player). PORTAL_NONE: no portal known.
     */
    private int aimAtPortal(BotPlayer bot, long tick, ResourceKey<Level> toward) {
        ServerLevel level = bot.level();
        // End portal when heading for the End from the Overworld, or leaving the End; else nether portal.
        boolean end = level.dimension() == Level.END || toward == Level.END && level.dimension() == Level.OVERWORLD;
        net.minecraft.world.level.block.Block portalBlock = end ? Blocks.END_PORTAL : Blocks.NETHER_PORTAL;
        BlockPos feet = BlockPos.containing(bot.getX(), bot.getY() + 0.2, bot.getZ());
        if (level.getBlockState(feet).is(portalBlock)) {
            bot.portalFrom = level.dimension();
            bot.setMoveInput(0.0F, 0.0F);
            bot.setSprinting(false);
            bot.path = null;
            return PORTAL_HANDLED;
        }

        boolean stillValid = bot.portalFrom == level.dimension()
            && bot.portalTarget != null
            && tick < bot.nextPortalScan
            && level.getBlockState(bot.portalTarget).is(portalBlock);
        if (bot.portalTarget == null && tick < bot.nextPortalScan) {
            return PORTAL_NONE; // nothing found a moment ago - don't search again every tick
        }

        if (!stillValid) {
            bot.nextPortalScan = tick + 40L + Math.floorMod(bot.getId(), 20);
            bot.portalTarget = end ? findEndPortal(level, bot.blockPosition()) : findPortal(level, bot.blockPosition());
            if (bot.portalTarget != null) {
                bot.portalTarget = spreadOverPortal(level, bot.portalTarget, portalBlock, bot.getId());
            }
            if (bot.portalTarget == null) {
                bot.portalFrom = null;
                bot.nextPortalScan = tick + 100L + Math.floorMod(bot.getId(), 40);
                return PORTAL_NONE;
            }
        }

        bot.portalFrom = level.dimension();
        double jitter = (Math.floorMod(bot.getId() * 13, 7) - 3) * 0.08;
        Vec3 target = new Vec3(bot.portalTarget.getX() + 0.5 + jitter, bot.portalTarget.getY(), bot.portalTarget.getZ() + 0.5 - jitter);
        double dx = target.x - bot.getX();
        double dz = target.z - bot.getZ();
        if (dx * dx + dz * dz < 6.25 && Math.abs(target.y - bot.getY()) < 1.5) {
            // Last step: walk straight into the portal plane - normal walking stops "close enough".
            bot.path = null;
            bot.setSprinting(false);
            this.lookAlong(bot, target);
            this.steerTowards(bot, target, tick, 0.85F, 0.0F);
            return PORTAL_HANDLED;
        }

        if (bot.walkGoal == null || bot.walkGoal.distanceToSqr(target) > 0.01) {
            bot.walkGoal = target;
            bot.path = null;
        }

        bot.walkDeadline = Math.max(bot.walkDeadline, tick + 1200L);
        bot.walkGroup = null;
        bot.marching = false;
        return PORTAL_WALK;
    }

    private void arrivedThroughPortal(BotPlayer bot) {
        bot.portalFrom = null;
        bot.portalTarget = null;
        bot.nextPortalScan = 0L;
        bot.path = null;
        bot.walkGoal = bot.walkGoalDimension == bot.level().dimension() && bot.crossGoal != null ? bot.crossGoal : null;
        if (bot.walkGoalDimension == bot.level().dimension()) {
            bot.walkGoalDimension = null;
            bot.crossGoal = null;
        }
    }

    /**
     * Nearest nether portal, via vanilla's point-of-interest index (the same lookup the game
     * uses to link portals) - no block scanning. Returns the lowest portal block of that column,
     * which is where a bot's feet go.
     */
    /**
     * End portals aren't indexed like nether portals. In the End the only one is the exit
     * fountain in the middle; elsewhere scan the loaded chunks around the bot, but only the
     * chunk sections whose block palette can contain an end portal at all (almost none) -
     * reading every block of a 97x97x49 box per bot was the big lag with many bots.
     */
    private static BlockPos findEndPortal(ServerLevel level, BlockPos from) {
        if (level.dimension() == Level.END) {
            return scanForEndPortal(level, new BlockPos(0, from.getY(), 0), 1, level.getMinY(), level.getMaxY() - 1, from);
        }

        return scanForEndPortal(level, from, 3, Math.max(level.getMinY(), from.getY() - 32), Math.min(level.getMaxY() - 1, from.getY() + 32), from);
    }

    private static BlockPos scanForEndPortal(ServerLevel level, BlockPos centre, int chunkRadius, int minY, int maxY, BlockPos from) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int cx = centre.getX() >> 4;
        int cz = centre.getZ() >> 4;
        for (int chunkX = cx - chunkRadius; chunkX <= cx + chunkRadius; chunkX++) {
            for (int chunkZ = cz - chunkRadius; chunkZ <= cz + chunkRadius; chunkZ++) {
                net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }

                net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
                for (int index = 0; index < sections.length; index++) {
                    net.minecraft.world.level.chunk.LevelChunkSection section = sections[index];
                    int sectionY = chunk.getSectionYFromSectionIndex(index) << 4;
                    if (section == null || section.hasOnlyAir() || sectionY + 15 < minY || sectionY > maxY
                        || !section.maybeHas(state -> state.is(Blocks.END_PORTAL))) {
                        continue;
                    }

                    for (int y = Math.max(minY, sectionY); y <= Math.min(maxY, sectionY + 15); y++) {
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                if (section.getBlockState(x, y & 15, z).is(Blocks.END_PORTAL)) {
                                    cursor.set((chunkX << 4) + x, y, (chunkZ << 4) + z);
                                    double dist = cursor.distSqr(from);
                                    if (dist < bestDist) {
                                        bestDist = dist;
                                        best = cursor.immutable();
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return best;
    }

    /** Different bots pick different blocks of the same portal (bottom row), so they don't queue for one. */
    private static BlockPos spreadOverPortal(ServerLevel level, BlockPos found, net.minecraft.world.level.block.Block block, int id) {
        List<BlockPos> row = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                BlockPos at = found.offset(dx, 0, dz);
                if (level.getBlockState(at).is(block) && !level.getBlockState(at.below()).is(block)) {
                    row.add(at);
                }
            }
        }

        return row.isEmpty() ? found : row.get(Math.floorMod(id, row.size()));
    }

    private static BlockPos findPortal(ServerLevel level, BlockPos from) {
        PoiManager poi = level.getPoiManager();
        int radius = 128;
        poi.ensureLoadedAndValid(level, from, radius);
        BlockPos found = poi.findClosest(type -> type.is(PoiTypes.NETHER_PORTAL), from, radius, PoiManager.Occupancy.ANY).orElse(null);
        if (found == null) {
            return null;
        }

        BlockPos.MutableBlockPos cursor = found.mutable();
        while (cursor.getY() > level.getMinY() && level.getBlockState(cursor.below()).is(Blocks.NETHER_PORTAL)) {
            cursor.move(0, -1, 0);
        }

        return cursor.immutable();
    }

    private BotBrain.Crowd crowdOf(LivingEntity owner, long tick) {
        BotBrain.Crowd crowd = this.crowds.computeIfAbsent(owner.getUUID(), id -> new BotBrain.Crowd());
        if (crowd.touched == tick) {
            return crowd;
        }

        crowd.touched = tick;
        if (tick - crowd.refreshed >= 40L) {
            crowd.refreshed = tick;
            crowd.members.clear();

            for (BotPlayer other : this.bots.all()) {
                if (owner.getUUID().equals(other.followTarget) && !other.isDeadOrDying() && other.level() == owner.level()) {
                    crowd.members.add(other);
                }
            }

            crowd.members.sort(Comparator.comparingInt(Entity::getId));
            int i = 0;

            while (i < crowd.members.size()) {
                crowd.members.get(i).crowdSlot = i++;
            }
        }

        Vec3 now = owner.position();
        if (crowd.lastPos != null) {
            double dx = now.x - crowd.lastPos.x;
            double dz = now.z - crowd.lastPos.z;
            double moved = dx * dx + dz * dz;
            crowd.moving = moved > 0.0016;
            if (crowd.moving) {
                double length = Math.sqrt(moved);
                crowd.headingX = crowd.headingX + (dx / length - crowd.headingX) * 0.12;
                crowd.headingZ = crowd.headingZ + (dz / length - crowd.headingZ) * 0.12;
                double norm = Math.sqrt(crowd.headingX * crowd.headingX + crowd.headingZ * crowd.headingZ);
                if (norm > 1.0E-4) {
                    crowd.headingX /= norm;
                    crowd.headingZ /= norm;
                }
            }
        }

        crowd.lastPos = now;
        return crowd;
    }

    private Vec3 crowdSpot(BotPlayer bot, LivingEntity owner, BotBrain.Crowd crowd, BotDefaults defaults, long tick, ThreadLocalRandom random) {
        String mode = defaults.followMode == null ? "tour" : defaults.followMode.toLowerCase(Locale.ROOT);
        double spacing = Math.max(1.2, defaults.followSpacing);
        int count = Math.max(1, crowd.members.size());
        int index = bot.crowdSlot >= 0 && bot.crowdSlot < count ? bot.crowdSlot : Math.floorMod(bot.getId(), count);
        if (tick >= bot.crowdJitterUntil) {
            double amount = Math.max(0.0, defaults.followJitter) * spacing;
            bot.crowdJitterX = (random.nextDouble() * 2.0 - 1.0) * amount;
            bot.crowdJitterZ = (random.nextDouble() * 2.0 - 1.0) * amount;
            bot.crowdJitterUntil = tick + 600L + random.nextLong(0L, 800L);
        }

        double localZ;
        double localX;
        if (mode.startsWith("ring") || mode.startsWith("kreis")) {
            double angle = (Math.PI * 2) * index / count;
            double ring = Math.max(1.5, bot.followDistance);
            localX = Math.cos(angle) * ring;
            localZ = Math.sin(angle) * ring;
            crowd.columns = count;
        } else if (!mode.startsWith("line") && !mode.startsWith("reihe")) {
            int columns = Math.max(1, Math.min(count, (int)Math.round(Math.max(1.0, defaults.followSpread) / spacing)));
            crowd.columns = columns;
            int row = index / columns;
            int column = index % columns;
            int inRow = Math.min(columns, count - row * columns);
            localX = (column - (inRow - 1) / 2.0) * spacing;
            localX *= 1.0 + row * 0.08;
            localZ = -(bot.followDistance + row * spacing);
        } else {
            localX = (index - (count - 1) / 2.0) * spacing;
            localZ = -bot.followDistance;
            crowd.columns = count;
        }

        localX += bot.crowdJitterX;
        localZ += bot.crowdJitterZ;
        double fx = crowd.headingX;
        double fz = crowd.headingZ;
        double worldX = owner.getX() + localX * fz + localZ * fx;
        double worldZ = owner.getZ() - localX * fx + localZ * fz;
        double ox = worldX - owner.getX();
        double oz = worldZ - owner.getZ();
        double near = Math.sqrt(ox * ox + oz * oz);
        if (near < 2.5) {
            if (near < 0.1) {
                ox = -fx;
                oz = -fz;
                near = 1.0;
            }

            worldX = owner.getX() + ox / near * 2.5;
            worldZ = owner.getZ() + oz / near * 2.5;
        }

        return new Vec3(worldX, owner.getY(), worldZ);
    }

    private boolean farm(BotPlayer bot, long tick) {
        return bot.farmEntity != null ? this.hunt(bot, tick) : this.mine(bot, tick);
    }

    private boolean hunt(BotPlayer bot, long tick) {
        LivingEntity current = bot.currentTarget;
        if (current != null && current.isAlive() && current.getType() == bot.farmEntity) {
            bot.fightMode = true;
            return false;
        }

        if (tick < bot.nextFarmScanTick) {
            this.stand(bot);
            return true;
        }

        bot.nextFarmScanTick = tick + 20L;
        double radius = bot.farmRadius;
        AABB box = bot.getBoundingBox().inflate(radius, radius / 2.0, radius);
        LivingEntity prey = null;
        double best = Double.MAX_VALUE;

        for (LivingEntity living : bot.level().getEntitiesOfClass(LivingEntity.class, box, e -> e.getType() == bot.farmEntity && e.isAlive())) {
            double distance = bot.distanceToSqr(living);
            if (distance < best) {
                best = distance;
                prey = living;
            }
        }

        if (prey == null) {
            this.stand(bot);
            return true;
        } else {
            bot.targetIds.add(prey.getUUID());
            bot.currentTarget = prey;
            bot.fightMode = true;
            bot.nextTargetTick = 0L;
            return false;
        }
    }

    private boolean mine(BotPlayer bot, long tick) {
        BlockPos target = this.currentBlock(bot, tick);
        if (target == null) {
            this.stand(bot);
            return true;
        }

        Vec3 centre = Vec3.atCenterOf(target);
        double reach = bot.getEyePosition().distanceTo(centre);
        if (!(reach > 4.5)) {
            this.stand(bot);
            this.face(bot, centre, 25.0F);
            if (tick < bot.nextFarmTick) {
                return true;
            }

            bot.swing(InteractionHand.MAIN_HAND, true);
            ServerLevel world = bot.level();
            if (!bot.gameMode.destroyBlock(target)) {
                world.destroyBlock(target, true, bot);
            }

            bot.farmBlockPos = null;
            bot.nextFarmTick = tick + 12L;
            return true;
        } else {
            bot.setSprinting(reach > 12.0 && !bot.isInWater());
            this.moveTo(bot, centre, tick);
            return true;
        }
    }

    private BlockPos currentBlock(BotPlayer bot, long tick) {
        ServerLevel world = bot.level();
        if (bot.farmBlockPos != null) {
            if (world.getBlockState(bot.farmBlockPos).is(bot.farmBlock)) {
                return bot.farmBlockPos;
            }

            bot.farmBlockPos = null;
        }

        if (tick < bot.nextFarmScanTick) {
            return null;
        }

        bot.farmBlockPos = this.searchBlock(bot);
        bot.nextFarmScanTick = tick + (bot.farmBlockPos == null ? 100L : 20L) + bot.getId() % 17;
        return bot.farmBlockPos;
    }

    private BlockPos searchBlock(BotPlayer bot) {
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        int radius = (int)Math.min(24.0, bot.farmRadius);
        int height = Math.min(8, Math.max(3, radius / 3));
        MutableBlockPos cursor = new MutableBlockPos();

        for (int ring = 0; ring <= radius; ring++) {
            for (int dy = 0; dy <= height; dy++) {
                for (int sign = 0; sign < 2; sign++) {
                    int y = origin.getY() + (sign == 0 ? dy : -dy);

                    for (int dx = -ring; dx <= ring; dx++) {
                        for (int dz = -ring; dz <= ring; dz++) {
                            if (Math.abs(dx) == ring || Math.abs(dz) == ring) {
                                cursor.set(origin.getX() + dx, y, origin.getZ() + dz);
                                BlockState state = world.getBlockState(cursor);
                                if (state.is(bot.farmBlock)) {
                                    return cursor.immutable();
                                }
                            }
                        }
                    }

                    if (dy == 0) {
                        break;
                    }
                }
            }
        }

        return null;
    }

    private void stand(BotPlayer bot) {
        bot.setMoveInput(0.0F, 0.0F);
        bot.wantJump(false);
        bot.setSprinting(false);
        bot.path = null;
        bot.avoidTicks = 0;
    }

    private boolean mayGoStraight(BotPlayer bot, Vec3 goal) {
        if (bot.pathfinding && !bot.longFailed) {
            Vec3 pos = bot.position();
            double flat = (goal.x - pos.x) * (goal.x - pos.x) + (goal.z - pos.z) * (goal.z - pos.z);
            return bot.longRoute && bot.navRoute != null && goal.y < pos.y - 1.0 && flat < 16.0 && ExitSense.walkable(bot.level(), pos, goal, 14)
                ? true
                : flat < 64.0 && Math.abs(goal.y - pos.y) < 3.0 && ExitSense.walkable(bot.level(), pos, goal);
        } else {
            return true;
        }
    }

    /**
     * A point 6 blocks toward a far goal, if that stretch is plainly walkable - lets a bot keep
     * moving while a path is still being computed instead of standing still (armies stuttered).
     */
    private Vec3 clearStepToward(BotPlayer bot, Vec3 goal) {
        Vec3 pos = bot.position();
        double dx = goal.x - pos.x;
        double dz = goal.z - pos.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= 6.0 || Math.abs(goal.y - pos.y) > 12.0) {
            return null;
        }

        Vec3 probe = new Vec3(pos.x + dx / distance * 6.0, pos.y, pos.z + dz / distance * 6.0);
        return this.lineWalkable(bot, pos, probe) && ExitSense.walkable(bot.level(), pos, probe) ? probe : null;
    }

    private void straightOrWait(BotPlayer bot, Vec3 goal, long tick, float speed) {
        if (this.mayGoStraight(bot, goal)) {
            this.lookAlong(bot, goal);
            this.steerTowards(bot, goal, tick, speed, 0.0F);
        } else {
            Vec3 step = this.clearStepToward(bot, goal);
            this.lookAlong(bot, goal);
            if (step != null) {
                this.steerTowards(bot, step, tick, speed, 0.0F);
            } else {
                this.stand(bot);
            }
        }
    }

    private void moveTo(BotPlayer bot, Vec3 goal, long tick) {
        if (!bot.pathfinding) {
            this.lookAlong(bot, goal);
            this.steerTowards(bot, goal, tick, 1.0F, 0.0F);
        } else {
            Vec3 pos = bot.position();
            if (tick - bot.lastMoveToTick > 2L) {
                bot.progressPos = null;
                bot.lastPos = pos;
                bot.stuckTicks = 0;
            }

            bot.lastMoveToTick = tick;
            double movedX = pos.x - bot.lastPos.x;
            double movedZ = pos.z - bot.lastPos.z;
            if (movedX * movedX + movedZ * movedZ < 0.0025) {
                bot.stuckTicks++;
            }

            if (bot.progressPos == null || tick - bot.progressTick >= 20L) {
                if (bot.progressPos != null) {
                    double netX = pos.x - bot.progressPos.x;
                    double netZ = pos.z - bot.progressPos.z;
                    bot.stuckTicks = netX * netX + netZ * netZ > 0.45 ? 0 : Math.max(bot.stuckTicks, 15);
                }

                bot.progressPos = pos;
                bot.progressTick = tick;
            }

            bot.lastPos = pos;
            if (bot.pillarBaseY != Integer.MIN_VALUE) {
                this.pillar(bot, goal, tick);
            } else if (tick < bot.crossUntil) {
                this.lookAlong(bot, goal);
                this.steerTowards(bot, goal, tick, 1.0F, 0.0F);
            } else {
                if (bot.pendingPath != null && bot.pendingPath.isDone() && bot.pendingGoal != null && bot.pendingGoal.distanceToSqr(goal) > 4.0) {
                    bot.pendingPath = null;
                    bot.forceRepath = true;
                }

                if (bot.pendingPath != null && bot.pendingPath.isDone()) {
                    Path result = bot.pendingPath.getNow(null);
                    bot.pendingPath = null;
                    bot.path = result;
                    bot.pathTick = tick;
                    bot.noWayVerdict = result == null || !result.canReach();
                    boolean partial = result == null || !result.canReach();
                    if (partial && bot.longRoute && bot.navRoute != null) {
                        bot.noWayVerdict = false;
                    }

                    Vec3 realGoal = bot.walkGoal != null ? bot.walkGoal : goal;
                    double realFlat = (realGoal.x - pos.x) * (realGoal.x - pos.x) + (realGoal.z - pos.z) * (realGoal.z - pos.z);
                    if (partial && !bot.longRoute && bot.pendingLong == null && tick >= bot.nextLongPlan && realFlat > 2.25) {
                        // Keep following the partial path while the long route is computed -
                        // standing still here made far-away charges stutter.
                        this.planLong(bot, pos, realGoal, tick);
                    }

                    bot.pathGoal = goal;
                    if (result != null) {
                        this.skipPassedNodes(bot);
                        this.advancePastBehind(bot);
                        this.sharedPaths.put(shareKey(pos, goal), new BotBrain.SharedPath(result.copy(), pos, goal, tick));
                    }

                    bot.preferClimb = false;
                    boolean decided = result != null && result.canReach() || bot.longFailed;
                    if (BotDefaults.get().build && decided && !bot.longRoute) {
                        double wallTop = this.wallOnLine(bot, goal);
                        if (wallTop > pos.y + 1.5) {
                            double straight = Math.sqrt((goal.x - pos.x) * (goal.x - pos.x) + (goal.z - pos.z) * (goal.z - pos.z));
                            double climb = (wallTop - pos.y) * BotDefaults.get().climbPenalty;
                            boolean full = result != null && result.canReach();
                            double detour = full ? result.getNodeCount() : Double.MAX_VALUE;
                            if (detour > straight * BotDefaults.get().detourLimit + climb && this.ceilingAbove(bot, wallTop + 1.0) > wallTop + 2.0) {
                                bot.preferClimb = true;
                                bot.climbGoal = bot.walkGoal != null ? bot.walkGoal : goal;
                                bot.path = null;
                                this.navLog(bot, "Umweg " + (full ? (int)detour : -1) + " statt " + (int)straight + "+" + (int)climb + " -> klettern");
                            }
                        }
                    }
                }

                if (DEBUG_NAV && (tick + bot.getId()) % 100L == 0L) {
                    Vec3 next = bot.path != null && !bot.path.isDone() ? bot.path.getNextEntityPos(bot) : null;
                    Node end = bot.path == null ? null : bot.path.getEndNode();
                    this.navLog(
                        bot,
                        "unterwegs: Pfad "
                            + (
                                bot.path == null
                                    ? "keiner"
                                    : (bot.path.canReach() ? "ganz " : "teil ") + bot.path.getNextNodeIndex() + "/" + bot.path.getNodeCount()
                            )
                            + " naechster "
                            + (next == null ? "-" : (int)next.x + "/" + (int)next.z)
                            + " Ende "
                            + (end == null ? "-" : end.x + "/" + end.y + "/" + end.z)
                            + " Lenkziel "
                            + (int)goal.x
                            + "/"
                            + (int)goal.z
                            + " walkGoal "
                            + (bot.walkGoal == null ? "-" : (int)bot.walkGoal.x + "/" + (int)bot.walkGoal.z)
                            + " Route "
                            + (bot.navRoute == null ? "-" : bot.navRouteIndex + "/" + bot.navRoute.size())
                            + (bot.detour != null ? " Umweg " + (int)bot.detour.x + "/" + (int)bot.detour.z : "")
                    );
                }

                if (bot.path != null
                    && !bot.path.canReach()
                    && bot.navRoute == null
                    && bot.pendingLong == null
                    && tick >= bot.nextLongPlan
                    && !bot.preferClimb
                    && bot.walkGoal != null
                    && (bot.walkGoal.x - pos.x) * (bot.walkGoal.x - pos.x) + (bot.walkGoal.z - pos.z) * (bot.walkGoal.z - pos.z) > 2.25) {
                    bot.longRoute = false;
                    this.planLong(bot, pos, bot.walkGoal, tick);
                }

                {
                    if (bot.preferClimb
                        && (
                            bot.climbGoal == null
                                || bot.climbGoal.distanceToSqr(bot.walkGoal != null ? bot.walkGoal : goal) > 4.0
                                || this.wallOnLine(bot, goal) <= pos.y + 1.5
                        )) {
                        bot.preferClimb = false;
                        bot.forceRepath = true;
                    }

                    if (bot.preferClimb && bot.pillarBaseY == Integer.MIN_VALUE && tick >= bot.pillarDoneUntil) {
                        double wallTop = this.wallHeightAhead(bot, goal);
                        if (!(wallTop > pos.y + 0.5) || !bot.onGround()) {
                            this.lookAlong(bot, goal);
                            this.steerTowards(bot, goal, tick, 1.0F, 0.0F);
                            return;
                        }

                        bot.stuckTicks = 15;
                    }

                    if (bot.portalFrom != null) {
                        bot.queueUntil = 0L;
                    }

                    if (tick < bot.queueUntil) {
                        Vec3 towardsQueue = bot.path != null && !bot.path.isDone() ? bot.path.getNextEntityPos(bot) : goal;
                        if ((tick + bot.getId()) % 5L != 0L || this.blockedByWalker(bot, pos, towardsQueue)) {
                            this.lookAlong(bot, towardsQueue);
                            this.holdStill(bot);
                            bot.stuckTicks = 0;
                            return;
                        }

                        bot.queueUntil = 0L;
                    }

                    if (bot.stuckTicks >= 6 && bot.portalFrom == null) {
                        Vec3 towardsQueue = bot.path != null && !bot.path.isDone() ? bot.path.getNextEntityPos(bot) : goal;
                        if (this.blockedByWalker(bot, pos, towardsQueue)) {
                            bot.queueUntil = tick + 20L + ThreadLocalRandom.current().nextLong(0L, 40L);
                            bot.stuckTicks = 0;
                            this.lookAlong(bot, towardsQueue);
                            this.holdStill(bot);
                            return;
                        }
                    }

                    this.gradeTrial(bot, tick);
                    if (this.watchProgress(bot, pos, goal, tick)) {
                        return;
                    }

                    if (BotDefaults.get().build
                        && bot.stuckTicks >= 8
                        && bot.onGround()
                        && goal.y <= pos.y + 1.5
                        && (bot.noWayVerdict || bot.longFailed || bot.stuckTicks >= 20 || bot.path == null || bot.path.isDone())
                        && this.bridgeGapToward(bot, goal)) {
                        // Stuck at an edge (cliff gap, lava) with no way around: bridge across like a
                        // player instead of pillaring into the sky or walking into the lava.
                        this.startBridge(bot, goal, tick);
                        bot.stuckTicks = 0;
                        return;
                    }

                    boolean stuck = bot.stuckTicks >= 15;
                    if (stuck && bot.pendingLong != null) {
                        bot.stuckTicks = 0;
                        this.holdStill(bot);
                    } else {
                        if (stuck && bot.isInWater() && tick >= bot.nextShoreSearch) {
                            // Swimming against a bank too high to climb out (2 blocks): repath/sidestep
                            // led straight back to it forever. Swim to a bank that can be climbed.
                            bot.nextShoreSearch = tick + 60L;
                            Vec3 shore = this.findShore(bot, goal);
                            if (shore != null) {
                                this.navLog(bot, "im Wasser fest -> Ausstieg bei " + (int)shore.x + "/" + (int)shore.y + "/" + (int)shore.z);
                                bot.detour = shore;
                                bot.detourUntil = tick + 160L;
                                bot.path = null;
                                bot.stuckTicks = 0;
                                return;
                            }
                        }

                        if (stuck) {
                            if (bot.skillTrial == null) {
                                String situation = this.situationOf(bot, goal);
                                List<String> options = new ArrayList<>(List.of("repath", "sidestep", "wait"));
                                if (BotDefaults.get().build) {
                                    options.add("build");
                                }

                                if (BotDefaults.get().dig) {
                                    options.add("dig");
                                }

                                options.add("jump");
                                String action;
                                if (!BotDefaults.get().build || !bot.noWayVerdict && !bot.preferClimb && !bot.longFailed) {
                                    if (!BotDefaults.get().dig || !bot.noWayVerdict && !bot.longFailed) {
                                        action = switch (bot.stuckStrikes % 3) {
                                            case 0 -> "repath";
                                            case 1 -> "sidestep";
                                            default -> "jump";
                                        };
                                    } else {
                                        action = "dig";
                                    }
                                } else {
                                    action = "build";
                                }

                                bot.skillTrial = situation + "|" + action;
                                bot.skillStart = tick;
                                bot.skillFrom = pos;
                                this.navLog(bot, "Lage " + situation + " -> " + action);
                                switch (action) {
                                    case "wait":
                                        bot.giveUpUntil = tick + 20L;
                                        bot.stuckTicks = 0;
                                        this.stand(bot);
                                        return;
                                    case "jump":
                                        bot.wantJump(true);
                                        bot.stuckTicks = 0;
                                        this.steerTowards(bot, goal, tick, 1.0F, 0.0F);
                                        return;
                                    case "sidestep":
                                        Vec3 toGoal = new Vec3(goal.x - pos.x, 0.0, goal.z - pos.z);
                                        if (toGoal.lengthSqr() < 0.01) {
                                            new Vec3(1.0, 0.0, 0.0);
                                        } else {
                                            new Vec3(-toGoal.z, 0.0, toGoal.x).normalize();
                                        }

                                        double sign = (bot.getId() + tick / 100L) % 2L == 0L ? 1.0 : -1.0;
                                        bot.detour = this.pickDetour(bot, pos, goal, 5.0, (int)sign, 0.0);
                                        bot.detourUntil = tick + 100L;
                                        if (bot.detour == null) {
                                            bot.forceRepath = true;
                                            bot.giveUpUntil = tick + 10L;
                                        } else {
                                            bot.path = null;
                                        }

                                        bot.stuckTicks = 0;
                                        return;
                                    case "repath":
                                        bot.forceRepath = true;
                                        bot.stuckTicks = 0;
                                        this.askMemory(bot, goal, tick);
                                        return;
                                }
                            }

                            if (bot.isInWater()) {
                                bot.wantJump(true);
                            }

                            boolean noWay = bot.pendingPath == null
                                && bot.pendingLong == null
                                && (bot.noWayVerdict || bot.longFailed || bot.path != null && !bot.path.canReach());
                            double flatToGoal = (goal.x - pos.x) * (goal.x - pos.x) + (goal.z - pos.z) * (goal.z - pos.z);
                            boolean goalAbove = goal.y > pos.y + 1.5 && flatToGoal < 9.0;
                            double wallTop = this.wallHeightAhead(bot, goal);
                            boolean wall = wallTop > pos.y + 0.5;
                            if (BotDefaults.get().build
                                && tick >= bot.pillarDoneUntil
                                && flatToGoal > 4.0
                                && (wall && (noWay || bot.preferClimb) || goalAbove || this.enclosed(bot) && noWay && goal.y > pos.y + 1.5)) {
                                double wanted = wall ? wallTop + 1.0 : (goal.y > pos.y + 1.5 ? goal.y : pos.y + 3.0);
                                wanted = Math.min(wanted, pos.y + 16.0);
                                double ceiling = this.ceilingAbove(bot, wanted);
                                if (wanted > ceiling - 1.0 && ceiling - pos.y >= 4.0 && !BotDefaults.get().dig) {
                                    wanted = ceiling - 2.0;
                                } else if (wanted > ceiling - 1.0 && !BotDefaults.get().dig) {
                                    bot.pillarDoneUntil = tick + 100L;
                                    this.navLog(bot, "Decke ueber uns - seitlich ausweichen");
                                    bot.stuckTicks = 0;
                                    bot.path = null;
                                    Vec3 toGoal = new Vec3(goal.x - pos.x, 0.0, goal.z - pos.z);
                                    if (toGoal.lengthSqr() < 0.01) {
                                        new Vec3(1.0, 0.0, 0.0);
                                    } else {
                                        new Vec3(-toGoal.z, 0.0, toGoal.x).normalize();
                                    }

                                    double sign = (bot.getId() + tick / 200L) % 2L == 0L ? 1.0 : -1.0;
                                    bot.detour = this.pickDetour(bot, pos, goal, 6.0, (int)sign, 0.0);
                                    bot.detourUntil = tick + 100L;
                                    bot.giveUpUntil = tick + 10L;
                                    return;
                                }

                                List<BotPlayer> climbers = bot.level()
                                    .getEntitiesOfClass(
                                        BotPlayer.class,
                                        bot.getBoundingBox().inflate(3.0, 6.0, 3.0),
                                        other -> other != bot && other.pillarBaseY != Integer.MIN_VALUE
                                    );
                                if (!climbers.isEmpty()) {
                                    Vec3 toGoal = new Vec3(goal.x - pos.x, 0.0, goal.z - pos.z);
                                    if (toGoal.lengthSqr() < 0.01) {
                                        new Vec3(1.0, 0.0, 0.0);
                                    } else {
                                        new Vec3(-toGoal.z, 0.0, toGoal.x).normalize();
                                    }

                                    double sign = (bot.getId() & 1) == 0 ? 1.0 : -1.0;
                                    bot.detour = this.pickDetour(bot, pos, goal, 2.5 + bot.getId() % 3, (int)sign, 0.0);
                                    bot.detourUntil = tick + 80L;
                                    bot.stuckTicks = 0;
                                    bot.path = null;
                                    if (tick % 40L == 0L) {
                                        this.navLog(bot, "Mauer besetzt - ein Stueck weiter klettern");
                                    }

                                    return;
                                }

                                bot.stuckTicks = 0;
                                bot.pillarBaseY = Mth.floor(pos.y) - 1;
                                bot.pillarStartTick = tick;
                                bot.pillarStartY = pos.y;
                                bot.pillarTargetY = wanted;
                                bot.path = null;
                                this.navLog(bot, "pillar start -> " + (int)wanted + (wall ? " (Mauer " + (int)wallTop + ")" : " (kein Mauerfund)"));
                                return;
                            }

                            if (BotDefaults.get().dig && tick >= bot.nextDigTick) {
                                Vec3 dir = new Vec3(goal.x - pos.x, 0.0, goal.z - pos.z);
                                if (dir.lengthSqr() > 0.01 && this.digAhead(bot, dir.normalize())) {
                                    bot.nextDigTick = tick + 10L;
                                    bot.stuckTicks = 0;
                                    return;
                                }
                            }

                            if (DEBUG_NAV) {
                                Vec3 node = bot.path != null && !bot.path.isDone() ? bot.path.getNextEntityPos(bot) : null;
                                this.navLog(
                                    bot,
                                    "steckt fest (Pfad "
                                        + (
                                            bot.path == null
                                                ? "keiner"
                                                : (bot.path.canReach() ? "erreicht" : "Teilstueck")
                                                    + ", Knoten "
                                                    + (node == null ? "-" : (int)node.x + "/" + (int)node.y + "/" + (int)node.z)
                                        )
                                        + ", Ziel "
                                        + (int)goal.x
                                        + "/"
                                        + (int)goal.y
                                        + "/"
                                        + (int)goal.z
                                        + ", Umweg "
                                        + (bot.detour == null ? "-" : (int)bot.detour.x + "/" + (int)bot.detour.z)
                                        + ")"
                                );
                            }

                            if (++bot.stuckStrikes >= 3) {
                                bot.stuckStrikes = 0;
                                bot.stuckTicks = 0;
                                bot.giveUpUntil = tick + 15L;
                                Vec3 toGoal = new Vec3(goal.x - pos.x, 0.0, goal.z - pos.z);
                                if (toGoal.lengthSqr() < 0.01) {
                                    new Vec3(1.0, 0.0, 0.0);
                                } else {
                                    new Vec3(-toGoal.z, 0.0, toGoal.x).normalize();
                                }

                                double sign = (bot.getId() + tick / 100L) % 2L == 0L ? 1.0 : -1.0;
                                bot.detour = this.pickDetour(bot, pos, goal, 5.0, (int)sign, 2.0);
                                bot.detourUntil = tick + 100L;
                                if (bot.detour == null) {
                                    bot.forceRepath = true;
                                }

                                bot.path = null;
                                this.stand(bot);
                                return;
                            }

                            bot.stuckTicks = 0;
                            bot.path = null;
                        }

                        if (tick < bot.giveUpUntil) {
                            this.stand(bot);
                        } else {
                            if (bot.detour != null) {
                                double ddx = bot.detour.x - pos.x;
                                double ddz = bot.detour.z - pos.z;
                                if (!(ddx * ddx + ddz * ddz < 2.25) && tick <= bot.detourUntil) {
                                    goal = bot.detour;
                                } else {
                                    bot.detour = null;
                                    bot.path = null;
                                }
                            }

                            boolean goalMoved = bot.pathGoal == null || bot.pathGoal.distanceToSqr(goal) > 4.0;
                            long maxAge = bot.path != null && bot.path.canReach() ? 600L : 300L;
                            boolean stale = bot.path != null && tick - bot.pathTick > maxAge + bot.getId() % 40;
                            boolean crowded = this.navigator.queued.get() > 60;
                            // A partial path that is nearly used up: ask for the next piece now and keep
                            // walking the old one meanwhile, instead of stopping at its end to wait.
                            boolean nearEnd = bot.path != null && !bot.path.canReach() && bot.path.getNodeCount() - bot.path.getNextNodeIndex() <= 5;
                            boolean needed = bot.path == null || bot.path.isDone() || goalMoved || stale && !crowded || bot.forceRepath || nearEnd;
                            if (needed && bot.pendingPath == null && tick - bot.pathRequestedTick >= (crowded ? 30L : 10L)) {
                                BotBrain.SharedPath shared = this.sharedPaths.get(shareKey(pos, goal));
                                if (shared != null
                                    && tick - shared.tick() <= 60L
                                    && shared.start().distanceToSqr(pos) < 36.0
                                    && shared.goal().distanceToSqr(goal) < 64.0
                                    && !shared.path().isDone()) {
                                    bot.path = shared.path().copy();
                                    bot.path.setNextNodeIndex(0);
                                    bot.pathTick = tick;
                                    bot.pathGoal = goal;
                                    this.skipPassedNodes(bot);
                                    this.advancePastBehind(bot);
                                } else {
                                    bot.pendingPath = this.navigator.requestPath(bot, goal);
                                    bot.pendingGoal = goal;
                                    bot.pathRequestedTick = tick;
                                    bot.pathGoal = goal;
                                }

                                bot.forceRepath = false;
                            }

                            if (bot.path != null && !bot.path.isDone()) {
                                Vec3 node = bot.path.getNextEntityPos(bot);
                                double dx = node.x - pos.x;
                                double dz = node.z - pos.z;
                                double nodeToGoal = (node.x - goal.x) * (node.x - goal.x) + (node.z - goal.z) * (node.z - goal.z);
                                double meToGoal = (pos.x - goal.x) * (pos.x - goal.x) + (pos.z - goal.z) * (pos.z - goal.z);
                                if (nodeToGoal > meToGoal + 81.0 && bot.stuckTicks > 10 && bot.navRoute == null) {
                                    bot.forceRepath = true;
                                }

                                if (dx * dx + dz * dz > 49.0) {
                                    bot.path = null;
                                    this.straightOrWait(bot, goal, tick, 0.6F);
                                } else {
                                    if (dx * dx + dz * dz < 0.36 && Math.abs(node.y - pos.y) < 2.0 || this.passedNode(bot, node, pos)) {
                                        bot.path.advance();
                                        if (bot.path.isDone()) {
                                            this.straightOrWait(bot, goal, tick, 1.0F);
                                            return;
                                        }

                                        this.pullString(bot);
                                        if (bot.path.isDone()) {
                                            this.straightOrWait(bot, goal, tick, 1.0F);
                                            return;
                                        }

                                        node = bot.path.getNextEntityPos(bot);
                                    }

                                    Vec3 look = goal;

                                    for (int i = bot.path.getNextNodeIndex(); i < Math.min(bot.path.getNextNodeIndex() + 8, bot.path.getNodeCount()); i++) {
                                        Vec3 candidate = bot.path.getEntityPosAtNode(bot, i);
                                        double cx = candidate.x - pos.x;
                                        double cz = candidate.z - pos.z;
                                        if (i + 1 < bot.path.getNodeCount()) {
                                            Vec3 after = bot.path.getEntityPosAtNode(bot, i + 1);
                                            if (cx * (after.x - candidate.x) + cz * (after.z - candidate.z) < 0.0 && cx * cx + cz * cz < 64.0) {
                                                continue;
                                            }
                                        }

                                        if (cx * cx + cz * cz >= 6.25 * bot.profile().get("corner")) {
                                            look = candidate;
                                            break;
                                        }
                                    }

                                    this.lookAlong(bot, look);
                                    this.glanceWhileWalking(bot, tick);
                                    this.openDoorsAhead(bot, node, tick);
                                    Vec3 target = this.laneTarget(bot, node, look);
                                    float speed = BotDefaults.get().build && this.bridgeAhead(bot, node, tick) ? 0.7F : (float)bot.skill.pace;
                                    this.steerTowards(bot, target, tick, speed, 0.0F);
                                    if (bot.sprintJump
                                        && bot.skill.hopHabit > 0.0
                                        && bot.isSprinting()
                                        && bot.onGround()
                                        && !bot.isInWater()
                                        && !bot.wantsJump()
                                        && goal.distanceToSqr(pos) > 36.0
                                        && Math.abs(node.y - pos.y) < 0.5
                                        && !bot.horizontalCollision
                                        && bot.path.getNodeCount() - bot.path.getNextNodeIndex() > 2) {
                                        if (tick < bot.hopStreakUntil) {
                                            bot.wantJump(true);
                                        } else if (tick >= bot.nextHopDecision) {
                                            ThreadLocalRandom random = ThreadLocalRandom.current();
                                            boolean hopPace = "sprintjump".equals(BotDefaults.get().movePace);
                                            if (hopPace || random.nextDouble() < bot.skill.hopHabit * bot.profile().get("jump")) {
                                                bot.hopStreakUntil = tick + 24L + random.nextLong(0L, 60L);
                                                bot.wantJump(true);
                                            }

                                            bot.nextHopDecision = tick + 60L + random.nextLong(0L, 140L);
                                        }
                                    }
                                }
                            } else {
                                this.askMemory(bot, goal, tick);
                                Vec3 towards = goal;
                                double distance = Math.sqrt(pos.distanceToSqr(goal));
                                Vec3 probe = distance > 6.0 ? pos.add(goal.subtract(pos).scale(6.0 / distance)) : goal;
                                boolean clear = this.lineWalkable(bot, pos, new Vec3(probe.x, pos.y, probe.z));
                                boolean waiting = bot.pendingPath != null && tick - bot.pathRequestedTick < 20L && bot.stuckTicks < 10;
                                if ((clear || !waiting) && this.mayGoStraight(bot, towards)) {
                                    if (pos.distanceToSqr(new Vec3(towards.x, pos.y, towards.z)) > 4.0) {
                                        this.lookAlong(bot, towards);
                                    }

                                    this.openDoorsAhead(bot, towards, tick);
                                    float speed = BotDefaults.get().build && this.bridgeAhead(bot, towards, tick) ? 0.7F : 1.0F;
                                    this.steerTowards(bot, towards, tick, speed, 0.0F);
                                } else {
                                    Vec3 step = clear ? this.clearStepToward(bot, goal) : null;
                                    this.lookAlong(bot, goal);
                                    if (step != null) {
                                        this.steerTowards(bot, step, tick, 1.0F, 0.0F);
                                    } else {
                                        this.stand(bot);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private double formationOffset(BotPlayer bot) {
        BotDefaults defaults = BotDefaults.get();
        String mode = defaults.marchFormation == null ? "loose" : defaults.marchFormation.toLowerCase(Locale.ROOT);
        if (!mode.startsWith("column") && !mode.startsWith("gaense") && bot.walkGoal != null && bot.marching) {
            double width = Math.max(2.0, defaults.marchWidth);
            if (!mode.startsWith("line") && !mode.startsWith("reihe")) {
                double normalised = (bot.getId() * 2654435761L >>> 8 & 1023L) / 1023.0 * 2.0 - 1.0;
                return normalised * width * 0.5;
            } else {
                this.refreshMarchSlots(bot);
                int count = Math.max(1, bot.marchCount);
                int slot = bot.marchSlot < 0 ? 0 : bot.marchSlot;
                return (slot - (count - 1) / 2.0) * Math.min(2.5, width / Math.max(1, count - 1)) * 2.0;
            }
        } else {
            return 0.0;
        }
    }

    private void refreshMarchSlots(BotPlayer bot) {
        if (bot.walkGroup == null) {
            bot.marchSlot = 0;
            bot.marchCount = 1;
        } else {
            long tick = this.bots.server().getTickCount();
            if (tick - this.marchSlotsTick >= 40L) {
                this.marchSlotsTick = tick;
                Map<UUID, List<BotPlayer>> groups = new HashMap<>();

                for (BotPlayer other : this.bots.all()) {
                    if (other.walkGroup != null && !other.isDeadOrDying()) {
                        groups.computeIfAbsent(other.walkGroup, key -> new ArrayList<>()).add(other);
                    }
                }

                for (List<BotPlayer> group : groups.values()) {
                    group.sort(Comparator.comparingInt(Entity::getId));

                    for (int i = 0; i < group.size(); i++) {
                        group.get(i).marchSlot = i;
                        group.get(i).marchCount = group.size();
                    }
                }
            }
        }
    }

    private void navLog(BotPlayer bot, String what) {
        if (DEBUG_NAV) {
            Vec3 pos = bot.position();
            BotSystemMod.LOGGER
                .info(
                    "[nav] {} {} @ {} {} {} stuck={} strikes={}",
                    bot.getGameProfile().name(),
                    what,
                    String.format(Locale.ROOT, "%.1f", pos.x),
                    String.format(Locale.ROOT, "%.1f", pos.y),
                    String.format(Locale.ROOT, "%.1f", pos.z),
                    bot.stuckTicks,
                    bot.stuckStrikes
                );
        }
    }

    private Vec3 laneTarget(BotPlayer bot, Vec3 node, Vec3 ahead) {
        double lane = (bot.skill.lane + this.formationOffset(bot)) * bot.profile().get("lane");
        if (!bot.marching) {
            lane = Mth.clamp(lane, -1.8, 1.8);
        }

        if (bot.stuckTicks >= 4) {
            bot.laneOffUntil = bot.tickCount + 80;
        }

        if (!(Math.abs(lane) < 0.2) && bot.tickCount >= bot.laneOffUntil && bot.path != null) {
            Vec3 from = node;
            Vec3 to = ahead;
            if (bot.path.getNextNodeIndex() + 1 < bot.path.getNodeCount()) {
                to = bot.path.getEntityPosAtNode(bot, bot.path.getNextNodeIndex() + 1);
            } else if (bot.path.getNextNodeIndex() > 0) {
                from = bot.path.getEntityPosAtNode(bot, bot.path.getNextNodeIndex() - 1);
                to = node;
            }

            double dx = to.x - from.x;
            double dz = to.z - from.z;
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length < 0.5) {
                return node;
            }

            Vec3 shifted = new Vec3(node.x - dz / length * lane, node.y, node.z + dx / length * lane);
            ServerLevel world = bot.level();
            BlockPos feet = BlockPos.containing(shifted.x, node.y + 0.1, shifted.z);
            return world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                    && world.getBlockState(feet.above()).getCollisionShape(world, feet.above()).isEmpty()
                    && !world.getBlockState(feet.below()).getCollisionShape(world, feet.below()).isEmpty()
                    && world.getFluidState(feet).isEmpty()
                    && this.lineWalkable(bot, bot.position(), shifted)
                ? shifted
                : node;
        } else {
            return node;
        }
    }

    private boolean glanceWhileWalking(BotPlayer bot, long tick) {
        if (!BotDefaults.get().humanMovement) {
            return false;
        }

        if (tick < bot.walkGlanceUntil) {
            this.glanceHead(bot, bot.walkGlanceAt);
            return true;
        }

        if (tick < bot.nextWalkGlance) {
            return false;
        }

        bot.nextWalkGlance = tick + 80L + ThreadLocalRandom.current().nextLong(0L, (long)(400.0 * (1.2 - bot.skill.curiosity)));
        if (ThreadLocalRandom.current().nextDouble() > 0.35 + bot.skill.curiosity * 0.5) {
            return false;
        }

        Vec3 eye = bot.getEyePosition();
        LivingEntity[] someone = new LivingEntity[]{null};
        this.forEachNearby(bot, 12.0, other -> {
            if (someone[0] == null && other != bot && other.distanceToSqr(bot) > 4.0 && this.inFront(bot, other.position(), 75.0)) {
                someone[0] = other;
            }
        });

        for (ServerPlayer player : Bots.realPlayers(bot.level().players())) {
            if (player.distanceToSqr(bot) < 144.0 && this.inFront(bot, player.position(), 75.0)) {
                someone[0] = player;
                break;
            }
        }

        if (someone[0] != null) {
            bot.walkGlanceAt = someone[0].getEyePosition();
        } else {
            double yaw = Math.toRadians(
                bot.getYRot() + (ThreadLocalRandom.current().nextBoolean() ? 1.0 : -1.0) * ThreadLocalRandom.current().nextDouble(20.0, 45.0)
            );
            bot.walkGlanceAt = eye.add(-Math.sin(yaw) * 6.0, ThreadLocalRandom.current().nextDouble(-0.6, 0.6), Math.cos(yaw) * 6.0);
        }

        bot.walkGlanceUntil = tick + 8L + ThreadLocalRandom.current().nextLong(0L, 14L);
        return true;
    }

    private void glanceHead(BotPlayer bot, Vec3 at) {
        Vec3 eye = bot.getEyePosition();
        double dx = at.x - eye.x;
        double dz = at.z - eye.z;
        double dy = at.y - eye.y;
        float yaw = (float)(Mth.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        float relative = Mth.clamp(Mth.wrapDegrees(yaw - bot.getYRot()), -60.0F, 60.0F);
        bot.setYHeadRot(this.approach(bot.getYHeadRot(), bot.getYRot() + relative, 12.0F));
        float pitch = (float)Mth.clamp(-(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI), -30.0, 30.0);
        bot.setXRot(this.approach(bot.getXRot(), pitch, 6.0F));
    }

    private boolean inFront(BotPlayer bot, Vec3 point, double halfAngle) {
        double dx = point.x - bot.getX();
        double dz = point.z - bot.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.01) {
            return true;
        }

        double yaw = Math.toRadians(bot.getYRot());
        double dot = (dx * -Math.sin(yaw) + dz * Math.cos(yaw)) / length;
        return dot >= Math.cos(Math.toRadians(halfAngle));
    }

    private void openDoorsAhead(BotPlayer bot, Vec3 towards, long tick) {
        if (tick - bot.lastDoorTick >= 5L && (tick + bot.getId()) % 4L == 0L) {
            ServerLevel world = bot.level();
            Vec3 pos = bot.position();
            Vec3 dir = new Vec3(towards.x - pos.x, 0.0, towards.z - pos.z);
            if (!(dir.lengthSqr() < 0.01)) {
                dir = dir.normalize();

                for (double reach : new double[]{0.6, 1.3}) {
                    Vec3 ahead = pos.add(dir.scale(reach));

                    for (int dy = 0; dy <= 1; dy++) {
                        BlockPos target = new BlockPos(Mth.floor(ahead.x), Mth.floor(pos.y) + dy, Mth.floor(ahead.z));
                        BlockState state = world.getBlockState(target);
                        if (state.getBlock() instanceof DoorBlock door && !door.isOpen(state)) {
                            door.setOpen(bot, world, state, target, true);
                            bot.swing(InteractionHand.MAIN_HAND, true);
                            bot.lastDoorTick = tick;
                            return;
                        }

                        if (state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)) {
                            world.setBlock(target, state.setValue(FenceGateBlock.OPEN, true), 3);
                            bot.swing(InteractionHand.MAIN_HAND, true);
                            bot.lastDoorTick = tick;
                            return;
                        }
                    }
                }
            }
        }
    }

    private void pullString(BotPlayer bot) {
        Path path = bot.path;
        int current = path.getNextNodeIndex();
        Vec3 from = bot.position();
        int best = current;

        for (int i = current + 1; i <= Math.min(current + 6, path.getNodeCount() - 1); best = i++) {
            Vec3 candidate = path.getEntityPosAtNode(bot, i);
            if (Math.abs(candidate.y - from.y) > 0.5 || !this.lineWalkable(bot, from, candidate)) {
                break;
            }
        }

        if (best > current) {
            path.setNextNodeIndex(best);
        }
    }

    private boolean lineWalkable(BotPlayer bot, Vec3 from, Vec3 to) {
        ServerLevel world = bot.level();
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        if (length < 0.01) {
            return true;
        }

        Vec3 step = delta.scale(0.5 / length);
        MutableBlockPos pos = new MutableBlockPos();
        Vec3 point = from;

        for (double walked = 0.0; walked <= length; walked += 0.5) {
            pos.set(Mth.floor(point.x), Mth.floor(point.y + 0.05), Mth.floor(point.z));
            if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()) {
                return false;
            }

            pos.move(0, 1, 0);
            if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()) {
                return false;
            }

            pos.move(0, -2, 0);
            BlockState floor = world.getBlockState(pos);
            if (floor.getCollisionShape(world, pos).isEmpty() && floor.getFluidState().isEmpty()) {
                return false;
            }

            point = point.add(step);
        }

        return true;
    }

    private boolean enclosed(BotPlayer bot) {
        ServerLevel world = bot.level();
        BlockPos feet = bot.blockPosition();
        int blocked = 0;

        for (Direction side : Plane.HORIZONTAL) {
            for (int distance = 1; distance <= 3; distance++) {
                BlockPos next = feet.relative(side, distance);
                if (!world.getBlockState(next).getCollisionShape(world, next).isEmpty()
                    && !world.getBlockState(next.above()).getCollisionShape(world, next.above()).isEmpty()) {
                    blocked++;
                    break;
                }
            }
        }

        return blocked >= 3;
    }

    private double ceilingAbove(BotPlayer bot, double wanted) {
        ServerLevel world = bot.level();
        Vec3 pos = bot.position();
        int x = Mth.floor(pos.x);
        int z = Mth.floor(pos.z);
        MutableBlockPos cursor = new MutableBlockPos();

        for (int y = Mth.floor(pos.y) + 2; y <= Mth.floor(wanted) + 2; y++) {
            cursor.set(x, y, z);
            if (!world.getBlockState(cursor).getCollisionShape(world, cursor).isEmpty()) {
                return y;
            }
        }

        return Double.MAX_VALUE;
    }

    private String situationOf(BotPlayer bot, Vec3 goal) {
        Vec3 pos = bot.position();
        double wallTop = this.wallHeightAhead(bot, goal);
        double height = wallTop - pos.y;
        boolean crowd = !bot.level().getEntitiesOfClass(BotPlayer.class, bot.getBoundingBox().inflate(1.6, 1.0, 1.6), other -> other != bot).isEmpty();
        String base;
        if (bot.isInWater()) {
            base = "water";
        } else if (height >= 0.5) {
            base = height <= 1.5 ? "step" : (height <= 3.5 ? "wall3" : (height <= 7.5 ? "wall6" : "wall12"));
        } else {
            Vec3 flat = new Vec3(goal.x - pos.x, 0.0, goal.z - pos.z);
            Vec3 direction = flat.lengthSqr() < 0.01 ? new Vec3(1.0, 0.0, 0.0) : flat.normalize();
            if (this.gapAhead(bot, direction)) {
                base = "gap";
            } else if (goal.y < pos.y - 2.5) {
                base = "drop";
            } else if (this.enclosed(bot)) {
                base = "boxed";
            } else {
                base = "open";
            }
        }

        return crowd ? base + "+crowd" : base;
    }

    private void gradeTrial(BotPlayer bot, long tick) {
        if (bot.skillTrial != null) {
            long age = tick - bot.skillStart;
            if (age >= 40L) {
                double dx = bot.getX() - bot.skillFrom.x;
                double dz = bot.getZ() - bot.skillFrom.z;
                double dy = bot.getY() - bot.skillFrom.y;
                boolean success = dx * dx + dz * dz > 9.0 || dy > 1.5;
                int split = bot.skillTrial.indexOf(124);
                if (split > 0) {
                    BotSystemMod.skills().report(bot.skillTrial.substring(0, split), bot.skillTrial.substring(split + 1), success, age);
                }

                bot.skillTrial = null;
            }
        }
    }

    /** "No wall" for wallOnLine/wallHeightAhead - below any real height (y goes down to -64). */
    private static final double NO_WALL = -10000.0;

    private double wallOnLine(BotPlayer bot, Vec3 goal) {
        Vec3 pos = bot.position();
        double dx = goal.x - pos.x;
        double dz = goal.z - pos.z;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 2.0) {
            return NO_WALL;
        }

        dx /= length;
        dz /= length;
        int feet = Mth.floor(pos.y);
        double best = NO_WALL;

        for (double reach = 1.0; reach <= Math.min(24.0, length); reach++) {
            int x = Mth.floor(pos.x + dx * reach);
            int z = Mth.floor(pos.z + dz * reach);
            if (this.solidAt(bot, x, feet, z) && this.solidAt(bot, x, feet + 1, z)) {
                for (int y = feet; y <= feet + 16; y++) {
                    if (!this.solidAt(bot, x, y, z) && !this.solidAt(bot, x, y + 1, z)) {
                        best = Math.max(best, y - 1);
                        return best;
                    }
                }
                break;
            }
        }

        return best;
    }

    private boolean solidAt(BotPlayer bot, int x, int y, int z) {
        BlockPos at = new BlockPos(x, y, z);
        return !bot.level().getBlockState(at).getCollisionShape(bot.level(), at).isEmpty();
    }

    private double wallHeightAhead(BotPlayer bot, Vec3 goal) {
        ServerLevel world = bot.level();
        Vec3 pos = bot.position();
        double dx = goal.x - pos.x;
        double dz = goal.z - pos.z;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.5) {
            return NO_WALL;
        }

        dx /= length;
        dz /= length;
        double best = NO_WALL;

        for (double reach : new double[]{1.0, 1.8}) {
            int x = Mth.floor(pos.x + dx * reach);
            int z = Mth.floor(pos.z + dz * reach);
            int feet = Mth.floor(pos.y);
            MutableBlockPos cursor = new MutableBlockPos();
            cursor.set(x, feet, z);
            if (!world.getBlockState(cursor).getCollisionShape(world, cursor).isEmpty()) {
                for (int y = feet; y <= feet + 16; y++) {
                    cursor.set(x, y, z);
                    boolean solid = !world.getBlockState(cursor).getCollisionShape(world, cursor).isEmpty();
                    if (!solid) {
                        cursor.set(x, y + 1, z);
                        boolean headroom = world.getBlockState(cursor).getCollisionShape(world, cursor).isEmpty();
                        if (headroom) {
                            best = Math.max(best, y - 1);
                        }
                        break;
                    }
                }
            }
        }

        return best;
    }

    private void pillar(BotPlayer bot, Vec3 goal, long tick) {
        ServerLevel world = bot.level();
        Vec3 pos = bot.position();
        boolean knockedOff = bot.onGround() && pos.y < bot.pillarBaseY + 0.5;
        boolean tooLong = tick - bot.pillarStartTick > 60L + 25L * Math.max(1L, (long)(bot.pillarTargetY - bot.pillarStartY));
        if (knockedOff || tooLong) {
            this.navLog(bot, knockedOff ? "pillar abgebrochen (runtergeschubst)" : "pillar abgebrochen (dauert zu lange)");
            bot.pillarBaseY = Integer.MIN_VALUE;
            bot.path = null;
            bot.forceRepath = true;
            bot.pillarDoneUntil = tick + 40L;
            bot.stuckTicks = 0;
            bot.setXRot(0.0F);
        } else if (!(pos.y >= bot.pillarTargetY) && BotDefaults.get().build) {
            bot.setMoveInput(0.0F, 0.0F);
            bot.setSprinting(false);
            double dx = goal.x - pos.x;
            double dz = goal.z - pos.z;
            double length = Math.sqrt(dx * dx + dz * dz);
            Vec3 lookAt = length < 0.5 ? new Vec3(pos.x, pos.y - 1.6, pos.z) : new Vec3(pos.x + dx / length * 0.12, pos.y - 1.6, pos.z + dz / length * 0.12);
            this.face(bot, lookAt, 30.0F);
            BlockPos head = BlockPos.containing(pos.x, pos.y + 2.0, pos.z);
            if (world.getBlockState(head).getCollisionShape(world, head).isEmpty()) {
                BlockPos below = new BlockPos(Mth.floor(pos.x), bot.pillarBaseY + 1, Mth.floor(pos.z));
                if (pos.y >= bot.pillarBaseY + 2.0) {
                    if (tick >= bot.nextPlaceTick && this.bots().blocks.place(world, below, bot, tick)) {
                        bot.pillarBaseY++;
                        bot.nextPlaceTick = tick + 2L + ThreadLocalRandom.current().nextLong(0L, 3L);
                    }

                    bot.wantJump(false);
                } else {
                    bot.wantJump(bot.onGround());
                }
            } else {
                if (BotDefaults.get().dig && tick >= bot.nextDigTick) {
                    bot.nextDigTick = tick + 10L;
                    this.destroyBlock(bot, head);
                } else if (!BotDefaults.get().dig) {
                    bot.pillarBaseY = Integer.MIN_VALUE;
                    bot.pillarDoneUntil = tick + 200L;
                }
            }
        } else {
            bot.pillarBaseY = Integer.MIN_VALUE;
            bot.path = null;
            bot.stuckTicks = 0;
            bot.stuckStrikes = 0;
            bot.setXRot(this.approach(bot.getXRot(), 0.0F, 25.0F));
            bot.pillarDoneUntil = tick + 160L;
            bot.noWayVerdict = false;
            bot.longFailed = false;
            bot.preferClimb = false;
            bot.forceRepath = true;
            bot.crossUntil = tick + 60L;
            this.navLog(bot, "pillar fertig (Ziel " + (int)bot.pillarTargetY + ")");
            Vec3 over = new Vec3(goal.x - pos.x, 0.0, goal.z - pos.z);
            if (over.lengthSqr() > 0.01) {
                bot.detour = pos.add(over.normalize().scale(3.0));
                bot.detourUntil = tick + 60L;
                bot.giveUpUntil = 0L;
            }
        }
    }

    private boolean bridgeAhead(BotPlayer bot, Vec3 towards, long tick) {
        Vec3 pos = bot.position();
        Vec3 dir = new Vec3(towards.x - pos.x, 0.0, towards.z - pos.z);
        if (!(dir.lengthSqr() < 0.25) && bot.onGround()) {
            if (towards.y < pos.y - 1.5) {
                return false;
            }

            dir = dir.normalize();
            ServerLevel world = bot.level();
            int floorY = Mth.floor(pos.y) - 1;

            for (double reach : new double[]{0.7, 1.2}) {
                Vec3 ahead = pos.add(dir.scale(reach));
                BlockPos under = new BlockPos(Mth.floor(ahead.x), floorY, Mth.floor(ahead.z));
                if (!under.equals(bot.blockPosition().below())) {
                    boolean lava = false;
                    boolean hole = world.getBlockState(under).getCollisionShape(world, under).isEmpty()
                        && world.getBlockState(under).getFluidState().isEmpty()
                        && world.getBlockState(under.below()).getCollisionShape(world, under.below()).isEmpty();
                    if (!hole && !lava) {
                        return false;
                    }

                    if (!lava) {
                        MutableBlockPos deeper = new MutableBlockPos();

                        for (int drop = 1; drop <= 4; drop++) {
                            deeper.set(under.getX(), floorY - drop, under.getZ());
                            if (!world.getBlockState(deeper).getCollisionShape(world, deeper).isEmpty()) {
                                return false;
                            }
                        }

                        for (int d = 2; d <= 3; d++) {
                            for (int dy = 0; dy >= -1; dy--) {
                                BlockPos land = new BlockPos(Mth.floor(pos.x + dir.x * d), floorY + dy, Mth.floor(pos.z + dir.z * d));
                                if (!world.getBlockState(land).getCollisionShape(world, land).isEmpty()
                                    && world.getBlockState(land.above()).getCollisionShape(world, land.above()).isEmpty()
                                    && world.getBlockState(land.above(2)).getCollisionShape(world, land.above(2)).isEmpty()) {
                                    return false;
                                }
                            }
                        }
                    }

                    // Wide gap straight ahead: bridge it properly (turned around, sneaking)
                    // instead of placing blocks in front while walking - that looked like hacks.
                    if (bot.bridgeDir == null) {
                        this.startBridge(bot, towards, tick);
                    }

                    return true;
                }
            }

            return false;
        } else {
            return false;
        }
    }

    private static Direction cardinalToward(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        if (dx * dx + dz * dz < 1.0) {
            return null;
        }

        return Math.abs(dx) >= Math.abs(dz) ? (dx > 0.0 ? Direction.EAST : Direction.WEST) : (dz > 0.0 ? Direction.SOUTH : Direction.NORTH);
    }

    private static boolean solidFloor(ServerLevel level, BlockPos pos) {
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() && !level.getFluidState(pos).is(FluidTags.LAVA);
    }

    /** The floor block one step toward the goal is lava, or air over a drop deeper than 3 / into lava. */
    private boolean bridgeGapToward(BotPlayer bot, Vec3 goal) {
        Direction dir = cardinalToward(bot.position(), goal);
        if (dir == null) {
            return false;
        }

        ServerLevel level = bot.level();
        BlockPos floor = BlockPos.containing(bot.getX(), bot.getY() - 0.5, bot.getZ()).relative(dir);
        if (level.getFluidState(floor).is(FluidTags.LAVA)) {
            return true;
        }

        if (!level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()) {
            return false;
        }

        return this.lavaBelow(level, floor, 3) || !this.safeToDropInto(level, floor.above());
    }

    private void startBridge(BotPlayer bot, Vec3 goal, long tick) {
        bot.bridgeDir = cardinalToward(bot.position(), goal);
        if (bot.bridgeDir == null) {
            return;
        }

        bot.bridgeFloorY = Mth.floor(bot.getY() - 0.5);
        bot.bridgeGoal = goal;
        bot.bridgePlaced = 0;
        bot.bridgeEnd = tick + 1200L;
        bot.path = null;
        bot.bridgeShift = this.existingBridge(bot, bot.bridgeDir);
        this.navLog(bot, bot.bridgeShift != null ? "nimmt vorhandene Bruecke bei " + bot.bridgeShift.toShortString() : "Bruecke Richtung " + bot.bridgeDir);
    }

    private static boolean headroom(ServerLevel level, BlockPos floor) {
        return level.getBlockState(floor.above()).getCollisionShape(level, floor.above()).isEmpty()
            && level.getBlockState(floor.above(2)).getCollisionShape(level, floor.above(2)).isEmpty();
    }

    /**
     * A walkable line beside the bot (up to 5 blocks to either side, reachable over solid
     * floor) that crosses the gap in front: someone already bridged here. Returns the floor
     * block where that line starts, or null.
     */
    private BlockPos existingBridge(BotPlayer bot, Direction dir) {
        ServerLevel level = bot.level();
        BlockPos here = BlockPos.containing(bot.getX(), bot.getY() - 0.5, bot.getZ());
        Direction side = dir.getClockWise();
        for (int offset : new int[]{1, -1, 2, -2, 3, -3, 4, -4, 5, -5}) {
            int step = offset > 0 ? 1 : -1;
            boolean reachable = true;
            for (int k = 1; k <= Math.abs(offset); k++) {
                BlockPos at = here.relative(side, step * k);
                if (!solidFloor(level, at) || !headroom(level, at)) {
                    reachable = false;
                    break;
                }
            }

            if (!reachable) {
                continue;
            }

            BlockPos start = here.relative(side, offset);
            int gap = 0;
            boolean crosses = true;
            boolean farSide = false;
            for (int d = 1; d <= 20; d++) {
                BlockPos line = start.relative(dir, d);
                if (!solidFloor(level, line) || !headroom(level, line)) {
                    crosses = false;
                    break;
                }

                if (!solidFloor(level, here.relative(dir, d))) {
                    gap++;
                } else if (gap > 0) {
                    farSide = true;
                    break;
                }
            }

            if (crosses && gap > 0 && (farSide || gap >= 3)) {
                return start;
            }
        }

        return null;
    }

    /** Walk input toward a world direction, whatever way the bot is facing. */
    private static void moveAlong(BotPlayer bot, double dx, double dz, float speed) {
        float yaw = (float)(Mth.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        float delta = Mth.wrapDegrees(yaw - bot.getYRot());
        float rad = delta * (float)(Math.PI / 180.0);
        bot.setMoveInput(speed * Mth.cos(rad), -speed * Mth.sin(rad));
    }

    private void stopBridge(BotPlayer bot) {
        bot.bridgeDir = null;
        bot.setShiftKeyDown(false);
        bot.path = null;
        bot.forceRepath = true;
        bot.stuckTicks = 0;
    }

    /**
     * One tick of bridging like a player: sneak (so the bot can't walk off the edge), creep to
     * the edge, look down and place the next floor block, step onto it, repeat. Never steps
     * forward unless solid floor is in front. True while busy.
     */
    private boolean continueBridge(BotPlayer bot, long tick) {
        ServerLevel level = bot.level();
        Direction dir = bot.bridgeDir;
        Vec3 pos = bot.position();
        if (tick > bot.bridgeEnd || bot.bridgePlaced > 96 || !BotDefaults.get().build || bot.isDeadOrDying() || bot.isInLava() || !bot.onGround() && bot.getDeltaMovement().y < -0.5) {
            this.stopBridge(bot);
            return false;
        }

        if (bot.bridgeShift != null) {
            // Someone already bridged beside us: walk over to that line; crossing it is then
            // just stepping onto solid floor below.
            Vec3 start = Vec3.atBottomCenterOf(bot.bridgeShift.above());
            double sx = start.x - pos.x;
            double sz = start.z - pos.z;
            if (sx * sx + sz * sz > 0.04) {
                bot.setShiftKeyDown(false);
                this.lookAlong(bot, start);
                this.steerTowards(bot, start, tick, 0.5F, 0.0F);
                return true;
            }

            bot.bridgeShift = null;
        }

        double along = (bot.bridgeGoal.x - pos.x) * dir.getStepX() + (bot.bridgeGoal.z - pos.z) * dir.getStepZ();
        BlockPos here = new BlockPos(Mth.floor(pos.x), bot.bridgeFloorY, Mth.floor(pos.z));
        BlockPos next = here.relative(dir);
        boolean nextSolid = solidFloor(level, next);
        boolean landed = nextSolid && solidFloor(level, next.relative(dir)) && !this.bridgeGapToward(bot, pos.add(dir.getStepX() * 3.0, 0.0, dir.getStepZ() * 3.0));
        if (along < 1.0 && !landed) {
            // Lined up with the goal on this axis but still over the gap: turn the bridge toward it.
            Direction turn = cardinalToward(pos, bot.bridgeGoal);
            if (turn != null && turn != dir && turn != dir.getOpposite() && this.bridgeGapToward(bot, bot.bridgeGoal)) {
                bot.bridgeDir = turn;
                return true;
            }
        }

        if (along < 1.0 || landed && (bot.bridgePlaced > 0 || along < 3.0)) {
            this.stopBridge(bot);
            return false;
        }

        bot.setShiftKeyDown(true);
        bot.setSprinting(false);
        bot.bridgeUntil = tick + 3L;
        // Bridge like a player: turned around, back to the gap, looking steeply down at the
        // edge of the own block, sneaking backwards.
        Vec3 back = new Vec3(-dir.getStepX(), 0.0, -dir.getStepZ());
        double pitchJitter = Math.floorMod(bot.getId() * 7 + (int)(tick / 40L), 5) * 0.03;
        Vec3 look = bot.getEyePosition().add(back.scale(0.32 + pitchJitter)).add(0.0, -1.6, 0.0);
        this.face(bot, look, 30.0F);
        if (nextSolid) {
            Vec3 onto = new Vec3(next.getX() + 0.5, pos.y, next.getZ() + 0.5);
            moveAlong(bot, onto.x - pos.x, onto.z - pos.z, 0.4F);
            return true;
        }

        double edge = dir.getStepX() > 0 ? here.getX() + 1 - pos.x
            : dir.getStepX() < 0 ? pos.x - here.getX()
            : dir.getStepZ() > 0 ? here.getZ() + 1 - pos.z
            : pos.z - here.getZ();
        if (edge > 0.8) {
            moveAlong(bot, dir.getStepX(), dir.getStepZ(), 0.3F);
            return true;
        }

        if (Math.abs(Mth.wrapDegrees(bot.getYRot() - ((float)(Mth.atan2(back.z, back.x) * 180.0 / Math.PI) - 90.0F))) > 40.0F) {
            bot.setMoveInput(0.0F, 0.0F);
            return true; // still turning around - no placing blocks behind the own back mid-turn
        }

        bot.setMoveInput(0.0F, 0.0F);
        if (tick >= bot.nextPlaceTick) {
            if (this.bots().blocks.place(level, next, bot, tick)) {
                bot.bridgePlaced++;
                bot.nextPlaceTick = tick + 3L + ThreadLocalRandom.current().nextInt(3);
            } else if (!level.getBlockState(next).canBeReplaced()) {
                this.stopBridge(bot);
                return false;
            }
        }

        return true;
    }

    private boolean lavaBelow(ServerLevel world, BlockPos from, int depth) {
        for (int down = 1; down <= depth; down++) {
            BlockPos below = from.below(down);
            if (world.getFluidState(below).is(FluidTags.LAVA)) {
                return true;
            }

            if (!world.getBlockState(below).getCollisionShape(world, below).isEmpty()) {
                return false;
            }
        }

        return false;
    }

    private boolean digAhead(BotPlayer bot, Vec3 dir) {
        ServerLevel world = bot.level();
        Vec3 ahead = bot.position().add(dir.scale(0.8));

        for (int dy = 1; dy >= 0; dy--) {
            BlockPos target = new BlockPos(Mth.floor(ahead.x), Mth.floor(bot.getY()) + dy, Mth.floor(ahead.z));
            BlockState state = world.getBlockState(target);
            if (!state.getCollisionShape(world, target).isEmpty()
                && state.getDestroySpeed(world, target) >= 0.0F
                && state.getDestroySpeed(world, target) < 60.0F) {
                return this.destroyBlock(bot, target);
            }
        }

        return false;
    }

    private boolean destroyBlock(BotPlayer bot, BlockPos target) {
        return this.startMining(bot, target, this.bots.server().getTickCount());
    }

    /**
     * Starts mining a block the way a player does: best tool from the inventory, real break
     * time (hardness, tool, enchantments, haste, water/air penalties) and the crack animation.
     * The work happens over the next ticks in continueMining. False if it can't be mined.
     */
    private boolean startMining(BotPlayer bot, BlockPos pos, long tick) {
        ServerLevel level = bot.level();
        BlockState state = level.getBlockState(pos);
        float hardness = state.getDestroySpeed(level, pos);
        if (state.isAir() || hardness < 0.0F || hardness >= 50.0F || !level.getWorldBorder().isWithinBounds(pos) || this.floodsWhenOpened(level, pos)) {
            return false;
        }

        if (pos.equals(bot.miningPos)) {
            return true;
        }

        this.stopMining(bot);
        bot.miningPos = pos.immutable();
        bot.miningProgress = 0.0F;
        bot.miningUntil = tick + 600L;
        this.equipBestTool(bot, state);
        return true;
    }

    /**
     * Target above ground (flying, or standing on the surface) while the bot is shut in a cave:
     * get to the surface first - a staircase dug upward, a block placed where a step is missing,
     * or pillaring straight up when no direction works - and only then go for the target.
     * The direction is picked once; the target moving meanwhile doesn't matter.
     * True while busy getting out.
     */
    private boolean surface(BotPlayer bot, LivingEntity target, long tick) {
        ServerLevel level = bot.level();
        BlockPos feet = BlockPos.containing(bot.getX(), bot.getY() + 0.2, bot.getZ());
        if (bot.surfaceDx == 0 && bot.surfaceDz == 0 && bot.surfacePillarFrom == null) {
            if (tick < bot.nextSurfaceCheck) {
                return false;
            }

            bot.nextSurfaceCheck = tick + 20L + bot.getId() % 10;
            if (bot.hasLineOfSight(target)) {
                return false;
            }

            BlockPos at = target.blockPosition();
            int targetGround = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
            boolean targetBuried = target.getY() < targetGround - 2.0;
            double flatGap = Math.sqrt(bot.distanceToSqr(target.getX(), bot.getY(), target.getZ()));
            if (target.getY() >= bot.getY() + 3.0 && !targetBuried && !this.outside(level, feet)) {
                bot.surfaceDown = false;
            } else if (target.getY() <= bot.getY() - 3.0
                && targetBuried
                && flatGap < 32.0
                && (bot.noWayVerdict || bot.longFailed || bot.stuckTicks >= 8 || flatGap < 6.0)) {
                // Target sits in a cave/bunker below and there is no walkable way down: dig to it.
                bot.surfaceDown = true;
            } else {
                return false;
            }

            double dx = target.getX() - bot.getX();
            double dz = target.getZ() - bot.getZ();
            if (dx * dx + dz * dz < 1.0) {
                dx = ThreadLocalRandom.current().nextDouble(-1.0, 1.0);
                dz = ThreadLocalRandom.current().nextDouble(-1.0, 1.0);
            }

            if (Math.abs(dx) >= Math.abs(dz)) {
                bot.surfaceDx = dx > 0.0 ? 1 : -1;
            } else {
                bot.surfaceDz = dz > 0.0 ? 1 : -1;
            }

            bot.surfaceTurns = 0;
            bot.surfaceUntil = tick + 2400L;
            bot.path = null;
            bot.chaseGoal = null;
            this.navLog(bot, bot.surfaceDown ? "graebt sich nach unten" : "graebt sich nach oben");
        }

        if (bot.surfaceDown) {
            return this.digDown(bot, target, feet, tick);
        }

        if (this.outside(level, feet) || tick > bot.surfaceUntil) {
            this.endSurface(bot);
            bot.nextSurfaceCheck = tick + 60L;
            return false;
        }

        if (bot.isUsingItem()) {
            bot.stopUsingItem();
            bot.drawStartTick = -1L;
        }

        bot.setSprinting(false);
        if (bot.surfacePillarFrom != null || bot.surfaceTurns >= 4) {
            return this.surfacePillar(bot, feet, tick);
        }

        if (!bot.onGround() && !bot.isInWater()) {
            bot.wantJump(false);
            return true; // mid-step: decide again once standing
        }

        // The block actually carrying the bot: standing on the edge of the new step with the
        // body still over the old column made it place a second step in its own way.
        feet = bot.onGround() ? bot.getOnPos().above() : feet;
        BlockPos front = feet.offset(bot.surfaceDx, 0, bot.surfaceDz);
        // Staircase: headroom above, then the two blocks of the next (higher) step.
        for (BlockPos block : new BlockPos[]{feet.above(2), front.above(2), front.above()}) {
            if (!level.getBlockState(block).getCollisionShape(level, block).isEmpty()) {
                if (this.startMining(bot, block, tick)) {
                    bot.stuckTicks = 0;
                    bot.setMoveInput(0.0F, 0.0F);
                    return true;
                }

                this.turnSurface(bot);
                return true;
            }
        }

        if (level.getBlockState(front).getCollisionShape(level, front).isEmpty()) {
            // No step to climb onto: put one there.
            bot.setMoveInput(0.0F, 0.0F);
            if (bot.getBoundingBox().intersects(new AABB(front))) {
                // Standing over the edge: step back to the middle of the own block first.
                this.steerTowards(bot, Vec3.atBottomCenterOf(feet), tick, 0.4F, 0.0F);
                return true;
            }

            if (tick >= bot.nextPlaceTick) {
                this.face(bot, Vec3.atCenterOf(front), 30.0F);
                if (this.bots().blocks.place(level, front, bot, tick)) {
                    bot.nextPlaceTick = tick + 3L + ThreadLocalRandom.current().nextInt(3);
                } else {
                    this.turnSurface(bot);
                }
            }

            return true;
        }

        Vec3 step = Vec3.atBottomCenterOf(front.above());
        this.lookAlong(bot, step);
        this.steerTowards(bot, step, tick, 0.7F, 0.0F);
        double gap = Math.max(Math.abs(step.x - bot.getX()) * Math.abs(bot.surfaceDx), Math.abs(step.z - bot.getZ()) * Math.abs(bot.surfaceDz));
        bot.wantJump(bot.horizontalCollision || gap < 0.95);
        return true;
    }

    /**
     * Staircase down toward a target below: clear the three blocks of the next lower step,
     * never drop onto lava or into a deep hole, turn when a direction is blocked.
     */
    private boolean digDown(BotPlayer bot, LivingEntity target, BlockPos feet, long tick) {
        ServerLevel level = bot.level();
        if (bot.getY() <= target.getY() + 0.5 || tick > bot.surfaceUntil || bot.surfaceTurns >= 4
            || bot.hasLineOfSight(target) && bot.distanceToSqr(target) < 400.0) {
            this.endSurface(bot);
            bot.nextSurfaceCheck = tick + 60L;
            return false;
        }

        if (bot.isUsingItem()) {
            bot.stopUsingItem();
            bot.drawStartTick = -1L;
        }

        bot.setSprinting(false);
        if (!bot.onGround() && !bot.isInWater()) {
            bot.wantJump(false);
            return true;
        }

        feet = bot.getOnPos().above();
        // Re-aim the stairs at the target each landing while it is still far to the side.
        double dx = target.getX() - bot.getX();
        double dz = target.getZ() - bot.getZ();
        boolean alongX = bot.surfaceDx != 0;
        // Only switch axis when the other one is clearly longer - a diagonal target flipped it
        // every tick and each flip threw away the half-mined block.
        if (bot.surfaceTurns == 0 && dx * dx + dz * dz > 4.0
            && (alongX ? Math.abs(dz) > Math.abs(dx) * 1.6 + 1.0 : Math.abs(dx) > Math.abs(dz) * 1.6 + 1.0)) {
            if (!alongX) {
                bot.surfaceDx = dx > 0.0 ? 1 : -1;
                bot.surfaceDz = 0;
            } else {
                bot.surfaceDx = 0;
                bot.surfaceDz = dz > 0.0 ? 1 : -1;
            }
        } else if (bot.surfaceTurns == 0) {
            // Same axis: still point the stairs toward the target, not away after passing it.
            if (bot.surfaceDx != 0 && Math.abs(dx) > 1.0) {
                bot.surfaceDx = dx > 0.0 ? 1 : -1;
            } else if (bot.surfaceDz != 0 && Math.abs(dz) > 1.0) {
                bot.surfaceDz = dz > 0.0 ? 1 : -1;
            }
        }

        BlockPos front = feet.offset(bot.surfaceDx, 0, bot.surfaceDz);
        BlockPos landing = front.below();
        BlockPos floor = landing.below();
        if (level.getFluidState(floor).is(FluidTags.LAVA) || level.getFluidState(landing).is(FluidTags.LAVA)
            || level.getBlockState(floor).getCollisionShape(level, floor).isEmpty() && !this.safeToDropInto(level, landing)) {
            this.turnSurface(bot);
            return true;
        }

        for (BlockPos block : new BlockPos[]{front.above(), front, landing}) {
            if (!level.getBlockState(block).getCollisionShape(level, block).isEmpty()) {
                if (this.startMining(bot, block, tick)) {
                    bot.stuckTicks = 0;
                    bot.setMoveInput(0.0F, 0.0F);
                    return true;
                }

                this.turnSurface(bot);
                return true;
            }
        }

        Vec3 step = Vec3.atBottomCenterOf(landing);
        this.lookAlong(bot, step);
        this.steerTowards(bot, step, tick, 0.6F, 0.0F);
        bot.wantJump(false);
        return true;
    }

    /** Straight up: clear the block over the head, jump, put a block where the feet were. */
    private boolean surfacePillar(BotPlayer bot, BlockPos feet, long tick) {
        ServerLevel level = bot.level();
        bot.setMoveInput(0.0F, 0.0F);
        BlockPos head = (bot.surfacePillarFrom != null ? bot.surfacePillarFrom : feet).above(2);
        if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()) {
            if (!this.startMining(bot, head, tick)) {
                this.endSurface(bot); // bedrock/lava overhead - give up, normal behaviour takes over
                bot.nextSurfaceCheck = tick + 200L;
                return false;
            }

            return true;
        }

        if (bot.surfacePillarFrom == null) {
            if (bot.onGround()) {
                bot.surfacePillarFrom = feet;
                bot.wantJump(true);
            }

            return true;
        }

        BlockPos from = bot.surfacePillarFrom;
        this.face(bot, Vec3.atCenterOf(from), 40.0F);
        if (bot.getY() >= from.getY() + 1.05) {
            if (!this.bots().blocks.place(level, from, bot, tick) && level.getBlockState(from).getCollisionShape(level, from).isEmpty()) {
                return true; // not high enough yet this tick
            }

            bot.surfacePillarFrom = null;
        } else if (bot.onGround() && bot.getY() < from.getY() + 0.5) {
            bot.wantJump(true);
        }

        return true;
    }

    private void turnSurface(BotPlayer bot) {
        int dx = bot.surfaceDx;
        bot.surfaceDx = -bot.surfaceDz;
        bot.surfaceDz = dx;
        bot.surfaceTurns++;
    }

    private void endSurface(BotPlayer bot) {
        bot.surfaceDown = false;
        bot.surfaceDx = 0;
        bot.surfaceDz = 0;
        bot.surfaceTurns = 0;
        bot.surfacePillarFrom = null;
    }

    /** Under open sky, or at most two blocks below the local surface. */
    private boolean outside(ServerLevel level, BlockPos feet) {
        return level.canSeeSky(feet.above())
            || level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ()) - feet.getY() <= 2;
    }

    /**
     * Target known but walled off (dug in, hiding in a base): dig toward it like a player -
     * a straight 1x2 tunnel, stairs down if it is below, stairs up if above. Only when there is
     * no line of sight and no walkable path. True while busy digging or stepping in.
     */
    private boolean tunnelToward(BotPlayer bot, LivingEntity target, long tick, double distance) {
        if (bot.hasLineOfSight(target) || !(bot.noWayVerdict || bot.longFailed || bot.stuckTicks >= 12 || distance <= 3.5)) {
            return false;
        }

        Vec3 pos = bot.position();
        Vec3 to = target.position();
        double dx = to.x - pos.x;
        double dz = to.z - pos.z;
        double dy = to.y - pos.y;
        double flat = Math.sqrt(dx * dx + dz * dz);
        ServerLevel level = bot.level();
        BlockPos feet = BlockPos.containing(pos.x, pos.y + 0.2, pos.z);
        int sx = 0;
        int sz = 0;
        if (flat > 0.9) {
            if (Math.abs(dx) >= Math.abs(dz)) {
                sx = dx > 0.0 ? 1 : -1;
            } else {
                sz = dz > 0.0 ? 1 : -1;
            }
        }

        BlockPos front = feet.offset(sx, 0, sz);
        List<BlockPos> dig = new ArrayList<>();
        if (sx == 0 && sz == 0) {
            if (dy < -0.8) {
                dig.add(feet.below());
            } else if (dy > 1.2) {
                dig.add(feet.above(2));
            }
        } else if (dy < -1.2) {
            dig.add(front.above());
            dig.add(front);
            dig.add(front.below());
        } else if (dy > 1.2) {
            dig.add(feet.above(2));
            dig.add(front.above());
            dig.add(front.above(2));
        } else {
            dig.add(front.above());
            dig.add(front);
        }

        for (BlockPos block : dig) {
            if (!level.getBlockState(block).getCollisionShape(level, block).isEmpty()) {
                boolean floor = block.equals(feet.below()) || block.equals(front.below());
                if (floor && !this.safeToDropInto(level, block)) {
                    return false;
                }

                if (!this.startMining(bot, block, tick)) {
                    return false;
                }

                bot.stuckTicks = 0;
                return true;
            }
        }

        if (sx == 0 && sz == 0) {
            return false;
        }

        Vec3 step = Vec3.atBottomCenterOf(front);
        this.lookAlong(bot, step);
        this.steerTowards(bot, step, tick, 0.6F, 0.0F);
        bot.wantJump(dy > 1.2 && bot.onGround() && bot.horizontalCollision);
        return true;
    }

    /**
     * Pillar fights: an enemy towered up out of reach gets the block under its feet broken (like a
     * player would), and a bot that is itself up on a pillar digs its way down instead of standing
     * there - before, both sides just waited with no input at all. True while busy with it.
     */
    private boolean perch(BotPlayer bot, LivingEntity target, long tick) {
        ServerLevel level = bot.level();
        double dy = target.getY() - bot.getY();
        double hx = target.getX() - bot.getX();
        double hz = target.getZ() - bot.getZ();
        double flat = Math.sqrt(hx * hx + hz * hz);
        if (dy >= 1.5 && target.onGround() && flat <= 4.5) {
            BlockPos under = BlockPos.containing(target.getX(), target.getY() - 0.3, target.getZ());
            if (!this.pillarLike(level, under)) {
                return false;
            }

            Vec3 centre = Vec3.atCenterOf(under);
            if (bot.getEyePosition().distanceToSqr(centre) <= 4.4 * 4.4) {
                if (bot.isUsingItem()) {
                    bot.stopUsingItem();
                }
                this.face(bot, centre, 30.0F);
                bot.setMoveInput(0.0F, 0.0F);
                return this.startMining(bot, under, tick);
            }

            if (flat > 1.2) {
                // Get right next to the pillar, where its top is in reach.
                this.steerTowards(bot, new Vec3(target.getX(), bot.getY(), target.getZ()), tick, 0.8F, 0.0F);
                return true;
            }

            return false;
        }

        if (dy <= -1.5 && bot.onGround() && flat <= 6.0 && !bot.hasLineOfSight(target)) {
            BlockPos mine = bot.getOnPos();
            if (this.pillarLike(level, mine) && this.safeToDropInto(level, mine)) {
                bot.setMoveInput(0.0F, 0.0F);
                this.face(bot, Vec3.atCenterOf(mine), 40.0F);
                return this.startMining(bot, mine, tick);
            }
        }

        return false;
    }

    /**
     * Caught in a cobweb (someone threw one): cut it with the sword like a player - after a short
     * reaction that depends on the tier - instead of crawling through it at web speed.
     */
    private boolean escapeWeb(BotPlayer bot, long tick) {
        if ((tick + bot.getId()) % 2L != 0L && bot.webSince < 0L) {
            return false;
        }

        ServerLevel level = bot.level();
        BlockPos feet = BlockPos.containing(bot.getX(), bot.getY() + 0.1, bot.getZ());
        BlockPos web = level.getBlockState(feet).is(Blocks.COBWEB) ? feet : (level.getBlockState(feet.above()).is(Blocks.COBWEB) ? feet.above() : null);
        if (web == null) {
            bot.webSince = -1L;
            return false;
        }

        if (bot.webSince < 0L) {
            bot.webSince = tick;
        }

        if (tick - bot.webSince < bot.skill.reactionTicks(6L)) {
            return false;
        }

        if (bot.isUsingItem()) {
            bot.stopUsingItem();
        }

        bot.setMoveInput(0.0F, 0.0F);
        this.face(bot, Vec3.atCenterOf(web), 45.0F);
        HABITS[5]++;
        return this.startMining(bot, web, tick) && this.continueMining(bot, tick);
    }

    /** Nearest bank within 10 blocks that a swimmer can climb (top at most 1 above the water), leaning toward the goal. */
    private Vec3 findShore(BotPlayer bot, Vec3 goal) {
        ServerLevel level = bot.level();
        MutableBlockPos at = new MutableBlockPos();
        int bx = bot.getBlockX();
        int bz = bot.getBlockZ();
        int surface = bot.getBlockY();
        for (int up = 0; up < 6; up++) {
            at.set(bx, surface, bz);
            if (!level.getFluidState(at).is(FluidTags.WATER)) {
                break;
            }
            surface++;
        }

        Vec3 best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -10; dx <= 10; dx++) {
            for (int dz = -10; dz <= 10; dz++) {
                double flat = Math.sqrt(dx * dx + dz * dz);
                if (flat < 1.5 || flat > 10.0) {
                    continue;
                }

                int x = bx + dx;
                int z = bz + dz;
                for (int standY = surface; standY <= surface + 1; standY++) {
                    at.set(x, standY - 1, z);
                    BlockState floor = level.getBlockState(at);
                    if (floor.getCollisionShape(level, at).isEmpty() || !floor.getFluidState().isEmpty() || floor.is(Blocks.MAGMA_BLOCK)) {
                        continue;
                    }
                    at.set(x, standY, z);
                    if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty() || !level.getFluidState(at).isEmpty()) {
                        continue;
                    }
                    at.set(x, standY + 1, z);
                    if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()) {
                        continue;
                    }

                    double toGoal = Math.sqrt((goal.x - x - 0.5) * (goal.x - x - 0.5) + (goal.z - z - 0.5) * (goal.z - z - 0.5));
                    double score = flat + toGoal * 0.5;
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Vec3(x + 0.5, standY, z + 0.5);
                    }
                    break;
                }
            }
        }

        return best;
    }

    /** A narrow column (a towered-up pillar), not a cliff or a house floor: open on 3+ sides. */
    private boolean pillarLike(ServerLevel level, BlockPos pos) {
        if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
            return false;
        }

        int open = 0;
        for (Direction side : Plane.HORIZONTAL) {
            BlockPos next = pos.relative(side);
            if (level.getBlockState(next).getCollisionShape(level, next).isEmpty()) {
                open++;
            }
        }

        return open >= 3;
    }

    /** Digging this floor block out drops the bot at most 3 blocks, and never into lava. */
    private boolean safeToDropInto(ServerLevel level, BlockPos floor) {
        for (int down = 1; down <= 3; down++) {
            BlockPos below = floor.below(down);
            if (level.getFluidState(below).is(FluidTags.LAVA)) {
                return false;
            }

            if (!level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
                return true;
            }
        }

        return false;
    }

    /** Opening this block would let lava pour in (or it IS next to lava) - don't dig there. */
    private boolean floodsWhenOpened(ServerLevel level, BlockPos pos) {
        for (Direction side : Direction.values()) {
            if (side != Direction.DOWN && level.getFluidState(pos.relative(side)).is(FluidTags.LAVA)) {
                return true;
            }
        }

        return false;
    }

    /** One tick of mining. True while busy (the bot does nothing else meanwhile). */
    private boolean continueMining(BotPlayer bot, long tick) {
        BlockPos pos = bot.miningPos;
        ServerLevel level = bot.level();
        BlockState state = level.getBlockState(pos);
        boolean gone = state.isAir() || state.getCollisionShape(level, pos).isEmpty() && !state.is(BlockTags.LEAVES);
        boolean underAttack = bot.hurtTime > 0 && bot.getLastHurtByMob() != null && bot.getLastHurtByMob().distanceToSqr(bot) < 16.0;
        if (gone || underAttack || tick > bot.miningUntil || bot.isDeadOrDying() || bot.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > 30.0) {
            this.stopMining(bot);
            return false;
        }

        bot.setMoveInput(0.0F, 0.0F);
        bot.setSprinting(false);
        bot.wantJump(false);
        this.face(bot, Vec3.atCenterOf(pos), 35.0F);
        if (tick - bot.miningLastSwing >= 4L) {
            bot.swing(InteractionHand.MAIN_HAND, true);
            bot.miningLastSwing = tick;
        }

        bot.miningProgress += state.getDestroyProgress(bot, level, pos);
        if (bot.miningProgress < 1.0F) {
            level.destroyBlockProgress(bot.getId(), pos, Math.min(9, (int)(bot.miningProgress * 10.0F)));
            return true;
        }

        level.destroyBlockProgress(bot.getId(), pos, -1);
        this.bots().blocks.remember(level, pos, tick);
        if (!bot.gameMode.destroyBlock(pos)) {
            level.destroyBlock(pos, false, bot);
        }

        bot.miningPos = null;
        bot.nextWeaponCheck = 0L;
        return true;
    }

    private void stopMining(BotPlayer bot) {
        if (bot.miningPos != null) {
            bot.level().destroyBlockProgress(bot.getId(), bot.miningPos, -1);
            bot.miningPos = null;
            bot.nextWeaponCheck = 0L;
        }
    }

    /** Pickaxe for stone, shovel for dirt, axe for wood ... from anywhere in the inventory. */
    private void equipBestTool(BotPlayer bot, BlockState state) {
        Inventory inventory = bot.getInventory();
        int best = -1;
        float bestSpeed = inventory.getItem(inventory.getSelectedSlot()).getDestroySpeed(state);
        for (int slot = 0; slot < 36; slot++) {
            float speed = inventory.getItem(slot).getDestroySpeed(state);
            if (speed > bestSpeed + 0.01F) {
                bestSpeed = speed;
                best = slot;
            }
        }

        if (best >= 9) {
            ItemStack tool = inventory.getItem(best);
            inventory.setItem(best, inventory.getItem(8));
            inventory.setItem(8, tool);
            best = 8;
        }

        if (best >= 0) {
            inventory.setSelectedSlot(best);
        }
    }

    private Bots bots() {
        return this.bots;
    }

    private boolean passedNode(BotPlayer bot, Vec3 node, Vec3 pos) {
        Path path = bot.path;
        int index = path.getNextNodeIndex();
        if (index + 1 < path.getNodeCount() && !(Math.abs(node.y - pos.y) >= 2.0)) {
            Vec3 next = path.getEntityPosAtNode(bot, index + 1);
            double sx = next.x - node.x;
            double sz = next.z - node.z;
            double length = Math.sqrt(sx * sx + sz * sz);
            if (length < 0.01) {
                return true;
            }

            double px = pos.x - node.x;
            double pz = pos.z - node.z;
            double along = (px * sx + pz * sz) / length;
            double across = Math.abs(px * sz - pz * sx) / length;
            return along > 0.0 && across < 3.5;
        } else {
            return false;
        }
    }

    private void skipPassedNodes(BotPlayer bot) {
        Vec3 pos = bot.position();

        while (!bot.path.isDone() && bot.path.getNextNodeIndex() < bot.path.getNodeCount() - 1) {
            Vec3 node = bot.path.getNextEntityPos(bot);
            double dx = node.x - pos.x;
            double dz = node.z - pos.z;
            if (dx * dx + dz * dz > 1.0) {
                break;
            }

            bot.path.advance();
        }
    }

    private void steerTowards(BotPlayer bot, Vec3 goal, long tick, float forward, float sideways) {
        Vec3 delta = goal.subtract(bot.position());
        Vec3 flat = new Vec3(delta.x, 0.0, delta.z);
        if (flat.lengthSqr() < 0.01) {
            bot.setMoveInput(0.0F, 0.0F);
        } else {
            Vec3 direction = flat.normalize();
            BlockPos goalBlock = BlockPos.containing(goal.x, bot.getY() + 0.1, goal.z);
            if (!(flat.lengthSqr() < 2.25) || !this.climbable(bot, goalBlock) && !this.climbable(bot, goalBlock.above())) {
                double separationWeight = BotDefaults.get().separation;
                if (separationWeight > 0.0) {
                    Vec3 push = this.separation(bot);
                    if (push != null) {
                        Vec3 mixed = direction.add(push.scale(separationWeight / 0.216));
                        if (mixed.lengthSqr() > 1.0E-4) {
                            direction = mixed.normalize();
                        }
                    }
                }

                if (bot.jumpCooldown > 0) {
                    bot.jumpCooldown--;
                }

                if (bot.avoidTicks > 0) {
                    bot.avoidTicks--;
                    direction = new Vec3(bot.avoidDirection > 0 ? -direction.z : direction.z, 0.0, bot.avoidDirection > 0 ? direction.x : -direction.x);
                }

                Vec3 speed = bot.getDeltaMovement();
                if (Math.sqrt(speed.x * speed.x + speed.z * speed.z) > 0.35 && bot.knockbackTicks < 10 && !bot.isSprinting()) {
                    bot.knockbackTicks++;
                    bot.setMoveInput(0.0F, 0.0F);
                    bot.setSprinting(false);
                } else {
                    bot.knockbackTicks = 0;
                    if (bot.onGround() && !bot.isInWater() && this.relaxed(bot, tick) && this.dropAhead(bot, direction) > 3) {
                        bot.setMoveInput(0.0F, 0.0F);
                        bot.wantJump(false);
                        bot.setSprinting(false);
                    } else if (!this.climbable(bot, bot.blockPosition()) && !this.climbable(bot, bot.blockPosition().above())) {
                        double yawRad = Math.toRadians(bot.getYRot());
                        double facingX = -Math.sin(yawRad);
                        double facingZ = Math.cos(yawRad);
                        double along = direction.x * facingX + direction.z * facingZ;
                        double across = direction.x * facingZ - direction.z * facingX;
                        double sideFactor = 0.4 + 0.6 * Mth.clamp((0.75 - along) / 0.75, 0.0, 1.0);
                        float fwd = along < 0.0 && flat.lengthSqr() > 4.0
                            ? 0.0F
                            : (float)(forward * Mth.clamp(along, -0.4, 1.0) * Math.min(1.25, bot.profile().get("pace")));
                        float side = (float)(Mth.clamp(across, -1.0, 1.0) * forward * sideFactor + sideways);
                        bot.setMoveInput(fwd, side);
                        bot.setYBodyRot(bot.getYRot());
                        boolean step;
                        if ((tick + bot.getId()) % 2L == 0L) {
                            step = this.stepAhead(bot, direction)
                                || bot.horizontalCollision
                                    && (this.stepAhead(bot, new Vec3(direction.x, 0.0, 0.0)) || this.stepAhead(bot, new Vec3(0.0, 0.0, direction.z)));
                            bot.lastStepAhead = step;
                        } else {
                            step = bot.lastStepAhead;
                        }

                        if (bot.isInWater() && bot.getFluidHeight(FluidTags.WATER) > 0.6) {
                            bot.waterTicks++;
                            boolean bank = flat.lengthSqr() < 4.0 && delta.y > 0.3 || bot.horizontalCollision;
                            boolean submerged = bot.isUnderWater();
                            bot.setSprinting(true);
                            if (bot.isSwimming()) {
                                bot.setXRot(submerged ? -25.0F : 0.0F);
                                bot.wantJump(bank);
                            } else {
                                bot.wantJump(bank || bot.waterTicks > 12 && (submerged || bot.getDeltaMovement().y < -0.05));
                            }
                        } else {
                            bot.waterTicks = 0;
                            if (bot.onGround() && bot.jumpCooldown <= 0 && !step && this.gapAhead(bot, direction)) {
                                Vec3 moving = bot.getDeltaMovement();
                                double pace = Math.sqrt(moving.x * moving.x + moving.z * moving.z);
                                if (pace < 0.16 && !bot.isInWater()) {
                                    bot.setSprinting(true);
                                    bot.setMoveInput(1.0F, 0.0F);
                                } else {
                                    bot.setSprinting(true);
                                    bot.wantJump(true);
                                    bot.setDeltaMovement(moving.x + direction.x * 0.22, moving.y, moving.z + direction.z * 0.22);
                                    bot.jumpCooldown = 14;
                                }
                            } else if (bot.onGround() && bot.horizontalCollision && !step && bot.avoidTicks <= 0 && bot.stuckTicks > 6) {
                                bot.avoidDirection = this.freeSide(bot, direction);
                                bot.avoidTicks = 25;
                                if (bot.jumpCooldown <= 0) {
                                    bot.wantJump(true);
                                    bot.jumpCooldown = 10;
                                }
                            } else if (step && bot.jumpCooldown > 0) {
                                bot.wantJump(false);
                            } else {
                                if (step) {
                                    bot.jumpCooldown = 4;
                                }

                                bot.wantJump(bot.onGround() && step);
                            }
                        }
                    } else {
                        bot.setMoveInput(1.0F, 0.0F);
                        bot.setSprinting(false);
                        bot.setDeltaMovement(speed.x, 0.16, speed.z);
                    }
                }
            } else {
                bot.avoidTicks = 0;
                this.face(bot, new Vec3(goal.x, bot.getEyeY(), goal.z), 30.0F);
                bot.setMoveInput(1.0F, 0.0F);
                bot.setSprinting(false);
                if (this.climbable(bot, bot.blockPosition()) || this.climbable(bot, bot.blockPosition().above())) {
                    bot.setDeltaMovement(bot.getDeltaMovement().x, 0.16, bot.getDeltaMovement().z);
                }
            }
        }
    }

    private boolean climbable(BotPlayer bot, BlockPos pos) {
        return bot.level().getBlockState(pos).is(BlockTags.CLIMBABLE);
    }

    private boolean gapAhead(BotPlayer bot, Vec3 direction) {
        ServerLevel world = bot.level();
        Vec3 pos = bot.position();
        int y = Mth.floor(pos.y) - 1;
        BlockPos near = new BlockPos(Mth.floor(pos.x + direction.x * 1.2), y, Mth.floor(pos.z + direction.z * 1.2));
        if (world.getBlockState(near).getCollisionShape(world, near).isEmpty()
            && world.getBlockState(near.below()).getCollisionShape(world, near.below()).isEmpty()) {
            for (double reach : new double[]{2.2, 3.0, 3.9}) {
                BlockPos far = new BlockPos(Mth.floor(pos.x + direction.x * reach), y, Mth.floor(pos.z + direction.z * reach));
                BlockPos head = far.above();
                if (!world.getBlockState(far).getCollisionShape(world, far).isEmpty() && world.getBlockState(head).getCollisionShape(world, head).isEmpty()) {
                    return true;
                }
            }

            return false;
        } else {
            return false;
        }
    }

    private void lookAlong(BotPlayer bot, Vec3 point) {
        Vec3 eye = bot.getEyePosition();
        double dx = point.x - eye.x;
        double dz = point.z - eye.z;
        if (!(dx * dx + dz * dz < 0.25)) {
            double dy = point.y + bot.getEyeHeight() - eye.y;
            dy = Math.abs(dy) < 1.5 ? 0.0 : Mth.clamp(dy, -1.0, 1.0);
            this.face(bot, new Vec3(point.x, eye.y + dy, point.z), 18.0F);
        }
    }

    private boolean stepAhead(BotPlayer bot, Vec3 direction) {
        if (direction.lengthSqr() < 1.0E-4) {
            return false;
        }

        ServerLevel world = bot.level();
        Vec3 pos = bot.position();
        direction = direction.normalize();

        for (double reach : new double[]{0.55, 1.0}) {
            Vec3 ahead = pos.add(direction.scale(reach));
            BlockPos feet = BlockPos.containing(ahead.x, pos.y + 0.05, ahead.z);
            if (!feet.equals(bot.blockPosition())) {
                VoxelShape shape = world.getBlockState(feet).getCollisionShape(world, feet);
                if (!shape.isEmpty() && !(shape.max(Axis.Y) <= 0.6)) {
                    return world.getBlockState(feet.above()).getCollisionShape(world, feet.above()).isEmpty()
                        && world.getBlockState(feet.above(2)).getCollisionShape(world, feet.above(2)).isEmpty();
                }
            }
        }

        return false;
    }

    private void face(BotPlayer bot, Vec3 point, float maxStep) {
        Vec3 eye = bot.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float)(Mth.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        float pitch = (float)(-(Mth.atan2(dy, horizontal) * 180.0 / Math.PI));
        bot.setYRot(Mth.wrapDegrees(this.approach(bot.getYRot(), yaw, maxStep)));
        bot.setYHeadRot(bot.getYRot());
        bot.setXRot(this.approach(bot.getXRot(), pitch, maxStep));
    }

    private float approach(float current, float wanted, float maxStep) {
        float delta = Mth.wrapDegrees(wanted - current);
        float step = Math.max(Math.min(1.2F, maxStep), Math.abs(delta) * 0.38F);
        return current + Mth.clamp(delta, -Math.min(step, maxStep), Math.min(step, maxStep));
    }

    public static boolean isHostileTo(BotPlayer bot, Entity entity) {
        return entity instanceof Player player && player.isSpectator()
            ? false
            : !(entity instanceof ServerPlayer serverPlayer && serverPlayer.gameMode.getGameModeForPlayer() == GameType.CREATIVE);
    }

    private static final class Crowd {
        final List<BotPlayer> members = new ArrayList<>();
        double headingX = 0.0;
        double headingZ = -1.0;
        Vec3 lastPos;
        boolean moving;
        int columns = 1;
        long refreshed = Long.MIN_VALUE;
        long touched;
    }

    private record SharedLong(Vec3 from, Vec3 goal, long tick, CompletableFuture<LongRoute.Route> future) {
    }

    private record SharedPath(Path path, Vec3 start, Vec3 goal, long tick) {
    }
}
