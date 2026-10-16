package de.fiden.botsystem.bot;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SkinColors {
    public static final String PROPERTY = "botsystem_color";
    private static final Map<String, String> BY_NAME = Map.ofEntries(
        Map.entry("black", "0f0f0f"),
        Map.entry("schwarz", "0f0f0f"),
        Map.entry("white", "f5f5f5"),
        Map.entry("weiss", "f5f5f5"),
        Map.entry("red", "b3231e"),
        Map.entry("rot", "b3231e"),
        Map.entry("green", "3c8f2a"),
        Map.entry("gruen", "3c8f2a"),
        Map.entry("blue", "2b4fd6"),
        Map.entry("blau", "2b4fd6"),
        Map.entry("yellow", "f2d13d"),
        Map.entry("gelb", "f2d13d"),
        Map.entry("orange", "e8842a"),
        Map.entry("purple", "7a2fb3"),
        Map.entry("lila", "7a2fb3"),
        Map.entry("violett", "7a2fb3"),
        Map.entry("pink", "ee6fb4"),
        Map.entry("rosa", "ee6fb4"),
        Map.entry("gray", "7d7d7d"),
        Map.entry("grey", "7d7d7d"),
        Map.entry("grau", "7d7d7d"),
        Map.entry("darkgray", "3f3f3f"),
        Map.entry("dunkelgrau", "3f3f3f"),
        Map.entry("cyan", "2aa7a1"),
        Map.entry("tuerkis", "2aa7a1"),
        Map.entry("lightblue", "5aa9e6"),
        Map.entry("hellblau", "5aa9e6"),
        Map.entry("lime", "7fd633"),
        Map.entry("hellgruen", "7fd633"),
        Map.entry("magenta", "c23fbf"),
        Map.entry("brown", "6b4423"),
        Map.entry("braun", "6b4423"),
        Map.entry("gold", "d4a017"),
        Map.entry("off", "off"),
        Map.entry("aus", "off"),
        Map.entry("none", "off"),
        Map.entry("reset", "off")
    );
    public static final List<String> NAMES = List.of(
        "black",
        "white",
        "red",
        "green",
        "blue",
        "yellow",
        "orange",
        "purple",
        "pink",
        "gray",
        "darkgray",
        "cyan",
        "lightblue",
        "lime",
        "magenta",
        "brown",
        "gold",
        "off"
    );

    private SkinColors() {
    }

    /** "black", "schwarz", "#202020" - a colour, not a player name. */
    public static boolean isColorWord(String input) {
        String value = input.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return BY_NAME.containsKey(value) || value.startsWith("#") && parse(value) != null;
    }

    public static String parse(String input) {
        if (input == null) {
            return null;
        }

        String value = input.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        String named = BY_NAME.get(value);
        if (named != null) {
            return named;
        }

        if (value.startsWith("#")) {
            value = value.substring(1);
        }

        if (value.length() == 3) {
            value = "" + value.charAt(0) + value.charAt(0) + value.charAt(1) + value.charAt(1) + value.charAt(2) + value.charAt(2);
        }

        return value.matches("[0-9a-f]{6}") ? value : null;
    }
}
