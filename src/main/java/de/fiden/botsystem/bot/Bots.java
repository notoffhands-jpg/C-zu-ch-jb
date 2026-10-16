package de.fiden.botsystem.bot;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.MinecraftClientHttpException;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import de.fiden.botsystem.BotSystemMod;
import de.fiden.botsystem.BotSystemPlugin;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class Bots {
    private static final List<String> FIRST = List.of(
        "Nexo", "Leon", "Jay", "Raven", "Turbo", "Pixel", "Nova", "Max", "Timo", "Noah", "Finn", "Luca", "Zoro", "Kai", "Mila", "Ben"
    );
    private static final List<String> SECOND = List.of("Craft", "Cube", "Fox", "Ninja", "Storm", "Wolf", "Blaze", "Hero", "Real", "Aim");
    private static final List<String> SKIN_POOL = List.of(
        "Notch",
        "jeb_",
        "Dinnerbone",
        "Grian",
        "Technoblade",
        "Dream",
        "GeorgeNotFound",
        "Sapnap",
        "Skeppy",
        "Ph1LzA",
        "TommyInnit",
        "Fundy",
        "Ranboo",
        "Punz",
        "Hannahxxrose",
        "xNestorio",
        "Illumina",
        "Fruitberries",
        "CaptainSparklez",
        "Mumbo",
        "Tubbo",
        "WilburSoot",
        "Quackity",
        "KarlJacobs",
        "BadBoyHalo",
        "Antfrost",
        "Foolish_Gamers",
        "Eret",
        "Nihachu",
        "Purpled",
        "Slimecicle",
        "Jschlatt",
        "ConnorEatsPants",
        "Vikkstar123",
        "LazarBeam",
        "Fruitberries",
        "Smajor1995",
        "Wisp",
        "PeteZahHutt",
        "Tapl",
        "Sylvee",
        "Krtzyy",
        "Ranboo",
        "aimsey",
        "Tina",
        "MrBeast",
        "Karl",
        "Chandler",
        "PewDiePie",
        "Jacksepticeye",
        "Markiplier",
        "Ninja",
        "Shroud",
        "xQcOW",
        "Sykkuno",
        "Valkyrae",
        "Pokimane",
        "Disguised_Toast",
        "Ludwig",
        "Mizkif",
        "Etoiles",
        "Cellbit",
        "Bagi",
        "Roier",
        "Spreen",
        "Rubius",
        "Vegetta777",
        "Willyrex",
        "Luzu",
        "Auronplay",
        "ItsFunneh",
        "Preston",
        "Unspeakable",
        "Jelly",
        "Slogo",
        "Crainer",
        "DanTDM",
        "Stampylonghead",
        "iBallisticSquid",
        "Ali_A",
        "Bajan_Canadian",
        "JeromeASF",
        "SkyDoesMinecraft",
        "Deadlox",
        "MinecraftUniverse",
        "AntVenom",
        "Etho",
        "Docm77",
        "Xisuma",
        "Tango",
        "Zedaph",
        "Impulse",
        "Rendog",
        "Keralis",
        "BdoubleO100",
        "Cubfan135",
        "iJevin",
        "Joehills",
        "GoodTimesWithScar",
        "ZombieCleo",
        "iskall85",
        "ImpulseSV",
        "TangoTek",
        "falsesymmetry",
        "Stressmonster101",
        "PearlescentMoon",
        "GeminiTay",
        "Welsknight",
        "Hypnotizd",
        "VintageBeef",
        "Pixlriffs",
        "MythicalSausage",
        "LDShadowLady",
        "Smallishbeans",
        "SolidarityGaming",
        "Seapeekay",
        "Shubble",
        "fWhip",
        "JoeyGraceffa",
        "Owengejuice",
        "TheOrionSound",
        "Hbomb94",
        "InTheLittleWood",
        "SSundee",
        "PrestonPlayz",
        "UnspeakableGaming",
        "Slogoman",
        "Lachlan",
        "Woofless",
        "Kwebbelkop",
        "Bionic",
        "F1NN5TER",
        "Quig",
        "Astelic",
        "Spifey",
        "FitMC",
        "Vixella",
        "Aphmau",
        "Muselk",
        "Fruit",
        "Sapnap",
        "Technothepig",
        "Hannah",
        "Michaelmcchill",
        "Tommy",
        "Wisp",
        "Bitzel",
        "Bloodmoon",
        "Zyphon",
        "Neon",
        "Feinberg",
        "couriway",
        "Illumina1337",
        "DreamXD",
        "Punzo",
        "Ponk",
        "Awesamdude",
        "HBomb",
        "Sneegsnag",
        "Jack_Manifold",
        "Nihachu",
        "Niki",
        "Minx",
        "Callahan",
        "Ranbob",
        "BoomerNA",
        "Vurb",
        "Zyph",
        "Mefs",
        "Gamerboy80",
        "Refraction",
        "Vitalasy",
        "Rekrap2",
        "Parrot",
        "Spoke",
        "Mapicc",
        "Zam",
        "Clown",
        "Roshambo",
        "Leowook",
        "Woogie",
        "Kab",
        "Jumper",
        "Ashswag",
        "Bacon",
        "Planetlord",
        "Flame",
        "PrinceZam",
        "Wemmbu",
        "DoctorBenx"
    );
    private int skinCursor = ThreadLocalRandom.current().nextInt(1000);
    private final List<String> shuffledPool = shuffledPool();
    private final Map<UUID, BotPlayer> bots = new LinkedHashMap<>();
    private final MinecraftServer server;
    public final BlockLog blocks = new BlockLog();
    public final Map<String, int[]> teamStats = new LinkedHashMap<>();
    private static final Map<String, Set<String>> TEAM_ALLIES = new HashMap<>();
    private static final List<Bots.PendingDrop> PENDING_DROPS = new ArrayList<>();
    private static final List<Bots.Expiring> EXPIRING = new ArrayList<>();
    private static final ConcurrentHashMap<String, String> LOWER = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> TEAM_ENEMIES = new HashMap<>();
    private static final Map<String, Set<String>> TEAM_HOSTILES = new HashMap<>();
    public final ArrayDeque<String> recentDeaths = new ArrayDeque<>();
    private final Map<ServerLevel, LongOpenHashSet> anchors = new HashMap<>();
    private static List<ServerPlayer> realPlayersCache = List.of();
    private static List<ServerPlayer> realPlayersSource;
    private static long realPlayersStamp = -1L;
    private final Map<String, Optional<GameProfile>> skinCache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Optional<GameProfile>>> skinLookups = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor skinExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>(), runnable -> {
        Thread thread = new Thread(runnable, "BotSystem-Skins");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong skinOrder = new AtomicLong();
    private static final Path SKIN_FILE = BotSystemPlugin.configDir().resolve("botsystem-skins.json");
    private final Map<String, String[]> skinDisk = new ConcurrentHashMap<>();
    private volatile boolean skinDiskDirty;
    public final Map<String, Vec3> spawnPoints = new HashMap<>();
    private final List<Bots.Ghost> ghosts = new ArrayList<>();

    public static void applyTrackDistance(MinecraftServer server) {
        Bots bots = BotSystemMod.bots();
        if (bots != null) {
            for (BotPlayer bot : bots.all()) {
                Nms.setTrackingRange(bot);
            }
        }
    }

    private static List<String> shuffledPool() {
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(SKIN_POOL));
        Collections.shuffle(unique);
        return unique;
    }

    public int prefetchSkins() {
        int queued = 0;

        for (String name : this.shuffledPool) {
            if (!this.skinCache.containsKey(name.toLowerCase(Locale.ROOT))) {
                this.lookupSkin(name, 1);
                queued++;
            }
        }

        return queued;
    }

    public int cachedSkins() {
        return this.skinCache.size();
    }

    public String nextPoolSkin() {
        return this.shuffledPool.get(Math.floorMod(this.skinCursor++, this.shuffledPool.size()));
    }

    public void recordDeath(LivingEntity victim, DamageSource source) {
        this.recordDeath(MobUnits.teamOf(victim), source == null ? null : MobUnits.teamOf(source.getEntity()));
    }

    public static void noteDrops(ServerLevel world, double x, double y, double z, long tick) {
        if (BotDefaults.get().dropLifetime != 0 && PENDING_DROPS.size() < 4096) {
            PENDING_DROPS.add(new Bots.PendingDrop(world, x, y, z, tick));
        }
    }

    public static void tickDrops(long tick) {
        if (!PENDING_DROPS.isEmpty()) {
            int lifetime = BotDefaults.get().dropLifetime;
            Iterator<Bots.PendingDrop> pending = PENDING_DROPS.iterator();

            while (pending.hasNext()) {
                Bots.PendingDrop drop = pending.next();
                if (tick > drop.tick()) {
                    pending.remove();
                    AABB box = new AABB(drop.x() - 3.0, drop.y() - 3.0, drop.z() - 3.0, drop.x() + 3.0, drop.y() + 3.0, drop.z() + 3.0);

                    for (ItemEntity item : drop.world().getEntitiesOfClass(ItemEntity.class, box, itemx -> itemx.getAge() <= 40)) {
                        if (lifetime < 0) {
                            item.setUnlimitedLifetime();
                        } else if (lifetime > 0 && EXPIRING.size() < 100000) {
                            EXPIRING.add(new Bots.Expiring(item, tick + lifetime * 20L));
                        }
                    }
                }
            }
        }

        if (!EXPIRING.isEmpty() && tick % 10L == 0L) {
            EXPIRING.removeIf(entry -> {
                if (entry.item().isRemoved()) {
                    return true;
                } else if (tick >= entry.until()) {
                    entry.item().discard();
                    return true;
                } else {
                    return false;
                }
            });
        }
    }

    public static String lower(String team) {
        if (team == null) {
            return null;
        }

        String cached = LOWER.get(team);
        if (cached == null) {
            cached = team.toLowerCase(Locale.ROOT);
            if (LOWER.size() < 4096) {
                LOWER.put(team, cached);
            }
        }

        return cached;
    }

    public static boolean allied(String teamA, String teamB) {
        if (teamA == null || teamB == null) {
            return false;
        }

        if (teamA == teamB || teamA.equals(teamB)) {
            return true;
        }

        if (TEAM_ALLIES.isEmpty()) {
            return teamA.equalsIgnoreCase(teamB);
        }

        String a = lower(teamA);
        String b = lower(teamB);
        if (a.equals(b)) {
            return true;
        }

        Set<String> allies = TEAM_ALLIES.get(a);
        return allies != null && allies.contains(b);
    }

    public static void setAllied(String teamA, String teamB, boolean allied) {
        String a = lower(teamA);
        String b = lower(teamB);
        if (allied) {
            TEAM_ALLIES.computeIfAbsent(a, key -> new HashSet<>()).add(b);
            TEAM_ALLIES.computeIfAbsent(b, key -> new HashSet<>()).add(a);
        } else {
            TEAM_ALLIES.getOrDefault(a, Set.of()).remove(b);
            TEAM_ALLIES.getOrDefault(b, Set.of()).remove(a);
        }
    }

    public static Map<String, Set<String>> teamAllies() {
        return TEAM_ALLIES;
    }

    public static void setTeamRelation(String team, String other, boolean attack, boolean on) {
        if (team != null && other != null) {
            String a = lower(team);
            String b = other.toLowerCase(Locale.ROOT);
            if (!a.equals(b) && !allied(a, b)) {
                Map<String, Set<String>> map = attack ? TEAM_ENEMIES : TEAM_HOSTILES;
                if (on) {
                    map.computeIfAbsent(a, key -> new HashSet<>()).add(b);
                } else {
                    map.getOrDefault(a, Set.of()).remove(b);
                }
            }
        }
    }

    public static void clearAllTeamRelations() {
        TEAM_ENEMIES.clear();
        TEAM_HOSTILES.clear();
    }

    public static void clearTeamRelations(String team) {
        if (team != null) {
            TEAM_ENEMIES.remove(lower(team));
            TEAM_HOSTILES.remove(lower(team));
        }
    }

    public static boolean teamRelation(String team, String otherTeam, boolean attack) {
        if (team != null && otherTeam != null) {
            String a = lower(team);
            String b = lower(otherTeam);
            if (allied(a, b)) {
                return false;
            }

            Map<String, Set<String>> map = attack ? TEAM_ENEMIES : TEAM_HOSTILES;
            Set<String> own = map.get(a);
            if (own != null && own.contains(b)) {
                return true;
            }

            Set<String> allies = TEAM_ALLIES.get(a);
            if (allies != null) {
                for (String ally : allies) {
                    Set<String> theirs = map.get(ally);
                    if (theirs != null && theirs.contains(b)) {
                        return true;
                    }
                }
            }

            return false;
        } else {
            return false;
        }
    }

    public static boolean hasTeamEnemies(String team) {
        if (team == null) {
            return false;
        }

        String a = team.toLowerCase(Locale.ROOT);
        Set<String> own = TEAM_ENEMIES.get(a);
        if (own != null && !own.isEmpty()) {
            return true;
        }

        Set<String> allies = TEAM_ALLIES.get(a);
        if (allies != null) {
            for (String ally : allies) {
                Set<String> theirs = TEAM_ENEMIES.get(ally);
                if (theirs != null && !theirs.isEmpty()) {
                    return true;
                }
            }
        }

        return false;
    }

    public static Map<String, Set<String>> teamEnemies() {
        return TEAM_ENEMIES;
    }

    public static Map<String, Set<String>> teamHostiles() {
        return TEAM_HOSTILES;
    }

    public void noteDeath(BotPlayer victim, DamageSource source) {
        String attacker = source.getEntity() == null
            ? "-"
            : source.getEntity().getName().getString() + (MobUnits.teamOf(source.getEntity()) == null ? "" : " [" + MobUnits.teamOf(source.getEntity()) + "]");
        String line = victim.getGameProfile().name()
            + (victim.team == null ? "" : " [" + victim.team + "]")
            + (victim.isGuard() ? " (Wache/Begleiter)" : "")
            + " - "
            + source.getMsgId()
            + " durch "
            + attacker
            + " bei "
            + (int)victim.getX()
            + "/"
            + (int)victim.getY()
            + "/"
            + (int)victim.getZ()
            + " (Tick "
            + this.server.getTickCount()
            + ")";
        this.recentDeaths.addLast(line);

        while (this.recentDeaths.size() > 30) {
            this.recentDeaths.pollFirst();
        }

        BotSystemMod.LOGGER.info("Bot-Tod: {}", line);
    }

    public void recordDeath(String victimTeam, String killerTeam) {
        if (victimTeam != null) {
            this.teamStats.computeIfAbsent(victimTeam, key -> new int[2])[1]++;
        }

        if (killerTeam != null) {
            this.teamStats.computeIfAbsent(killerTeam, key -> new int[2])[0]++;
        }
    }

    public Bots(MinecraftServer server) {
        this.server = server;
        this.loadSkinCache();
    }

    private void loadSkinCache() {
        try {
            if (Files.exists(SKIN_FILE)) {
                JsonObject root = JsonParser.parseString(Files.readString(SKIN_FILE)).getAsJsonObject();

                for (Entry<String, JsonElement> entry : root.entrySet()) {
                    JsonObject skin = entry.getValue().getAsJsonObject();
                    if (skin.has("value")) {
                        String value = skin.get("value").getAsString();
                        String signature = skin.has("signature") ? skin.get("signature").getAsString() : null;
                        this.skinDisk.put(entry.getKey(), new String[]{value, signature});
                        Multimap<String, Property> map = HashMultimap.create();
                        map.put("textures", new Property("textures", value, signature));
                        this.skinCache.put(entry.getKey(), Optional.of(new GameProfile(offlineUuid(entry.getKey()), entry.getKey(), new PropertyMap(map))));
                    } else {
                        this.skinCache.put(entry.getKey(), Optional.empty());
                        this.skinDisk.put(entry.getKey(), new String[0]);
                    }
                }
            }
        } catch (RuntimeException | IOException error) {
            BotSystemMod.LOGGER.warn("Skin-Cache nicht lesbar: {}", error.toString());
        }
    }

    private synchronized void saveSkinCache() {
        if (this.skinDiskDirty) {
            this.skinDiskDirty = false;
            JsonObject root = new JsonObject();

            for (Entry<String, String[]> entry : this.skinDisk.entrySet()) {
                JsonObject skin = new JsonObject();
                if (entry.getValue().length > 0) {
                    skin.addProperty("value", entry.getValue()[0]);
                    if (entry.getValue()[1] != null) {
                        skin.addProperty("signature", entry.getValue()[1]);
                    }
                }

                root.add(entry.getKey(), skin);
            }

            try {
                Files.createDirectories(SKIN_FILE.getParent());
                Files.writeString(SKIN_FILE, new GsonBuilder().create().toJson(root));
            } catch (IOException error) {
                BotSystemMod.LOGGER.warn("Skin-Cache nicht schreibbar: {}", error.toString());
            }
        }
    }

    private void rememberSkin(String key, Optional<GameProfile> found) {
        this.skinCache.put(key, found);
        if (found.isPresent()) {
            Iterator var3 = found.get().properties().get("textures").iterator();
            if (var3.hasNext()) {
                Property property = (Property)var3.next();
                this.skinDisk.put(key, new String[]{property.value(), property.signature()});
            }
        } else {
            this.skinDisk.put(key, new String[0]);
        }

        this.skinDiskDirty = true;
        this.saveSkinCache();
    }

    public MinecraftServer server() {
        return this.server;
    }

    public BotPlayer byId(UUID id) {
        return this.bots.get(id);
    }

    public Collection<BotPlayer> all() {
        return new ArrayList<>(this.bots.values());
    }

    public MobUnits mobs() {
        return BotSystemMod.mobs();
    }

    public static List<ServerPlayer> realPlayers(List<ServerPlayer> players) {
        long stamp = System.nanoTime() / 25000000L;
        if (players == realPlayersSource && stamp == realPlayersStamp && realPlayersCache.size() <= players.size()) {
            return realPlayersCache;
        }

        boolean anyBot = false;

        for (ServerPlayer player : players) {
            if (player instanceof BotPlayer) {
                anyBot = true;
                break;
            }
        }

        List<ServerPlayer> real;
        if (!anyBot) {
            real = players;
        } else {
            real = new ArrayList<>();

            for (ServerPlayer player : players) {
                if (!(player instanceof BotPlayer)) {
                    real.add(player);
                }
            }
        }

        realPlayersSource = players;
        realPlayersStamp = stamp;
        realPlayersCache = real;
        return real;
    }

    public static void bootstrap() {
    }

    public void refreshAnchors(long tick) {
        this.blocks.tick(tick);
        if (tick % 20L == 0L) {
            if (!BotDefaults.get().anchors) {
                if (!this.anchors.isEmpty()) {
                    this.releaseAnchors();
                }
            } else {
                int budget = Math.max(1, BotDefaults.get().anchorsPerSecond);
                Map<ServerLevel, LongOpenHashSet> wanted = new HashMap<>();

                for (BotPlayer bot : this.bots.values()) {
                    if (!bot.isRemoved()) {
                        wantAround(wanted, bot.level(), bot.chunkPosition().x, bot.chunkPosition().z);
                    }
                }

                MobUnits mobs = this.mobs();
                if (mobs != null) {
                    for (MobUnits.Unit unit : mobs.all()) {
                        if (!unit.mob.isRemoved()) {
                            wantAround(wanted, (ServerLevel)unit.mob.level(), unit.mob.chunkPosition().x, unit.mob.chunkPosition().z);
                        }
                    }
                }

                for (Entry<ServerLevel, LongOpenHashSet> entry : wanted.entrySet()) {
                    LongOpenHashSet current = this.anchors.computeIfAbsent(entry.getKey(), keyx -> new LongOpenHashSet());

                    for (long key : entry.getValue()) {
                        if (!current.contains(key)) {
                            int cx = ChunkPos.getX(key);
                            int cz = ChunkPos.getZ(key);
                            boolean loaded = entry.getKey().getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) != null;
                            if (loaded || budget-- > 0) {
                                current.add(key);
                                entry.getKey().getWorld().addPluginChunkTicket(cx, cz, BotSystemPlugin.get());
                            }
                        }
                    }
                }

                for (Entry<ServerLevel, LongOpenHashSet> entry : this.anchors.entrySet()) {
                    LongOpenHashSet keep = wanted.get(entry.getKey());
                    LongOpenHashSet current = entry.getValue();
                    LongIterator it = current.iterator();

                    while (it.hasNext()) {
                        long key = it.nextLong();
                        if (keep == null || !keep.contains(key)) {
                            entry.getKey().getWorld().removePluginChunkTicket(ChunkPos.getX(key), ChunkPos.getZ(key), BotSystemPlugin.get());
                            it.remove();
                        }
                    }
                }
            }
        }
    }

    private static void wantAround(Map<ServerLevel, LongOpenHashSet> wanted, ServerLevel world, int cx, int cz) {
        LongOpenHashSet set = wanted.computeIfAbsent(world, key -> new LongOpenHashSet());

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                set.add(ChunkPos.asLong(cx + dx, cz + dz));
            }
        }
    }

    public void releaseAnchors() {
        for (Entry<ServerLevel, LongOpenHashSet> entry : this.anchors.entrySet()) {
            for (long key : entry.getValue()) {
                entry.getKey().getWorld().removePluginChunkTicket(ChunkPos.getX(key), ChunkPos.getZ(key), BotSystemPlugin.get());
            }
        }

        this.anchors.clear();
    }

    public Optional<BotPlayer> byName(String name) {
        return this.bots.values().stream().filter(bot -> bot.getGameProfile().name().equalsIgnoreCase(name)).findFirst();
    }

    public Optional<BotPlayer> byUuid(UUID id) {
        return Optional.ofNullable(this.bots.get(id));
    }

    public boolean isBot(UUID id) {
        return this.bots.containsKey(id);
    }

    public List<String> names() {
        return this.bots.values().stream().map(bot -> bot.getGameProfile().name()).toList();
    }

    public List<String> teams() {
        List<String> teams = new ArrayList<>(this.bots.values().stream().map(bot -> bot.team).filter(Objects::nonNull).distinct().toList());
        if (this.mobs() != null) {
            for (String team : this.mobs().teams()) {
                if (teams.stream().noneMatch(t -> t.equalsIgnoreCase(team))) {
                    teams.add(team);
                }
            }
        }

        teams.sort(String.CASE_INSENSITIVE_ORDER);
        return teams;
    }

    public String freshName(BotNameStyle style, int min, int max) {
        for (int attempt = 0; attempt < 2000; attempt++) {
            String candidate = style.generate(FIRST, SECOND, min, max);
            if (this.nameFree(candidate)) {
                return candidate;
            }
        }

        return "Bot" + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    public String freshNameWith(String tag, BotNameStyle style, int min, int max) {
        String clean = tag.replaceAll("[^A-Za-z0-9_]", "");
        if (!clean.isEmpty() && clean.length() <= 14) {
            int room = 16 - clean.length();
            int lo = Math.max(1, Math.min(min, max) - clean.length());
            int hi = Math.max(lo, Math.min(room, Math.max(min, max) - clean.length()));
            ThreadLocalRandom random = ThreadLocalRandom.current();

            for (int attempt = 0; attempt < 2000; attempt++) {
                String extra = hi <= 2 ? String.valueOf(random.nextInt(10, 99)) : style.generate(FIRST, SECOND, Math.max(2, lo), hi);
                if (extra.length() > room) {
                    extra = extra.substring(0, room);
                }

                boolean underscore = extra.length() + 1 <= room && random.nextInt(3) == 0;

                String candidate = switch (random.nextInt(4)) {
                    case 0 -> clean + (underscore ? "_" : "") + extra;
                    case 1 -> extra + (underscore ? "_" : "") + clean;
                    case 2 -> clean + random.nextInt(10, 99);
                    default -> (random.nextBoolean() ? "x" : "") + clean + (random.nextBoolean() ? "_" : "") + random.nextInt(100, 999);
                };
                if (candidate.length() <= 16 && this.nameFree(candidate)) {
                    return candidate;
                }
            }

            return this.freshName(style, min, max);
        } else {
            return this.freshName(style, min, max);
        }
    }

    public boolean nameFree(String name) {
        return name.matches("[A-Za-z0-9_]{3,16}") && this.byName(name).isEmpty() && this.server.getPlayerList().getPlayer(name) == null;
    }

    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("BotSystem:" + name.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
    }

    public CompletableFuture<GameProfile> profileFor(String botName, String skinSource) {
        GameProfile plain = new GameProfile(offlineUuid(botName), botName);
        if (skinSource == null || skinSource.isBlank()) {
            return CompletableFuture.completedFuture(plain);
        } else {
            return !skinSource.equalsIgnoreCase("random")
                ? this.lookupSkin(skinSource).thenApply(real -> real.<GameProfile>map(profile -> withTextures(plain, profile)).orElse(plain))
                : this.randomSkin(plain, 4);
        }
    }

    public String nextCachedPoolSkin() {
        for (int i = 0; i < this.shuffledPool.size(); i++) {
            String name = this.nextPoolSkin();
            Optional<GameProfile> cached = this.skinCache.get(name.toLowerCase(Locale.ROOT));
            if (cached != null && cached.isPresent()) {
                return name;
            }
        }

        return null;
    }

    public void applySkin(BotPlayer bot, GameProfile withTextures) {
        if (!bot.isRemoved() && this.bots.containsKey(bot.getUUID()) && !withTextures.properties().get("textures").isEmpty()) {
            Multimap<String, Property> merged = LinkedHashMultimap.create(withTextures.properties());
            merged.removeAll("botsystem_color");
            merged.removeAll(SkinFiles.PROPERTY);
            merged.removeAll(SkinFiles.FILE_PROPERTY);
            merged.putAll("botsystem_color", bot.getGameProfile().properties().get("botsystem_color"));
            this.applyProfile(bot, new PropertyMap(merged));
        }
    }

    /**
     * Skin from a PNG file: the image rides along in the profile for the client mod, and if
     * MineSkin signed it ({value, signature}), vanilla clients get the real texture as well.
     */
    public void applySkinFile(BotPlayer bot, SkinFiles.Skin skin, String[] signed) {
        if (!bot.isRemoved() && this.bots.containsKey(bot.getUUID())) {
            Multimap<String, Property> merged = LinkedHashMultimap.create(bot.getGameProfile().properties());
            merged.removeAll(SkinFiles.PROPERTY);
            merged.removeAll(SkinFiles.FILE_PROPERTY);
            merged.put(SkinFiles.PROPERTY, new Property(SkinFiles.PROPERTY, skin.propertyValue()));
            merged.put(SkinFiles.FILE_PROPERTY, new Property(SkinFiles.FILE_PROPERTY, skin.name() + (skin.slim() ? "|slim" : "|classic")));
            if (signed != null) {
                merged.removeAll("textures");
                merged.put("textures", new Property("textures", signed[0], signed[1]));
            }

            this.applyProfile(bot, new PropertyMap(merged));
        }
    }

    public void applySkinColor(BotPlayer bot, String hex) {
        if (!bot.isRemoved() && this.bots.containsKey(bot.getUUID())) {
            Multimap<String, Property> merged = LinkedHashMultimap.create(bot.getGameProfile().properties());
            merged.removeAll("botsystem_color");
            if (hex != null) {
                merged.put("botsystem_color", new Property("botsystem_color", hex));
            }

            this.applyProfile(bot, new PropertyMap(merged));
        }
    }

    private void applyProfile(BotPlayer bot, PropertyMap properties) {
        Nms.setGameProfile(bot, new GameProfile(bot.getUUID(), bot.getGameProfile().name(), properties));
        List<ServerPlayer> viewers = realPlayers(this.server.getPlayerList().getPlayers());
        if (!viewers.isEmpty()) {
            ClientboundPlayerInfoRemovePacket remove = new ClientboundPlayerInfoRemovePacket(List.of(bot.getUUID()));
            ClientboundPlayerInfoUpdatePacket add = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(bot));

            for (ServerPlayer viewer : viewers) {
                viewer.connection.send(remove);
                viewer.connection.send(add);
            }

            ServerLevel world = bot.level();
            world.getChunkSource().removeEntity(bot);
            world.getChunkSource().addEntity(bot);
        }
    }

    private CompletableFuture<GameProfile> randomSkin(GameProfile plain, int attempts) {
        String name = this.nextPoolSkin();
        return this.lookupSkin(name).thenCompose(real -> {
            if (real.isPresent() && !real.get().properties().get("textures").isEmpty()) {
                return CompletableFuture.completedFuture(withTextures(plain, real.get()));
            } else {
                return attempts > 1 ? this.randomSkin(plain, attempts - 1) : CompletableFuture.completedFuture(plain);
            }
        });
    }

    private CompletableFuture<Optional<GameProfile>> lookupSkin(String name) {
        return this.lookupSkin(name, 0);
    }

    public Optional<GameProfile> cachedSkin(String name) {
        return this.skinCache.get(name.toLowerCase(Locale.ROOT));
    }

    private CompletableFuture<Optional<GameProfile>> lookupSkin(String name, int priority) {
        String key = name.toLowerCase(Locale.ROOT);
        Optional<GameProfile> cached = this.skinCache.get(key);
        return cached != null ? CompletableFuture.completedFuture(cached) : this.skinLookups.computeIfAbsent(key, k -> {
            CompletableFuture<Optional<GameProfile>> future = new CompletableFuture<>();
            this.skinExecutor.execute(new Bots.SkinTask(priority, this.skinOrder.incrementAndGet(), () -> {
                try {
                    future.complete(this.fetchSkin(name));
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            }));
            return future.orTimeout(120L, TimeUnit.SECONDS).handle((result, error) -> {
                Optional<GameProfile> found = (Optional<GameProfile>)(error == null && result != null ? result : Optional.empty());
                if (error == null && result != null) {
                    this.rememberSkin(key, found);
                }

                this.skinLookups.remove(key);
                return found;
            });
        });
    }

    private Optional<GameProfile> fetchSkin(String name) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Thread.sleep(attempt == 0 ? 350L : 2500L * attempt);
                Optional<GameProfile> found = this.server.services().profileResolver().fetchByName(name);
                if (found.isPresent() && found.get().properties().get("textures").isEmpty()) {
                    return Optional.empty();
                }

                return found;
            } catch (MinecraftClientHttpException error) {
                if (error.getStatus() == 404) {
                    return Optional.empty();
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return null;
            } catch (RuntimeException var6) {
            }
        }

        return null;
    }

    public static GameProfile withTextures(GameProfile target, GameProfile source) {
        Multimap<String, Property> map = LinkedHashMultimap.create();

        for (Property property : source.properties().get("textures")) {
            map.put("textures", property);
        }

        for (Property property : source.properties().get("botsystem_color")) {
            map.put("botsystem_color", property);
        }

        if (!map.containsKey("botsystem_color")) {
            for (Property property : target.properties().get("botsystem_color")) {
                map.put("botsystem_color", property);
            }
        }

        return map.isEmpty() ? target : new GameProfile(target.id(), target.name(), new PropertyMap(map));
    }

    public BotPlayer spawn(ServerLevel world, GameProfile profile, Vec3 position, float yaw) {
        BotPlayer bot = BotPlayer.spawn(this.server, world, profile, position, yaw);
        BotDefaults.get().applyTo(bot);
        this.bots.put(bot.getUUID(), bot);
        return bot;
    }

    public void remove(BotPlayer bot) {
        this.bots.remove(bot.getUUID());
        bot.despawn();
    }

    private void scheduleRespawn(BotPlayer bot, long tick) {
        this.ghosts
            .add(
                new Bots.Ghost(
                    bot.getGameProfile(),
                    bot.level(),
                    bot.team,
                    bot.kitName,
                    bot.skill.skill(),
                    bot.stance,
                    bot.immortal,
                    new HashSet<>(bot.enemyTeams),
                    new HashSet<>(bot.hostileTeams),
                    new HashSet<>(bot.allyTeams),
                    bot.fightMode,
                    bot.freeForAll,
                    bot.sightRange,
                    bot.post,
                    bot.patrolArea,
                    bot.followTarget,
                    bot.guardMode,
                    tick + 20L * Math.max(1, BotDefaults.get().respawnSeconds)
                )
            );
    }

    private void respawnDue(long tick) {
        if (!this.ghosts.isEmpty()) {
            Iterator<Bots.Ghost> it = this.ghosts.iterator();
            int done = 0;

            while (it.hasNext() && done < 8) {
                Bots.Ghost ghost = it.next();
                if (tick >= ghost.dueTick()) {
                    it.remove();
                    done++;
                    if (!this.bots.containsKey(ghost.profile().id()) && this.server.getPlayerList().getPlayer(ghost.profile().id()) == null) {
                        Vec3 spot = this.spawnPointFor(ghost.world(), ghost.team());
                        BotPlayer fresh = this.spawn(ghost.world(), ghost.profile(), spot, ThreadLocalRandom.current().nextFloat() * 360.0F);
                        fresh.team = ghost.team();
                        fresh.skill.setSkill(ghost.skill());
                        fresh.stance = ghost.stance();
                        fresh.immortal = ghost.immortal();
                        fresh.enemyTeams.addAll(ghost.enemyTeams());
                        fresh.hostileTeams.addAll(ghost.hostileTeams());
                        fresh.allyTeams.addAll(ghost.allyTeams());
                        fresh.fightMode = ghost.fightMode();
                        fresh.freeForAll = ghost.freeForAll();
                        fresh.sightRange = ghost.sightRange();
                        fresh.post = ghost.post();
                        fresh.patrolArea = ghost.patrolArea();
                        fresh.followTarget = ghost.followTarget();
                        fresh.guardMode = ghost.guardMode();
                        if (ghost.kit() != null && BotSystemMod.kits() != null) {
                            BotSystemMod.kits().find(ghost.kit()).ifPresent(kit -> kit.applyTo(fresh));
                        }
                    }
                }
            }
        }
    }

    public Vec3 spawnPointFor(ServerLevel world, String team) {
        Vec3 base = team == null ? null : this.spawnPoints.get(team.toLowerCase(Locale.ROOT));
        if (base == null) {
            BlockPos pos = world.getRespawnData().pos();
            base = new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        }

        double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2.0;
        double radius = ThreadLocalRandom.current().nextDouble(0.5, 4.0);
        Vec3 dry = Ground.findDry(world, base.x + Math.cos(angle) * radius, base.z + Math.sin(angle) * radius, base.y);
        return dry != null ? dry : Ground.find(world, base.x, base.z, base.y);
    }

    public int pendingRespawns() {
        return this.ghosts.size();
    }

    public int removeAll() {
        int count = 0;

        for (BotPlayer bot : this.all()) {
            this.remove(bot);
            count++;
        }

        this.releaseAnchors();
        this.teamStats.clear();
        return count;
    }

    public void sweep(long tick) {
        for (BotPlayer bot : this.all()) {
            if (bot.removeAtTick >= 0L && tick >= bot.removeAtTick) {
                if (BotDefaults.get().respawn) {
                    this.scheduleRespawn(bot, tick);
                }

                this.remove(bot);
            } else if (bot.isRemoved() && bot.removeAtTick < 0L) {
                this.bots.remove(bot.getUUID());
            }
        }

        this.respawnDue(tick);
    }

    private record Expiring(ItemEntity item, long until) {
    }

    private record Ghost(
        GameProfile profile,
        ServerLevel world,
        String team,
        String kit,
        double skill,
        Stance stance,
        boolean immortal,
        Set<String> enemyTeams,
        Set<String> hostileTeams,
        Set<String> allyTeams,
        boolean fightMode,
        boolean freeForAll,
        double sightRange,
        Vec3 post,
        double[] patrolArea,
        UUID followTarget,
        boolean guardMode,
        long dueTick
    ) {
    }

    private record PendingDrop(ServerLevel world, double x, double y, double z, long tick) {
    }

    private record SkinTask(int priority, long order, Runnable body) implements Runnable, Comparable<Bots.SkinTask> {
        @Override
        public void run() {
            this.body.run();
        }

        public int compareTo(Bots.SkinTask other) {
            return this.priority != other.priority ? Integer.compare(this.priority, other.priority) : Long.compare(this.order, other.order);
        }
    }
}
