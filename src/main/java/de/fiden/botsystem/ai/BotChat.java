package de.fiden.botsystem.ai;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.fiden.botsystem.BotSystemMod;
import de.fiden.botsystem.BotSystemPlugin;
import de.fiden.botsystem.bot.BotDefaults;
import de.fiden.botsystem.bot.BotPlayer;
import de.fiden.botsystem.bot.Bots;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;

public final class BotChat {
    private static final Path FILE = BotSystemPlugin.configDir().resolve("botsystem-chat.json");
    private static final List<String> KILL = List.of("gg", "ez", "lol", "haha", "gg ez", "rip", "xd", "nice", "get rekt", "lmao", "ggs", "too easy");
    private static final List<String> DEATH = List.of(
        "f", "wtf", "lag", "hacker", "nein", "omg", "bruh", "wie", "ok", "rip me", "lol", "neeein", "unfair", "wtf lag", "fml", "1 hp", "combo"
    );
    private static final List<String> HURT = List.of("ouch", "ah", "hilfe", "help", "stop", "aua", "wer", "wtf", "nein nein", "hey");
    private static final List<String> IDLE = List.of(
        "wo seid ihr",
        "kommt",
        "gogogo",
        "los",
        "wo lang",
        "jemand da",
        "wer hat mich geschlagen",
        ":)",
        "xd",
        "lol",
        "gg",
        "brb",
        "kurz afk",
        "wieder da",
        "hallo",
        "hi",
        "moin",
        "was machen wir",
        "wann gehts los",
        "ich hab hunger",
        "warum",
        "ok",
        "jo",
        "same",
        "true",
        "rush b",
        "push",
        "wait",
        "warte",
        "kommt mal her",
        "team",
        "hilfe",
        "wo",
        "help",
        "nice",
        "lets go",
        "gogo",
        "hmm",
        "?",
        "??",
        "was",
        "wie",
        "lag",
        "lags",
        "mein ping",
        "hab lag",
        "sry",
        "np",
        "danke",
        "thx"
    );
    private final Map<String, List<String>> lines = new HashMap<>();
    private long lastMessageTick = -1000L;
    private final Map<UUID, Long> lastByBot = new HashMap<>();

    public BotChat() {
        this.lines.put("kill", new ArrayList<>(KILL));
        this.lines.put("death", new ArrayList<>(DEATH));
        this.lines.put("hurt", new ArrayList<>(HURT));
        this.lines.put("idle", new ArrayList<>(IDLE));
        this.load();
    }

    private void load() {
        try {
            if (!Files.exists(FILE)) {
                this.save();
                return;
            }

            JsonObject root = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();

            for (String key : List.of("kill", "death", "hurt", "idle")) {
                if (root.has(key)) {
                    List<String> list = new ArrayList<>();
                    root.getAsJsonArray(key).forEach(element -> list.add(element.getAsString()));
                    if (!list.isEmpty()) {
                        this.lines.put(key, list);
                    }
                }
            }
        } catch (RuntimeException | IOException error) {
            BotSystemMod.LOGGER.warn("botsystem-chat.json nicht lesbar: {}", error.toString());
        }
    }

    private void save() {
        try {
            JsonObject root = new JsonObject();

            for (Entry<String, List<String>> entry : this.lines.entrySet()) {
                JsonArray array = new JsonArray();
                entry.getValue().forEach(array::add);
                root.add(entry.getKey(), array);
            }

            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (IOException var5) {
        }
    }

    private boolean enabled() {
        return BotDefaults.get().chat;
    }

    private void say(MinecraftServer server, BotPlayer bot, String kind, long tick, double chance) {
        if (this.enabled() && !bot.isRemoved() && !(ThreadLocalRandom.current().nextDouble() > chance)) {
            if (tick - this.lastMessageTick >= 60L && tick - this.lastByBot.getOrDefault(bot.getUUID(), -10000L) >= 1200L) {
                List<String> options = this.lines.get(kind);
                if (options != null && !options.isEmpty()) {
                    String line = options.get(ThreadLocalRandom.current().nextInt(options.size()));
                    this.lastMessageTick = tick;
                    this.lastByBot.put(bot.getUUID(), tick);
                    server.getPlayerList()
                        .broadcastSystemMessage(Component.translatable("chat.type.text", bot.getDisplayName(), Component.literal(line)), false);
                }
            }
        }
    }

    public void onKill(MinecraftServer server, BotPlayer killer, long tick) {
        this.say(server, killer, "kill", tick, 0.12);
    }

    public void onDeath(MinecraftServer server, BotPlayer victim, long tick) {
        this.say(server, victim, "death", tick, 0.1);
    }

    public void onHurt(MinecraftServer server, BotPlayer victim, LivingEntity attacker, long tick) {
        this.say(server, victim, "hurt", tick, 0.02);
    }

    public void idle(MinecraftServer server, Bots bots, long tick) {
        if (this.enabled() && tick % 20L == 0L && !bots.all().isEmpty()) {
            if (ThreadLocalRandom.current().nextInt(150) == 0) {
                List<BotPlayer> all = new ArrayList<>(bots.all());
                BotPlayer bot = all.get(ThreadLocalRandom.current().nextInt(all.size()));
                if (!bot.isDeadOrDying()) {
                    this.say(server, bot, "idle", tick, 1.0);
                }
            }
        }
    }
}
