package de.fiden.botsystem.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.fiden.botsystem.ai.ExitSense;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ThreadLocalRandom;

public final class SkillMemory {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public Map<String, Map<String, SkillMemory.Stat>> skills = new HashMap<>();
    public Map<String, Double> sharedStyle = new HashMap<>();
    public int sharedUpdates;
    public double[] explore;
    public double exploreRate = 0.3;
    public double exploreTemperature = 0.45;
    public double exploreBaseline;
    public int exploreEpisodes;
    public int exploreEscapes;
    public Map<String, BotProfile> bots = new HashMap<>();
    public double curiosity = 0.08;
    private transient Path file;
    private transient boolean dirty;

    private Map<String, SkillMemory.Stat> table(String situation) {
        return this.skills.computeIfAbsent(situation, key -> new HashMap<>());
    }

    public String pick(String situation, List<String> candidates) {
        if (candidates.isEmpty()) {
            return "repath";
        }

        Map<String, SkillMemory.Stat> table = this.table(situation);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<String> untried = new ArrayList<>();

        for (String action : candidates) {
            if (!table.containsKey(action)) {
                untried.add(action);
            }
        }

        if (!untried.isEmpty() && random.nextDouble() < 0.5) {
            return untried.get(random.nextInt(untried.size()));
        }

        if (random.nextDouble() < this.curiosity) {
            return candidates.get(random.nextInt(candidates.size()));
        }

        String best = candidates.get(0);
        double bestScore = -1.0;

        for (String action : candidates) {
            SkillMemory.Stat stat = table.get(action);
            double score = stat == null ? 0.5 : stat.score();
            if (score > bestScore) {
                bestScore = score;
                best = action;
            }
        }

        return best;
    }

    public void report(String situation, String action, boolean success, long ticks) {
        SkillMemory.Stat stat = this.table(situation).computeIfAbsent(action, key -> new SkillMemory.Stat());
        stat.tries++;
        if (success) {
            stat.wins++;
            stat.ticks = stat.ticks * 0.8 + Math.max(1.0, ticks) * 0.2;
        } else {
            stat.ticks = stat.ticks * 0.9 + 12.0;
        }

        this.dirty = true;
    }

    public BotProfile profile(String name) {
        this.dirty = true;
        BotProfile existing = this.bots.get(name);
        if (existing != null) {
            return existing;
        }

        BotProfile fresh = new BotProfile();
        BotProfile coach = this.coach();
        this.bots.put(name, fresh);
        return fresh;
    }

    public BotProfile coach() {
        BotProfile best = null;
        double bestScore = -1.0;

        for (BotProfile profile : this.bots.values()) {
            if (profile.laps >= 20) {
                double score = (profile.improvements + 1.0) / (profile.laps + 5.0) + Math.min(1.0, profile.laps / 400.0);
                if (score > bestScore) {
                    bestScore = score;
                    best = profile;
                }
            }
        }

        return best;
    }

    public String coachName() {
        BotProfile coach = this.coach();
        if (coach == null) {
            return null;
        }

        for (Entry<String, BotProfile> entry : this.bots.entrySet()) {
            if (entry.getValue() == coach) {
                return entry.getKey();
            }
        }

        return null;
    }

    public double shared(String knob) {
        return this.sharedStyle.getOrDefault(knob, 1.0);
    }

    public void absorb(BotProfile profile) {
        for (String knob : BotProfile.KNOBS) {
            double effective = profile.get(knob);
            double current = this.shared(knob);
            this.sharedStyle.put(knob, Math.max(0.3, Math.min(2.0, current + (effective - current) * 0.25)));
        }

        this.sharedUpdates++;
        this.dirty = true;
    }

    public double[] exploreWeights() {
        int size = ExitSense.FEATURES.length;
        if (this.explore != null && this.explore.length < size) {
            this.explore = Arrays.copyOf(this.explore, size);
        }

        if (this.explore == null || this.explore.length != size) {
            double[] fresh = new double[size];
            fresh[2] = 1.0;
            fresh[8] = 0.6;
            fresh[9] = 0.3;
            fresh[10] = 0.8;
            this.explore = fresh;
        }

        return this.explore;
    }

    public boolean hasProfile(String name) {
        return this.bots.containsKey(name);
    }

    public void touch() {
        this.dirty = true;
    }

    public int situations() {
        return this.skills.size();
    }

