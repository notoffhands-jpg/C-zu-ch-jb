package de.fiden.botsystem.ai;

import de.fiden.botsystem.bot.BotDefaults;
import de.fiden.botsystem.bot.BotPlayer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class StuckReport {
    private static final Path FILE = Path.of("logs", "botsystem-stuck.txt");
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BotSystem-StuckReport");
        thread.setDaemon(true);
        return thread;
    });
    private static long windowStart;
    private static int inWindow;

    private StuckReport() {
    }

    public static void check(BotPlayer bot, Vec3 goal, long tick, Supplier<String> pathInfo) {
        Vec3 pos = bot.position();
        if (bot.stuckSamplePos == null || pos.distanceToSqr(bot.stuckSamplePos) > 4.0) {
            bot.stuckSamplePos = pos;
            bot.stuckSampleTick = tick;
        } else if (tick - bot.stuckSampleTick >= 200L && tick >= bot.nextStuckReport) {
            bot.nextStuckReport = tick + 1200L;
            if (tick - windowStart > 1200L) {
                windowStart = tick;
                inWindow = 0;
            }

            if (++inWindow <= 30) {
                String text = describe(bot, goal, pathInfo.get(), (tick - bot.stuckSampleTick) / 20L);
                WRITER.execute(() -> write(text));
            }
        }
    }

    private static String describe(BotPlayer bot, Vec3 goal, String pathInfo, long seconds) {
        ServerLevel world = bot.level();
        Vec3 pos = bot.position();
        double dx = goal.x - pos.x;
        double dz = goal.z - pos.z;
        StringBuilder text = new StringBuilder();
        text.append(
            String.format(
                Locale.ROOT,
                "[%tT] %s steckt seit %d s fest | pos %.1f %.1f %.1f | Ziel %.0f %.0f %.0f (%.0f Bl., Hoehe %+.0f) | %s | bauen %s, graben %s%s%n",
                new Date(),
                bot.getGameProfile().name(),
                seconds,
                pos.x,
                pos.y,
                pos.z,
                goal.x,
                goal.y,
                goal.z,
                Math.sqrt(dx * dx + dz * dz),
                goal.y - pos.y,
                pathInfo,
                BotDefaults.get().build ? "an" : "aus",
                BotDefaults.get().dig ? "an" : "aus",
                bot.isInWater() ? ", im Wasser" : ""
            )
        );
        int bx = Mth.floor(pos.x);
        int by = Mth.floor(pos.y + 0.2);
        int bz = Mth.floor(pos.z);
        double length = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
        int gx = length < 5.0 ? Mth.floor(goal.x) - bx : (int)Math.round(dx / length * 5.0);
        int gz = length < 5.0 ? Mth.floor(goal.z) - bz : (int)Math.round(dz / length * 5.0);
        Set<Long> others = new HashSet<>();

        for (BotPlayer other : world.getEntitiesOfClass(BotPlayer.class, bot.getBoundingBox().inflate(6.0, 2.0, 6.0), otherx -> otherx != bot)) {
            others.add(BlockPos.asLong(Mth.floor(other.getX()) - bx, 0, Mth.floor(other.getZ()) - bz));
        }

        MutableBlockPos at = new MutableBlockPos();

        for (int z = -5; z <= 5; z++) {
            text.append("   ");

            for (int x = -5; x <= 5; x++) {
                char c;
                if (x == 0 && z == 0) {
                    c = 'B';
                } else if (x == gx && z == gz) {
                    c = 'Z';
                } else if (others.contains(BlockPos.asLong(x, 0, z))) {
                    c = 'o';
                } else {
                    c = cell(world, at, bx + x, by, bz + z);
                }

                text.append(c);
            }

            text.append('\n');
        }

        text.append("   Legende: . frei  # Wand  ^ Stufe (1 hoch)  x Kopfhoehe zu  _ Loch/Absturz  ~ Wasser  D Tuer  o Bot  Z Richtung Ziel\n");
        return text.toString();
    }

    private static boolean solid(ServerLevel world, MutableBlockPos at, int x, int y, int z) {
        at.set(x, y, z);
        return !world.getBlockState(at).getCollisionShape(world, at).isEmpty();
    }

    private static char cell(ServerLevel world, MutableBlockPos at, int x, int y, int z) {
        at.set(x, y, z);
        BlockState feet = world.getBlockState(at);
        if (feet.is(BlockTags.DOORS) || feet.is(BlockTags.FENCE_GATES)) {
            return 'D';
        } else if (!world.getFluidState(at).isEmpty()) {
            return '~';
        } else {
            boolean low = solid(world, at, x, y, z);
            boolean high = solid(world, at, x, y + 1, z);
            if (low && high) {
                return '#';
            } else if (low) {
                return (char)(solid(world, at, x, y + 2, z) ? '#' : '^');
            } else if (high) {
                return 'x';
            } else if (!solid(world, at, x, y - 1, z)) {
                at.set(x, y - 1, z);
                return (char)(!world.getFluidState(at).isEmpty() ? '~' : '_');
            } else {
                return '.';
            }
        }
    }

    private static void write(String text) {
        try {
            Files.createDirectories(FILE.getParent());
            if (Files.exists(FILE) && Files.size(FILE) > 2000000L) {
                Files.move(FILE, FILE.resolveSibling("botsystem-stuck.old.txt"), StandardCopyOption.REPLACE_EXISTING);
            }

            Files.writeString(FILE, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException var2) {
        }
    }
}
