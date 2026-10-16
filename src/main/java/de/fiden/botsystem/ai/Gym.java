package de.fiden.botsystem.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.fiden.botsystem.BotSystemMod;
import de.fiden.botsystem.bot.BotDefaults;
import de.fiden.botsystem.bot.BotPlayer;
import de.fiden.botsystem.bot.BotProfile;
import de.fiden.botsystem.bot.Bots;
import de.fiden.botsystem.bot.Ground;
import de.fiden.botsystem.bot.NavMemory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class Gym {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Gym.Course> COURSES = new HashMap<>();
    private static final Gym.Stats STATS = new Gym.Stats();
    private static Path file;
    private static long lastReport;

    private Gym() {
    }

    public static Gym.Stats stats() {
        return STATS;
    }

    public static Gym.Course course(String world) {
        return COURSES.get(world);
    }

    public static Gym.Course build(ServerLevel world, BlockPos origin) {
        return build(world, origin, 0L);
    }

    public static Gym.Course build(ServerLevel world, BlockPos origin, long seed) {
        int baseY = origin.getY() - 1;
        int x0 = origin.getX();
        int z0 = origin.getZ();
        int length = 60;
        String[] kinds = new String[]{"mauer3", "mauer6", "luecke", "grube", "treppe", "tuer", "tunnel", "zickzack", "wasser", "mauer10"};
        BlockState floor = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState wall = Blocks.COBBLESTONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        MutableBlockPos cursor = new MutableBlockPos();
        Gym.Course course = new Gym.Course();
        course.world = NavMemory.worldId(world);

        for (int lane = 0; lane < kinds.length; lane++) {
            int zl = z0 + lane * 11;

            for (int x = x0 - 1; x <= x0 + length + 1; x++) {
                for (int z = zl - 1; z <= zl + 9; z++) {
                    for (int y = baseY - 4; y <= baseY; y++) {
                        world.setBlock(cursor.set(x, y, z), floor, 2);
                    }

                    for (int y = baseY + 1; y <= baseY + 14; y++) {
                        boolean border = z == zl - 1 || z == zl + 9;
                        world.setBlock(cursor.set(x, y, z), border ? wall : air, 2);
                    }
                }
            }

            int mid = x0 + length / 2;
            if (seed != 0L) {
                Random random = new Random(seed * 31L + lane);
                int count = 2 + random.nextInt(2);
                StringBuilder name = new StringBuilder();

                for (int n = 0; n < count; n++) {
                    int x = x0 + 12 + n * ((length - 22) / (count - 1)) + random.nextInt(3) - 1;
                    if (name.length() > 0) {
                        name.append('+');
                    }

                    name.append(randomObstacle(world, random, x, baseY, zl, floor, wall, air));
                }

                course.lanes.add(new Gym.Lane(name.toString(), x0 + 3.5, baseY + 1, zl + 4.5, x0 + length - 2.5, baseY + 1, zl + 4.5));
            } else {
                switch (kinds[lane]) {
                    case "mauer3":
                        fill(world, mid - 1, baseY + 1, zl, mid + 1, baseY + 3, zl + 8, wall);
                        break;
                    case "mauer6":
                        fill(world, mid - 1, baseY + 1, zl, mid + 1, baseY + 6, zl + 8, wall);
                        break;
                    case "mauer10":
                        fill(world, mid - 1, baseY + 1, zl, mid + 1, baseY + 10, zl + 8, wall);
                        break;
                    case "luecke":
                        fill(world, mid - 1, baseY - 4, zl, mid, baseY, zl + 8, air);
                        break;
                    case "grube":
                        fill(world, mid - 3, baseY - 3, zl, mid + 3, baseY, zl + 8, air);
                        break;
                    case "wasser":
                        fill(world, mid - 3, baseY - 2, zl, mid + 3, baseY, zl + 8, air);
                        fill(world, mid - 3, baseY - 2, zl, mid + 3, baseY, zl + 8, Blocks.WATER.defaultBlockState());
                        break;
                    case "treppe":
                        for (int step = 0; step < 4; step++) {
                            fill(world, mid - 8 + step, baseY + 1, zl, mid - 8 + step, baseY + 1 + step, zl + 8, floor);
                            fill(world, mid + 8 - step, baseY + 1, zl, mid + 8 - step, baseY + 1 + step, zl + 8, floor);
                        }

                        fill(world, mid - 4, baseY + 1, zl, mid + 4, baseY + 4, zl + 8, floor);
                        break;
                    case "tuer":
                        fill(world, mid - 1, baseY + 1, zl, mid + 1, baseY + 5, zl + 8, wall);
                        fill(world, mid - 1, baseY + 1, zl + 4, mid + 1, baseY + 2, zl + 4, air);
                        break;
                    case "tunnel":
                        fill(world, mid - 3, baseY + 1, zl, mid + 3, baseY + 7, zl + 8, wall);
                        fill(world, mid - 3, baseY + 1, zl + 3, mid + 3, baseY + 2, zl + 5, air);
                        break;
                    default:
                        fill(world, mid - 10, baseY + 1, zl, mid - 10, baseY + 5, zl + 8, wall);
                        fill(world, mid - 10, baseY + 1, zl, mid - 10, baseY + 5, zl + 1, air);
                        fill(world, mid, baseY + 1, zl, mid, baseY + 5, zl + 8, wall);
                        fill(world, mid, baseY + 1, zl + 7, mid, baseY + 5, zl + 8, air);
                        fill(world, mid + 10, baseY + 1, zl, mid + 10, baseY + 5, zl + 8, wall);
                        fill(world, mid + 10, baseY + 1, zl, mid + 10, baseY + 5, zl + 1, air);
                }

                course.lanes.add(new Gym.Lane(kinds[lane], x0 + 3.5, baseY + 1, zl + 4.5, x0 + length - 2.5, baseY + 1, zl + 4.5));
            }
        }

        int zEnd = z0 + kinds.length * 11;

        for (int side = 0; side < 2; side++) {
            int xa = side == 0 ? x0 - 8 : x0 + length + 2;
            int xb = side == 0 ? x0 - 2 : x0 + length + 8;

            for (int x = xa; x <= xb; x++) {
                for (int z = z0 - 3; z <= zEnd + 1; z++) {
                    for (int y = baseY - 4; y <= baseY; y++) {
                        world.setBlock(cursor.set(x, y, z), floor, 2);
                    }

                    for (int y = baseY + 1; y <= baseY + 14; y++) {
                        world.setBlock(cursor.set(x, y, z), air, 2);
                    }
                }
            }
        }

        course.ox = origin.getX();
        course.oy = origin.getY();
        course.oz = origin.getZ();
        course.seed = seed;
        Gym.Course previous = COURSES.get(course.world);
        if (previous != null) {
            course.shuffleMinutes = previous.shuffleMinutes;
        }

        COURSES.put(course.world, course);
        save();
        return course;
    }

    private static String randomObstacle(ServerLevel world, Random random, int x, int baseY, int zl, BlockState floor, BlockState wall, BlockState air) {
        switch (random.nextInt(10)) {
            case 0: {
                int height = 2 + random.nextInt(8);
                int thick = random.nextInt(3);
                fill(world, x, baseY + 1, zl, x + thick, baseY + height, zl + 8, wall);
                return "mauer" + height;
            }
            case 1: {
                int height = 4 + random.nextInt(5);
                int door = random.nextInt(8);
                int width = 1 + random.nextInt(2);
                fill(world, x, baseY + 1, zl, x + 1, baseY + height, zl + 8, wall);
                fill(world, x, baseY + 1, zl + door, x + 1, baseY + 2, zl + Math.min(8, door + width - 1), air);
                return "durchgang";
            }
            case 2: {
                int width = 1 + random.nextInt(3);
                fill(world, x, baseY - 4, zl, x + width - 1, baseY, zl + 8, air);
                return "graben" + width;
            }
            case 3: {
                int width = 4 + random.nextInt(4);
                int depth = 2 + random.nextInt(2);
                fill(world, x - width / 2, baseY - depth + 1, zl, x + width / 2, baseY, zl + 8, air);
                return "grube" + depth;
            }
            case 4: {
                int width = 3 + random.nextInt(5);
                fill(world, x - width / 2, baseY - 1, zl, x + width / 2, baseY, zl + 8, air);
                fill(world, x - width / 2, baseY - 1, zl, x + width / 2, baseY, zl + 8, Blocks.WATER.defaultBlockState());
                return "wasser";
            }
            case 5: {
                int height = 2 + random.nextInt(4);

                for (int step = 0; step < height; step++) {
                    fill(world, x - height - 1 + step, baseY + 1, zl, x - height - 1 + step, baseY + 1 + step, zl + 8, floor);
                    fill(world, x + height + 1 - step, baseY + 1, zl, x + height + 1 - step, baseY + 1 + step, zl + 8, floor);
                }

                fill(world, x - 1, baseY + 1, zl, x + 1, baseY + height, zl + 8, floor);
                return "treppe" + height;
            }
            case 6: {
                int door = 1 + random.nextInt(7);
                fill(world, x - 2, baseY + 1, zl, x + 2, baseY + 7, zl + 8, wall);
                fill(world, x - 2, baseY + 1, zl + door - 1, x + 2, baseY + 2, zl + door, air);
                return "tunnel";
            }
            case 7:
                for (int i = 0; i < 14; i++) {
                    int px = x - 4 + random.nextInt(9);
                    int pz = zl + random.nextInt(9);
                    fill(world, px, baseY + 1, pz, px, baseY + 3, pz, wall);
                }

                return "pfeiler";
            case 8:
                boolean left = random.nextBoolean();
                fill(world, x - 3, baseY + 1, zl, x - 3, baseY + 5, zl + 8, wall);
                fill(world, x - 3, baseY + 1, left ? zl : zl + 7, x - 3, baseY + 5, left ? zl + 1 : zl + 8, air);
                fill(world, x + 3, baseY + 1, zl, x + 3, baseY + 5, zl + 8, wall);
                fill(world, x + 3, baseY + 1, left ? zl + 7 : zl, x + 3, baseY + 5, left ? zl + 8 : zl + 1, air);
                return "schikane";
            default:
                fill(world, x - 3, baseY + 4, zl, x + 3, baseY + 4, zl + 8, wall);
                fill(world, x - 1, baseY + 1, zl, x + 1, baseY + 1, zl + 8, Blocks.STONE_SLAB.defaultBlockState());
                return "decke";
        }
    }

    public static String tickShuffle(MinecraftServer server, Bots bots, long tick) {
        for (Gym.Course course : new ArrayList<>(COURSES.values())) {
            if (course.shuffleMinutes > 0) {
                if (course.nextShuffle < 0L) {
                    course.nextShuffle = tick + course.shuffleMinutes * 1200L;
                } else if (tick >= course.nextShuffle) {
                    ServerLevel world = null;

                    for (ServerLevel candidate : server.getAllLevels()) {
                        if (NavMemory.worldId(candidate).equals(course.world)) {
                            world = candidate;
                        }
                    }

                    if (world != null) {
                        int lanes = Math.max(1, course.lanes.size());

                        for (BotPlayer bot : bots.all()) {
                            if (bot.level() == world) {
                                double x = bot.getX();
                                double z = bot.getZ();
                                if (x >= course.ox - 1 && x <= course.ox + 61 && z >= course.oz - 1 && z <= course.oz + lanes * 11) {
                                    bot.teleportTo(world, course.ox - 5.0, course.oy, z, Set.of(), bot.getYRot(), 0.0F, false);
                                    bot.path = null;
                                    bot.navRoute = null;
                                    bot.walkGoal = null;
                                    bot.gymRepsLeft = 0;
                                }
                            }
                        }

                        int minutes = course.shuffleMinutes;
                        Gym.Course fresh = build(world, new BlockPos(course.ox, course.oy, course.oz), ThreadLocalRandom.current().nextLong() | 1L);
                        fresh.shuffleMinutes = minutes;
                        fresh.nextShuffle = tick + minutes * 1200L;
                        save();
                        StringBuilder names = new StringBuilder();

                        for (Gym.Lane lane : fresh.lanes) {
                            names.append(lane.name).append("  ");
                        }

                        return "Neuer Zufalls-Parcours (alle " + minutes + " min): " + names.toString().trim();
                    }
                }
            }
        }

        return null;
    }

    public static boolean setShuffle(String world, int minutes) {
        Gym.Course course = COURSES.get(world);
        if (course == null) {
            return false;
        }

        course.shuffleMinutes = minutes;
        course.nextShuffle = -1L;
        save();
        return true;
    }

    private static void fill(ServerLevel world, int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
        MutableBlockPos cursor = new MutableBlockPos();

        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    world.setBlock(cursor.set(x, y, z), state, 2);
                }
            }
        }
    }

    public static Vec3 pick(BotPlayer bot) {
        ServerLevel world = bot.level();
        Gym.Course course = COURSES.get(NavMemory.worldId(world));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (course == null || course.lanes.isEmpty() || bot.gymCentre != null && !bot.gymUseCourse) {
            Vec3 centre = bot.gymCentre != null ? bot.gymCentre : bot.position();
            double radius = Math.max(8.0, bot.gymRadius);
            double[] area = new double[]{centre.x - radius, centre.z - radius, centre.x + radius, centre.z + radius};
            Vec3 best = null;
            double bestScore = -1.0;

            for (int attempt = 0; attempt < 6; attempt++) {
                Vec3 spot = Ground.randomDryNear(world, area, bot.getX(), bot.getZ(), radius, bot.getY(), random);
                if (spot != null) {
                    double climb = Math.abs(spot.y - bot.getY());
                    double far = Math.sqrt(bot.distanceToSqr(spot));
                    double score = climb * 2.0 + Math.min(30.0, far) + random.nextDouble() * 6.0;
                    if (score > bestScore) {
                        bestScore = score;
                        best = spot;
                    }
                }
            }

            bot.gymLane = "frei";
            bot.gymRun = best != null && Math.sqrt(bot.distanceToSqr(best)) >= 12.0;
            return best;
        } else {
            Gym.Lane lane = null;
            if (bot.gymLane != null && bot.gymRepsLeft > 0) {
                for (Gym.Lane candidate : course.lanes) {
                    if (candidate.name.equals(bot.gymLane)) {
                        lane = candidate;
                    }
                }
            }

            if (lane == null) {
                lane = weakestLane(bot, course, random);
                bot.gymRepsLeft = Math.max(1, BotDefaults.get().gymReps);
            }

            bot.gymLane = lane.name;
            double dStart = bot.distanceToSqr(lane.start());
            double dEnd = bot.distanceToSqr(lane.end());
            boolean onLane = Math.abs(bot.getZ() - lane.sz) < 5.0 && bot.getX() > lane.sx - 4.0 && bot.getX() < lane.ex + 4.0;
            if (!onLane) {
                bot.gymRun = false;
                return dStart < dEnd ? lane.start() : lane.end();
            } else {
                bot.gymRun = true;
                bot.gymRepsLeft--;
                return dStart < dEnd ? lane.end() : lane.start();
            }
        }
    }

    private static Gym.Lane weakestLane(BotPlayer bot, Gym.Course course, ThreadLocalRandom random) {
        BotProfile profile = bot.profile();
        List<Gym.Lane> lanes = course.lanes;
        double total = 0.0;
        double[] weight = new double[lanes.size()];

        for (int i = 0; i < lanes.size(); i++) {
            Gym.Lane lane = lanes.get(i);
            String key = BotProfile.routeKey(lane.sx, lane.sz, lane.ex, lane.ez);
            Double mine = profile.best(key);
            Double record = STATS.laneBest.get(lane.name);
            double w = mine == null ? 3.0 : (record == null ? 1.0 : Math.max(0.4, Math.min(3.0, mine / Math.max(1.0, record))));
            int occupants = 0;

            for (BotPlayer other : BotSystemMod.bots().all()) {
                if (other != bot && other.gymActive && lane.name.equals(other.gymLane)) {
                    occupants++;
                }
            }

            w /= 1.0 + occupants * 2.0;
            if (occupants >= Math.max(1, BotDefaults.get().gymLaneCap)) {
                w *= 0.02;
            }

            double near = Math.min(bot.distanceToSqr(lane.start()), bot.distanceToSqr(lane.end()));
            w /= 1.0 + Math.sqrt(near) / 25.0;
            weight[i] = w;
            total += w;
        }

        double roll = random.nextDouble() * total;

        for (int i = 0; i < lanes.size(); i++) {
            roll -= weight[i];
            if (roll <= 0.0) {
                return lanes.get(i);
            }
        }

        return lanes.get(lanes.size() - 1);
    }

    public static void lap(BotPlayer bot, double seconds, long tick) {
        if (STATS.startTick < 0L) {
            STATS.startTick = tick;
        }

        STATS.laps++;
        if (tick - STATS.startTick < 1200L) {
            STATS.firstMinuteSum += seconds;
            STATS.firstMinuteCount++;
        }

        STATS.recentSum = STATS.recentSum * 0.97 + seconds;
        STATS.recentCount = (int)Math.min(200.0, STATS.recentCount * 0.97 + 1.0);
        if (bot.gymLane != null && !bot.gymLane.equals("frei")) {
            double[] first = STATS.laneFirst.computeIfAbsent(bot.gymLane, key -> new double[2]);
            if (first[1] < 5.0) {
                first[0] += seconds;
                first[1]++;
            }

            Double recent = STATS.laneRecent.get(bot.gymLane);
            STATS.laneRecent.put(bot.gymLane, recent == null ? seconds : recent * 0.8 + seconds * 0.2);
            Double best = STATS.laneBest.get(bot.gymLane);
            if (best == null || seconds < best) {
                STATS.laneBest.put(bot.gymLane, seconds);
                STATS.records++;
            }
        }
    }

    public static String report(int active) {
        double first = STATS.firstMinuteCount == 0 ? 0.0 : STATS.firstMinuteSum / STATS.firstMinuteCount;
        double recent = STATS.recentCount == 0 ? 0.0 : STATS.recentSum / Math.max(1.0, sumWeights());
        if (!(first <= 0.0) && !(recent <= 0.0)) {
            String.format(Locale.ROOT, " (%+.0f %% gegenueber der ersten Minute)", (recent / first - 1.0) * 100.0);
        } else {
            String change = "";
        }

        StringBuilder lanes = new StringBuilder();
        STATS.laneFirst
            .entrySet()
            .stream()
            .sorted(Entry.comparingByKey())
            .forEach(
                entry -> {
                    double[] opening = entry.getValue();
                    Double now = STATS.laneRecent.get(entry.getKey());
                    Double best = STATS.laneBest.get(entry.getKey());
                    if (opening[1] > 0.0 && now != null) {
                        double start = opening[0] / opening[1];
                        lanes.append(entry.getKey())
                            .append(' ')
                            .append(String.format(Locale.ROOT, "%.1f>%.1fs", start, now))
                            .append(String.format(Locale.ROOT, "(%+.0f%%, best %.1f)", (now / start - 1.0) * 100.0, best == null ? now : best))
                            .append("  ");
                    }
                }
            );
        return "Trainingslager: "
            + active
            + " Bot(s), "
            + STATS.laps
            + " Runden"
            + (STATS.laps > 0L ? ", Schnitt " + String.format(Locale.ROOT, "%.1f", recent) + " s" : "")
            + (lanes.length() > 0 ? " | je Bahn (Anfang>jetzt): " + lanes.toString().trim() : "");
    }

    private static double sumWeights() {
        double sum = 0.0;
        double weight = 1.0;

        for (int i = 0; i < Math.min(200L, STATS.laps); i++) {
            sum += weight;
            weight *= 0.97;
        }

        return Math.max(1.0, sum);
    }

    public static boolean dueReport(long tick) {
        if (tick - lastReport >= 1200L) {
            lastReport = tick;
            return true;
        } else {
            return false;
        }
    }

    public static void resetStats() {
        STATS.laps = 0L;
        STATS.firstMinuteSum = 0.0;
        STATS.firstMinuteCount = 0;
        STATS.recentSum = 0.0;
        STATS.recentCount = 0;
        STATS.startTick = -1L;
        STATS.records = 0;
        STATS.laneBest.clear();
        STATS.laneFirst.clear();
        STATS.laneRecent.clear();
    }

    public static void load(Path path) {
        file = path;

        try {
            if (Files.exists(path)) {
                Gym.Course[] loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Gym.Course[].class);
                if (loaded != null) {
                    for (Gym.Course course : loaded) {
                        if (course != null && course.world != null) {
                            COURSES.put(course.world, course);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException var6) {
        }
    }

    public static void save() {
        if (file != null) {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(COURSES.values().toArray(new Gym.Course[0])), StandardCharsets.UTF_8);
            } catch (IOException var1) {
            }
        }
    }

    public static boolean remove(String world) {
        boolean removed = COURSES.remove(world) != null;
        save();
        return removed;
    }

    public static final class Course {
        public String world;
        public List<Gym.Lane> lanes = new ArrayList<>();
        public int ox;
        public int oy;
        public int oz;
        public long seed;
        public int shuffleMinutes;
        public transient long nextShuffle = -1L;
    }

    public static final class Lane {
        public String name;
        public double sx;
        public double sy;
        public double sz;
        public double ex;
        public double ey;
        public double ez;

        Lane() {
        }

        Lane(String name, double sx, double sy, double sz, double ex, double ey, double ez) {
            this.name = name;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.ex = ex;
            this.ey = ey;
            this.ez = ez;
        }

        public Vec3 start() {
            return new Vec3(this.sx, this.sy, this.sz);
        }

        public Vec3 end() {
            return new Vec3(this.ex, this.ey, this.ez);
        }
    }

    public static final class Stats {
        public long laps;
        public double firstMinuteSum;
        public int firstMinuteCount;
        public double recentSum;
        public int recentCount;
        public long startTick = -1L;
        public int records;
        public final Map<String, Double> laneBest = new HashMap<>();
        public final Map<String, double[]> laneFirst = new HashMap<>();
        public final Map<String, Double> laneRecent = new HashMap<>();
    }
}
