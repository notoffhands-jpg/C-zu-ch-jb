package de.fiden.botsystem.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Map.Entry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public final class NavMemory {
    private static final int GRID = 4;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, NavMemory.WorldNet> worlds = new HashMap<>();
    public int format;
    private transient Path file;
    private transient boolean dirty;

    public static String key(int x, int y, int z) {
        return Math.floorDiv(x, 4) + ":" + Math.floorDiv(y, 3) + ":" + Math.floorDiv(z, 4);
    }

    public static String key(Vec3 pos) {
        return key((int)Math.floor(pos.x), (int)Math.floor(pos.y), (int)Math.floor(pos.z));
    }

    public static String worldId(ServerLevel world) {
        return world.getWorld().getName() + "|" + world.dimension().identifier();
    }

    private NavMemory.WorldNet net(String world) {
        return this.worlds.computeIfAbsent(world, id -> new NavMemory.WorldNet());
    }

    public NavMemory.Node remember(String world, Vec3 pos) {
        NavMemory.WorldNet net = this.net(world);
        String id = key(pos);
        NavMemory.Node node = net.nodes.get(id);
        if (node == null) {
            node = new NavMemory.Node((int)Math.floor(pos.x), (int)Math.floor(pos.y), (int)Math.floor(pos.z));
            net.nodes.put(id, node);
        }

        node.visits++;
        this.dirty = true;
        return node;
    }

    public void link(String world, Vec3 from, Vec3 to, double ticks, boolean success) {
        if (from != null && to != null) {
            String a = key(from);
            String b = key(to);
            if (!a.equals(b)) {
                NavMemory.WorldNet net = this.net(world);
                this.remember(world, from);
                this.remember(world, to);
                List<NavMemory.Edge> list = net.edges.computeIfAbsent(a, id -> new ArrayList<>());
                NavMemory.Edge edge = null;

                for (NavMemory.Edge candidate : list) {
                    if (candidate.to.equals(b)) {
                        edge = candidate;
                        break;
                    }
                }

                if (edge == null) {
                    edge = new NavMemory.Edge(b, ticks);
                    list.add(edge);
                }

                if (success) {
                    edge.cost = edge.cost * 0.7 + Math.max(1.0, ticks) * 0.3;
                    edge.successes++;
                } else {
                    edge.failures++;
                }

                this.dirty = true;
            }
        }
    }

    public boolean has(String world) {
        NavMemory.WorldNet net = this.worlds.get(world);
        return net != null && net.nodes.size() > 2;
    }

    public int nodeCount(String world) {
        NavMemory.WorldNet net = this.worlds.get(world);
        return net == null ? 0 : net.nodes.size();
    }

    public int edgeCount(String world) {
        NavMemory.WorldNet net = this.worlds.get(world);
        if (net == null) {
            return 0;
        }

        int count = 0;

        for (List<NavMemory.Edge> list : net.edges.values()) {
            count += list.size();
        }

        return count;
    }

    private String nearest(NavMemory.WorldNet net, Vec3 pos, double limit) {
        String best = null;
        double bestDistance = limit * limit;

        for (Entry<String, NavMemory.Node> entry : net.nodes.entrySet()) {
            NavMemory.Node node = entry.getValue();
            double dx = node.x + 0.5 - pos.x;
            double dy = node.y - pos.y;
            double dz = node.z + 0.5 - pos.z;
            double distance = dx * dx + dy * dy * 4.0 + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entry.getKey();
            }
        }

        return best;
    }

    public List<Vec3> route(String world, Vec3 from, Vec3 to) {
        NavMemory.WorldNet net = this.worlds.get(world);
        if (net != null && !net.nodes.isEmpty()) {
            String start = this.nearest(net, from, 48.0);
            String goal = this.nearest(net, to, 48.0);
            if (start != null && goal != null && !start.equals(goal)) {
                Map<String, Double> best = new HashMap<>();
                Map<String, String> back = new HashMap<>();
                PriorityQueue<String> queue = new PriorityQueue<>(Comparator.comparingDouble(id -> best.getOrDefault(id, Double.MAX_VALUE)));
                best.put(start, 0.0);
                queue.add(start);

                while (!queue.isEmpty()) {
                    String current = queue.poll();
                    if (current.equals(goal)) {
                        break;
                    }

                    double base = best.getOrDefault(current, Double.MAX_VALUE);

                    for (NavMemory.Edge edge : net.edges.getOrDefault(current, List.of())) {
                        if (edge.usable() && net.nodes.containsKey(edge.to)) {
                            double next = base + edge.weight();
                            if (next < best.getOrDefault(edge.to, Double.MAX_VALUE)) {
                                best.put(edge.to, next);
                                back.put(edge.to, current);
                                queue.add(edge.to);
                            }
                        }
                    }
                }

                if (!back.containsKey(goal)) {
                    return List.of();
                }

                List<Vec3> route = new ArrayList<>();

                for (String cursor = goal; cursor != null && !cursor.equals(start); cursor = back.get(cursor)) {
                    NavMemory.Node node = net.nodes.get(cursor);
                    if (node != null) {
                        route.add(0, node.center());
                    }
                }

                return route;
            } else {
                return List.of();
            }
        } else {
            return List.of();
        }
    }

    public int clear(String world) {
        NavMemory.WorldNet net = this.worlds.remove(world);
        this.dirty = true;
        return net == null ? 0 : net.nodes.size();
    }

    public void bind(Path file) {
        this.file = file;
    }

    public void load(Path path) {
        this.file = path;

        try {
            if (Files.exists(path)) {
                NavMemory loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), NavMemory.class);
                if (loaded != null && loaded.worlds != null && loaded.format < 2) {
                    for (NavMemory.WorldNet net : loaded.worlds.values()) {
                        net.edges.forEach((from, list) -> {
                            NavMemory.Node a = net.nodes.get(from);
                            list.removeIf(edge -> {
                                NavMemory.Node b = net.nodes.get(edge.to);
                                return a == null || b == null || b.y < a.y - 3;
                            });
                        });
                    }
                }

                if (loaded != null && loaded.worlds != null && loaded.format < 3) {
                    loaded.worlds.keySet().removeIf(key -> !key.contains("|"));
                }

                if (loaded != null && loaded.worlds != null) {
                    this.worlds.clear();
                    this.worlds.putAll(loaded.worlds);
                }
            }
        } catch (IOException | RuntimeException var5) {
        }
    }

    public boolean save() {
        this.format = 3;
        if (this.dirty && this.file != null) {
            try {
                Files.createDirectories(this.file.getParent());
                Files.writeString(this.file, GSON.toJson(this), StandardCharsets.UTF_8);
                this.dirty = false;
                return true;
            } catch (IOException ignored) {
                return false;
            }
        } else {
            return false;
        }
    }

    public static final class Edge {
        public String to;
        public double cost = 20.0;
        public int successes;
        public int failures;

        Edge() {
        }

        Edge(String to, double cost) {
            this.to = to;
            this.cost = cost;
        }

        public double weight() {
            return this.cost * (1.0 + this.failures * 1.5) / (1.0 + Math.min(4, this.successes) * 0.15);
        }

        public boolean usable() {
            return this.failures < 3 || this.successes > this.failures;
        }
    }

    public static final class Node {
        public int x;
        public int y;
        public int z;
        public int visits;

        Node() {
        }

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Vec3 center() {
            return new Vec3(this.x + 0.5, this.y, this.z + 0.5);
        }
    }

    public static final class WorldNet {
        public Map<String, NavMemory.Node> nodes = new HashMap<>();
        public Map<String, List<NavMemory.Edge>> edges = new HashMap<>();
    }
}
