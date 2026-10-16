package de.fiden.botsystem.bot;

import de.fiden.botsystem.BotSystemMod;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public final class BotProfile {
    public Map<String, Double> style = new HashMap<>();
    public Map<String, Double> bestTime = new HashMap<>();
    public Map<String, List<double[]>> bestLine = new HashMap<>();
    public Map<String, Double> bestStyle = new HashMap<>();
    public int laps;
    public int improvements;
    public String probing;
    public static final String[] KNOBS = new String[]{"sprint", "jump", "lane", "corner", "pace", "patience"};

    public double get(String knob) {
        return 1.0;
    }

    public void probe(double explore) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String knob = KNOBS[random.nextInt(KNOBS.length)];
        double current = this.style.getOrDefault(knob, 1.0);
        double step = (random.nextDouble() * 2.0 - 1.0) * Math.max(0.02, explore);
        this.style.put(knob, Math.max(0.4, Math.min(1.6, current + step)));
        this.probing = knob;
    }

    public boolean lap(String route, double seconds, List<double[]> line) {
        this.laps++;
        Double best = this.bestTime.get(route);
        boolean better = best == null || seconds < best - 0.05;
        if (better) {
            this.bestTime.put(route, seconds);
            if (line != null && line.size() > 1) {
                this.bestLine.put(route, new ArrayList<>(line));
            }

            this.bestStyle = new HashMap<>(this.style);
            this.improvements++;
            if (best != null) {
                BotSystemMod.skills().absorb(this);

                for (String knob : KNOBS) {
                    this.style.put(knob, 1.0 + (this.style.getOrDefault(knob, 1.0) - 1.0) * 0.75);
                }

                this.bestStyle = new HashMap<>(this.style);
            }
        } else if (this.probing != null && !this.bestStyle.isEmpty()) {
            this.style = new HashMap<>(this.bestStyle);
        }

        this.probing = null;
        return better;
    }

    public List<double[]> line(String route) {
        return this.bestLine.get(route);
    }

    public Double best(String route) {
        return this.bestTime.get(route);
    }

    public static String routeKey(double x1, double z1, double x2, double z2) {
        return Math.round(x1 / 8.0) + "," + Math.round(z1 / 8.0) + ">" + Math.round(x2 / 8.0) + "," + Math.round(z2 / 8.0);
    }

    public String summary() {
        StringBuilder text = new StringBuilder();
        text.append(this.laps).append(" Runden, ").append(this.improvements).append(" Verbesserungen | Stil ");

        for (String knob : KNOBS) {
            text.append(knob).append(' ').append(String.format(Locale.ROOT, "%.2f", this.get(knob))).append(' ');
        }

        if (!this.bestTime.isEmpty()) {
            text.append("| Bestzeiten ");
            this.bestTime.forEach((route, seconds) -> text.append(route).append('=').append(String.format(Locale.ROOT, "%.1f", seconds)).append("s "));
        }

        return text.toString().trim();
    }
}
