package de.fiden.botsystem.ai;

import de.fiden.botsystem.bot.BotPlayer;
import de.fiden.botsystem.bot.Ground;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;

public final class BotNavigator {
    public static final int RANGE = 150;
    private static final int SEARCH_REACH = 176;
    private static final float MAX_PATH_LENGTH = 420.0F;
    private static final int NODE_BUDGET = 30000;
    private static final int THREADS = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() / 2));
    public final AtomicInteger pathsComputed = new AtomicInteger();
    public final AtomicLong pathNanos = new AtomicLong();
    public final AtomicLong pathCpuNanos = new AtomicLong();
    private static final ThreadMXBean THREADS_MX = ManagementFactory.getThreadMXBean();
    public final AtomicInteger queued = new AtomicInteger();
    private final ExecutorService workers;
    private final ThreadLocal<BotNavigator.Worker> worker = ThreadLocal.withInitial(BotNavigator.Worker::new);

    public BotNavigator() {
        this.workers = Executors.newFixedThreadPool(THREADS, runnable -> {
            Thread thread = new Thread(runnable, "BotSystem-Pathfinder");
            thread.setDaemon(true);
            thread.setPriority(1);
            return thread;
        });
    }

    public int threads() {
        return THREADS;
    }

    private static Set<BlockPos> targetsAround(BlockPos target) {
        return Set.of(target, target.above(), target.below());
    }

    public BlockPos targetFor(BotPlayer bot, Vec3 goal) {
        ServerLevel world = bot.level();
        Vec3 flat = new Vec3(goal.x - bot.getX(), 0.0, goal.z - bot.getZ());
        if (flat.length() > 150.0) {
            Vec3 step = bot.position().add(flat.normalize().scale(146.0));
            Vec3 ground = Ground.find(world, step.x, step.z, bot.getY());
            return BlockPos.containing(ground);
        } else {
            return BlockPos.containing(goal);
        }
    }

    public Path findPathNow(ServerLevel world, Vec3 from, Vec3 to) {
        BlockPos start = BlockPos.containing(from);
        BlockPos target = BlockPos.containing(to);
        int reach = (int)Math.min(176.0, Math.max(64.0, Math.sqrt(start.distSqr(target)) * 2.0 + 48.0));
        int vertical = Math.min(reach, 48);
        PathNavigationRegion cache = new PathNavigationRegion(world, start.offset(-reach, -vertical, -reach), start.offset(reach, vertical, reach));
        BotNavigator.Worker worker = new BotNavigator.Worker();
        Mob dummy = worker.dummy(world);
        dummy.snapTo(from.x, from.y, from.z, 0.0F, 0.0F);
        dummy.setOnGround(true);
        float pathLength = Math.max(420.0F, (float)Math.sqrt(start.distSqr(target)) * 2.5F + 40.0F);
        return worker.navigator.findPath(cache, dummy, targetsAround(target), pathLength, 1, 1.0F);
    }

    public CompletableFuture<Path> requestPath(BotPlayer bot, Vec3 goal) {
        ServerLevel world = bot.level();
        BlockPos from = bot.blockPosition();
        BlockPos target = this.targetFor(bot, goal);
        int reach = (int)Math.min(176.0, Math.max(64.0, Math.sqrt(from.distSqr(target)) * 2.0 + 48.0));
        int vertical = Math.min(reach, 48);
        PathNavigationRegion cache = new PathNavigationRegion(world, from.offset(-reach, -vertical, -reach), from.offset(reach, vertical, reach));
        double distance = Math.sqrt(from.distSqr(target));
        double verticalGap = Math.abs(target.getY() - from.getY());
        float pathLength = Math.max(420.0F, (float)distance * 2.5F + 40.0F);
        double x = bot.getX();
        double y = bot.getY();
        double z = bot.getZ();
        this.queued.incrementAndGet();
        return CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            long cpuStart = THREADS_MX.getCurrentThreadCpuTime();

            try {
                BotNavigator.Worker worker = this.worker.get();
                Mob dummy = worker.dummy(world);
                dummy.snapTo(x, y, z, 0.0F, 0.0F);
                dummy.setOnGround(true);
                PathFinder search = worker.smallNavigator;
                return search.findPath(cache, dummy, targetsAround(target), pathLength, 1, 1.0F);
            } catch (Throwable error) {
                return null;
            } finally {
                this.queued.decrementAndGet();
                this.pathsComputed.incrementAndGet();
                this.pathNanos.addAndGet(System.nanoTime() - start);
                this.pathCpuNanos.addAndGet(Math.max(0L, THREADS_MX.getCurrentThreadCpuTime() - cpuStart));
            }
        }, this.workers);
    }

    private static final class Worker {
        final WalkNodeEvaluator maker = new WalkNodeEvaluator();
        final PathFinder navigator;
        final PathFinder smallNavigator;
        Mob dummy;
        ServerLevel dummyWorld;

        Worker() {
            this.maker.setCanPassDoors(true);
            this.maker.setCanOpenDoors(true);
            this.maker.setCanFloat(true);
            this.navigator = new PathFinder(this.maker, 30000);
            this.smallNavigator = new PathFinder(this.maker, 9000);
        }

        Mob dummy(ServerLevel world) {
            if (this.dummy == null || this.dummyWorld != world) {
                Zombie zombie = new Zombie(EntityType.ZOMBIE, world) {
                    @Override
                    public int getMaxFallDistance() {
                        return 5;
                    }
                };
                zombie.setPathfindingMalus(PathType.WATER, 14.0F);
                zombie.setPathfindingMalus(PathType.WATER_BORDER, 3.0F);
                zombie.setPathfindingMalus(PathType.DOOR_WOOD_CLOSED, 1.0F);
                zombie.setPathfindingMalus(PathType.DOOR_OPEN, 0.0F);
                this.dummy = zombie;
                this.dummyWorld = world;
            }

            return this.dummy;
        }
    }
}
