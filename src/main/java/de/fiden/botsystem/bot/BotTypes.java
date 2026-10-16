package de.fiden.botsystem.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import de.fiden.botsystem.BotSystemPlugin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bot types: a named bundle of name style, skin and fighting style.
 * {@code /bot type create null randomnick 10 13 chaos skin black}, then
 * {@code /bot spawn 20 iron Rot null} spawns 20 bots that all follow it.
 */
public final class BotTypes {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file = BotSystemPlugin.configDir().resolve("botsystem-types.json");
    private final Map<String, BotTypes.BotType> types = new LinkedHashMap<>();

    public BotTypes() {
        this.load();
    }

    public BotTypes.BotType get(String name) {
        if (name == null) {
            return null;
        }

        String key = name.toLowerCase(Locale.ROOT);
        if (key.startsWith("type:") || key.startsWith("typ:")) {
            key = key.substring(key.indexOf(':') + 1);
        }

        return this.types.get(key);
    }

    public List<String> names() {
        List<String> names = new ArrayList<>();
        this.types.values().forEach(type -> names.add(type.name));
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public void save(BotTypes.BotType type) {
        this.types.put(type.name.toLowerCase(Locale.ROOT), type);
        this.store();
    }

    public boolean delete(String name) {
        boolean removed = this.types.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (removed) {
            this.store();
        }

        return removed;
    }

    private void load() {
        if (Files.exists(this.file)) {
            try {
                Map<String, BotTypes.BotType> loaded = GSON.fromJson(
                    Files.readString(this.file, StandardCharsets.UTF_8), new TypeToken<Map<String, BotTypes.BotType>>() {}.getType()
                );
                if (loaded != null) {
                    loaded.forEach((key, type) -> {
                        if (type != null && type.name != null) {
                            this.types.put(type.name.toLowerCase(Locale.ROOT), type);
                        }
                    });
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }

    private void store() {
        try {
            Files.createDirectories(this.file.getParent());
            Files.writeString(this.file, GSON.toJson(this.types), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    /** Every field is optional; null = keep the normal random behaviour. */
    public static final class BotType {
        public String name;
        // ---- names
        public String nick;
        public Integer nickMin;
        public Integer nickMax;
        public String nickStyle;
        public String nickWord;
        // ---- looks
        public String skin;
        public String skinColor;
        public String skinFile;
        // ---- defaults for spawning
        public String kit;
        public String team;
        public String tier;
        // ---- fighting style
        public String personality;
        public Double aggression;
        public Double range;
        public Double strafe;
        public Double retreat;
        public Double reaction;
        public Double accuracy;
        public Double crit;
        public Double shield;
        public Double hop;
        public String target;
        public Boolean sprintJump;

        public BotType() {
        }

        public BotType(String name) {
            this.name = name;
        }

        /**
         * Reads settings like {@code randomnick 10 13 chaos skin black tier ht4 aggression 1.4}.
         * Unknown words are an error so typos don't silently do nothing.
         */
        public void apply(String text) {
            String[] words = text.trim().isEmpty() ? new String[0] : text.trim().split("\\s+");
            int i = 0;

            while (i < words.length) {
                String key = words[i++].toLowerCase(Locale.ROOT);
                switch (key) {
                    case "randomnick", "nickrandom", "names", "namen", "zufallsname" -> {
                        this.nick = null;
                        this.nickMin = 4;
                        this.nickMax = 12;
                        this.nickStyle = null;
                        this.nickWord = null;
                        if (i < words.length && words[i].matches("\\d+")) {
                            this.nickMin = clampLen(Integer.parseInt(words[i++]));
                            if (i < words.length && words[i].matches("\\d+")) {
                                this.nickMax = clampLen(Integer.parseInt(words[i++]));
                            } else {
                                this.nickMax = this.nickMin;
                            }
                        }
                        if (this.nickMin > this.nickMax) {
                            int swap = this.nickMin;
                            this.nickMin = this.nickMax;
                            this.nickMax = swap;
                        }
                        if (i < words.length && isStyle(words[i])) {
                            this.nickStyle = BotNameStyle.parse(words[i++]).name();
                            if (i < words.length && !isKeyword(words[i])) {
                                this.nickWord = words[i++].replaceAll("[^A-Za-z0-9_]", "");
                            }
                        }
                    }
                    case "nick", "name" -> {
                        String base = need(words, i++, key).replaceAll("[^A-Za-z0-9_]", "");
                        if (base.length() < 1 || base.length() > 14) {
                            throw new IllegalArgumentException("nick: 1-14 Zeichen (A-Z 0-9 _), es wird durchnummeriert.");
                        }
                        this.nick = base;
                        this.nickMin = null;
                        this.nickMax = null;
                    }
                    case "skin" -> {
                        String value = need(words, i++, key);
                        String lower = value.toLowerCase(Locale.ROOT);
                        if (lower.startsWith("file:") || lower.startsWith("datei:")) {
                            this.skinFile = value.substring(value.indexOf(':') + 1);
                            this.skin = null;
                        } else if (lower.equals("off") || lower.equals("none") || lower.equals("-")) {
                            this.skin = null;
                            this.skinFile = null;
                            this.skinColor = null;
                        } else if (SkinColors.isColorWord(lower) && !lower.equals("off")) {
                            // "skin black" / "skin #202020" = a colour; anything name-like = that player's skin
                            this.skinColor = SkinColors.parse(lower);
                        } else {
                            this.skin = value;
                            this.skinFile = null;
                        }
                    }
                    case "color", "colour", "farbe" -> {
                        String hex = SkinColors.parse(need(words, i++, key));
                        if (hex == null) {
                            throw new IllegalArgumentException("Farbe unbekannt. Farben: " + String.join(", ", SkinColors.NAMES) + " oder #rrggbb.");
                        }
                        this.skinColor = hex.equals("off") ? null : hex;
                    }
                    case "kit" -> this.kit = noValue(need(words, i++, key)) ? null : words[i - 1].toLowerCase(Locale.ROOT);
                    case "team" -> this.team = noValue(need(words, i++, key)) ? null : words[i - 1];
                    case "tier", "stufe", "skill", "rank" -> {
                        String value = need(words, i++, key);
                        if (noValue(value)) {
                            this.tier = null;
                        } else if (Tiers.range(value) == null) {
                            throw new IllegalArgumentException("Stufe unbekannt: " + value + " (z.B. lt5, ht4, ht4-ht3).");
                        } else {
                            this.tier = value;
                        }
                    }
                    case "personality", "style", "stil", "charakter" -> {
                        String value = need(words, i++, key);
                        if (BotSkill.Personality.parse(value) == null) {
                            throw new IllegalArgumentException("personality: aggressive, balanced oder cautious.");
                        }
                        this.personality = BotSkill.Personality.parse(value).name();
                    }
                    case "aggression", "aggro" -> this.aggression = number(words, i++, key, 0.2, 2.0);
                    case "range", "reach", "distanz", "abstand" -> this.range = number(words, i++, key, 1.8, 3.0);
                    case "strafe" -> this.strafe = number(words, i++, key, 0.0, 2.0);
                    case "retreat", "rueckzug", "flee" -> this.retreat = number(words, i++, key, 0.0, 19.0);
                    case "reaction", "reaktion" -> this.reaction = number(words, i++, key, 0.3, 3.0);
                    case "accuracy", "aim", "genauigkeit" -> this.accuracy = number(words, i++, key, 0.3, 1.0);
                    case "crit", "crits" -> this.crit = number(words, i++, key, 0.0, 1.0);
                    case "shield", "schild" -> this.shield = number(words, i++, key, 0.0, 1.0);
                    case "hop", "bhop" -> this.hop = number(words, i++, key, 0.0, 1.0);
                    case "sprintjump" -> this.sprintJump = bool(need(words, i++, key));
                    case "target", "ziel", "focus" -> {
                        String value = need(words, i++, key);
                        if (BotSkill.TargetPref.parse(value) == null) {
                            throw new IllegalArgumentException("target: smart, nearest, weakest, strongest oder threat.");
                        }
                        this.target = BotSkill.TargetPref.parse(value).name();
                    }
                    case "reset", "clear" -> {
                        BotType fresh = new BotType(this.name);
                        this.copyFrom(fresh);
                    }
                    default -> throw new IllegalArgumentException(
                        "Unbekannt: " + key + ". Moeglich: randomnick <min> <max> [stil] [wort], nick <name>, skin <farbe|spieler|random|file:datei>, kit, team, tier, personality, aggression, range, strafe, retreat, reaction, accuracy, crit, shield, hop, target"
                    );
                }
            }
        }

        private void copyFrom(BotType other) {
            this.nick = other.nick;
            this.nickMin = other.nickMin;
            this.nickMax = other.nickMax;
            this.nickStyle = other.nickStyle;
            this.nickWord = other.nickWord;
            this.skin = other.skin;
            this.skinColor = other.skinColor;
            this.skinFile = other.skinFile;
            this.kit = other.kit;
            this.team = other.team;
            this.tier = other.tier;
            this.personality = other.personality;
            this.aggression = other.aggression;
            this.range = other.range;
            this.strafe = other.strafe;
            this.retreat = other.retreat;
            this.reaction = other.reaction;
            this.accuracy = other.accuracy;
            this.crit = other.crit;
            this.shield = other.shield;
            this.hop = other.hop;
            this.target = other.target;
            this.sprintJump = other.sprintJump;
        }

        /**
         * A fresh name following this type (null = the normal random name). {@code taken} holds the
         * names already given out in this batch (lower case).
         */
        public String freshName(Bots bots, int index, int count, java.util.Set<String> taken) {
            if (this.nick != null) {
                if (count == 1 && bots.nameFree(this.nick) && !taken.contains(this.nick.toLowerCase(Locale.ROOT))) {
                    return this.nick;
                }

                for (int n = index; n < index + 5000; n++) {
                    String name = this.nick + n;
                    if (name.length() <= 16 && bots.nameFree(name) && !taken.contains(name.toLowerCase(Locale.ROOT))) {
                        return name;
                    }
                }
                return null;
            }

            if (this.nickMin == null) {
                return null;
            }

            BotNameStyle style = this.nickStyle == null ? BotNameStyle.MIX : BotNameStyle.valueOf(this.nickStyle);
            for (int attempt = 0; attempt < 50; attempt++) {
                String name = this.nickWord == null
                    ? bots.freshName(style, this.nickMin, this.nickMax)
                    : bots.freshNameWith(this.nickWord, style, this.nickMin, this.nickMax);
                if (!taken.contains(name.toLowerCase(Locale.ROOT))) {
                    return name;
                }
            }
            return null;
        }

        /** Style knobs onto a freshly spawned bot (the tier first, so the rest builds on it). */
        public void applyStyle(BotPlayer bot, boolean keepTier) {
            BotSkill skill = bot.skill;
            if (this.tier != null && !keepTier) {
                List<Double> range = Tiers.range(this.tier);
                if (range != null && !range.isEmpty()) {
                    skill.setSkill(Tiers.pick(range));
                }
            }

            ThreadLocalRandom r = ThreadLocalRandom.current();
            if (this.personality != null) {
                skill.personality = BotSkill.Personality.valueOf(this.personality);
                skill.aggression = skill.personality.aggression * r.nextDouble(0.9, 1.1);
            }

            // A little personal spread around the type's numbers, so 20 bots of one type still
            // don't move in lockstep.
            if (this.aggression != null) {
                skill.aggression = this.aggression * r.nextDouble(0.93, 1.07);
            }
            if (this.range != null) {
                Tiers.Tier tier = skill.tier();
                skill.rangeBias = this.range - (tier.reachMin + tier.reachMax) / 2.0 + r.nextDouble(-0.08, 0.08);
            }
            if (this.strafe != null) {
                skill.strafeScale = this.strafe * r.nextDouble(0.9, 1.1);
            }
            if (this.retreat != null) {
                skill.retreatHealth = this.retreat;
            }
            if (this.reaction != null) {
                skill.reactionScale = this.reaction * r.nextDouble(0.92, 1.08);
            }
            if (this.accuracy != null) {
                skill.accuracyBonus = this.accuracy - skill.tier().accuracy;
            }
            if (this.crit != null) {
                skill.critHabit = this.crit;
            }
            if (this.shield != null) {
                skill.shieldHabit = this.shield;
            }
            if (this.hop != null) {
                skill.hopHabit = this.hop;
            }
            if (this.target != null) {
                skill.targetPref = BotSkill.TargetPref.valueOf(this.target);
            }
            if (this.sprintJump != null) {
                bot.sprintJump = this.sprintJump;
            }

            bot.typeName = this.name;
        }

        public String summary() {
            List<String> parts = new ArrayList<>();
            if (this.nick != null) {
                parts.add("Name " + this.nick + "1, " + this.nick + "2 ...");
            } else if (this.nickMin != null) {
                parts.add("Namen " + this.nickMin + "-" + this.nickMax + " " + (this.nickStyle == null ? "mix" : BotNameStyle.valueOf(this.nickStyle).label())
                    + (this.nickWord == null ? "" : " mit '" + this.nickWord + "'"));
            }
            if (this.skinColor != null) {
                parts.add("Farbe #" + this.skinColor);
            }
            if (this.skinFile != null) {
                parts.add("Skin-Datei " + this.skinFile);
            }
            if (this.skin != null) {
                parts.add("Skin " + this.skin);
            }
            if (this.kit != null) {
                parts.add("Kit " + this.kit);
            }
            if (this.team != null) {
                parts.add("Team " + this.team);
            }
            if (this.tier != null) {
                parts.add("Stufe " + this.tier);
            }
            if (this.personality != null) {
                parts.add(this.personality.toLowerCase(Locale.ROOT));
            }
            addNum(parts, "aggression", this.aggression);
            addNum(parts, "range", this.range);
            addNum(parts, "strafe", this.strafe);
            addNum(parts, "retreat", this.retreat);
            addNum(parts, "reaction", this.reaction);
            addNum(parts, "accuracy", this.accuracy);
            addNum(parts, "crit", this.crit);
            addNum(parts, "shield", this.shield);
            addNum(parts, "hop", this.hop);
            if (this.target != null) {
                parts.add("Ziel " + this.target.toLowerCase(Locale.ROOT));
            }
            if (this.sprintJump != null) {
                parts.add("sprintjump " + (this.sprintJump ? "an" : "aus"));
            }
            return parts.isEmpty() ? "(alles normal/zufaellig)" : String.join(", ", parts);
        }

        private static void addNum(List<String> parts, String label, Double value) {
            if (value != null) {
                parts.add(label + " " + String.format(Locale.ROOT, "%.2f", value));
            }
        }

        private static int clampLen(int value) {
            return Math.max(3, Math.min(16, value));
        }

        private static boolean isStyle(String word) {
            try {
                BotNameStyle.parse(word);
                return true;
            } catch (IllegalArgumentException error) {
                return false;
            }
        }

        static final List<String> KEYWORDS = List.of(
            "randomnick", "nickrandom", "names", "namen", "zufallsname", "nick", "name", "skin", "color", "colour", "farbe", "kit", "team",
            "tier", "stufe", "skill", "rank", "personality", "style", "stil", "charakter", "aggression", "aggro", "range", "reach", "distanz",
            "abstand", "strafe", "retreat", "rueckzug", "flee", "reaction", "reaktion", "accuracy", "aim", "genauigkeit", "crit", "crits",
            "shield", "schild", "hop", "bhop", "sprintjump", "target", "ziel", "focus", "reset", "clear"
        );

        private static boolean isKeyword(String word) {
            return KEYWORDS.contains(word.toLowerCase(Locale.ROOT));
        }

        private static String need(String[] words, int i, String key) {
            if (i >= words.length) {
                throw new IllegalArgumentException(key + ": Wert fehlt.");
            }
            return words[i];
        }

        private static double number(String[] words, int i, String key, double min, double max) {
            String value = need(words, i, key);
            try {
                double number = Double.parseDouble(value.replace(',', '.'));
                return Math.max(min, Math.min(max, number));
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(key + ": Zahl erwartet (" + min + " bis " + max + ").");
            }
        }

        private static boolean bool(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "on", "an", "true", "ja", "yes", "1" -> true;
                default -> false;
            };
        }

        private static boolean noValue(String value) {
            return value.equals("-") || value.equalsIgnoreCase("none") || value.equalsIgnoreCase("keins") || value.equalsIgnoreCase("off");
        }
    }
}
