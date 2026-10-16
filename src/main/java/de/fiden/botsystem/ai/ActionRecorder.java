package de.fiden.botsystem.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.fiden.botsystem.BotSystemPlugin;
import de.fiden.botsystem.bot.BotPlayer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Records everything a player does - movement, looking, sneaking, sprinting, pose, held items,
 * blocking/eating, arm swings, placed and broken blocks - tick by tick, and plays it back on any
 * number of bots, each relative to where it stands.
 *
 * Playback is cheap on purpose: a replaying bot gets no AI and no physics; its recorded position
 * is simply set each tick (about what an idle bot costs), so 1000 bots can dance in sync.
 */
public final class ActionRecorder {
    private static final Gson GSON = new GsonBuilder().create();
    private static final int MAX_FRAMES = 20 * 60 * 20; // 20 minutes
    private static final Map<UUID, Session> ACTIVE = new HashMap<>();
    private static final Map<UUID, Recording> LAST = new HashMap<>();
    private static final Map<String, Recording> CACHE = new HashMap<>();

    private ActionRecorder() {
    }

    private static Path folder() {
        Path dir = BotSystemPlugin.configDir().resolve("actions");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        return dir;
    }

    // ------------------------------------------------------------------ recording

    /** Stored form: flat arrays, small on disk and quick to play. */
    public static final class Recording {
        public String name;
        public String world;
        public int frames;
        /** x,y,z per frame, relative to the first frame. */
        public float[] pos;
        /** yaw, pitch, head yaw, body yaw per frame. */
        public float[] rot;
        /** bit 0 sneak, 1 sprint, 2 using item, 3 using off hand. */
        public byte[] flags;
        public byte[] pose;
        /** Index into items for main/off hand per frame. */
        public short[] main;
        public short[] off;
        public List<String> items = new ArrayList<>();
        public List<int[]> events = new ArrayList<>();
        /** Block states of PLACE events, by event index. */
        public Map<Integer, String> blocks = new HashMap<>();

        public double seconds() {
            return this.frames / 20.0;
        }
    }

    /** Event types (events: frame, type, dx, dy, dz). */
    static final int SWING_MAIN = 0;
    static final int SWING_OFF = 1;
    static final int PLACE = 2;
    static final int BREAK = 3;

    private static final class Session {
        final String world;
        final Vec3 origin;
        final List<float[]> frames = new ArrayList<>();
        final List<String> items = new ArrayList<>();
        final Map<String, Short> itemIndex = new HashMap<>();
        final List<int[]> events = new ArrayList<>();
        final Map<Integer, String> blocks = new HashMap<>();

        Session(ServerPlayer player) {
            this.world = player.level().dimension().identifier().toString();
            this.origin = player.position();
        }

        short item(ItemStack stack) {
            String key = stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            return this.itemIndex.computeIfAbsent(key, k -> {
                this.items.add(k);
                return (short)(this.items.size() - 1);
            });
        }
    }

    public static boolean recording(UUID player) {
        return ACTIVE.containsKey(player);
    }

    public static void start(ServerPlayer player) {
        Session session = new Session(player);
        ACTIVE.put(player.getUUID(), session);
        sample(player, session);
    }

    /** Stops the recording; it is kept as the player's "last" one until saved. */
    public static Recording stop(UUID player) {
        Session session = ACTIVE.remove(player);
        if (session == null || session.frames.size() < 2) {
            return LAST.get(player);
        }

        Recording recording = pack(session);
        LAST.put(player, recording);
        return recording;
    }

    public static Recording last(UUID player) {
        return LAST.get(player);
    }

    private static Recording pack(Session session) {
        Recording rec = new Recording();
        int n = session.frames.size();
        rec.world = session.world;
        rec.frames = n;
        rec.pos = new float[n * 3];
        rec.rot = new float[n * 4];
        rec.flags = new byte[n];
        rec.pose = new byte[n];
        rec.main = new short[n];
        rec.off = new short[n];
        for (int i = 0; i < n; i++) {
            float[] f = session.frames.get(i);
            System.arraycopy(f, 0, rec.pos, i * 3, 3);
            System.arraycopy(f, 3, rec.rot, i * 4, 4);
            rec.flags[i] = (byte)f[7];
            rec.pose[i] = (byte)f[8];
            rec.main[i] = (short)f[9];
            rec.off[i] = (short)f[10];
        }
        rec.items = new ArrayList<>(session.items);
        rec.events = new ArrayList<>(session.events);
        rec.blocks = new HashMap<>(session.blocks);
        return rec;
    }

