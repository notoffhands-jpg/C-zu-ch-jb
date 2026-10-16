package de.fiden.botsystem.net;

import de.fiden.botsystem.BotSystemMod;
import de.fiden.botsystem.bot.BotPlayer;
import de.fiden.botsystem.bot.Bots;
import de.fiden.botsystem.bot.MobUnits;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;

public final class MapServer {
    private MapServer() {
    }

    public static void register(Plugin plugin) {
        Messenger messenger = Bukkit.getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, "botsystem:map_data");
        messenger.registerOutgoingPluginChannel(plugin, "botsystem:terrain_data");
        messenger.registerOutgoingPluginChannel(plugin, "botsystem:map_hud");
        messenger.registerOutgoingPluginChannel(plugin, "botsystem:render_settings");
        messenger.registerOutgoingPluginChannel(plugin, "botsystem:map_request");
        messenger.registerOutgoingPluginChannel(plugin, "botsystem:terrain_request");
        messenger.registerIncomingPluginChannel(plugin, "botsystem:map_request", (channel, player, message) -> send(((CraftPlayer)player).getHandle(), false));
        messenger.registerIncomingPluginChannel(plugin, "botsystem:terrain_request", (channel, player, message) -> {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(message));
            sendTerrain(((CraftPlayer)player).getHandle(), MapPayloads.TerrainRequest.read(buf).chunks());
        });
    }

    private static void sendTerrain(ServerPlayer player, List<Long> keys) {
        ServerLevel world = player.level();
        List<MapPayloads.Tile> tiles = new ArrayList<>();
        MutableBlockPos pos = new MutableBlockPos();
        int limit = Math.min(keys.size(), 64);

        for (int i = 0; i < limit; i++) {
            long key = keys.get(i);
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            ChunkAccess chunk = world.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk == null) {
                tiles.add(new MapPayloads.Tile(cx, cz, null));
            } else {
                int[] colors = new int[256];
                int[] heights = new int[256];

                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int y = chunk.getHeight(Types.MOTION_BLOCKING, lx, lz);
                        pos.set((cx << 4) + lx, y - 1, (cz << 4) + lz);
                        int color = chunk.getBlockState(pos).getMapColor(world, pos).col;

                        for (int steps = 0; color == 0 && pos.getY() > world.getMinY() && steps < 96; steps++) {
                            pos.setY(pos.getY() - 1);
                            BlockState below = chunk.getBlockState(pos);
                            if (!below.isAir()) {
                                color = below.getMapColor(world, pos).col;
                            }
                        }

                        y = pos.getY() + 1;
                        heights[lz * 16 + lx] = y;
                        colors[lz * 16 + lx] = color == 0 ? 1710618 : color;
                    }
                }

                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int index = lz * 16 + lx;
                        int north;
                        if (lz > 0) {
                            north = heights[index - 16];
                        } else {
                            pos.set((cx << 4) + lx, 0, (cz << 4) + lz - 1);
                            ChunkAccess above = world.getChunkSource().getChunk(cx, cz - 1, ChunkStatus.FULL, false);
                            north = above == null ? heights[index] : above.getHeight(Types.MOTION_BLOCKING, lx, 15);
                        }

                        int height = heights[index];
                        float factor = height > north ? 1.18F : (height < north ? 0.78F : 1.0F);
                        colors[index] = 0xFF000000 | shade(colors[index], factor);
                    }
                }

                tiles.add(new MapPayloads.Tile(cx, cz, colors));
            }
        }

        new MapPayloads.TerrainData(tiles).sendTo(player);
    }

    private static int shade(int rgb, float factor) {
        int r = Math.min(255, Math.round((rgb >> 16 & 0xFF) * factor));
        int g = Math.min(255, Math.round((rgb >> 8 & 0xFF) * factor));
        int b = Math.min(255, Math.round((rgb & 0xFF) * factor));
        return r << 16 | g << 8 | b;
    }

    public static void send(ServerPlayer player, boolean open) {
        Bots bots = BotSystemMod.bots();
        List<MapPayloads.MapBot> list = new ArrayList<>();
        if (bots != null) {
            for (BotPlayer bot : bots.all()) {
                if (!bot.isRemoved() && bot.level() == player.level()) {
                    Vec3 goal = bot.walkGoal != null ? bot.walkGoal : (bot.fightMode && bot.currentTarget != null ? bot.currentTarget.position() : null);
                    String mapTeam = bot.followTarget != null ? "Wachen" : (bot.team == null ? "" : bot.team);
                    list.add(
                        new MapPayloads.MapBot(
                            bot.getGameProfile().name(),
                            mapTeam,
                            bot.getX(),
                            bot.getY(),
                            bot.getZ(),
                            bot.state(),
                            bot.getHealth(),
                            goal == null ? Double.NaN : goal.x,
                            goal == null ? Double.NaN : goal.z
                        )
                    );
                }
            }
        }

        MobUnits mobs = BotSystemMod.mobs();
        if (mobs != null) {
            for (MobUnits.Unit unit : mobs.all()) {
                if (!unit.mob.isRemoved() && unit.mob.level() == player.level()) {
                    Vec3 goal = unit.goal != null ? unit.goal : (unit.mob.getTarget() != null ? unit.mob.getTarget().position() : null);
                    list.add(
                        new MapPayloads.MapBot(
                            "mob:" + unit.name() + "#" + unit.mob.getId(),
                            unit.team == null ? "" : unit.team,
                            unit.mob.getX(),
                            unit.mob.getY(),
                            unit.mob.getZ(),
                            unit.state(),
                            unit.mob.getHealth(),
                            goal == null ? Double.NaN : goal.x,
                            goal == null ? Double.NaN : goal.z
                        )
                    );
                }
            }
        }

        List<MapPayloads.TeamStat> stats = new ArrayList<>();
        if (bots != null) {
            bots.teamStats.forEach((team, counts) -> stats.add(new MapPayloads.TeamStat(team, counts[0], counts[1])));
        }

        if (MapPayloads.canSend(player, "botsystem:map_data")) {
            new MapPayloads.MapData(open, player.getX(), player.getZ(), list, (float)(player.level().getServer().getAverageTickTimeNanos() / 1000000.0), stats)
                .sendTo(player);
        } else if (open) {
            player.sendSystemMessage(
                Component.literal("Die Strategiekarte braucht die BotSystem-Mod auf deinem Client (Server-Befehle gehen auch ohne).")
                    .withStyle(ChatFormatting.YELLOW),
                false
            );
        }
    }
}
