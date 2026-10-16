package de.fiden.botsystem.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The PvP tier table (MCTiers style: HT3 best offered, LT5 the lowest *tested* tier - still a
 * real PvP player, not someone who knows nothing). Every combat number a bot uses comes from
 * here, interpolated by its skill value, and the whole table is a setting: botsystem-tiers.json,
 * or /bot tiers set <tier> <wert> <zahl>.
 */
public final class Tiers {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = BotDefaults.dir().resolve("botsystem-tiers.json");
    private static List<Tier> table;

    /** One row. Chances are 0..1, reach in blocks (eye to hitbox), reaction in ticks. */
    public static final class Tier {
        public String name;
        public double skill;
        /** Melee swing reach range; each swing rolls inside it, mostly around the middle. */
        public double reachMin;
        public double reachMax;
        /** Share of swings that connect when in reach (aim). */
        public double accuracy;
        /** Ticks to react to a new target / threat. */
        public double reaction;
        /** How often a hit is a jump-crit when it fits. */
        public double crit;
        /** W-tap / sprint reset before a hit (more knockback, combos). */
        public double wtap;
        /** Swapping to an axe to stun (disable) a raised shield. */
        public double stun;
        /** Blocking with the shield between own swings. */
        public double shield;
        /** Strafing while trading hits (0 = straight at you, 1 = constant circling). */
        public double strafe;
        /** Extra ticks of attack-timing slop on top of the weapon cooldown. */
        public double timingSlop;

        Tier(String name, double skill, double reachMin, double reachMax, double accuracy, double reaction, double crit, double wtap,
            double stun, double shield, double strafe, double timingSlop) {
            this.name = name;
            this.skill = skill;
            this.reachMin = reachMin;
            this.reachMax = reachMax;
            this.accuracy = accuracy;
            this.reaction = reaction;
            this.crit = crit;
            this.wtap = wtap;
            this.stun = stun;
            this.shield = shield;
            this.strafe = strafe;
            this.timingSlop = timingSlop;
        }
    }

    private Tiers() {
    }

    public static List<Tier> defaults() {
        List<Tier> rows = new ArrayList<>();
        //                  name    skill reachMin reachMax acc  react crit wtap stun shield strafe slop
        rows.add(new Tier("Noob", 0.16, 1.6, 2.2, 0.70, 8, 0.15, 0.05, 0.05, 0.10, 0.20, 4));
        rows.add(new Tier("Okay", 0.34, 1.9, 2.4, 0.78, 6, 0.35, 0.25, 0.25, 0.20, 0.40, 3));
        rows.add(new Tier("LT5", 0.50, 2.0, 2.5, 0.85, 5, 0.55, 0.50, 0.50, 0.30, 0.55, 2));
        rows.add(new Tier("HT5", 0.60, 2.0, 2.5, 0.87, 5, 0.60, 0.60, 0.60, 0.35, 0.60, 2));
        rows.add(new Tier("LT4", 0.72, 2.4, 2.6, 0.89, 4, 0.68, 0.70, 0.70, 0.40, 0.65, 1));
        rows.add(new Tier("HT4", 0.84, 2.55, 2.65, 0.91, 4, 0.74, 0.80, 0.80, 0.45, 0.70, 1));
        rows.add(new Tier("LT3", 0.92, 2.6, 2.8, 0.93, 3, 0.80, 0.88, 0.88, 0.50, 0.75, 0));
        rows.add(new Tier("HT3", 1.00, 2.8, 3.0, 0.95, 3, 0.85, 0.93, 0.93, 0.55, 0.80, 0));
        return rows;
    }

    public static List<Tier> table() {
        if (table == null) {
            table = load();
        }

        return table;
    }

    private static List<Tier> load() {
        try {
            if (Files.exists(FILE)) {
                Tier[] rows = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), Tier[].class);
                if (rows != null && rows.length >= 2) {
                    List<Tier> list = new ArrayList<>(List.of(rows));
                    list.sort((a, b) -> Double.compare(a.skill, b.skill));
                    return list;
                }
            }
        } catch (IOException | RuntimeException broken) {
        }

        List<Tier> fresh = defaults();
        save(fresh);
        return fresh;
    }

    public static void reload() {
        table = load();
    }

    public static void save() {
        save(table());
    }

    private static void save(List<Tier> rows) {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(rows), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public static void reset() {
        table = defaults();
        save(table);
    }

    public static Tier byName(String name) {
        for (Tier tier : table()) {
            if (tier.name.equalsIgnoreCase(name)) {
                return tier;
            }
        }

        return null;
    }

    /** The table row blended for a skill value between two tiers. */
    public static Tier at(double skill) {
        List<Tier> rows = table();
        if (skill <= rows.get(0).skill) {
            return rows.get(0);
        }

        for (int i = 1; i < rows.size(); i++) {
            Tier high = rows.get(i);
            if (skill <= high.skill) {
                Tier low = rows.get(i - 1);
                double t = (skill - low.skill) / Math.max(1.0E-6, high.skill - low.skill);
                return new Tier(
                    t < 0.5 ? low.name : high.name,
                    skill,
                    lerp(low.reachMin, high.reachMin, t),
                    lerp(low.reachMax, high.reachMax, t),
                    lerp(low.accuracy, high.accuracy, t),
                    lerp(low.reaction, high.reaction, t),
                    lerp(low.crit, high.crit, t),
                    lerp(low.wtap, high.wtap, t),
                    lerp(low.stun, high.stun, t),
                    lerp(low.shield, high.shield, t),
                    lerp(low.strafe, high.strafe, t),
                    lerp(low.timingSlop, high.timingSlop, t)
                );
            }
        }

        return rows.get(rows.size() - 1);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** Name of the tier closest to a skill value. */
    public static String label(double skill) {
        Tier best = table().get(0);
        for (Tier tier : table()) {
            if (Math.abs(tier.skill - skill) < Math.abs(best.skill - skill)) {
                best = tier;
            }
        }

        return best.name;
    }

    /**
     * "ht4", "ht4-ht3", "lt5-lt4", "random" -> the skill values of every tier in that range
     * (a random pick among them per bot). Null when it isn't a tier (range) at all.
     */
    public static List<Double> range(String text) {
        String value = text.toLowerCase(Locale.ROOT).trim();
        if (value.equals("random") || value.equals("zufall")) {
            List<Double> all = new ArrayList<>();
            for (Tier tier : table()) {
                if (tier.skill >= 0.5) {
                    all.add(tier.skill);
                }
            }

            return all;
        }

        String[] ends = value.split("-|\\.\\.|:|/", 2);
        Tier a = byName(ends[0]);
        Tier b = ends.length > 1 ? byName(ends[1]) : a;
        if (a == null || b == null) {
            return null;
        }

        double low = Math.min(a.skill, b.skill);
        double high = Math.max(a.skill, b.skill);
        List<Double> picks = new ArrayList<>();
        for (Tier tier : table()) {
            if (tier.skill >= low - 1.0E-6 && tier.skill <= high + 1.0E-6) {
                picks.add(tier.skill);
            }
        }

        return picks;
    }

    public static double pick(List<Double> skills) {
        return skills.get(ThreadLocalRandom.current().nextInt(skills.size()));
    }
}