    private static void sample(ServerPlayer player, Session session) {
        Vec3 at = player.position().subtract(session.origin);
        int flags = (player.isShiftKeyDown() ? 1 : 0)
            | (player.isSprinting() ? 2 : 0)
            | (player.isUsingItem() ? 4 : 0)
            | (player.isUsingItem() && player.getUsedItemHand() == InteractionHand.OFF_HAND ? 8 : 0);
        session.frames.add(new float[]{
            (float)at.x, (float)at.y, (float)at.z,
            player.getYRot(), player.getXRot(), player.getYHeadRot(), player.yBodyRot,
            flags, player.getPose().ordinal(),
            session.item(player.getMainHandItem()), session.item(player.getOffhandItem())
        });
    }

    /** Called every tick: samples everyone recording. */
    public static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) {
            return;
        }

        ACTIVE.entrySet().removeIf(entry -> {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            Session session = entry.getValue();
            if (player == null || !player.level().dimension().identifier().toString().equals(session.world) || session.frames.size() >= MAX_FRAMES) {
                if (session.frames.size() >= 2) {
                    LAST.put(entry.getKey(), pack(session));
                }
                return true;
            }

            sample(player, session);
            return false;
        });
    }

    /** Arm swing (left click, hit, use): from the Bukkit listener. */
    public static void onSwing(UUID player, boolean offHand) {
        Session session = ACTIVE.get(player);
        if (session != null) {
            session.events.add(new int[]{session.frames.size() - 1, offHand ? SWING_OFF : SWING_MAIN, 0, 0, 0});
        }
    }

    public static void onBlock(UUID player, BlockPos pos, BlockState placed) {
        Session session = ACTIVE.get(player);
        if (session != null) {
            int dx = pos.getX() - (int)Math.floor(session.origin.x);
            int dy = pos.getY() - (int)Math.floor(session.origin.y);
            int dz = pos.getZ() - (int)Math.floor(session.origin.z);
            session.events.add(new int[]{session.frames.size() - 1, placed == null ? BREAK : PLACE, dx, dy, dz});
            if (placed != null) {
                session.blocks.put(session.events.size() - 1, BuiltInRegistries.BLOCK.getKey(placed.getBlock()).toString());
            }
        }
    }

    // ------------------------------------------------------------------ storage

    public static void save(String name, Recording recording) throws IOException {
        recording.name = name;
        Files.writeString(folder().resolve(name.toLowerCase(Locale.ROOT) + ".json"), GSON.toJson(recording), StandardCharsets.UTF_8);
        CACHE.put(name.toLowerCase(Locale.ROOT), recording);
    }

    public static Recording load(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Recording cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        Path file = folder().resolve(key + ".json");
        if (!Files.exists(file)) {
            return null;
        }

        try {
            Recording rec = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Recording.class);
            if (rec != null) {
                CACHE.put(key, rec);
            }
            return rec;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    public static List<String> names() {
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder())) {
            files.map(file -> file.getFileName().toString()).filter(file -> file.endsWith(".json")).forEach(file -> names.add(file.substring(0, file.length() - 5)));
        } catch (IOException ignored) {
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public static boolean delete(String name) {
        CACHE.remove(name.toLowerCase(Locale.ROOT));
        try {
            return Files.deleteIfExists(folder().resolve(name.toLowerCase(Locale.ROOT) + ".json"));
        } catch (IOException error) {
            return false;
        }
    }

    // ------------------------------------------------------------------ playback

    /** One bot playing a recording. */
    public static final class Playback {
        final Recording rec;
        final Vec3 origin;
        final BlockPos blockOrigin;
        final boolean loop;
        final boolean blocks;
        final ItemStack keptMain;
        final ItemStack keptOff;
        final Item[] items;
        int frame;
        int nextEvent;
        int lastMain = -1;
        int lastOff = -1;

        Playback(BotPlayer bot, Recording rec, boolean loop, boolean blocks, int startFrame) {
            this.rec = rec;
            this.origin = bot.position();
            this.blockOrigin = BlockPos.containing(bot.position());
            this.loop = loop;
            this.blocks = blocks;
            this.keptMain = bot.getMainHandItem().copy();
            this.keptOff = bot.getOffhandItem().copy();
            this.items = new Item[rec.items.size()];
            for (int i = 0; i < this.items.length; i++) {
                Identifier id = Identifier.tryParse(rec.items.get(i));
                this.items[i] = id == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(id);
            }
            this.frame = Math.min(startFrame, rec.frames - 1); // negative = wait that many ticks first
        }
    }

    public static void play(BotPlayer bot, Recording rec, boolean loop, boolean blocks, int startFrame) {
        if (bot.replay instanceof Playback old) {
            finish(bot, old);
        }

        bot.replay = new Playback(bot, rec, loop, blocks, startFrame);
        bot.setMoveInput(0.0F, 0.0F);
        bot.wantJump(false);
        bot.walkGoal = null;
        bot.path = null;
    }

    public static void halt(BotPlayer bot) {
        if (bot.replay instanceof Playback playback) {
            finish(bot, playback);
        }
    }

    private static void finish(BotPlayer bot, Playback playback) {
        bot.replay = null;
        bot.setShiftKeyDown(false);
        bot.setSprinting(false);
        bot.setPose(Pose.STANDING);
        if (bot.isUsingItem()) {
            bot.stopUsingItem();
        }
        bot.setItemSlot(EquipmentSlot.MAINHAND, playback.keptMain);
        bot.setItemSlot(EquipmentSlot.OFFHAND, playback.keptOff);
        bot.setDeltaMovement(Vec3.ZERO);
    }

    /**
     * One playback tick (instead of the bot's normal tick). False when the playback just ended -
     * the bot then ticks normally again.
     */
    public static boolean step(BotPlayer bot) {
        if (!(bot.replay instanceof Playback pb)) {
            return false;
        }

        Recording rec = pb.rec;
        if (pb.frame < 0) {
            pb.frame++;
            return true;
        }

        if (pb.frame >= rec.frames) {
            if (!pb.loop) {
                finish(bot, pb);
                return false;
            }
            pb.frame = 0;
            pb.nextEvent = 0;
        }

        int f = pb.frame;
        double x = pb.origin.x + rec.pos[f * 3];
        double y = pb.origin.y + rec.pos[f * 3 + 1];
        double z = pb.origin.z + rec.pos[f * 3 + 2];
        ServerLevel level = bot.level();
        if (!level.hasChunkAt(BlockPos.containing(x, y, z))) {
            return true; // wait for the ground to load
        }

        float yaw = rec.rot[f * 4];
        float pitch = rec.rot[f * 4 + 1];
        bot.absSnapTo(x, y, z, yaw, pitch);
        bot.setYHeadRot(rec.rot[f * 4 + 2]);
        bot.yBodyRot = rec.rot[f * 4 + 3];
        bot.setDeltaMovement(Vec3.ZERO);
        bot.setOnGround(true);

        int flags = rec.flags[f];
        bot.setShiftKeyDown((flags & 1) != 0);
        bot.setSprinting((flags & 2) != 0);
        Pose[] poses = Pose.values();
        int pose = rec.pose[f];
        bot.setPose(pose >= 0 && pose < poses.length ? poses[pose] : Pose.STANDING);

        if (rec.main[f] != pb.lastMain) {
            pb.lastMain = rec.main[f];
            bot.setItemSlot(EquipmentSlot.MAINHAND, pb.lastMain < pb.items.length ? new ItemStack(pb.items[pb.lastMain]) : ItemStack.EMPTY);
        }
        if (rec.off[f] != pb.lastOff) {
            pb.lastOff = rec.off[f];
            bot.setItemSlot(EquipmentSlot.OFFHAND, pb.lastOff < pb.items.length ? new ItemStack(pb.items[pb.lastOff]) : ItemStack.EMPTY);
        }

        boolean using = (flags & 4) != 0;
        if (using && !bot.isUsingItem()) {
            bot.startUsingItem((flags & 8) != 0 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        } else if (!using && bot.isUsingItem()) {
            bot.stopUsingItem();
        }

        while (pb.nextEvent < rec.events.size() && rec.events.get(pb.nextEvent)[0] <= f) {
            int index = pb.nextEvent++;
            int[] event = rec.events.get(index);
            switch (event[1]) {
                case SWING_MAIN -> bot.swing(InteractionHand.MAIN_HAND, true);
                case SWING_OFF -> bot.swing(InteractionHand.OFF_HAND, true);
                case PLACE, BREAK -> {
                    if (pb.blocks) {
                        replayBlock(level, pb.blockOrigin.offset(event[2], event[3], event[4]), event[1] == PLACE ? rec.blocks.get(index) : null);
                    }
                    bot.swing(InteractionHand.MAIN_HAND, true);
                }
                default -> {
                }
            }
        }

        // What the skipped vanilla tick would have counted down (damage flash, hit immunity).
        if (bot.hurtTime > 0) {
            bot.hurtTime--;
        }
        if (bot.invulnerableTime > 0) {
            bot.invulnerableTime--;
        }

        pb.frame++;
        return true;
    }

    private static void replayBlock(ServerLevel level, BlockPos pos, String placed) {
        if (!level.hasChunkAt(pos)) {
            return;
        }

        BlockState now = level.getBlockState(pos);
        if (placed == null) {
            if (!now.isAir() && now.getDestroySpeed(level, pos) >= 0.0F && now.getDestroySpeed(level, pos) < 50.0F) {
                level.destroyBlock(pos, false);
            }
            return;
        }

        Identifier id = Identifier.tryParse(placed);
        Block block = id == null ? Blocks.AIR : BuiltInRegistries.BLOCK.getValue(id);
        if (block != Blocks.AIR && now.canBeReplaced()) {
            level.setBlock(pos, block.defaultBlockState(), 3);
        }
    }
}
