package de.fiden.botsystem.ai;

import de.fiden.botsystem.BotSystemMod;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class LongRoute {
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "BotSystem-LongRoute");
        thread.setDaemon(true);
        thread.setPriority(1);
        return thread;
    });
    private static final int NODE_BUDGET = 400000;
    private static final int[][] DIRS = new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private LongRoute() {
    }

    public static CompletableFuture<LongRoute.Route> plan(Level world, Vec3 from, Vec3 to, int maxDrop) {
        int margin = 160;
        int minX = (int)Math.floor(Math.min(from.x, to.x)) - margin;
        int maxX = (int)Math.floor(Math.max(from.x, to.x)) + margin;
        int minZ = (int)Math.floor(Math.min(from.z, to.z)) - margin;
        int maxZ = (int)Math.floor(Math.max(from.z, to.z)) + margin;
        if (maxX - minX > 400) {
            if (to.x >= from.x) {
                minX = (int)Math.floor(from.x) - 40;
                maxX = minX + 400;
            } else {
                maxX = (int)Math.floor(from.x) + 40;
                minX = maxX - 400;
            }
        }

        if (maxZ - minZ > 400) {
            if (to.z >= from.z) {
                minZ = (int)Math.floor(from.z) - 40;
                maxZ = minZ + 400;
            } else {
                maxZ = (int)Math.floor(from.z) + 40;
                minZ = maxZ - 400;
            }
        }

        int minY = (int)Math.floor(Math.min(from.y, to.y)) - 24;
        int maxY = (int)Math.floor(Math.max(from.y, to.y)) + 24;
        PathNavigationRegion cache = new PathNavigationRegion(world, new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
        int[] box = new int[]{minX, minY, minZ, maxX, maxY, maxZ};
        return CompletableFuture.supplyAsync(() -> {
            try {
                return search(cache, box, from, to, maxDrop);
            } catch (Throwable error) {
                return null;
            }
        }, WORKERS);
    }

    private static long key(int x, int y, int z) {
        return (long)(x & 67108863) << 38 | (long)(z & 67108863) << 12 | y & 4095;
    }

    private static LongRoute.Route search(BlockGetter view, int[] box, Vec3 from, Vec3 to, int maxDrop) {
        MutableBlockPos at = new MutableBlockPos();
        int sx = Mth.floor(from.x);
        int sz = Mth.floor(from.z);
        int sy = settle(view, at, sx, Mth.floor(from.y + 0.2), sz, box);
        int gx = Mth.floor(to.x);
        int gz = Mth.floor(to.z);
        int gy = Mth.floor(to.y + 0.2);
        if (sy == Integer.MIN_VALUE) {
            if (System.getProperty("botsystem.debugnav") != null) {
                BotSystemMod.LOGGER.info("[longroute] Start {} nicht begehbar", sx + "/" + Mth.floor(from.y) + "/" + sz);
            }

            return null;
        } else {
            Long2DoubleOpenHashMap best = new Long2DoubleOpenHashMap();
            best.defaultReturnValue(Double.MAX_VALUE);
            PriorityQueue<LongRoute.Node> open = new PriorityQueue<>();
            open.add(new LongRoute.Node(sx, sy, sz, 0.0, heuristic(sx, sy, sz, gx, gy, gz), null));
            best.put(key(sx, sy, sz), 0.0);
            LongRoute.Node closest = null;
            double closestH = Double.MAX_VALUE;
            int expanded = 0;

            while (!open.isEmpty() && expanded < 400000) {
                LongRoute.Node node = open.poll();
                if (!(node.g > best.get(key(node.x, node.y, node.z)))) {
                    expanded++;
                    double h = heuristic(node.x, node.y, node.z, gx, gy, gz);
                    if (h < closestH) {
                        closestH = h;
                        closest = node;
                    }

                    if ((node.x - gx) * (node.x - gx) + (node.z - gz) * (node.z - gz) <= 2 && Math.abs(node.y - gy) <= 1) {
                        return build(view, node, false);
                    }

                    at.set(node.x, node.y, node.z);
                    boolean onLadder = view.getBlockState(at).is(BlockTags.CLIMBABLE);
                    at.set(node.x, node.y + 1, node.z);
                    boolean ladderAbove = view.getBlockState(at).is(BlockTags.CLIMBABLE);
                    at.set(node.x, node.y - 1, node.z);
                    boolean ladderBelow = view.getBlockState(at).is(BlockTags.CLIMBABLE);
                    if ((onLadder || ladderAbove) && node.y + 1 <= box[4] && clear(view, at, node.x, node.y + 1, node.z)) {
                        relax(open, best, node, node.x, node.y + 1, node.z, 1.5, gx, gy, gz);
                    }

                    if (ladderBelow && node.y - 1 >= box[1] && clear(view, at, node.x, node.y - 1, node.z)) {
                        relax(open, best, node, node.x, node.y - 1, node.z, 1.2, gx, gy, gz);
                    }

                    for (int[] dir : DIRS) {
                        int nx = node.x + dir[0];
                        int nz = node.z + dir[1];
                        if (nx >= box[0] && nx <= box[3] && nz >= box[2] && nz <= box[5]) {
                            boolean diagonal = dir[0] != 0 && dir[1] != 0;
                            if (!diagonal || clear(view, at, node.x + dir[0], node.y, node.z) && clear(view, at, node.x, node.y, node.z + dir[1])) {
                                int ny = step(view, at, node.x, node.y, node.z, nx, nz, maxDrop, box);
                                if (ny != Integer.MIN_VALUE && (!diagonal || ny == node.y)) {
                                    double cost = diagonal ? 1.414 : 1.0;
                                    if (ny > node.y) {
                                        cost += 0.6;
                                    } else if (ny < node.y - 3) {
                                        cost += (node.y - ny) * 2.0;
                                    }

                                    at.set(nx, ny, nz);
                                    if (!view.getFluidState(at).isEmpty()) {
                                        cost += 3.0;
                                    }

                                    int walls = 0;
                                    walls += blocked(view, at, nx + 1, ny, nz) ? 1 : 0;
                                    walls += blocked(view, at, nx - 1, ny, nz) ? 1 : 0;
                                    walls += blocked(view, at, nx, ny, nz + 1) ? 1 : 0;
                                    walls += blocked(view, at, nx, ny, nz - 1) ? 1 : 0;
                                    cost += walls * 0.35;
                                    double g = node.g + cost;
                                    long k = key(nx, ny, nz);
                                    if (g < best.get(k)) {
                                        best.put(k, g);
                                        open.add(new LongRoute.Node(nx, ny, nz, g, g + heuristic(nx, ny, nz, gx, gy, gz), node));
                                    }
                                }
                            }
                        }
                    }
                }
            }

            double startH = heuristic(sx, sy, sz, gx, gy, gz);
            if (closest != null && !open.isEmpty() && startH - closestH >= 16.0) {
                return build(view, closest, true);
            }

            boolean goalOutside = gx < box[0] || gx > box[3] || gz < box[2] || gz > box[5];
            if (closest != null && goalOutside && startH - closestH >= 16.0) {
                return build(view, closest, true);
            }

            if (System.getProperty("botsystem.debugnav") != null) {
                BotSystemMod.LOGGER
                    .info(
                        "[longroute] kein Weg {} -> {} nach {} Knoten (Budget {}), offen {}, naechster {}",
                        sx + "/" + sy + "/" + sz,
                        gx + "/" + gy + "/" + gz,
                        expanded,
                        400000,
                        open.size(),
                        closest == null ? "-" : closest.x + "/" + closest.y + "/" + closest.z
                    );
            }

            return null;
        }
    }

    private static void relax(
        PriorityQueue<LongRoute.Node> open, Long2DoubleOpenHashMap best, LongRoute.Node node, int x, int y, int z, double cost, int gx, int gy, int gz
    ) {
        double g = node.g + cost;
        long k = key(x, y, z);
        if (g < best.get(k)) {
            best.put(k, g);
            open.add(new LongRoute.Node(x, y, z, g, g + heuristic(x, y, z, gx, gy, gz), node));
        }
    }

    private static double heuristic(int x, int y, int z, int gx, int gy, int gz) {
        int dx = Math.abs(x - gx);
        int dz = Math.abs(z - gz);
        return Math.max(dx, dz) + 0.414 * Math.min(dx, dz) + Math.abs(y - gy) * 0.5;
    }

    private static boolean blocked(BlockGetter view, MutableBlockPos at, int x, int y, int z) {
        at.set(x, y, z);
        BlockState state = view.getBlockState(at);
        if (state.isAir()) {
            return false;
        } else if (state.getFluidState().is(FluidTags.LAVA)) {
            return true;
        } else if (state.is(BlockTags.CLIMBABLE)) {
            return false;
        } else {
            return !state.is(BlockTags.WOODEN_DOORS) && !state.is(BlockTags.FENCE_GATES) ? !state.getCollisionShape(view, at).isEmpty() : false;
        }
    }

    private static boolean clear(BlockGetter view, MutableBlockPos at, int x, int y, int z) {
        return !blocked(view, at, x, y, z) && !blocked(view, at, x, y + 1, z);
    }

    private static boolean standable(BlockGetter view, MutableBlockPos at, int x, int y, int z) {
        if (!clear(view, at, x, y, z)) {
            return false;
        }

        at.set(x, y, z);
        BlockState feet = view.getBlockState(at);
        if (feet.is(BlockTags.FIRE) || feet.is(BlockTags.CAMPFIRES) || feet.is(Blocks.SWEET_BERRY_BUSH) || feet.is(Blocks.POWDER_SNOW)) {
            return false;
        }

        if (view.getFluidState(at).isEmpty() && !feet.is(BlockTags.CLIMBABLE)) {
            at.set(x, y - 1, z);
            BlockState below = view.getBlockState(at);
            if (below.is(BlockTags.FENCES) || below.is(BlockTags.WALLS)) {
                return false;
            } else if (!below.is(Blocks.MAGMA_BLOCK) && !below.is(Blocks.CACTUS)) {
                return !below.getFluidState().isEmpty() && below.getFluidState().is(FluidTags.LAVA)
                    ? false
                    : !below.getCollisionShape(view, at).isEmpty() || !below.getFluidState().isEmpty();
            } else {
                return false;
            }
        } else {
            return true;
        }
    }

    private static int settle(BlockGetter view, MutableBlockPos at, int x, int y, int z, int[] box) {
        for (int dy = 0; dy <= 3; dy++) {
            if (y - dy >= box[1] && standable(view, at, x, y - dy, z)) {
                return y - dy;
            }
        }

        return standable(view, at, x, y + 1, z) ? y + 1 : Integer.MIN_VALUE;
    }

    private static int step(BlockGetter view, MutableBlockPos at, int x, int y, int z, int nx, int nz, int maxDrop, int[] box) {
        if (standable(view, at, nx, y, nz)) {
            return y;
        }

        if (y + 1 <= box[4] && standable(view, at, nx, y + 1, nz) && !blocked(view, at, x, y + 2, z)) {
            return y + 1;
        }

        at.set(x, y, z);
        if (view.getBlockState(at).is(BlockTags.CLIMBABLE)) {
            for (int up = 2; up <= 12 && y + up <= box[4]; up++) {
                if (standable(view, at, nx, y + up, nz)) {
                    return y + up;
                }

                at.set(x, y + up - 1, z);
                if (!view.getBlockState(at).is(BlockTags.CLIMBABLE)) {
                    break;
                }
            }
        }

        if (!clear(view, at, nx, y, nz)) {
            return Integer.MIN_VALUE;
        }

        for (int drop = 1; drop <= maxDrop && y - drop >= box[1]; drop++) {
            if (blocked(view, at, nx, y - drop, nz)) {
                return Integer.MIN_VALUE;
            }

            if (standable(view, at, nx, y - drop, nz)) {
                return y - drop;
            }
        }

        return Integer.MIN_VALUE;
    }

    private static LongRoute.Route build(BlockGetter view, LongRoute.Node end, boolean partial) {
        List<LongRoute.Node> chain = new ArrayList<>();

        for (LongRoute.Node node = end; node != null; node = node.parent) {
            chain.add(node);
        }

        Collections.reverse(chain);
        List<Vec3> points = new ArrayList<>();
        if (chain.size() < 2) {
            return new LongRoute.Route(points, end.g, partial);
        }

        MutableBlockPos at = new MutableBlockPos();
        LongRoute.Node kept = chain.get(0);

        for (int i = 1; i < chain.size(); i++) {
            LongRoute.Node a = chain.get(i - 1);
            LongRoute.Node b = chain.get(i);
            boolean jump = Math.abs(b.y - a.y) > 1 || a.x == b.x && a.z == b.z;
            if (jump) {
                if (a != kept) {
                    points.add(center(a));
                }

                points.add(center(b));
                kept = b;
            } else {
                if (i == chain.size() - 1) {
                    points.add(center(b));
                    break;
                }

                LongRoute.Node next = chain.get(i + 1);
                double span = Math.sqrt((next.x - kept.x) * (next.x - kept.x) + (next.z - kept.z) * (next.z - kept.z));
                if (span > 12.0 || !straight(view, at, kept, next)) {
                    points.add(center(b));
                    kept = b;
                }
            }
        }

        return new LongRoute.Route(points, end.g, partial);
    }

    private static Vec3 center(LongRoute.Node node) {
        return new Vec3(node.x + 0.5, node.y, node.z + 0.5);
    }

    private static boolean straight(BlockGetter view, MutableBlockPos at, LongRoute.Node a, LongRoute.Node b) {
        double ax = a.x + 0.5;
        double az = a.z + 0.5;
        double dx = b.x + 0.5 - ax;
        double dz = b.z + 0.5 - az;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.01) {
            return true;
        }

        double px = -dz / length * 0.3;
        double pz = dx / length * 0.3;

        for (double offset : new double[]{0.0, 1.0, -1.0}) {
            int y = a.y;
            int lastX = Integer.MIN_VALUE;
            int lastZ = Integer.MIN_VALUE;

            for (double t = 0.0; t <= length; t += 0.4) {
                int x = Mth.floor(ax + dx * t / length + px * offset);
                int z = Mth.floor(az + dz * t / length + pz * offset);
                if (x != lastX || z != lastZ) {
                    lastX = x;
                    lastZ = z;
                    if (!standable(view, at, x, y, z)) {
                        if (standable(view, at, x, y + 1, z)) {
                            y++;
                        } else {
                            if (!standable(view, at, x, y - 1, z)) {
                                return false;
                            }

                            y--;
                        }
                    }
                }
            }
        }

        return true;
    }

    private static final class Node implements Comparable<LongRoute.Node> {
        final int x;
        final int y;
        final int z;
        final double g;
        final double f;
        final LongRoute.Node parent;

        Node(int x, int y, int z, double g, double f, LongRoute.Node parent) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.g = g;
            this.f = f;
            this.parent = parent;
        }

        public int compareTo(LongRoute.Node other) {
            return Double.compare(this.f, other.f);
        }
    }

    public record Route(List<Vec3> points, double length, boolean partial) {
    }
}
