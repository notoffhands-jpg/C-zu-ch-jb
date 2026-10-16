package de.fiden.botsystem.bot;

import java.util.Locale;

public enum Stance {
    PASSIVE,
    DEFENSIVE,
    AGGRESSIVE;

    public static Stance parse(String value) {
        String lower = value.toLowerCase(Locale.ROOT);

        return switch (lower) {
            case "passive", "passiv", "p" -> PASSIVE;
            case "defensive", "defensiv", "defend", "d", "hold" -> DEFENSIVE;
            case "aggressive", "aggressiv", "offensive", "offensiv", "a", "o" -> AGGRESSIVE;
            default -> null;
        };
    }

    public String label() {
        return switch (this) {
            case PASSIVE -> "passiv";
            case DEFENSIVE -> "defensiv";
            case AGGRESSIVE -> "aggressiv";
        };
    }
}
