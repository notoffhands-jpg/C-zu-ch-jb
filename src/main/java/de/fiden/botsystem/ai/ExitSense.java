package de.fiden.botsystem.ai;

import de.fiden.botsystem.BotSystemMod;
import de.fiden.botsystem.bot.BotPlayer;
import de.fiden.botsystem.bot.Bots;
import de.fiden.botsystem.bot.Ground;
import de.fiden.botsystem.bot.SkillMemory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

public final class ExitSense {
    public static final String[] FEATURES = new String[]{
        "basis", "entfernung", "neu", "offen", "tuer", "himmel", "hoch", "luecke", "zielwaerts", "bekannt", "fortschritt"
    };
    private static final Map<Long, List<Vec3>> KNOWN_EXITS = new HashMap<>();
    private static final int F = FEATURES.length;
    private static ExitSense.Cage cage;
    private static String lastLayout = "";

    private static long area(double x, double z) {
        return (long)Math.floorDiv((int)Math.floor(x), 32) << 32 ^ Math.floorDiv((int)Math.floor(z), 32) & 4294967295L;
    }

    public static void rememberExit(Vec3 where) {
        List<Vec3> list = KNOWN_EXITS.computeIfAbsent(area(where.x, where.z), key -> new ArrayList<>());

        for (Vec3 known : list) {
            if (known.distanceToSqr(where) < 9.0) {
                return;
            }
        }

        list.add(where);
        if (list.size() > 16) {
            list.remove(0);
        }
    }

    public static void forgetExits() {
        KNOWN_EXITS.clear();
    }

    private static Vec3 nearestKnownExit(Vec3 pos) {
        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int ax = -1; ax <= 1; ax++) {
            for (int az = -1; az <= 1; az++) {
                List<Vec3> list = KNOWN_EXITS.get(area(pos.x + ax * 32.0, pos.z + az * 32.0));
                if (list != null) {
                    for (Vec3 exit : list) {
                        double distance = exit.distanceToSqr(pos);
                        if (distance < bestDistance && distance < 2304.0) {
                            bestDistance = distance;
                            best = exit;
                        }
                    }
                }
            }
        }

