package de.fiden.botsystem.net;

import de.fiden.botsystem.BotSystemPlugin;
import de.fiden.botsystem.bot.BotDefaults;
import de.fiden.botsystem.bot.BotPlayer;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;

public final class MapPayloads {
    private MapPayloads() {
    }

    static byte[] bytes(Consumer<FriendlyByteBuf> writer) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        writer.accept(buf);
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
        return data;
    }

    public static boolean canSend(ServerPlayer player, String channel) {
        return player != null && player.getBukkitEntity().getListeningPluginChannels().contains(channel);
    }

    static void send(ServerPlayer player, String channel, Consumer<FriendlyByteBuf> writer) {
        if (canSend(player, channel)) {
            CraftPlayer bukkit = player.getBukkitEntity();
            bukkit.sendPluginMessage(BotSystemPlugin.get(), channel, bytes(writer));
        }
    }

    public interface Channel {
        String channel();
    }

    public record MapBot(String name, String team, double x, double y, double z, String state, float health, double goalX, double goalZ) {
        public boolean hasGoal() {
            return !Double.isNaN(this.goalX);
        }

        void write(FriendlyByteBuf buf) {
            buf.writeUtf(this.name);
            buf.writeUtf(this.team);
            buf.writeDouble(this.x);
            buf.writeDouble(this.y);
            buf.writeDouble(this.z);
            buf.writeUtf(this.state);
            buf.writeFloat(this.health);
            buf.writeDouble(this.goalX);
            buf.writeDouble(this.goalZ);
        }
    }

    public record MapData(boolean open, double playerX, double playerZ, List<MapPayloads.MapBot> bots, float mspt, List<MapPayloads.TeamStat> stats) {
        public static final String ID = "botsystem:map_data";

        public void sendTo(ServerPlayer player) {
            MapPayloads.send(player, "botsystem:map_data", buf -> {
                buf.writeBoolean(this.open);
                buf.writeDouble(this.playerX);
                buf.writeDouble(this.playerZ);
                buf.writeVarInt(this.bots.size());
                this.bots.forEach(bot -> bot.write(buf));
                buf.writeFloat(this.mspt);
                buf.writeVarInt(this.stats.size());

                for (MapPayloads.TeamStat stat : this.stats) {
                    buf.writeUtf(stat.team());
                    buf.writeVarInt(stat.kills());
                    buf.writeVarInt(stat.deaths());
                }
            });
        }
    }

    public record MapHud(boolean enabled) {
        public static final String ID = "botsystem:map_hud";

        public void sendTo(ServerPlayer player) {
            MapPayloads.send(player, "botsystem:map_hud", buf -> buf.writeBoolean(this.enabled));
        }
    }

    public record MapRequest() {
        public static final String ID = "botsystem:map_request";
    }

    public record RenderSettings(int lodDistance, int nameTagDistance) {
        public static final String ID = "botsystem:render_settings";

        public void sendTo(ServerPlayer player) {
            MapPayloads.send(player, "botsystem:render_settings", buf -> {
                buf.writeVarInt(this.lodDistance);
                buf.writeVarInt(this.nameTagDistance);
            });
        }

        public static MapPayloads.RenderSettings current() {
            BotDefaults defaults = BotDefaults.get();
            return new MapPayloads.RenderSettings(defaults.lodDistance, defaults.nameTagDistance);
        }

        public static void broadcast(MinecraftServer server) {
            MapPayloads.RenderSettings settings = current();

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!(player instanceof BotPlayer)) {
                    settings.sendTo(player);
                }
            }
        }
    }

    public record TeamStat(String team, int kills, int deaths) {
    }

    public record TerrainData(List<MapPayloads.Tile> tiles) {
        public static final String ID = "botsystem:terrain_data";

        public void sendTo(ServerPlayer player) {
            MapPayloads.send(player, "botsystem:terrain_data", buf -> {
                buf.writeVarInt(this.tiles.size());

                for (MapPayloads.Tile tile : this.tiles) {
                    buf.writeInt(tile.chunkX());
                    buf.writeInt(tile.chunkZ());
                    buf.writeBoolean(tile.colors() != null);
                    if (tile.colors() != null) {
                        for (int color : tile.colors()) {
                            buf.writeInt(color);
                        }
                    }
                }
            });
        }
    }

    public record TerrainRequest(List<Long> chunks) {
        public static final String ID = "botsystem:terrain_request";

        public static MapPayloads.TerrainRequest read(FriendlyByteBuf buf) {
            int count = buf.readVarInt();
            List<Long> chunks = new ArrayList<>(count);

            for (int i = 0; i < count; i++) {
                chunks.add(buf.readLong());
            }

            return new MapPayloads.TerrainRequest(chunks);
        }
    }

    public record Tile(int chunkX, int chunkZ, int[] colors) {
    }
}
