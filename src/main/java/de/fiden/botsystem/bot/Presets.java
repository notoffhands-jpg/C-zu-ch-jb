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

public final class Presets {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file = BotSystemPlugin.configDir().resolve("botsystem-presets.json");
    private final Map<String, Presets.Preset> presets = new LinkedHashMap<>();

    public Presets() {
        this.load();
    }

    public Presets.Preset get(String name) {
        return this.presets.get(name.toLowerCase(Locale.ROOT));
    }

    public List<String> names() {
        List<String> names = new ArrayList<>();
        this.presets.values().forEach(preset -> names.add(preset.name()));
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public void save(Presets.Preset preset) {
        this.presets.put(preset.name().toLowerCase(Locale.ROOT), preset);
        this.store();
    }

    public boolean delete(String name) {
        boolean removed = this.presets.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (removed) {
            this.store();
        }

        return removed;
    }

    private void load() {
        if (Files.exists(this.file)) {
            try {
                String json = Files.readString(this.file, StandardCharsets.UTF_8);
                Map<String, Presets.Preset> loaded = GSON.fromJson(json, (new TypeToken<Map<String, Presets.Preset>>() {}).getType());
                if (loaded != null) {
                    this.presets.putAll(loaded);
                }
            } catch (IOException | RuntimeException var3) {
            }
        }
    }

    private void store() {
        try {
            Files.createDirectories(this.file.getParent());
            Files.writeString(this.file, GSON.toJson(this.presets), StandardCharsets.UTF_8);
        } catch (IOException var2) {
        }
    }

    public record Preset(String name, String kit, String team, int count) {
        public String summary() {
            return this.count + " Bot(s), Kit " + (this.kit == null ? "-" : this.kit) + ", Team " + (this.team == null ? "-" : this.team);
        }
    }
}
