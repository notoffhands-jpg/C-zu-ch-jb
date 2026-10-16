package de.fiden.botsystem.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.fiden.botsystem.BotSystemMod;
import de.fiden.botsystem.BotSystemPlugin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

public final class PathRecorder {
    private static final Path FILE = BotSystemPlugin.configDir().resolve("botsystem-paths.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_POINTS = 40000;
    private static final Map<UUID, PathRecorder.Session> ACTIVE = new HashMap<>();
    private static final Map<UUID, RecordedPath> LAST = new HashMap<>();
    private static Map<String, RecordedPath> saved;
    private static final List<PathRecorder.Show> SHOWS = new ArrayList<>();

    private PathRecorder() {
    }

    public static boolean recording(UUID player) {
        return ACTIVE.containsKey(player);
    }

    public static void start(ServerPlayer player) {
        ActionRecorder.start(player); // everything the player does, too (/bot record load)
        PathRecorder.Session session = new PathRecorder.Session(player.level().dimension().identifier().toString());
        ACTIVE.put(player.getUUID(), session);
        sample(session, player);
    }

    public static RecordedPath stop(UUID player) {
        ActionRecorder.stop(player);
        PathRecorder.Session session = ACTIVE.remove(player);
        if (session != null && session.points.size() >= 2) {
            RecordedPath path = new RecordedPath("", session.world, session.points);
            LAST.put(player, path);
            return path;
        } else {
            return null;
        }
    }

    public static void discard(UUID player) {
        ACTIVE.remove(player);
        LAST.remove(player);
    }

    public static RecordedPath last(UUID player) {
        return LAST.get(player);
    }

    public static void show(ServerPlayer player, RecordedPath path) {
        SHOWS.add(new PathRecorder.Show(player.getUUID(), path, player.level().getServer().getTickCount() + 400L));
    }

    private static void tickShows(MinecraftServer server) {
        long now = server.getTickCount();
        SHOWS.removeIf(showx -> now > showx.until());
        if (now % 10L == 0L) {
            for (PathRecorder.Show show : SHOWS) {
                ServerPlayer player = server.getPlayerList().getPlayer(show.player());
                if (player != null) {
                    RecordedPath path = show.path();
                    ServerLevel world = player.level();
                    double next = 0.0;

                    for (int i = 0; i < path.size(); i++) {
                        boolean jump = (path.flags[i] & 2) != 0;
                        if (!(path.s[i] < next) || jump || i == 0) {
                            next = path.s[i] + 1.0;
                            if (!(player.distanceToSqr(path.x[i], path.y[i], path.z[i]) > 9216.0)) {
                                ParticleOptions effect = i == 0
                                    ? new DustParticleOptions(3407667, 1.6F)
                                    : (jump ? new DustParticleOptions(16724787, 1.4F) : new DustParticleOptions(16777215, 0.9F));
                                world.sendParticles(player, effect, true, false, path.x[i], path.y[i] + 0.15, path.z[i], 1, 0.0, 0.0, 0.0, 0.0);
                            }
                        }
                    }
                }
            }
        }
    }

    public static void tick(MinecraftServer server) {
        if (!SHOWS.isEmpty()) {
            tickShows(server);
        }

        if (!ACTIVE.isEmpty()) {
            ACTIVE.entrySet()
                .removeIf(
                    entry -> {
                        ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                        if (player == null) {
                            if (entry.getValue().points.size() >= 2) {
                                LAST.put(entry.getKey(), new RecordedPath("", entry.getValue().world, entry.getValue().points));
                            }

                            return true;
                        } else if (!player.level().dimension().identifier().toString().equals(entry.getValue().world)) {
                            if (entry.getValue().points.size() >= 2) {
                                LAST.put(entry.getKey(), new RecordedPath("", entry.getValue().world, entry.getValue().points));
                            }

                            player.sendSystemMessage(
                                Component.literal("Aufnahme beendet (Dimension gewechselt). Speichern: /bot record save <name>")
                                    .withStyle(ChatFormatting.YELLOW),
                                false
                            );
                            return true;
                        } else {
                            sample(entry.getValue(), player);
                            if (entry.getValue().points.size() >= 40000) {
                                LAST.put(entry.getKey(), new RecordedPath("", entry.getValue().world, entry.getValue().points));
                                player.sendSystemMessage(
                                    Component.literal("Aufnahme voll (40000 Punkte) und beendet. Speichern: /bot record save <name>")
                                        .withStyle(ChatFormatting.YELLOW),
                                    false
                                );
                                return true;
                            }

                            if (player.tickCount % 20 == 0) {
                                player.sendSystemMessage(
                                    Component.literal("● Aufnahme laeuft - " + entry.getValue().points.size() + " Punkte").withStyle(ChatFormatting.RED), true
                                );
                            }

                            return false;
                        }
                    }
                );
        }
    }

    private static void sample(PathRecorder.Session session, ServerPlayer player) {
        Vec3 pos = player.position();
        boolean ground = player.onGround();
        int flags = (ground ? 1 : 0)
            | (player.isSprinting() ? 4 : 0)
            | (player.isShiftKeyDown() ? 8 : 0)
            | (player.onClimbable() ? 16 : 0)
            | (player.isInWater() ? 32 : 0);
        List<double[]> points = session.points;
        if (points.isEmpty()) {
            add(session, pos, flags | 1);
        } else {
            if (session.wasGround && !ground && session.prev != null) {
                double[] last = points.get(points.size() - 1);
                if (distanceSq(last, session.prev) > 0.0025) {
                    add(session, session.prev, session.prevFlags | 1);
                }

                session.takeoff = points.size() - 1;
                session.takeoffY = session.prev.y;
                session.takeoffSprint = (session.prevFlags & 4) != 0;
            }

            if (!ground && session.takeoff >= 0 && pos.y > session.takeoffY + 0.45) {
                double[] takeoff = points.get(session.takeoff);
                takeoff[3] = (int)takeoff[3] | 2 | (session.takeoffSprint ? 4 : 0);
                session.takeoff = -1;
            }

            double[] last = points.get(points.size() - 1);
            int toggles = 56;
            if (!session.wasGround && ground) {
                add(session, pos, flags);
                session.takeoff = -1;
            } else if (distanceSq(last, pos) >= 0.36 || (flags & toggles) != (session.keptFlags & toggles)) {
                add(session, pos, flags);
            }
        }

        session.wasGround = ground;
        session.prev = pos;
        session.prevFlags = flags;
    }

    private static void add(PathRecorder.Session session, Vec3 pos, int flags) {
        session.points.add(new double[]{round(pos.x), round(pos.y), round(pos.z), flags});
        session.keptFlags = flags;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double distanceSq(double[] a, Vec3 b) {
        double dx = a[0] - b.x;
        double dy = a[1] - b.y;
        double dz = a[2] - b.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static Map<String, RecordedPath> saved() {
        if (saved == null) {
            saved = new LinkedHashMap<>();
            if (Files.exists(FILE)) {
                try {
                    JsonObject root = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonObject paths = root.has("paths") ? root.getAsJsonObject("paths") : root;

                    for (Entry<String, JsonElement> entry : paths.entrySet()) {
                        JsonObject json = entry.getValue().getAsJsonObject();
                        JsonArray flat = json.getAsJsonArray("points");
                        List<double[]> points = new ArrayList<>(flat.size() / 4);

                        for (int i = 0; i + 3 < flat.size(); i += 4) {
                            points.add(
                                new double[]{
                                    flat.get(i).getAsDouble(), flat.get(i + 1).getAsDouble(), flat.get(i + 2).getAsDouble(), flat.get(i + 3).getAsInt()
                                }
                            );
                        }

                        if (points.size() >= 2) {
                            String name = json.has("name") ? json.get("name").getAsString() : entry.getKey();
                            String world = json.has("world") ? json.get("world").getAsString() : "minecraft:overworld";
                            saved.put(entry.getKey().toLowerCase(Locale.ROOT), new RecordedPath(name, world, points));
                        }
                    }
                } catch (Exception exception) {
                    BotSystemMod.LOGGER.warn("[BotSystem] botsystem-paths.json nicht lesbar: {}", exception.toString());
                }
            }
        }

        return saved;
    }

    public static RecordedPath get(String name) {
        return saved().get(name.toLowerCase(Locale.ROOT));
    }

    public static List<RecordedPath> all() {
        return new ArrayList<>(saved().values());
    }

    public static RecordedPath save(String name, RecordedPath path) {
        RecordedPath named = new RecordedPath(name, path.world, path.points());
        saved().put(name.toLowerCase(Locale.ROOT), named);
        write();
        return named;
    }

    public static boolean delete(String name) {
        boolean removed = saved().remove(name.toLowerCase(Locale.ROOT)) != null;
        if (removed) {
            write();
        }

        return removed;
    }

    private static void write() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject paths = new JsonObject();

        for (Entry<String, RecordedPath> entry : saved().entrySet()) {
            RecordedPath path = entry.getValue();
            JsonObject json = new JsonObject();
            json.addProperty("name", path.name);
            json.addProperty("world", path.world);
            json.addProperty("length", Math.round(path.length()));
            JsonArray flat = new JsonArray();

            for (double[] point : path.points()) {
                flat.add(point[0]);
                flat.add(point[1]);
                flat.add(point[2]);
                flat.add((int)point[3]);
            }

            json.add("points", flat);
            paths.add(entry.getKey(), json);
        }

        root.add("paths", paths);

        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(root).replaceAll(",\\s+(?=-?[0-9])", ","), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            BotSystemMod.LOGGER.warn("[BotSystem] botsystem-paths.json nicht schreibbar: {}", exception.toString());
        }
    }

    private static final class Session {
        final List<double[]> points = new ArrayList<>();
        final String world;
        boolean wasGround = true;
        int takeoff = -1;
        double takeoffY;
        boolean takeoffSprint;
        Vec3 prev;
        int prevFlags;
        int keptFlags;

        Session(String world) {
            this.world = world;
        }
    }

    private record Show(UUID player, RecordedPath path, long until) {
    }
}
