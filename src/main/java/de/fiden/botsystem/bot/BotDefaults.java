package de.fiden.botsystem.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.fiden.botsystem.BotSystemPlugin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BotDefaults {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = BotSystemPlugin.configDir().resolve("botsystem-settings.json");
    private static BotDefaults instance;
    public boolean assistOwner = true;
    public boolean guardsJoinWars = false;
    public int responseRadius = 64;
    public boolean minimap = false;
    public boolean ignorePlayers = false;
    public boolean attackActors = false;
    public boolean filmReset = true;
    public boolean potions = true;
    public boolean deathAnimation = false;
    public boolean autosave = false;
    public boolean defendOwner = true;
    public boolean defendSelf = true;
    /** Show the outer skin layer (hat, jacket, sleeves, trousers) on bots. */
    public boolean skinLayers = true;
    /** Hide the green confirmation messages of bot commands in chat. */
    public boolean quietChat = false;
    /** Optional mineskin.org API key: signs skin files so players without the mod see them too. */
    public String mineskinKey = "";
    public boolean pathfinding = true;
    /** Bots count as players for natural mob spawning (vanilla behaviour). Off: like Citizens NPCs - no extra mobs around armies. */
    public boolean botsSpawnMobs = false;
    /** Mob armies push each other with BotSystem's own cheap grid instead of vanilla's per-mob world query. */
    public boolean fastMobPush = true;
    /** Idle bots (standing still, unhurt) get the full vanilla tick only every 4th tick. */
    public boolean liteIdle = true;
    /** usercache.json saved at shutdown only instead of on every (bot) join. */
    public boolean fastJoin = true;
    /** More than 12 bots on one target: outer rings wait around it instead of all pressing in. */
    public boolean crowdRings = true;
    /** Spawn time per tick for big spawns in ms (0 = all at once in one tick, freezing the server meanwhile). */
    public double spawnBudgetMs = 30.0;
    /** Bot AI time per tick (ms) after which idle bots wait for the next tick. 0 = no limit. */
    public double aiBudgetMs = 12.0;
    public double sightRange = 0.0;
    public double followDistance = 8.0;
    public String marchFormation = "loose";
    public double separation = 1.0;
    public double marchWidth = 14.0;
    public String followMode = "tour";
    public double followSpread = 11.0;
    public double followSpacing = 2.4;
    public double followSlack = 5.0;
    public int followStagger = 30;
    public int followLook = 12;
    public boolean followSprint = true;
    public double followCatchUp = 9.0;
    public double followJitter = 0.45;
    public int trackDistance = 160;
    public boolean sprintJump = true;
    public double defendRadius = 20.0;
    public boolean humanMovement = true;
    public boolean idleFidget = true;
    public double idleHeadDrift = 1.8;
    public boolean autoWar = false;
    public boolean respawn = false;
    public boolean chat = false;
    public String deathSound = "";
    public int respawnSeconds = 5;
    public boolean archersHold = true;
    public boolean drops = false;
    public String movePace = "walk";
    public int dropLifetime = 0;
    public boolean xpDrops = false;
    public boolean build = false;
    public boolean dig = false;
    public int buildLifetime = 180;
    public boolean learnExplore = true;
    public int gymReps = 6;
    public double detourLimit = 4.0;
    public double climbPenalty = 4.0;
    public int gymLaneCap = 2;
    public double trainExplore = 0.08;
    public boolean learnAlways = true;
    public double trainSpeed = 3.0;
    public int digRestore = 0;
    public int buildMax = 3000;
    public String buildBlock = "minecraft:cobblestone";
    public boolean hideFromSelectors = true;
    public boolean anchors = true;
    public int anchorsPerSecond = 12;
    public int lodDistance = 0;
    public int nameTagDistance = 64;
    public static final int NAMETAGS_ALWAYS = 1000000;

    public static Path dir() {
        return FILE.getParent();
    }

    public static BotDefaults get() {
        if (instance == null) {
            instance = load();
        }

        return instance;
    }

    private static BotDefaults load() {
        try {
            if (Files.exists(FILE)) {
                BotDefaults loaded = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), BotDefaults.class);
                if (loaded != null) {
                    return loaded;
                }
            }
        } catch (IOException | RuntimeException var1) {
        }

        return new BotDefaults();
    }

    public void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException var2) {
        }
    }

    public void applyTo(BotPlayer bot) {
        bot.assistOwner = this.assistOwner;
        bot.defendOwner = this.defendOwner;
        bot.defendSelf = this.defendSelf;
        bot.pathfinding = this.pathfinding;
        bot.sightRange = this.sightRange;
        bot.followDistance = this.followDistance;
        bot.sprintJump = this.sprintJump;
        bot.affectsSpawning = this.botsSpawnMobs;
    }

    public void rememberFrom(BotPlayer bot) {
        this.assistOwner = bot.assistOwner;
        this.defendOwner = bot.defendOwner;
        this.defendSelf = bot.defendSelf;
        this.pathfinding = bot.pathfinding;
        this.sightRange = bot.sightRange;
        this.followDistance = bot.followDistance;
        this.save();
    }
}
