package de.fiden.botsystem.debug;

import de.fiden.botsystem.bot.BotPlayer;
import de.fiden.botsystem.bot.Bots;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

public final class MoveTrace {
    private final Set<UUID> traced = new HashSet<>();
    private Writer writer;
    private long until = -1L;
    private Path file;

    public int start(MinecraftServer server, Iterable<BotPlayer> bots, int seconds) {
        this.stop();
        this.traced.clear();
        int count = 0;

        for (BotPlayer bot : bots) {
            this.traced.add(bot.getUUID());
            count++;
        }

        try {
            this.file = server.getServerDirectory().resolve("trace.csv");
            this.writer = Files.newBufferedWriter(this.file, StandardCharsets.UTF_8);
            this.writer.write("tick,name,x,y,z,yaw,pitch,headYaw,forward,sideways,sprint,jump,ground,state,vx,vz,target\n");
        } catch (IOException error) {
            this.writer = null;
            return 0;
        }

        this.until = server.getTickCount() + seconds * 20L;
        return count;
    }

    public void tick(MinecraftServer server, Bots bots, long tick) {
        if (this.writer != null) {
            if (tick > this.until) {
                this.stop();
            } else {
                try {
                    for (BotPlayer bot : bots.all()) {
                        if (this.traced.contains(bot.getUUID())) {
                            this.writer
                                .write(
                                    tick
                                        + ","
                                        + bot.getGameProfile().name()
                                        + ","
                                        + f(bot.getX())
                                        + ","
                                        + f(bot.getY())
                                        + ","
                                        + f(bot.getZ())
                                        + ","
                                        + f(bot.getYRot())
                                        + ","
                                        + f(bot.getXRot())
                                        + ","
                                        + f(bot.getYHeadRot())
                                        + ","
                                        + f(bot.zza)
                                        + ","
                                        + f(bot.xxa)
                                        + ","
                                        + (bot.isSprinting() ? 1 : 0)
                                        + ","
                                        + (bot.isJumpingDebug() ? 1 : 0)
                                        + ","
                                        + (bot.onGround() ? 1 : 0)
                                        + ","
                                        + bot.state().replace(',', ' ')
                                        + ","
                                        + f(bot.getDeltaMovement().x)
                                        + ","
                                        + f(bot.getDeltaMovement().z)
                                        + ","
                                        + (bot.currentTarget == null ? "-" : bot.currentTarget.getName().getString())
                                        + "\n"
                                );
                        }
                    }
                } catch (IOException ignored) {
                    this.stop();
                }
            }
        }
    }

    public void stop() {
        if (this.writer != null) {
            try {
                this.writer.close();
            } catch (IOException var2) {
            }

            this.writer = null;
        }
    }

    public boolean running() {
        return this.writer != null;
    }

    public Path file() {
        return this.file;
    }

    private static String f(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