        return best;
    }

    private ExitSense() {
    }

    private static boolean solid(ServerLevel world, BlockPos pos) {
        return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private static long cell(double x, double z) {
        return (long)Math.floorDiv((int)Math.floor(x), 3) << 32 ^ Math.floorDiv((int)Math.floor(z), 3) & 4294967295L;
    }

    static double[] features(ServerLevel world, BotPlayer bot, ExitSense.Episode episode, Vec3 candidate) {
        double[] f = new double[F];
        Vec3 pos = bot.position();
        double dx = candidate.x - pos.x;
        double dz = candidate.z - pos.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        f[0] = 1.0;
        f[1] = Math.min(1.0, distance / 12.0);
        f[2] = episode.visited.contains(cell(candidate.x, candidate.z)) ? 0.0 : 1.0;
        BlockPos feet = BlockPos.containing(candidate.x, candidate.y + 0.1, candidate.z);
        int open = 0;

        for (int ray = 0; ray < 8; ray++) {
            double angle = ray * Math.PI / 4.0;
            boolean free = true;

            for (int step = 1; step <= 6 && free; step++) {
                BlockPos probe = BlockPos.containing(candidate.x + Math.cos(angle) * step, candidate.y + 1.1, candidate.z + Math.sin(angle) * step);
                free = !solid(world, probe);
            }

            open += free ? 1 : 0;
        }

        f[3] = open / 8.0;
        boolean door = false;

        for (BlockPos near : BlockPos.betweenClosed(feet.offset(-1, -1, -1), feet.offset(1, 2, 1))) {
            BlockState state = world.getBlockState(near);
            if (state.is(BlockTags.DOORS) || state.is(BlockTags.FENCE_GATES) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.CLIMBABLE)) {
                door = true;
                break;
            }
        }

        f[4] = door ? 1.0 : 0.0;
        f[5] = world.canSeeSky(feet.above()) ? 1.0 : 0.0;
        f[6] = Mth.clamp((candidate.y - pos.y) / 6.0, -1.0, 1.0);
        BlockPos head = feet.above();
        boolean gapX = solid(world, head.east()) && solid(world, head.west()) && !solid(world, head.north()) && !solid(world, head.south());
        boolean gapZ = solid(world, head.north()) && solid(world, head.south()) && !solid(world, head.east()) && !solid(world, head.west());
        f[7] = !gapX && !gapZ && !passage(world, feet.getX(), feet.getY(), feet.getZ()) ? 0.0 : 1.0;
        if (episode.goal != null && distance > 0.1) {
            double gx = episode.goal.x - pos.x;
            double gz = episode.goal.z - pos.z;
            double gl = Math.sqrt(gx * gx + gz * gz);
            f[8] = gl < 0.1 ? 0.0 : (dx * gx + dz * gz) / (distance * gl);
        } else if (episode.start != null && distance > 0.1) {
            double ox = pos.x - episode.start.x;
            double oz = pos.z - episode.start.z;
            double ol = Math.sqrt(ox * ox + oz * oz);
            f[8] = ol < 0.5 ? 0.0 : (dx * ox + dz * oz) / (distance * ol);
        }

        Vec3 known = nearestKnownExit(pos);
        if (known != null && distance > 0.1) {
            double kx = known.x - pos.x;
            double kz = known.z - pos.z;
            double kl = Math.sqrt(kx * kx + kz * kz);
            f[9] = kl < 0.5 ? 0.0 : (dx * kx + dz * kz) / (distance * kl);
        }

        if (episode.goal != null) {
            double now = Math.sqrt((episode.goal.x - pos.x) * (episode.goal.x - pos.x) + (episode.goal.z - pos.z) * (episode.goal.z - pos.z));
            double then = Math.sqrt(
                (episode.goal.x - candidate.x) * (episode.goal.x - candidate.x) + (episode.goal.z - candidate.z) * (episode.goal.z - candidate.z)
            );
            f[10] = Mth.clamp((now - then) / 8.0, -1.0, 1.0);
        } else if (episode.start != null) {
            double now = Math.sqrt((pos.x - episode.start.x) * (pos.x - episode.start.x) + (pos.z - episode.start.z) * (pos.z - episode.start.z));
            double then = Math.sqrt(
                (candidate.x - episode.start.x) * (candidate.x - episode.start.x) + (candidate.z - episode.start.z) * (candidate.z - episode.start.z)
            );
            f[10] = Mth.clamp((then - now) / 8.0, -1.0, 1.0);
        }

        return f;
    }

    static List<Vec3> candidates(ServerLevel world, BotPlayer bot) {
        List<Vec3> list = new ArrayList<>();
        Vec3 pos = bot.position();

        for (int ray = 0; ray < 16; ray++) {
            double angle = ray * Math.PI / 8.0;

            for (double reach : new double[]{3.0, 6.0, 10.0}) {
                double x = pos.x + Math.cos(angle) * reach;
                double z = pos.z + Math.sin(angle) * reach;
                Vec3 spot = Ground.findDry(world, x, z, pos.y);
                if (spot != null && Math.abs(spot.y - pos.y) <= 6.0 && walkable(world, pos, spot)) {
                    list.add(spot);
                }
            }
        }

        Vec3 eye = pos.add(0.0, 1.5, 0.0);
        int bx = Mth.floor(pos.x);
        int by = Mth.floor(pos.y + 0.1);
        int bz = Mth.floor(pos.z);
        List<Vec3> passages = new ArrayList<>();

        for (int dx = -10; dx <= 10; dx++) {
            for (int dz = -10; dz <= 10; dz++) {
                if (dx * dx + dz * dz <= 100 && dx * dx + dz * dz >= 2) {
                    for (int y = by - 3; y <= by + 6; y++) {
                        int x = bx + dx;
                        int z = bz + dz;
                        if (standable(world, x, y, z)) {
                            boolean climb = y >= by + 1
                                && (
                                    standable(world, x + 1, y - 1, z)
                                        || standable(world, x - 1, y - 1, z)
                                        || standable(world, x, y - 1, z + 1)
                                        || standable(world, x, y - 1, z - 1)
                                );
                            if (climb || passage(world, x, y, z)) {
                                Vec3 spot = new Vec3(x + 0.5, y, z + 0.5);
                                if (sees(world, eye, spot.add(0.0, 1.0, 0.0))) {
                                    passages.add(spot);
                                }
                            }
                        }
                    }
                }
            }
        }

        if (passages.size() > 24) {
            Collections.shuffle(passages);
            passages = passages.subList(0, 24);
        }

        list.addAll(passages);
        return list;
    }

    static boolean standable(ServerLevel world, int x, int y, int z) {
        MutableBlockPos at = new MutableBlockPos();
        return !blocked(world, at.set(x, y, z)) && !blocked(world, at.set(x, y + 1, z)) && solid(world, at.set(x, y - 1, z));
    }

    static boolean passage(ServerLevel world, int x, int y, int z) {
        MutableBlockPos at = new MutableBlockPos();
        BlockState feet = world.getBlockState(at.set(x, y, z));
        if (!feet.is(BlockTags.DOORS) && !feet.is(BlockTags.FENCE_GATES)) {
            for (int level = y; level <= y + 1; level++) {
                boolean east = solid(world, at.set(x + 1, level, z));
                boolean west = solid(world, at.set(x - 1, level, z));
                boolean north = solid(world, at.set(x, level, z - 1));
                boolean south = solid(world, at.set(x, level, z + 1));
                if (east && west && !north && !south || north && south && !east && !west) {
                    return true;
                }
            }

            return false;
        } else {
            return true;
        }
    }

    static boolean sees(ServerLevel world, Vec3 from, Vec3 to) {
        double length = from.distanceTo(to);
        MutableBlockPos at = new MutableBlockPos();

        for (double s = 0.3; s < length - 0.3; s += 0.3) {
            double t = s / length;
            if (blocked(
                world, at.set(Mth.floor(from.x + (to.x - from.x) * t), Mth.floor(from.y + (to.y - from.y) * t), Mth.floor(from.z + (to.z - from.z) * t))
            )) {
                return false;
            }
        }

        return true;
    }

    static boolean walkable(ServerLevel world, Vec3 from, Vec3 to) {
        return walkable(world, from, to, 3);
    }

    static boolean walkable(ServerLevel world, Vec3 from, Vec3 to, int maxDrop) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double length = Math.sqrt(dx * dx + dz * dz);
        int feet = Mth.floor(from.y + 0.1);
        MutableBlockPos at = new MutableBlockPos();

        for (double s = 0.5; s <= length; s += 0.5) {
            int x = Mth.floor(from.x + dx * s / length);
            int z = Mth.floor(from.z + dz * s / length);
            if (blocked(world, at.set(x, feet, z))) {
                if (blocked(world, at.set(x, feet + 1, z)) || blocked(world, at.set(x, feet + 2, z))) {
                    return false;
                }

                feet++;
            } else {
                if (blocked(world, at.set(x, feet + 1, z))) {
                    return false;
                }

                int drop;
                for (drop = 0; drop <= maxDrop && !blocked(world, at.set(x, feet - 1, z)); drop++) {
                    feet--;
                }

                if (drop > maxDrop) {
                    return false;
                }
            }

            // Lava and fire have no collision box, so the checks above happily walk into them.
            if (deadly(world, at.set(x, feet, z)) || deadly(world, at.set(x, feet - 1, z))) {
                return false;
            }
        }

        return Math.abs(feet - Mth.floor(to.y + 0.1)) <= 1;
    }

    static boolean deadly(ServerLevel world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getFluidState().is(FluidTags.LAVA) || state.is(BlockTags.FIRE) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS);
    }

    private static boolean blocked(ServerLevel world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.is(BlockTags.DOORS) && !state.is(BlockTags.FENCE_GATES) && !state.getCollisionShape(world, pos).isEmpty();
    }

    public static void visit(ExitSense.Episode episode, Vec3 pos) {
        episode.visited.add(cell(pos.x, pos.z));
    }

    public static Vec3 choose(ServerLevel world, BotPlayer bot, ExitSense.Episode episode) {
        SkillMemory memory = BotSystemMod.skills();
        double[] w = memory.exploreWeights();
        List<Vec3> options = candidates(world, bot);
        if (options.isEmpty()) {
            return null;
        }

        double[][] feats = new double[options.size()][];
        double[] scores = new double[options.size()];
        double max = -1.0E9;

        for (int i = 0; i < options.size(); i++) {
            feats[i] = features(world, bot, episode, options.get(i));
            double score = 0.0;

            for (int k = 0; k < F; k++) {
                score += w[k] * feats[i][k];
            }

            scores[i] = score;
            max = Math.max(max, score);
        }

        double temperature = Math.max(0.15, Math.max(0.05, memory.exploreTemperature) / Math.sqrt(1.0 + memory.exploreEpisodes / 200.0));
        double total = 0.0;
        double[] prob = new double[options.size()];

        for (int i = 0; i < options.size(); i++) {
            prob[i] = Math.exp((scores[i] - max) / temperature);
            total += prob[i];
        }

        double roll = ThreadLocalRandom.current().nextDouble() * total;
        int chosen = options.size() - 1;

        for (int i = 0; i < options.size(); i++) {
            roll -= prob[i];
            if (roll <= 0.0) {
                chosen = i;
                break;
            }
        }

        double[] expected = new double[F];

        for (int i = 0; i < options.size(); i++) {
            for (int k = 0; k < F; k++) {
                expected[k] += prob[i] / total * feats[i][k];
            }
        }

        double[] advantage = new double[F];

        for (int k = 0; k < F; k++) {
            advantage[k] = feats[chosen][k] - expected[k];
        }

        episode.steps.add(new ExitSense.Step(advantage));
        return options.get(chosen);
    }

    public static double finish(ExitSense.Episode episode, boolean escaped, long tick) {
        SkillMemory memory = BotSystemMod.skills();
        double seconds = (tick - episode.startTick) / 20.0;
        double horizon = Math.max(10.0, (episode.deadline - episode.startTick) / 20.0);
        double reward = escaped ? 0.5 + Math.max(0.0, 1.0 - seconds / horizon) : -0.3;
        double baseline = memory.exploreBaseline;
        double gain = reward - baseline;
        double[] w = memory.exploreWeights();
        double rate = memory.exploreRate / Math.max(1.0, Math.sqrt(episode.steps.size()));

        for (ExitSense.Step step : episode.steps) {
            for (int k = 0; k < F; k++) {
                w[k] = Mth.clamp(w[k] + rate * gain * step.advantage[k], -6.0, 6.0);
            }
        }

        memory.exploreBaseline = baseline * 0.9 + reward * 0.1;
        memory.exploreEpisodes++;
        if (escaped) {
            memory.exploreEscapes++;
        }

        memory.touch();
        return seconds;
    }

    public static boolean escaped(BotPlayer bot, ExitSense.Episode episode) {
        Vec3 pos = bot.position();
        if (episode.goal != null) {
            double gx = episode.goal.x - pos.x;
            double gz = episode.goal.z - pos.z;
            return gx * gx + gz * gz < 9.0;
        } else {
            double dx = pos.x - episode.start.x;
            double dz = pos.z - episode.start.z;
            return dx * dx + dz * dz > episode.radius * episode.radius;
        }
    }

    public static ExitSense.Cage cage() {
        return cage;
    }

    public static void startCage(ServerLevel world, BlockPos centre, List<BotPlayer> bots, int episodes, int size, int exits, int timeoutSeconds, long tick) {
        startCage(world, centre, bots, episodes, size, exits, timeoutSeconds, tick, 0L);
    }

    public static void startCage(
        ServerLevel world, BlockPos centre, List<BotPlayer> bots, int episodes, int size, int exits, int timeoutSeconds, long tick, long seed
    ) {
        ExitSense.Cage fresh = new ExitSense.Cage();
        fresh.baseSeed = seed;
        fresh.world = world;
        fresh.centre = centre;
        fresh.size = size;
        fresh.exits = exits;
        fresh.episodesLeft = episodes;
        fresh.timeoutTicks = timeoutSeconds * 20L;
        fresh.nextEpisodeAt = tick;

        for (BotPlayer bot : bots) {
            fresh.bots.add(bot.getUUID());
        }

        cage = fresh;
    }

    public static void stopCage(Bots all) {
        if (cage != null) {
            for (UUID id : cage.bots) {
                BotPlayer bot = all.byId(id);
                if (bot != null) {
                    bot.escape = null;
                    bot.walkGoal = null;
                }
            }
        }

        cage = null;
    }

    public static void cageResult(BotPlayer bot, boolean escaped, double seconds) {
        if (cage != null && cage.bots.contains(bot.getUUID())) {
            cage.finishedThisEpisode++;
            if (escaped) {
                cage.escapedThisEpisode++;
                cage.timeThisEpisode += seconds;
            } else {
                cage.timeThisEpisode = cage.timeThisEpisode + cage.timeoutTicks / 20.0;
            }
        }
    }

    public static String tickCage(Bots all, long tick) {
        if (cage == null) {
            return null;
        }

        List<BotPlayer> members = new ArrayList<>();

        for (UUID id : cage.bots) {
            BotPlayer bot = all.byId(id);
            if (bot != null && !bot.isRemoved() && !bot.isDeadOrDying()) {
                members.add(bot);
            }
        }

        if (members.isEmpty()) {
            cage = null;
            return "Kaefig-Training beendet: keine Bots mehr da.";
        }

        if (cage.episodeStart < 0L) {
            if (tick < cage.nextEpisodeAt) {
                return null;
            }

            cage.episode++;
            KNOWN_EXITS.remove(area(cage.centre.getX(), cage.centre.getZ()));

            for (int ax = -1; ax <= 1; ax++) {
                for (int az = -1; az <= 1; az++) {
                    KNOWN_EXITS.remove(area(cage.centre.getX() + ax * 32.0, cage.centre.getZ() + az * 32.0));
                }
            }

            cage.radius = buildCage(
                cage.world,
                cage.centre,
                cage.size,
                cage.exits,
                cage.baseSeed != 0L ? cage.baseSeed * 7919L + cage.episode : ThreadLocalRandom.current().nextLong()
            );
            cage.escapedThisEpisode = 0;
            cage.timeThisEpisode = 0.0;
            cage.finishedThisEpisode = 0;
            ThreadLocalRandom random = ThreadLocalRandom.current();

            for (BotPlayer bot : members) {
                double x = cage.centre.getX() + 0.5 + random.nextDouble(-1.5, 1.5);
                double z = cage.centre.getZ() + 0.5 + random.nextDouble(-1.5, 1.5);
                bot.teleportTo(cage.world, x, cage.centre.getY(), z, Set.of(), random.nextFloat() * 360.0F, 0.0F, false);
                bot.path = null;
                bot.navRoute = null;
                bot.walkGoal = null;
                ExitSense.Episode episode = new ExitSense.Episode();
                episode.start = new Vec3(cage.centre.getX() + 0.5, cage.centre.getY(), cage.centre.getZ() + 0.5);
                episode.radius = cage.radius;
                episode.startTick = tick;
                episode.deadline = tick + cage.timeoutTicks;
                episode.training = true;
                bot.escape = episode;
            }

            cage.episodeStart = tick;
            return null;
        } else {
            if (cage.finishedThisEpisode < members.size() && tick - cage.episodeStart < cage.timeoutTicks + 40L) {
                return null;
            }

            int missing = Math.max(0, members.size() - cage.finishedThisEpisode);
            double average = (cage.timeThisEpisode + missing * cage.timeoutTicks / 20.0) / members.size();
            double rate = (double)cage.escapedThisEpisode / members.size();
            cage.results.add(new double[]{average, rate});
            if (cage.firstAverage < 0.0) {
                cage.firstAverage = average;
            }

            int n = cage.results.size();
            int window = Math.min(5, n);
            double first = 0.0;
            double recent = 0.0;
            double recentRate = 0.0;

            for (int i = 0; i < window; i++) {
                first += ((double[])cage.results.get(i))[0] / window;
                recent += ((double[])cage.results.get(n - 1 - i))[0] / window;
                recentRate += ((double[])cage.results.get(n - 1 - i))[1] / window;
            }

            String line = String.format(
                Locale.ROOT,
                "Kaefig %d [%s]%s: %d/%d draussen in %.1f s | letzte %d: %.1f s, %.0f %% draussen | erste %d: %.1f s (%+.0f %%)",
                cage.episode,
                lastLayout,
                cage.baseSeed != 0L ? " (seed " + cage.baseSeed + ")" : "",
                cage.escapedThisEpisode,
                members.size(),
                average,
                window,
                recent,
                recentRate * 100.0,
                window,
                first,
                (recent / Math.max(0.1, first) - 1.0) * 100.0
            );
            cage.history.add(line);
            cage.episodesLeft--;
            cage.episodeStart = -1L;
            cage.nextEpisodeAt = tick + 40L;
            if (cage.episodesLeft <= 0) {
                cage = null;
                BotSystemMod.skills().save();
                return line + " - Training fertig. Gelerntes gespeichert (/bot learn info).";
            } else {
                return line;
            }
        }
    }

    public static double buildCage(ServerLevel world, BlockPos centre, int size, int exits, long seed) {
        Random random = new Random(seed);
        int half = Math.max(5, size / 2);
        int height = 4 + random.nextInt(3);
        int y0 = centre.getY();
        BlockState wall = random.nextBoolean() ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
        BlockState floor = Blocks.SMOOTH_STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        MutableBlockPos cursor = new MutableBlockPos();
        int outer = half + 8;

        for (int x = -outer; x <= outer; x++) {
            for (int z = -outer; z <= outer; z++) {
                for (int y = 1; y <= 4; y++) {
                    world.setBlock(cursor.set(centre.getX() + x, y0 - y, centre.getZ() + z), floor, 2);
                }

                for (int y = 0; y <= height + 3; y++) {
                    world.setBlock(cursor.set(centre.getX() + x, y0 + y, centre.getZ() + z), air, 2);
                }
            }
        }

        for (int i = -half; i <= half; i++) {
            for (int y = 0; y < height; y++) {
                world.setBlock(cursor.set(centre.getX() + i, y0 + y, centre.getZ() - half), wall, 2);
                world.setBlock(cursor.set(centre.getX() + i, y0 + y, centre.getZ() + half), wall, 2);
                world.setBlock(cursor.set(centre.getX() - half, y0 + y, centre.getZ() + i), wall, 2);
                world.setBlock(cursor.set(centre.getX() + half, y0 + y, centre.getZ() + i), wall, 2);
            }
        }

        int inner = random.nextInt(3);

        for (int n = 0; n < inner; n++) {
            boolean alongX = random.nextBoolean();
            int offset = random.nextInt(half - 2) - (half - 2) / 2;
            int door = random.nextInt(half * 2 - 4) - (half - 2);

            for (int i = -half + 1; i < half; i++) {
                if (Math.abs(i - door) > 1) {
                    for (int y = 0; y < height - 1; y++) {
                        if (alongX) {
                            world.setBlock(cursor.set(centre.getX() + i, y0 + y, centre.getZ() + offset), wall, 2);
                        } else {
                            world.setBlock(cursor.set(centre.getX() + offset, y0 + y, centre.getZ() + i), wall, 2);
                        }
                    }
                }
            }
        }

        int made = Math.max(1, Math.min(4, exits));
        StringBuilder layout = new StringBuilder();

        for (int e = 0; e < made; e++) {
            int side = random.nextInt(4);
            int along = random.nextInt(half * 2 - 3) - (half - 2);
            int wx = side == 0 ? -half : (side == 1 ? half : along);
            int wz = side == 2 ? -half : (side == 3 ? half : along);
            int outX = side == 0 ? -1 : (side == 1 ? 1 : 0);
            int outZ = side == 2 ? -1 : (side == 3 ? 1 : 0);
            BlockPos gate = new BlockPos(centre.getX() + wx, y0, centre.getZ() + wz);
            int kind = random.nextInt(5);
            layout.append(layout.length() > 0 ? "+" : "").append(new String[]{"Luecke", "Tuer", "Treppe", "Tunnel", "Stufe"}[kind]);
            switch (kind) {
                case 0:
                    world.setBlock(gate, air, 2);
                    world.setBlock(gate.above(), air, 2);
                    break;
                case 1:
                    Direction facing = outX != 0 ? Direction.EAST : Direction.SOUTH;
                    world.setBlock(gate.above(), air, 2);
                    world.setBlock(
                        gate, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 2
                    );
                    world.setBlock(
                        gate.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2
                    );
                    break;
                case 2:
                    for (int step = 0; step < height; step++) {
                        BlockPos inside = gate.offset(-outX * (height - step), step, -outZ * (height - step));

                        for (int y = 0; y <= step; y++) {
                            world.setBlock(inside.below(step - y), wall, 2);
                        }

                        BlockPos outside = gate.offset(outX * (height - step), step, outZ * (height - step));

                        for (int y = 0; y <= step; y++) {
                            world.setBlock(outside.below(step - y), wall, 2);
                        }
                    }
                    break;
                case 3:
                    for (int t = -2; t <= 2; t++) {
                        BlockPos tunnel = gate.offset(outX * t, 0, outZ * t);
                        boolean step = Math.abs(t) == 2;
                        world.setBlock(tunnel.below(), air, 2);
                        world.setBlock(tunnel.below(2), step ? floor : air, 2);
                        if (!step) {
                            world.setBlock(tunnel.below(3), floor, 2);
                        }

                        world.setBlock(tunnel, t == 0 ? wall : air, 2);
                        world.setBlock(tunnel.above(), t == 0 ? wall : air, 2);
                    }
                    break;
                default:
                    world.setBlock(gate, Blocks.STONE_SLAB.defaultBlockState(), 2);
                    world.setBlock(gate.above(), air, 2);
                    world.setBlock(gate.above(2), air, 2);
            }
        }

        lastLayout = layout.toString();
        return half + 2.5;
    }

    public static final class Cage {
        public ServerLevel world;
        public BlockPos centre;
        public int size;
        public int exits;
        public int episodesLeft;
        public int episode;
        public long episodeStart = -1L;
        public long nextEpisodeAt;
        public long timeoutTicks;
        public double radius;
        public final List<UUID> bots = new ArrayList<>();
        public int escapedThisEpisode;
        public double timeThisEpisode;
        public int finishedThisEpisode;
        public double firstAverage = -1.0;
        public final List<String> history = new ArrayList<>();
        public long baseSeed;
        public final List<double[]> results = new ArrayList<>();
    }

    public static final class Episode {
        public Vec3 start;
        public double radius;
        public long startTick;
        public long deadline;
        public Vec3 goal;
        public final Set<Long> visited = new HashSet<>();
        public final List<ExitSense.Step> steps = new ArrayList<>();
        public Vec3 target;
        public long targetUntil;
        public boolean training;
    }

    public static final class Step {
        final double[] advantage;

        Step(double[] advantage) {
            this.advantage = advantage;
        }
    }
}
