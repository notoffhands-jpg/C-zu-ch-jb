package de.fiden.botsystem.bot;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public enum BotNameStyle {
    MIX("mix"),
    WORD("wort"),
    GAMER("gamer"),
    SYLLABLE("silben"),
    RANDOM("zufall"),
    CHAOS("chaos");

    private static final String[] SYLLABLES = new String[]{
        "ka", "ri", "zen", "vex", "lo", "mir", "tau", "nyx", "sol", "fen", "dra", "ki", "ora", "wyn", "rex", "iva", "bo", "sha", "kro", "elu"
    };
    private static final String[] TAGS = new String[]{"YT", "HD", "TV", "MC", "PVP", "GG", "xD"};
    private static final char[] CONSONANTS = "bcdfghjklmnpqrstvwxyz".toCharArray();
    private static final char[] VOWELS = "aeiou".toCharArray();
    private final String label;

    BotNameStyle(String label) {
        this.label = label;
    }

    public String label() {
        return this.label;
    }

    public static BotNameStyle parse(String input) {
        String value = input.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");

        for (BotNameStyle style : values()) {
            if (style.label.equals(value) || style.name().toLowerCase(Locale.ROOT).equals(value)) {
                return style;
            }
        }
        return switch (value) {
            case "woerter", "words", "word", "english" -> WORD;
            case "spieler", "player", "gaming" -> GAMER;
            case "silbe", "syllable", "fantasy" -> SYLLABLE;
            case "random", "selten", "rare" -> RANDOM;
            case "alle", "mixed" -> MIX;
            case "zeichen", "salat", "gibberish", "letters", "nuil", "nuill", "numbers", "zahlen", "unstable", "smp" -> CHAOS;
            default -> throw new IllegalArgumentException("Stil: mix, wort, gamer, silben, zufall oder chaos.");
        };
    }

    public String generate(List<String> first, List<String> second, int min, int max) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        BotNameStyle style = this == MIX ? values()[random.nextInt(1, values().length)] : this;
        String a = first.isEmpty() ? "Nexo" : first.get(random.nextInt(first.size()));
        String b = second.isEmpty() ? "Craft" : second.get(random.nextInt(second.size()));

        String generated = switch (style) {
            case WORD -> {
                switch (random.nextInt(3)) {
                    case 0:
                        yield a;
                    case 1:
                        yield a + b;
                    default:
                        yield b + a;
                }
            }
            case GAMER -> this.gamer(a, b, random);
            case SYLLABLE -> this.syllables(random);
            default -> this.noise(random, min, max);
            case CHAOS -> this.chaos(random, min, max);
        };
        return this.fit(generated, min, max, random);
    }

    private String gamer(String a, String b, ThreadLocalRandom random) {
        String core = random.nextBoolean() ? a : b;

        String name = switch (random.nextInt(8)) {
            case 0 -> "x" + core + "_" + random.nextInt(1, 100);
            case 1 -> core + random.nextInt(2000, 2013);
            case 2 -> TAGS[random.nextInt(TAGS.length)] + core;
            case 3 -> core + "_" + TAGS[random.nextInt(TAGS.length)];
            case 4 -> "x" + core.toLowerCase(Locale.ROOT) + "x";
            case 5 -> core.substring(0, Math.min(4, core.length())) + "_" + random.nextInt(10, 1000);
            case 6 -> core + (char)(65 + random.nextInt(26)) + random.nextInt(10, 100);
            default -> core.toLowerCase(Locale.ROOT) + random.nextInt(100, 10000);
        };
        if (random.nextInt(3) == 0) {
            name = name.replace('o', '0').replace('e', '3').replace('i', '1').replace('s', '5');
        }

        return name;
    }

    private String syllables(ThreadLocalRandom random) {
        StringBuilder builder = new StringBuilder();
        int count = random.nextInt(2, 5);

        for (int i = 0; i < count; i++) {
            builder.append(SYLLABLES[random.nextInt(SYLLABLES.length)]);
        }

        if (random.nextInt(3) == 0) {
            builder.append(random.nextInt(10, 100));
        }

        return builder.toString();
    }

    private String noise(ThreadLocalRandom random, int min, int max) {
        int length = random.nextInt(min, Math.max(min + 1, Math.min(max, 12) + 1));
        StringBuilder builder = new StringBuilder();

        while (builder.length() < length) {
            int roll = random.nextInt(10);
            if (roll < 5) {
                builder.append(CONSONANTS[random.nextInt(CONSONANTS.length)]);
            } else if (roll < 8) {
                builder.append(VOWELS[random.nextInt(VOWELS.length)]);
            } else if (roll == 8 && builder.length() > 0 && builder.length() < length - 1) {
                builder.append('_');
            } else {
                builder.append(random.nextInt(10));
            }
        }

        return builder.toString();
    }

    private String chaos(ThreadLocalRandom random, int min, int max) {
        int length = random.nextInt(min, Math.max(min + 1, Math.min(max, 16) + 1));
        StringBuilder builder = new StringBuilder();
        boolean lastUnderscore = true;

        while (builder.length() < length) {
            int roll = random.nextInt(100);
            if (roll < 46) {
                builder.append((char)(97 + random.nextInt(26)));
                lastUnderscore = false;
            } else if (roll < 54) {
                builder.append((char)(65 + random.nextInt(26)));
                lastUnderscore = false;
            } else if (roll < 86) {
                builder.append(random.nextInt(10));
                lastUnderscore = false;
            } else if (!lastUnderscore && builder.length() < length - 1) {
                builder.append('_');
                lastUnderscore = true;
            }
        }

        if (random.nextInt(5) == 0 && length >= 6) {
            builder.insert(0, random.nextBoolean() ? "xX" : "xx").append(random.nextBoolean() ? "Xx" : "xx");
        }

        return builder.toString();
    }

    private String fit(String value, int min, int max, ThreadLocalRandom random) {
        String name = value.replaceAll("[^A-Za-z0-9_]", "").replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        if (name.length() > max) {
            name = name.substring(0, max);
        }

        while (name.length() < min) {
            name = name + random.nextInt(10);
        }

        return name;
    }
}