    public int entries() {
        int count = 0;

        for (Map<String, SkillMemory.Stat> table : this.skills.values()) {
            count += table.size();
        }

        return count;
    }

    public List<String> summary() {
        List<String> lines = new ArrayList<>();

        for (Entry<String, Map<String, SkillMemory.Stat>> entry : this.skills.entrySet()) {
            String best = null;
            double bestScore = -1.0;
            int tries = 0;

            for (Entry<String, SkillMemory.Stat> action : entry.getValue().entrySet()) {
                tries += action.getValue().tries;
                double score = action.getValue().score();
                if (score > bestScore) {
                    bestScore = score;
                    best = action.getKey();
                }
            }

            SkillMemory.Stat stat = best == null ? null : entry.getValue().get(best);
            lines.add(
                entry.getKey()
                    + ": "
                    + best
                    + " ("
                    + (stat == null ? 0 : stat.wins)
                    + "/"
                    + (stat == null ? 0 : stat.tries)
                    + " geschafft, "
                    + Math.round(stat == null ? 0.0 : stat.ticks)
                    + " Ticks, "
                    + tries
                    + " Versuche gesamt)"
            );
        }

        lines.sort(String::compareTo);
        return lines;
    }

    public int clear() {
        int count = this.entries() + this.bots.size();
        this.skills.clear();
        this.bots.clear();
        this.sharedStyle.clear();
        this.sharedUpdates = 0;
        this.explore = null;
        this.exploreBaseline = 0.0;
        this.exploreEpisodes = 0;
        this.exploreEscapes = 0;
        this.dirty = true;
        return count;
    }

    public void load(Path path) {
        this.file = path;

        try {
            if (Files.exists(path)) {
                SkillMemory loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), SkillMemory.class);
                if (loaded != null && loaded.skills != null) {
                    this.skills.clear();
                    this.skills.putAll(loaded.skills);
                    this.curiosity = loaded.curiosity;
                    if (loaded.sharedStyle != null) {
                        this.sharedStyle.clear();
                        this.sharedStyle.putAll(loaded.sharedStyle);
                    }

                    this.sharedUpdates = loaded.sharedUpdates;
                    this.explore = loaded.explore;
                    this.exploreRate = loaded.exploreRate > 0.0 ? loaded.exploreRate : this.exploreRate;
                    this.exploreTemperature = loaded.exploreTemperature > 0.0 ? loaded.exploreTemperature : this.exploreTemperature;
                    this.exploreBaseline = loaded.exploreBaseline;
                    this.exploreEpisodes = loaded.exploreEpisodes;
                    this.exploreEscapes = loaded.exploreEscapes;
                    if (loaded.bots != null) {
                        this.bots.clear();
                        this.bots.putAll(loaded.bots);
                    }
                }
            }
        } catch (IOException | RuntimeException var3) {
        }
    }

    public boolean save() {
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

    public boolean exportTo(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    public int importFrom(Path path) {
        try {
            SkillMemory other = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), SkillMemory.class);
            if (other != null && other.skills != null) {
                int merged = 0;

                for (Entry<String, Map<String, SkillMemory.Stat>> entry : other.skills.entrySet()) {
                    Map<String, SkillMemory.Stat> table = this.table(entry.getKey());

                    for (Entry<String, SkillMemory.Stat> action : entry.getValue().entrySet()) {
                        SkillMemory.Stat mine = table.computeIfAbsent(action.getKey(), key -> new SkillMemory.Stat());
                        SkillMemory.Stat theirs = action.getValue();
                        double weight = theirs.tries + mine.tries == 0 ? 0.5 : (double)theirs.tries / (theirs.tries + mine.tries);
                        mine.ticks = mine.ticks * (1.0 - weight) + theirs.ticks * weight;
                        mine.tries = mine.tries + theirs.tries;
                        mine.wins = mine.wins + theirs.wins;
                        merged++;
                    }
                }

                if (other.bots != null) {
                    other.bots.forEach((name, profile) -> this.bots.putIfAbsent(name, profile));
                }

                this.dirty = true;
                return merged;
            } else {
                return 0;
            }
        } catch (IOException | RuntimeException ignored) {
            return 0;
        }
    }

    public static final class Stat {
        public int tries;
        public int wins;
        public double ticks = 40.0;

        double score() {
            double rate = (this.wins + 1.0) / (this.tries + 2.0);
            return rate * 40.0 / Math.max(8.0, this.ticks);
        }
    }
}
