package de.fiden.botsystem;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import de.fiden.botsystem.bot.BotPlayer;
import de.fiden.botsystem.command.BotCommands;
import de.fiden.botsystem.net.MapServer;
import io.papermc.paper.event.player.PlayerServerFullCheckEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.damage.CraftDamageSource;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class BotSystemPlugin extends JavaPlugin implements Listener {
    private static BotSystemPlugin instance;

    public static BotSystemPlugin get() {
        return instance;
    }

    public static Path configDir() {
        Path dir = instance.getDataFolder().toPath();

        try {
            Files.createDirectories(dir);
        } catch (IOException var2) {
        }

        return dir;
    }

    @Override
    public void onLoad() {
        instance = this;
    }

    @Override
    public void onEnable() {
        instance = this;
        MinecraftServer server = MinecraftServer.getServer();
        // Leftover no-collision teams from a crash would keep old army mobs from colliding.
        de.fiden.botsystem.bot.UnitPush.releaseAll(java.util.List.of());
        // Every join (each bot!) re-wrote usercache.json, sorted by last use - with thousands of
        // bot names that alone made big spawns crawl. Spigot's own option: save it at shutdown only.
        if (de.fiden.botsystem.bot.BotDefaults.get().fastJoin) {
            org.spigotmc.SpigotConfig.saveUserCacheOnStopOnly = true;
        }
        BotSystemMod.start(server);
        MapServer.register(this);
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            BotCommands.register(dispatcher);
            LiteralCommandNode<CommandSourceStack> node = (LiteralCommandNode<CommandSourceStack>)dispatcher.getRoot().getChild("bot");
            @SuppressWarnings({"unchecked", "rawtypes"})
            LiteralCommandNode<io.papermc.paper.command.brigadier.CommandSourceStack> apiNode = (LiteralCommandNode) node;
            event.registrar().register(apiNode, "BotSystem: Bots, Mob-Armeen, Strategiekarte");

            for (CommandNode<CommandSourceStack> other : dispatcher.getRoot().getChildren()) {
                if (other != node && other instanceof LiteralCommandNode<CommandSourceStack> literal) {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    LiteralCommandNode<io.papermc.paper.command.brigadier.CommandSourceStack> extra = (LiteralCommandNode) literal;
                    event.registrar().register(extra, "BotSystem: /" + literal.getLiteral());
                }
            }
        });
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, () -> BotSystemMod.tick(server), 1L, 1L);
        // One tick later every plugin is enabled, whatever the load order.
        Bukkit.getScheduler().runTask(this, () -> de.fiden.botsystem.bot.BbsPaperHook.install(this));

        for (World world : Bukkit.getWorlds()) {
            ((CraftWorld)world).getHandle().spigotConfig.ignoreSpectatorActivation = true;
        }

        Bukkit.getPluginManager().registerEvents(new Listener() {
            /** Mob guards/followers never target their owner - also not vanilla's hurt-by/anger AI. */
            @EventHandler(ignoreCancelled = true)
            public void onTarget(org.bukkit.event.entity.EntityTargetLivingEntityEvent event) {
                if (event.getTarget() != null
                    && ((org.bukkit.craftbukkit.entity.CraftEntity)event.getEntity()).getHandle() instanceof net.minecraft.world.entity.LivingEntity living) {
                    de.fiden.botsystem.bot.MobUnits.Unit unit = de.fiden.botsystem.bot.MobUnits.unitOf(living);
                    if (unit != null && event.getTarget().getUniqueId().equals(unit.followId)) {
                        event.setCancelled(true);
                    }
                }
            }

            /**
             * Arrows of army mobs and bots stuck in the ground vanish after 3 s instead of 60 s -
             * a skeleton army leaves thousands lying around otherwise, each one ticking.
             */
            @EventHandler(ignoreCancelled = true)
            public void onArrowLand(org.bukkit.event.entity.ProjectileHitEvent event) {
                if (event.getHitBlock() != null
                    && event.getEntity() instanceof org.bukkit.entity.AbstractArrow arrow
                    && arrow.getShooter() instanceof org.bukkit.entity.LivingEntity shooter
                    && ((org.bukkit.craftbukkit.entity.CraftEntity)shooter).getHandle() instanceof net.minecraft.world.entity.LivingEntity living
                    && (living instanceof BotPlayer || de.fiden.botsystem.bot.MobUnits.unitOf(living) != null)) {
                    arrow.setLifetimeTicks(Math.max(arrow.getLifetimeTicks(), 1200 - 60));
                }
            }

            @EventHandler(ignoreCancelled = true)
            public void onSwing(io.papermc.paper.event.player.PlayerArmSwingEvent event) {
                de.fiden.botsystem.ai.ActionRecorder.onSwing(event.getPlayer().getUniqueId(), event.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND);
            }

            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void onPlace(org.bukkit.event.block.BlockPlaceEvent event) {
                if (de.fiden.botsystem.ai.ActionRecorder.recording(event.getPlayer().getUniqueId())) {
                    org.bukkit.block.Block block = event.getBlockPlaced();
                    de.fiden.botsystem.ai.ActionRecorder.onBlock(
                        event.getPlayer().getUniqueId(),
                        new net.minecraft.core.BlockPos(block.getX(), block.getY(), block.getZ()),
                        ((org.bukkit.craftbukkit.block.data.CraftBlockData)block.getBlockData()).getState()
                    );
                }
            }

            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void onBreak(org.bukkit.event.block.BlockBreakEvent event) {
                if (de.fiden.botsystem.ai.ActionRecorder.recording(event.getPlayer().getUniqueId())) {
                    org.bukkit.block.Block block = event.getBlock();
                    de.fiden.botsystem.ai.ActionRecorder.onBlock(event.getPlayer().getUniqueId(), new net.minecraft.core.BlockPos(block.getX(), block.getY(), block.getZ()), null);
                }
            }

            @EventHandler
            public void onWorldLoad(WorldLoadEvent event) {
                ((CraftWorld)event.getWorld()).getHandle().spigotConfig.ignoreSpectatorActivation = true;
            }
        }, this);
        this.getLogger().info("BotSystem " + BotSystemMod.BUILD + " (Paper) bereit.");
    }

    @Override
    public void onDisable() {
        BotSystemMod.stop(MinecraftServer.getServer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        ServerPlayer player = ((CraftPlayer)event.getPlayer()).getHandle();
        Bukkit.getScheduler().runTaskLater(this, () -> BotSystemMod.onJoin(player), 40L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFullCheck(PlayerServerFullCheckEvent event) {
        if (!event.isAllowed()) {
            long real = Bukkit.getOnlinePlayers().stream().filter(player -> !(((CraftPlayer)player).getHandle() instanceof BotPlayer)).count();
            if (real < Bukkit.getMaxPlayers()) {
                event.allow(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamageEarly(EntityDamageEvent event) {
        if (((CraftEntity)event.getEntity()).getHandle() instanceof LivingEntity living) {
            DamageSource var4 = ((CraftDamageSource)event.getDamageSource()).getHandle();
            if (!BotSystemMod.allowDamage(living, var4, (float)event.getDamage())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageLate(EntityDamageEvent event) {
        if (((CraftEntity)event.getEntity()).getHandle() instanceof LivingEntity living) {
            DamageSource var4 = ((CraftDamageSource)event.getDamageSource()).getHandle();
            BotSystemMod.afterDamage(living, var4);
        }
    }
}
