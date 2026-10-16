package de.fiden.botsystem.bot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.phys.Vec3;

public final class Ground {
    private static final Map<double[], Ground.CachedLevels> LEVELS = new WeakHashMap<>();

    private Ground() {
    }

    public static boolean loaded(ServerLevel world, double x, double z) {
        return world.getChunkSource().getChunkNow((int)Math.floor(x) >> 4, (int)Math.floor(z) >> 4) != null;
    }

    public static Vec3 find(ServerLevel world, double x, double z, double referenceY) {
        int bx = (int)Math.floor(x);
        int bz = (int)Math.floor(z);
        if (!loaded(world, x, z)) {
            return new Vec3(bx + 0.5, Math.floor(referenceY), bz + 0.5);
        }

        Vec3 dry = findDry(world, x, z, referenceY);
        return dry != null ? dry : new Vec3(bx + 0.5, world.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, bx, bz), bz + 0.5);
    }

    public static Vec3 findBelow(ServerLevel world, double x, double z, double referenceY) {
        int bx = (int)Math.floor(x);
        int bz = (int)Math.floor(z);
        if (!loaded(world, x, z)) {
            return null;
        }

        for (int down = 0; down <= 32; down++) {
            Vec3 spot = findDry(world, x, z, referenceY - down);
            if (spot != null && spot.y <= referenceY + 2.0) {
                return spot;
            }
        }

        return findDry(world, x, z, referenceY);
    }

    public static Vec3 findDry(ServerLevel world, double x, double z, double referenceY) {
        if (!loaded(world, x, z)) {
            return null;
        }

        int bx = (int)Math.floor(x);
        int bz = (int)Math.floor(z);
        int ref = (int)Math.floor(referenceY);
        MutableBlockPos pos = new MutableBlockPos();

        for (int offset = 0; offset <= 24; offset++) {
            for (int sign = 0; sign < 2; sign++) {
                if (offset != 0 || sign != 1) {
                    int y = ref + (sign == 0 ? offset : -offset);
                    pos.set(bx, y, bz);
                    if (world.isInWorldBounds(pos) && world.getBlockState(pos).getCollisionShape(world, pos).isEmpty() && world.getFluidState(pos).isEmpty()) {
                        pos.set(bx, y + 1, bz);
                        if (world.getBlockState(pos).getCollisionShape(world, pos).isEmpty() && world.getFluidState(pos).isEmpty()) {
                            pos.set(bx, y - 1, bz);
                            BlockState floor = world.getBlockState(pos);
                            if (!floor.getCollisionShape(world, pos).isEmpty() && floor.getFluidState().isEmpty() && !floor.is(BlockTags.LEAVES)) {
                                return new Vec3(bx + 0.5, y, bz + 0.5);
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    public static Vec3 randomDryLoading(ServerLevel world, double[] area, double referenceY, Random random) {
        for (int attempt = 0; attempt < 12; attempt++) {
            double px = area[0] + random.nextDouble() * Math.max(1.0, area[2] - area[0]);
            double pz = area[1] + random.nextDouble() * Math.max(1.0, area[3] - area[1]);
            world.getChunk((int)Math.floor(px) >> 4, (int)Math.floor(pz) >> 4);
            int top = world.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, (int)Math.floor(px), (int)Math.floor(pz));
            Vec3 dry = findDry(world, px, pz, top);
            if (dry != null) {
                return dry;
            }
        }

        return null;
    }

    public static boolean hasHeight(double[] area) {
        return area != null && area.length >= 6;
    }

    public static Ground.Levels levels(ServerLevel world, double[] area) {
        Ground.CachedLevels cached = LEVELS.get(area);
        long now = System.nanoTime();
        if (cached != null && now - cached.time() < 60000000000L) {
            return cached.levels();
        }

        List<Vec3> main = new ArrayList<>();
        List<Vec3> reserve = new ArrayList<>();
        TreeMap<Integer, List<Vec3>> byLevel = new TreeMap<>();
        int minX = (int)Math.floor(area[0]);
        int minZ = (int)Math.floor(area[1]);
        int maxX = (int)Math.ceil(area[2]) - 1;
        int maxZ = (int)Math.ceil(area[3]) - 1;
        int minY = (int)Math.floor(Math.min(area[4], area[5]));
        int maxY = (int)Math.floor(Math.max(area[4], area[5]));
        long volume = (long)(maxX - minX + 1) * (maxZ - minZ + 1) * (maxY - minY + 1);
        int step = volume > 400000L ? 2 : 1;
        MutableBlockPos pos = new MutableBlockPos();

        for (int x = minX; x <= maxX; x += step) {
            for (int z = minZ; z <= maxZ; z += step) {
                if (loaded(world, x + 0.5, z + 0.5)) {
                    boolean lower = false;

                    for (int y = minY; y <= maxY; y++) {
                        pos.set(x, y, z);
                        if (world.isInWorldBounds(pos)
                            && world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()
                            && world.getFluidState(pos).isEmpty()) {
                            pos.set(x, y + 1, z);
                            if (world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()) {
                                pos.set(x, y - 1, z);
                                BlockState floor = world.getBlockState(pos);
                                if (!floor.getCollisionShape(world, pos).isEmpty() && floor.getFluidState().isEmpty() && !floor.is(BlockTags.LEAVES)) {
                                    String id = BuiltInRegistries.BLOCK.getKey(floor.getBlock()).getPath();
                                    if (!id.contains("magma")
                                        && !id.contains("cactus")
                                        && !id.contains("campfire")
                                        && !id.contains("fence")
                                        && !id.endsWith("_wall")) {
                                        pos.set(x, y, z);
                                        boolean sky = world.canSeeSky(pos);
                                        Vec3 spot = new Vec3(x + 0.5, y, z + 0.5);
                                        if (!sky || !floor.is(BlockTags.STAIRS) && !floor.is(BlockTags.SLABS)) {
                                            if (sky && lower) {
                                                reserve.add(spot);
                                            } else {
                                                main.add(spot);
                                                byLevel.computeIfAbsent(y, key -> new ArrayList<>()).add(spot);
                                            }
                                        }

                                        lower = true;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Ground.Levels levels = new Ground.Levels(main, reserve, byLevel);
        LEVELS.put(area, new Ground.CachedLevels(levels, now));
        return levels;
    }

    public static List<Vec3> spreadOverLevels(ServerLevel world, double[] area, int count, Random random) {
        Ground.Levels levels = levels(world, area);
        List<List<Vec3>> floors = new ArrayList<>();
        int biggest = 0;

        for (List<Vec3> level : levels.byLevel().values()) {
            biggest = Math.max(biggest, level.size());
        }

        for (List<Vec3> level : levels.byLevel().values()) {
            if (level.size() >= Math.max(6, biggest / 10)) {
                List<Vec3> copy = new ArrayList<>(level);
                Collections.shuffle(copy, random);
                floors.add(copy);
            }
        }

        List<Vec3> result = new ArrayList<>();
        if (floors.isEmpty()) {
            List<Vec3> all = new ArrayList<>(levels.main());
            all.addAll(levels.reserve());
            Collections.shuffle(all, random);

            for (int i = 0; i < count && !all.isEmpty(); i++) {
                result.add(all.get(i % all.size()));
            }

            return result;
        } else {
            int[] cursor = new int[floors.size()];
            int stalls = 0;

            for (int turn = 0; result.size() < count && stalls < floors.size(); turn = (turn + 1) % floors.size()) {
                List<Vec3> floor = floors.get(turn);
                Vec3 picked = null;

                while (true) {
                    if (cursor[turn] < floor.size()) {
                        Vec3 candidate = floor.get((int)(cursor[turn]++));
                        boolean free = true;

                        for (Vec3 taken : result) {
                            if (Math.abs(taken.y - candidate.y) < 1.0 && taken.distanceToSqr(candidate) < 1.96) {
                                free = false;
                                break;
                            }
                        }

                        if (!free) {
                            continue;
                        }

                        picked = candidate;
                    }

                    if (picked == null) {
                        stalls++;
                    } else {
                        stalls = 0;
                        result.add(picked);
                    }
                    break;
                }
            }

            List<Vec3> extra = new ArrayList<>(levels.reserve());
            Collections.shuffle(extra, random);

            for (int i = 0; result.size() < count && i < extra.size(); i++) {
                result.add(extra.get(i));
            }

            for (int i = 0; result.size() < count && !levels.main().isEmpty(); i++) {
                result.add(levels.main().get(random.nextInt(levels.main().size())));
            }

            return result;
        }
    }

    public static Vec3 patrolSpot(ServerLevel world, double[] area, Vec3 from, Random random) {
        Ground.Levels levels = levels(world, area);
        if (levels.main().isEmpty()) {
            return levels.reserve().isEmpty() ? null : levels.reserve().get(random.nextInt(levels.reserve().size()));
        }

        List<Integer> keys = new ArrayList<>();

        for (Entry<Integer, List<Vec3>> entry : levels.byLevel().entrySet()) {
            if (entry.getValue().size() >= 6) {
                keys.add(entry.getKey());
            }
        }

        List<Vec3> pool = levels.main();
        if (!keys.isEmpty()) {
            int current = keys.get(0);

            for (int key : keys) {
                if (Math.abs(key - from.y) < Math.abs(current - from.y)) {
                    current = key;
                }
            }

            int level = random.nextDouble() < 0.4 ? keys.get(random.nextInt(keys.size())) : current;
            pool = levels.byLevel().get(level);
        }

        Vec3 best = null;

        for (int attempt = 0; attempt < 12; attempt++) {
            Vec3 candidate = pool.get(random.nextInt(pool.size()));
            double distance = Math.sqrt(Math.pow(candidate.x - from.x, 2.0) + Math.pow(candidate.z - from.z, 2.0));
            if (distance > 3.0 && distance < 32.0) {
                return candidate;
            }

            if (best == null || distance > 3.0) {
                best = candidate;
            }
        }

        return best;
    }

    public static Vec3 nearestSpot(ServerLevel world, double[] area, Vec3 from) {
        Ground.Levels levels = levels(world, area);
        Vec3 best = null;
        double bestCost = Double.MAX_VALUE;

        for (List<Vec3> list : List.of(levels.main(), levels.reserve())) {
            for (Vec3 spot : list) {
                double dy = spot.y - from.y;
                double cost = Math.pow(spot.x - from.x, 2.0) + Math.pow(spot.z - from.z, 2.0) + 9.0 * dy * dy;
                if (cost < bestCost) {
                    bestCost = cost;
                    best = spot;
                }
            }

            if (best != null) {
                return best;
            }
        }

        return null;
    }

    public static Vec3 randomDry(ServerLevel world, double[] area, double referenceY, Random random) {
        return randomDryNear(world, area, Double.NaN, Double.NaN, 0.0, referenceY, random);
    }

    public static Vec3 randomDryNear(ServerLevel world, double[] area, double x, double z, double radius, double referenceY, Random random) {
        Vec3 fallback = null;

        for (int attempt = 0; attempt < 10; attempt++) {
            double px;
            double pz;
            if (radius > 0.0 && !Double.isNaN(x)) {
                px = area[0] + random.nextDouble() * Math.max(1.0, area[2] - area[0]);
                pz = area[1] + random.nextDouble() * Math.max(1.0, area[3] - area[1]);
                double ddx = px - x;
                double ddz = pz - z;
                double gap = ddx * ddx + ddz * ddz;
                if ((gap > radius * radius || gap < 9.0) && attempt < 7) {
                    continue;
                }
            } else {
                px = area[0] + random.nextDouble() * Math.max(1.0, area[2] - area[0]);
                pz = area[1] + random.nextDouble() * Math.max(1.0, area[3] - area[1]);
            }

            Vec3 dry = findDry(world, px, pz, referenceY);
            if (dry != null) {
                return dry;
            }

            if (fallback == null && loaded(world, px, pz)) {
                fallback = find(world, px, pz, referenceY);
            }
        }

        return fallback;
    }

    private record CachedLevels(Ground.Levels levels, long time) {
    }

    public record Levels(List<Vec3> main, List<Vec3> reserve, TreeMap<Integer, List<Vec3>> byLevel) {
    }
}
