package de.fiden.botsystem.command;

import de.fiden.botsystem.ai.ArmyDrill;
import java.util.Comparator;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;
import de.fiden.botsystem.BotSystemMod;
import de.fiden.botsystem.ai.BotBrain;
import de.fiden.botsystem.ai.BotNavigator;
import de.fiden.botsystem.ai.ExitSense;
import de.fiden.botsystem.ai.Gym;
import de.fiden.botsystem.ai.PathRecorder;
import de.fiden.botsystem.ai.RecordedPath;
import de.fiden.botsystem.bot.Actors;
import de.fiden.botsystem.bot.BotDefaults;
import de.fiden.botsystem.bot.BotNameStyle;
import de.fiden.botsystem.bot.BotPlayer;
import de.fiden.botsystem.bot.BotProfile;
import de.fiden.botsystem.bot.BotSaves;
import de.fiden.botsystem.bot.BotSkill;
import de.fiden.botsystem.bot.Bots;
import de.fiden.botsystem.bot.FarmTarget;
import de.fiden.botsystem.bot.FilmScene;
import de.fiden.botsystem.bot.SkinFiles;
import de.fiden.botsystem.bot.Ground;
import de.fiden.botsystem.bot.Kits;
import de.fiden.botsystem.bot.MobUnits;
import de.fiden.botsystem.bot.NavMemory;
import de.fiden.botsystem.bot.Nms;
import de.fiden.botsystem.bot.Presets;
import de.fiden.botsystem.bot.SkillMemory;
import de.fiden.botsystem.bot.SkinColors;
import de.fiden.botsystem.bot.Stance;
import de.fiden.botsystem.debug.DebugWatch;
import de.fiden.botsystem.debug.TickProfiler;
import de.fiden.botsystem.gui.SettingsMenu;
import de.fiden.botsystem.net.MapPayloads;
import de.fiden.botsystem.net.MapServer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.EquipmentSlot.Type;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ClipContext.Block;
import net.minecraft.world.level.ClipContext.Fluid;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.event.entity.EntityTargetEvent.TargetReason;
import org.slf4j.LoggerFactory;

public final class BotCommands {
    private static final SimpleCommandExceptionType NO_BOTS = new SimpleCommandExceptionType(Component.literal("Keine Bots vorhanden."));
    private static final SuggestionProvider<CommandSourceStack> SELECTORS = (context, builder) -> {
        List<String> options = new ArrayList<>(List.of("@a", "@guards", "@near:10", "@near:25", "near-all"));
        Bots bots = BotSystemMod.bots();
        if (bots != null) {
            bots.teams().forEach(team -> options.add("@team:" + team));
            options.addAll(bots.names());
        }

        return SharedSuggestionProvider.suggest(options, builder);
    };
    private static final SuggestionProvider<CommandSourceStack> TEAMS = (context, builder) -> {
        Bots bots = BotSystemMod.bots();
        return SharedSuggestionProvider.suggest(bots == null ? List.of() : bots.teams(), builder);
    };
    private static final SuggestionProvider<CommandSourceStack> PLAYERS_AND_SELECTORS = (context, builder) -> {
        List<String> options = new ArrayList<>(List.of("me", "@a"));

        for (ServerPlayer player : Bots.realPlayers(context.getSource().getServer().getPlayerList().getPlayers())) {
            options.add(player.getGameProfile().name());
        }

        Bots bots = BotSystemMod.bots();
        if (bots != null) {
            bots.teams().forEach(team -> options.add("@team:" + team));
            options.addAll(bots.names());
        }

        return SharedSuggestionProvider.suggest(options, builder);
    };
    private static final SuggestionProvider<CommandSourceStack> PARTIES = (context, builder) -> {
        String remaining = builder.getRemaining();
        int lastSpace = remaining.lastIndexOf(32);
        SuggestionsBuilder word = builder.createOffset(builder.getStart() + lastSpace + 1);
        List<String> options = new ArrayList<>(List.of(",", "me", "nearby", "@a", "@guards", "sight:10", "sight:off"));
        Bots bots = BotSystemMod.bots();
        if (bots != null) {
            bots.teams().forEach(team -> options.add("@team:" + team));
            options.addAll(bots.names());
        }

        options.addAll(List.of(context.getSource().getServer().getPlayerList().getPlayerNamesArray()));
        return SharedSuggestionProvider.suggest(options, word);
    };
    private static final SuggestionProvider<CommandSourceStack> COLORS = (context, builder) -> SharedSuggestionProvider.suggest(SkinColors.NAMES, builder);
    private static final SuggestionProvider<CommandSourceStack> KITS = (context, builder) -> SharedSuggestionProvider.suggest(
        BotSystemMod.kits() == null ? Kits.BUILTIN : BotSystemMod.kits().names(), builder
    );
    private static final SuggestionProvider<CommandSourceStack> CUSTOM_KITS = (context, builder) -> SharedSuggestionProvider.suggest(
        BotSystemMod.kits() == null ? List.of() : BotSystemMod.kits().customNames(), builder
    );
    private static final SuggestionProvider<CommandSourceStack> SKILLS = (context, builder) -> SharedSuggestionProvider.suggest(
        List.of("Noob", "Okay", "LT5", "HT5", "LT4", "HT4", "LT3", "HT3", "lt5-ht5", "ht4-ht3", "random", "50"), builder
    );
    private static final SuggestionProvider<CommandSourceStack> RECORDED = (context, builder) -> {
        for (RecordedPath path : PathRecorder.all()) {
            builder.suggest(path.name);
        }

        return builder.buildFuture();
    };
    private static final SuggestionProvider<CommandSourceStack> TYPES = (context, builder) -> SharedSuggestionProvider.suggest(
        BotSystemMod.types().names(), builder
    );
    private static final SuggestionProvider<CommandSourceStack> TEAMS_OR_TYPES = (context, builder) -> {
        for (String type : BotSystemMod.types().names()) {
            if (type.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                builder.suggest(type);
            }
        }
        return TEAMS.getSuggestions(context, builder);
    };
    /** Suggests the next word of "/bot type create <name> ..." from the word before it. */
    private static final SuggestionProvider<CommandSourceStack> TYPE_SETTINGS = (context, builder) -> {
        String typed = builder.getRemaining();
        int cut = typed.lastIndexOf(' ') + 1;
        String[] words = typed.substring(0, cut).trim().isEmpty() ? new String[0] : typed.substring(0, cut).trim().split("\\s+");
        String last = words.length == 0 ? "" : words[words.length - 1].toLowerCase(Locale.ROOT);
        String before = words.length < 2 ? "" : words[words.length - 2].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        switch (last) {
            case "randomnick" -> options.addAll(List.of("4", "6", "8", "10", "12"));
            case "skin" -> {
                options.addAll(SkinColors.NAMES);
                options.add("random");
                for (String file : SkinFiles.names()) {
                    options.add("file:" + file);
                }
            }
            case "color", "farbe" -> options.addAll(SkinColors.NAMES);
            case "kit" -> options.addAll(BotSystemMod.kits() == null ? Kits.BUILTIN : BotSystemMod.kits().names());
            case "tier", "stufe" -> {
                for (de.fiden.botsystem.bot.Tiers.Tier tier : de.fiden.botsystem.bot.Tiers.table()) {
                    options.add(tier.name.toLowerCase(Locale.ROOT));
                }
                options.addAll(List.of("lt5-ht5", "ht4-ht3"));
            }
            case "personality", "style" -> options.addAll(List.of("aggressive", "balanced", "cautious"));
            case "target", "ziel" -> options.addAll(List.of("smart", "nearest", "weakest", "strongest", "threat"));
            case "aggression" -> options.addAll(List.of("0.6", "1.0", "1.4", "1.8"));
            case "range" -> options.addAll(List.of("2.2", "2.6", "2.9"));
            case "strafe" -> options.addAll(List.of("0.5", "1.0", "1.5"));
            case "retreat" -> options.addAll(List.of("0", "4", "8"));
            case "reaction" -> options.addAll(List.of("0.6", "1.0", "1.5"));
            case "accuracy", "crit", "shield", "hop" -> options.addAll(List.of("0.5", "0.75", "0.9"));
            case "sprintjump" -> options.addAll(List.of("on", "off"));
            case "nick", "team" -> {
            }
            default -> {
                if (before.equals("randomnick") && last.matches("\\d+")) {
                    options.addAll(List.of("10", "12", "13", "16"));
                } else if (words.length >= 3 && words[words.length - 3].equalsIgnoreCase("randomnick") && last.matches("\\d+")) {
                    options.addAll(List.of("chaos", "gamer", "wort", "silben", "zufall", "mix"));
                } else {
                    options.addAll(List.of(
                        "randomnick", "nick", "skin", "kit", "team", "tier", "personality", "aggression", "range", "strafe", "retreat",
                        "reaction", "accuracy", "crit", "shield", "hop", "target", "sprintjump"
                    ));
                }
            }
        }

        SuggestionsBuilder next = builder.createOffset(builder.getStart() + cut);
        return SharedSuggestionProvider.suggest(options, next);
    };
    private static final SuggestionProvider<CommandSourceStack> PRESETS = (context, builder) -> SharedSuggestionProvider.suggest(
        BotSystemMod.presets().names(), builder
    );
    private static final SuggestionProvider<CommandSourceStack> PLAYERS = (context, builder) -> {
        List<String> options = new ArrayList<>(List.of("me"));
        options.addAll(List.of(context.getSource().getServer().getPlayerList().getPlayerNamesArray()));
        return SharedSuggestionProvider.suggest(options, builder);
    };
    private static final SuggestionProvider<CommandSourceStack> LOOK_TARGETS = (context, builder) -> {
        List<String> options = new ArrayList<>(List.of("me", "off"));
        Bots bots = BotSystemMod.bots();
        if (bots != null) {
            bots.teams().forEach(team -> options.add("@team:" + team));
            options.addAll(bots.names());
        }

        options.addAll(List.of(context.getSource().getServer().getPlayerList().getPlayerNamesArray()));
        return SharedSuggestionProvider.suggest(options, builder);
    };
    private static final SuggestionProvider<CommandSourceStack> MOB_TYPES = (context, builder) -> SharedSuggestionProvider.suggest(
        List.of(
            "wolf",
            "iron_golem",
            "zombie",
            "skeleton",
            "husk",
            "stray",
            "pillager",
            "vindicator",
            "spider",
            "creeper",
            "enderman",
            "piglin",
            "zombified_piglin",
            "villager",
            "cow",
            "pig",
            "sheep",
            "polar_bear",
            "ravager",
            "warden",
            "list"
        ),
        builder
    );
    private static final SuggestionProvider<CommandSourceStack> FARM = (context, builder) -> SharedSuggestionProvider.suggest(
        List.of(
            "oak_log", "birch_log", "spruce_log", "stone", "cobblestone", "iron_ore", "coal_ore", "wheat", "beef", "cow", "pig", "sheep", "chicken", "zombie"
        ),
        builder
    );
    private static long lastGcStamp;
    private static long lastGcCount;
    private static long lastGcMs;

    private BotCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("bot")
                .requires(source -> Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS).test(source) || isScriptedSource(source))
                .then(
                    Commands.literal("arrive")
                        .then(Commands.argument("auswahl", SelectorArgumentType.selector()).suggests(SELECTORS).executes(BotCommands::arrive))
                )
                .then(
                    Commands.literal("armymarch")
                        .then(
                            Commands.literal("stop")
                                .then(Commands.argument("auswahl", SelectorArgumentType.selector()).suggests(SELECTORS).executes(BotCommands::armyStop))
                        )
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(
                                    Commands.argument("ziel", Vec3Argument.vec3())
                                        .executes(context -> armyMarch(context, -1.0, -1.0, 1.0))
                                        .then(
                                            Commands.argument("breite", DoubleArgumentType.doubleArg(0.0, 300.0))
                                                .suggests(ARMY_WIDTH)
                                                .executes(context -> armyMarch(context, DoubleArgumentType.getDouble(context, "breite"), -1.0, 1.0))
                                                .then(
                                                    Commands.argument("laenge", DoubleArgumentType.doubleArg(0.0, 300.0))
                                                        .suggests(ARMY_LENGTH)
                                                        .executes(
                                                            context -> armyMarch(
                                                                context, DoubleArgumentType.getDouble(context, "breite"), DoubleArgumentType.getDouble(context, "laenge"), 1.0
                                                            )
                                                        )
                                                        .then(
                                                            Commands.argument("tempo", DoubleArgumentType.doubleArg(0.3, 1.3))
                                                                .executes(
                                                                    context -> armyMarch(
                                                                        context,
                                                                        DoubleArgumentType.getDouble(context, "breite"),
                                                                        DoubleArgumentType.getDouble(context, "laenge"),
                                                                        DoubleArgumentType.getDouble(context, "tempo")
                                                                    )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
                .then(
                    Commands.literal("tiers")
                        .executes(BotCommands::tiersShow)
                        .then(Commands.literal("reset").executes(context -> {
                            de.fiden.botsystem.bot.Tiers.reset();
                            bots().all().forEach(bot -> bot.skill.setSkill(bot.skill.skill()));
                            ok(context, "Stufen-Tabelle auf Standard zurueckgesetzt.");
                            return 1;
                        }))
                        .then(Commands.literal("reload").executes(context -> {
                            de.fiden.botsystem.bot.Tiers.reload();
                            bots().all().forEach(bot -> bot.skill.setSkill(bot.skill.skill()));
                            ok(context, "Stufen-Tabelle neu geladen (botsystem-tiers.json).");
                            return 1;
                        }))
                        .then(
                            Commands.literal("set")
                                .then(
                                    Commands.argument("stufe", StringArgumentType.word())
                                        .suggests(TIER_SUGGESTIONS)
                                        .then(
                                            Commands.argument("wert", StringArgumentType.word())
                                                .suggests((context, builder) -> {
                                                    for (String field : TIER_FIELDS) {
                                                        builder.suggest(field);
                                                    }

                                                    return builder.buildFuture();
                                                })
                                                .then(Commands.argument("zahl", DoubleArgumentType.doubleArg(0.0, 100.0)).executes(BotCommands::tiersSet))
                                        )
                                )
                        )
                )
                .then(
                    Commands.literal("spawn")
                        .executes(context -> spawn(context, "1", null, null))
                        .then(
                            Commands.argument("anzahl_oder_name", StringArgumentType.word())
                                .executes(context -> spawn(context, StringArgumentType.getString(context, "anzahl_oder_name"), null, null))
                                .then(
                                    Commands.argument("kit_oder_skin", StringArgumentType.word())
                                        .suggests(KITS)
                                        .executes(
                                            context -> spawn(
                                                context,
                                                StringArgumentType.getString(context, "anzahl_oder_name"),
                                                StringArgumentType.getString(context, "kit_oder_skin"),
                                                null
                                            )
                                        )
                                        .then(
                                            Commands.argument("team", StringArgumentType.word())
                                                .executes(
                                                    context -> spawn(
                                                        context,
                                                        StringArgumentType.getString(context, "anzahl_oder_name"),
                                                        StringArgumentType.getString(context, "kit_oder_skin"),
                                                        StringArgumentType.getString(context, "team")
                                                    )
                                                )
                                                .then(
                                                    Commands.argument("verbuendet", StringArgumentType.word())
                                                        .suggests(TEAMS_OR_TYPES)
                                                        .executes(
                                                            context -> spawnAllied(
                                                                context,
                                                                StringArgumentType.getString(context, "anzahl_oder_name"),
                                                                StringArgumentType.getString(context, "kit_oder_skin"),
                                                                StringArgumentType.getString(context, "team"),
                                                                StringArgumentType.getString(context, "verbuendet")
                                                            )
                                                        )
                                                        .then(
                                                            Commands.argument("stufe", StringArgumentType.word())
                                                                .suggests(TIER_SUGGESTIONS)
                                                                .executes(
                                                                    context -> spawnTiered(
                                                                        context,
                                                                        StringArgumentType.getString(context, "anzahl_oder_name"),
                                                                        StringArgumentType.getString(context, "kit_oder_skin"),
                                                                        StringArgumentType.getString(context, "team"),
                                                                        StringArgumentType.getString(context, "verbuendet"),
                                                                        StringArgumentType.getString(context, "stufe")
                                                                    )
                                                                )
                                                                .then(
                                                                    Commands.argument("typ", StringArgumentType.word())
                                                                        .suggests(TYPES)
                                                                        .executes(
                                                                            context -> spawnWithType(
                                                                                context,
                                                                                needType(StringArgumentType.getString(context, "typ")),
                                                                                StringArgumentType.getString(context, "anzahl_oder_name"),
                                                                                StringArgumentType.getString(context, "kit_oder_skin"),
                                                                                StringArgumentType.getString(context, "team"),
                                                                                StringArgumentType.getString(context, "verbuendet"),
                                                                                StringArgumentType.getString(context, "stufe")
                                                                            )
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
                .then(
                    Commands.literal("remove")
                        .then(Commands.argument("auswahl", SelectorArgumentType.selector()).suggests(SELECTORS).executes(BotCommands::remove))
                )
                .then(Commands.literal("removeall").executes(context -> {
                    clearSpawnQueue();
                    int count = bots().removeAll();
                    Bots.clearAllTeamRelations();
                    int mobsRemoved = BotSystemMod.mobs() == null ? 0 : BotSystemMod.mobs().removeAll();
                    ok(context, count + " Bot(s) und " + mobsRemoved + " Mob(s) entfernt.");
                    return count;
                }))
                .then(Commands.literal("list").executes(BotCommands::list))
                .then(Commands.literal("status").executes(BotCommands::status))
                .then(
                    Commands.literal("mob")
                        .then(Commands.literal("list").executes(BotCommands::mobList))
                        .then(
                            Commands.argument("typ", StringArgumentType.word())
                                .suggests(MOB_TYPES)
                                .executes(context -> mob(context, 1, null))
                                .then(
                                    Commands.argument("anzahl", IntegerArgumentType.integer(1, 1000))
                                        .executes(context -> mob(context, IntegerArgumentType.getInteger(context, "anzahl"), null))
                                        .then(
                                            Commands.argument("team", StringArgumentType.word())
                                                .executes(
                                                    context -> mob(
                                                        context,
                                                        IntegerArgumentType.getInteger(context, "anzahl"),
                                                        StringArgumentType.getString(context, "team")
                                                    )
                                                )
                                        )
                                )
                        )
                )
                .then(Commands.literal("map").executes(context -> {
                    MapServer.send(context.getSource().getPlayerOrException(), true);
                    return 1;
                }))
                .then(
                    Commands.literal("kit")
                        .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word()).executes(BotCommands::kitCreate)))
                        .then(Commands.literal("list").executes(BotCommands::kitList))
                        .then(Commands.literal("info").then(Commands.argument("name", StringArgumentType.word()).suggests(KITS).executes(BotCommands::kitInfo)))
                        .then(
                            Commands.literal("delete")
                                .then(Commands.argument("name", StringArgumentType.word()).suggests(CUSTOM_KITS).executes(BotCommands::kitDelete))
                        )
                )
                .then(Commands.literal("separation").then(Commands.argument("staerke", DoubleArgumentType.doubleArg(0.0, 2.0)).executes(context -> {
                    BotDefaults.get().separation = DoubleArgumentType.getDouble(context, "staerke");
                    BotDefaults.get().save();
                    ok(context, "Abstand im Gedraenge: " + BotDefaults.get().separation + (BotDefaults.get().separation == 0.0 ? " (aus)" : ""));
                    return 1;
                })))
                .then(
                    Commands.literal("record")
                        .executes(BotCommands::recordStart)
                        .then(Commands.literal("start").executes(BotCommands::recordStart))
                        .then(Commands.literal("load").then(recordLoadArgs()))
                        .then(Commands.literal("play").then(recordLoadArgs()))
                        .then(
                            Commands.literal("halt")
                                .then(Commands.argument("auswahl", SelectorArgumentType.selector()).suggests(SELECTORS).executes(BotCommands::recordHalt))
                        )
                        .then(
                            Commands.literal("stop")
                                .executes(context -> recordStop(context, null))
                                .then(
                                    Commands.argument("name", StringArgumentType.word())
                                        .executes(context -> recordStop(context, StringArgumentType.getString(context, "name")))
                                )
                        )
                        .then(
                            Commands.literal("save")
                                .then(
                                    Commands.argument("name", StringArgumentType.word())
                                        .executes(context -> recordSave(context, StringArgumentType.getString(context, "name")))
                                )
                        )
                        .then(Commands.literal("discard").executes(context -> {
                            PathRecorder.discard(context.getSource().getPlayerOrException().getUUID());
                            ok(context, "Aufnahme verworfen.");
                            return 1;
                        }))
                        .then(Commands.literal("list").executes(BotCommands::recordList))
                        .then(Commands.literal("delete").then(Commands.argument("name", StringArgumentType.word()).suggests(RECORDED).executes(context -> {
                            String name = StringArgumentType.getString(context, "name");
                            if (!PathRecorder.delete(name)) {
                                throw fail("Kein Weg namens " + name + ".");
                            }

                            ok(context, "Weg " + name + " geloescht.");
                            return 1;
                        })))
                        .then(
                            Commands.literal("show")
                                .then(Commands.argument("name", StringArgumentType.word()).suggests(RECORDED).executes(BotCommands::recordShow))
                        )
                )
                .then(
                    Commands.literal("path")
                        .then(
                            Commands.argument("name", StringArgumentType.word())
                                .suggests(RECORDED)
                                .then(
                                    Commands.argument("auswahl", SelectorArgumentType.selector())
                                        .suggests(SELECTORS)
                                        .executes(context -> followPath(context, false))
                                        .then(Commands.literal("reverse").executes(context -> followPath(context, true)))
                                )
                        )
                )
                .then(Commands.literal("perf").executes(BotCommands::perf))
                .then(
                    Commands.literal("archers")
                        .then(Commands.literal("hold").executes(context -> archers(context, true)))
                        .then(Commands.literal("advance").executes(context -> archers(context, false)))
                )
                .then(
                    Commands.literal("drops")
                        .then(
                            Commands.literal("lifetime")
                                .then(
                                    Commands.argument("sekunden", IntegerArgumentType.integer(1, 3600))
                                        .executes(context -> dropLifetime(context, IntegerArgumentType.getInteger(context, "sekunden")))
                                )
                        )
                        .then(Commands.literal("forever").executes(context -> dropLifetime(context, -1)))
                        .then(Commands.literal("normal").executes(context -> dropLifetime(context, 0)))
                        .then(Commands.literal("on").executes(context -> drops(context, true, true)))
                        .then(Commands.literal("off").executes(context -> drops(context, false, true)))
                        .then(
                            Commands.literal("items")
                                .then(Commands.literal("on").executes(context -> drops(context, true, false)))
                                .then(Commands.literal("off").executes(context -> drops(context, false, false)))
                        )
                        .then(
                            Commands.literal("xp")
                                .then(Commands.literal("on").executes(context -> xpDrops(context, true)))
                                .then(Commands.literal("off").executes(context -> xpDrops(context, false)))
                        )
                )
                .then(
                    Commands.literal("build")
                        .then(Commands.literal("on").executes(context -> build(context, true)))
                        .then(Commands.literal("off").executes(context -> build(context, false)))
                        .then(
                            Commands.literal("lifetime")
                                .then(
                                    Commands.argument("sekunden", IntegerArgumentType.integer(0, 3600))
                                        .executes(
                                            context -> {
                                                BotDefaults.get().buildLifetime = IntegerArgumentType.getInteger(context, "sekunden");
                                                BotDefaults.get().save();
                                                ok(
                                                    context,
                                                    "Gesetzte Bloecke verschwinden nach "
                                                        + (
                                                            IntegerArgumentType.getInteger(context, "sekunden") == 0
                                                                ? "nie"
                                                                : IntegerArgumentType.getInteger(context, "sekunden") + " s"
                                                        )
                                                        + "."
                                                );
                                                return 1;
                                            }
                                        )
                                )
                        )
                        .then(
                            Commands.literal("max")
                                .then(
                                    Commands.argument("anzahl", IntegerArgumentType.integer(0, 100000))
                                        .executes(
                                            context -> {
                                                BotDefaults.get().buildMax = IntegerArgumentType.getInteger(context, "anzahl");
                                                BotDefaults.get().save();
                                                ok(
                                                    context,
                                                    "Hoechstens "
                                                        + (
                                                            IntegerArgumentType.getInteger(context, "anzahl") == 0
                                                                ? "unbegrenzt viele"
                                                                : IntegerArgumentType.getInteger(context, "anzahl")
                                                        )
                                                        + " gesetzte Bloecke gleichzeitig - die aeltesten verschwinden zuerst."
                                                );
                                                return 1;
                                            }
                                        )
                                )
                        )
                        .then(Commands.literal("block").then(Commands.argument("block", StringArgumentType.word()).executes(context -> {
                            String id = StringArgumentType.getString(context, "block");
                            Identifier ident = Identifier.tryParse(id);
                            if (ident != null && !BuiltInRegistries.BLOCK.getOptional(ident).isEmpty()) {
                                BotDefaults.get().buildBlock = ident.toString();
                                BotDefaults.get().save();
                                ok(context, "Bots bauen jetzt mit " + ident + ".");
                                return 1;
                            } else {
                                throw fail("Unbekannter Block: " + id);
                            }
                        })))
                        .then(Commands.literal("clear").executes(context -> {
                            int removed = bots().blocks.clear();
                            ok(context, removed + " gesetzte Bloecke entfernt.");
                            return removed;
                        }))
                )
                .then(
                    Commands.literal("dig")
                        .then(Commands.literal("on").executes(context -> dig(context, true)))
                        .then(Commands.literal("off").executes(context -> dig(context, false)))
                )
                .then(
                    Commands.literal("selectors")
                        .then(Commands.literal("on").executes(context -> selectors(context, true)))
                        .then(Commands.literal("off").executes(context -> selectors(context, false)))
                )
                .then(
                    Commands.literal("anchors")
                        .then(Commands.literal("on").executes(context -> anchors(context, true)))
                        .then(Commands.literal("off").executes(context -> anchors(context, false)))
                        .then(Commands.argument("pro_sekunde", IntegerArgumentType.integer(1, 200)).executes(context -> {
                            BotDefaults.get().anchorsPerSecond = IntegerArgumentType.getInteger(context, "pro_sekunde");
                            BotDefaults.get().save();
                            ok(context, "Bots laden hoechstens " + IntegerArgumentType.getInteger(context, "pro_sekunde") + " neue Chunks pro Sekunde.");
                            return 1;
                        }))
                )
                .then(
                    Commands.literal("lod")
                        .then(
                            Commands.argument("bloecke", IntegerArgumentType.integer(0, 512))
                                .executes(
                                    context -> {
                                        int blocks = IntegerArgumentType.getInteger(context, "bloecke");
                                        BotDefaults.get().lodDistance = blocks;
                                        BotDefaults.get().save();
                                        MapPayloads.RenderSettings.broadcast(context.getSource().getServer());
                                        ok(
                                            context,
                                            blocks == 0
                                                ? "Bots werden immer mit voller Ruestung gerendert."
                                                : "Bots ab " + blocks + " Blöcken ohne Ruestung/Gegenstaende (spart FPS)."
                                        );
                                        return blocks;
                                    }
                                )
                        )
                )
                .then(
                    Commands.literal("nametags")
                        .then(
                            Commands.argument("bloecke", IntegerArgumentType.integer(0, 1000000))
                                .executes(
                                    context -> {
                                        int blocks = IntegerArgumentType.getInteger(context, "bloecke");
                                        BotDefaults.get().nameTagDistance = blocks;
                                        BotDefaults.get().save();
                                        MapPayloads.RenderSettings.broadcast(context.getSource().getServer());
                                        ok(
                                            context,
                                            blocks == 0
                                                ? "Bot-Namensschilder aus."
                                                : (
                                                    blocks >= 1000000
                                                        ? "Bot-Namensschilder immer (so weit Bots gerendert werden)."
                                                        : "Bot-Namensschilder bis " + blocks + " Blöcke."
                                                )
                                        );
                                        return blocks;
                                    }
                                )
                        )
                )
                .then(
                    Commands.literal("render")
                        .then(
                            Commands.argument("bloecke", IntegerArgumentType.integer(0, 512))
                                .executes(
                                    context -> {
                                        int blocks = IntegerArgumentType.getInteger(context, "bloecke");
                                        BotDefaults.get().trackDistance = blocks;
                                        BotDefaults.get().save();
                                        Bots.applyTrackDistance(context.getSource().getServer());
                                        ok(
                                            context,
                                            blocks == 0
                                                ? "Bots werden immer angezeigt - bis zur Sichtweite des Servers (view-distance x 16 Blöcke)."
                                                : "Bots werden bis "
                                                    + blocks
                                                    + " Blöcke Entfernung angezeigt (gespeichert; wirkt, sobald Bots neu in Sicht kommen)."
                                        );
                                        return blocks;
                                    }
                                )
                        )
                )
                .then(
                    Commands.literal("profile")
                        .executes(context -> profile(context, 10))
                        .then(
                            Commands.argument("sekunden", IntegerArgumentType.integer(2, 60))
                                .executes(context -> profile(context, IntegerArgumentType.getInteger(context, "sekunden")))
                        )
                )
                .then(
                    Commands.literal("dump")
                        .then(Commands.argument("auswahl", SelectorArgumentType.selector()).suggests(SELECTORS).executes(BotCommands::dump))
                )
                .then(
                    Commands.literal("team")
                        .then(
                            Commands.literal("add")
                                .then(
                                    Commands.argument("team", StringArgumentType.word())
                                        .then(
                                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                                .suggests(SELECTORS)
                                                .executes(context -> team(context, true))
                                        )
                                )
                        )
                        .then(
                            Commands.literal("remove")
                                .then(
                                    Commands.argument("team", StringArgumentType.word())
                                        .then(
                                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                                .suggests(SELECTORS)
                                                .executes(context -> team(context, false))
                                        )
                                )
                        )
                        .then(
                            Commands.literal("ally")
                                .then(
                                    Commands.argument("team", StringArgumentType.word())
                                        .suggests(TEAMS)
                                        .then(
                                            Commands.argument("team2", StringArgumentType.word()).suggests(TEAMS).executes(context -> teamAlly(context, true))
                                        )
                                )
                        )
                        .then(
                            Commands.literal("unally")
                                .then(
                                    Commands.argument("team", StringArgumentType.word())
                                        .suggests(TEAMS)
                                        .then(
                                            Commands.argument("team2", StringArgumentType.word()).suggests(TEAMS).executes(context -> teamAlly(context, false))
                                        )
                                )
                        )
                        .then(Commands.literal("allies").executes(context -> {
                            Map<String, Set<String>> allies = Bots.teamAllies();
                            if (allies.values().stream().allMatch(Set::isEmpty)) {
                                ok(context, "Keine Buendnisse. /bot team ally <Team> <Team>");
                                return 0;
                            } else {
                                allies.forEach((team, friends) -> {
                                    if (!friends.isEmpty()) {
                                        info(context, "- " + team + " <-> " + String.join(", ", friends), ChatFormatting.YELLOW);
                                    }
                                });
                                return allies.size();
                            }
                        }))
                        .then(Commands.literal("list").executes(context -> {
                            List<String> teams = bots().teams();
                            ok(context, "Teams: " + (teams.isEmpty() ? "keine" : String.join(", ", teams)));
                            return teams.size();
                        }))
                )
                .then(
                    Commands.literal("spread")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(
                                    Commands.argument("radius", IntegerArgumentType.integer(2, 500))
                                        .executes(context -> spread(context, false))
                                        .then(Commands.literal("tp").executes(context -> spread(context, true)))
                                )
                        )
                )
                .then(
                    Commands.literal("goto")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(
                                    Commands.argument("ziel", Vec3Argument.vec3())
                                        .executes(context -> walkTo(context, context.getSource().getLevel()))
                                        .then(
                                            Commands.argument("dimension", StringArgumentType.word())
                                                .suggests(DIMENSIONS)
                                                .executes(context -> walkTo(context, dimension(context, "dimension")))
                                        )
                                )
                        )
                )
                .then(
                    Commands.literal("follow")
                        .then(
                            Commands.literal("stop")
                                .then(
                                    Commands.argument("auswahl", SelectorArgumentType.selector())
                                        .suggests(SELECTORS)
                                        .executes(context -> follow(context, false, false))
                                )
                        )
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector()).suggests(SELECTORS).executes(context -> follow(context, true, false))
                        )
                )
                .then(
                    Commands.literal("guard")
                        .then(
                            Commands.literal("stop")
                                .then(
                                    Commands.argument("auswahl", SelectorArgumentType.selector())
                                        .suggests(SELECTORS)
                                        .executes(context -> follow(context, false, true))
                                )
                        )
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector()).suggests(SELECTORS).executes(context -> follow(context, true, true))
                        )
                )
                .then(
                    Commands.literal("fight")
                        .then(
                            Commands.literal("stop")
                                .then(Commands.argument("auswahl", SelectorArgumentType.selector()).suggests(SELECTORS).executes(BotCommands::stopFight))
                        )
                        .then(Commands.argument("parteien", StringArgumentType.greedyString()).suggests(PARTIES).executes(BotCommands::fight))
                )
                .then(
                    Commands.literal("attack")
                        .then(Commands.argument("parteien", StringArgumentType.greedyString()).suggests(PARTIES).executes(BotCommands::attack))
                )
                .then(
                    Commands.literal("march")
                        .then(
                            Commands.literal("stop")
                                .then(Commands.argument("auswahl", SelectorArgumentType.selector()).suggests(SELECTORS).executes(BotCommands::stopMarch))
                        )
                        .then(
                            Commands.argument("ziel", Vec3Argument.vec3())
                                .then(
                                    Commands.argument("parteien", StringArgumentType.greedyString())
                                        .suggests(PARTIES)
                                        .executes(context -> march(context, Vec3Argument.getVec3(context, "ziel")))
                                )
                        )
                        .then(Commands.argument("parteien", StringArgumentType.greedyString()).suggests(PARTIES).executes(context -> march(context, null)))
                )
                .then(
                    Commands.literal("gym")
                        .then(
                            Commands.literal("shuffle")
                                .then(
                                    Commands.argument("minuten", IntegerArgumentType.integer(0, 100000))
                                        .executes(
                                            context -> {
                                                int minutes = IntegerArgumentType.getInteger(context, "minuten");
                                                if (!Gym.setShuffle(NavMemory.worldId(context.getSource().getLevel()), minutes)) {
                                                    throw fail("In dieser Welt gibt es noch keinen Parcours: /bot gym build random");
                                                }

                                                ok(
                                                    context,
                                                    minutes == 0
                                                        ? "Parcours bleibt, wie er ist."
                                                        : "Alle "
                                                            + minutes
                                                            + " min ein neuer Zufalls-Parcours an derselben Stelle (Bots auf der Strecke werden vorher auf den Vorplatz gesetzt)."
                                                );
                                                return 1;
                                            }
                                        )
                                )
                        )
                        .then(
                            Commands.literal("build")
                                .executes(context -> gymBuild(context, null))
                                .then(
                                    Commands.literal("random")
                                        .executes(context -> gymBuildRandom(context, 0L))
                                        .then(
                                            Commands.argument("seed", LongArgumentType.longArg())
                                                .executes(context -> gymBuildRandom(context, LongArgumentType.getLong(context, "seed")))
                                        )
                                )
                                .then(
                                    Commands.argument("pos", Vec3Argument.vec3()).executes(context -> gymBuild(context, Vec3Argument.getVec3(context, "pos")))
                                )
                        )
                        .then(
                            Commands.literal("start")
                                .then(
                                    Commands.argument("auswahl", SelectorArgumentType.selector())
                                        .suggests(SELECTORS)
                                        .executes(context -> gymStart(context, -1.0, -1))
                                        .then(
                                            Commands.argument("radius", DoubleArgumentType.doubleArg(0.0, 400.0))
                                                .executes(context -> gymStart(context, DoubleArgumentType.getDouble(context, "radius"), -1))
                                                .then(
                                                    Commands.argument("minuten", IntegerArgumentType.integer(1, 100000))
                                                        .executes(
                                                            context -> gymStart(
                                                                context,
                                                                DoubleArgumentType.getDouble(context, "radius"),
                                                                IntegerArgumentType.getInteger(context, "minuten")
                                                            )
                                                        )
                                                )
                                        )
                                )
                        )
                        .then(
                            Commands.literal("stop")
                                .then(Commands.argument("auswahl", SelectorArgumentType.selector()).suggests(SELECTORS).executes(context -> {
                                    List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
                                    int stopped = 0;

                                    for (BotPlayer bot : targets) {
                                        if (bot.gymActive) {
                                            BotSystemMod.brain().stopGym(bot);
                                            bot.walkGoal = null;
                                            bot.orderUntil = 0L;
                                            stopped++;
                                        }
                                    }

                                    ok(context, stopped + " Bot(s) beenden das Training. Gelerntes bleibt.");
                                    return stopped;
                                }))
                        )
                        .then(Commands.literal("stats").executes(context -> {
                            int active = 0;

                            for (BotPlayer bot : bots().all()) {
                                if (bot.gymActive) {
                                    active++;
                                }
                            }

                            ok(context, Gym.report(active));
                            String coach = BotSystemMod.skills().coachName();
                            if (coach != null) {
                                info(context, "Vorbild (neue Bots starten mit seinem Stil): " + coach, ChatFormatting.GRAY);
                            }

                            return active;
                        }))
                        .then(Commands.literal("lanecap").then(Commands.argument("bots", IntegerArgumentType.integer(1, 50)).executes(context -> {
                            BotDefaults.get().gymLaneCap = IntegerArgumentType.getInteger(context, "bots");
                            BotDefaults.get().save();
                            ok(context, "Hoechstens " + BotDefaults.get().gymLaneCap + " Bot(s) gleichzeitig pro Bahn.");
                            return 1;
                        })))
                        .then(Commands.literal("reps").then(Commands.argument("laeufe", IntegerArgumentType.integer(1, 100)).executes(context -> {
                            BotDefaults.get().gymReps = IntegerArgumentType.getInteger(context, "laeufe");
                            BotDefaults.get().save();
                            ok(context, "Pro Bahn " + BotDefaults.get().gymReps + " Laeufe, dann Bahnwechsel.");
                            return 1;
                        })))
                        .then(Commands.literal("reset").executes(context -> {
                            Gym.resetStats();
                            ok(context, "Trainingsstatistik zurueckgesetzt (Gelerntes bleibt).");
                            return 1;
                        }))
                        .then(Commands.literal("remove").executes(context -> {
                            boolean removed = Gym.remove(NavMemory.worldId(context.getSource().getLevel()));
                            ok(context, removed ? "Parcours vergessen (die Bloecke bleiben stehen)." : "Hier gibt es keinen gespeicherten Parcours.");
                            return removed ? 1 : 0;
                        }))
                )
                .then(Commands.literal("detour").then(Commands.argument("faktor", DoubleArgumentType.doubleArg(1.0, 20.0)).executes(context -> {
                    BotDefaults.get().detourLimit = DoubleArgumentType.getDouble(context, "faktor");
                    BotDefaults.get().save();
                    ok(context, "Umwege bis zum " + BotDefaults.get().detourLimit + "-fachen der Luftlinie werden gelaufen, laengere werden ueberklettert.");
                    return 1;
                })))
                .then(Commands.literal("climbcost").then(Commands.argument("bloecke", DoubleArgumentType.doubleArg(0.5, 20.0)).executes(context -> {
                    BotDefaults.get().climbPenalty = DoubleArgumentType.getDouble(context, "bloecke");
                    BotDefaults.get().save();
                    ok(context, "Ein Block Klettern zaehlt wie " + BotDefaults.get().climbPenalty + " Bloecke Laufen.");
                    return 1;
                })))
                .then(
                    Commands.literal("escape")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .executes(context -> escape(context, 20.0))
                                .then(
                                    Commands.argument("radius", DoubleArgumentType.doubleArg(3.0, 200.0))
                                        .executes(context -> escape(context, DoubleArgumentType.getDouble(context, "radius")))
                                )
                        )
                )
                .then(
                    Commands.literal("learn")
                        .then(
                            Commands.literal("info")
                                .executes(
                                    context -> {
                                        SkillMemory memory = BotSystemMod.skills();
                                        double[] w = memory.exploreWeights();
                                        StringBuilder weights = new StringBuilder();

                                        for (int i = 0; i < w.length; i++) {
                                            weights.append(ExitSense.FEATURES[i]).append(' ').append(String.format(Locale.ROOT, "%+.2f", w[i])).append("  ");
                                        }

                                        ok(
                                            context,
                                            "Gemeinsames Gedaechtnis (gilt fuer ALLE Bots, jede Welt): "
                                                + memory.exploreEpisodes
                                                + " Erkundungen, "
                                                + memory.exploreEscapes
                                                + " geschafft, "
                                                + memory.sharedUpdates
                                                + " Stil-Verbesserungen, "
                                                + memory.entries()
                                                + " gelernte Handlungen."
                                        );
                                        info(context, "Erkunden-Gewichte: " + weights.toString().trim(), ChatFormatting.GRAY);
                                        StringBuilder style = new StringBuilder();

                                        for (String knob : BotProfile.KNOBS) {
                                            style.append(knob).append(' ').append(String.format(Locale.ROOT, "%.2f", memory.shared(knob))).append("  ");
                                        }

                                        info(context, "Gemeinsamer Stil: " + style.toString().trim(), ChatFormatting.GRAY);
                                        return 1;
                                    }
                                )
                        )
                        .then(Commands.literal("rate").then(Commands.argument("wert", DoubleArgumentType.doubleArg(0.0, 1.0)).executes(context -> {
                            BotSystemMod.skills().exploreRate = DoubleArgumentType.getDouble(context, "wert");
                            BotSystemMod.skills().touch();
                            ok(context, "Lernrate: " + BotSystemMod.skills().exploreRate + " (wie stark eine Erfahrung die Gewichte verschiebt).");
                            return 1;
                        })))
                        .then(
                            Commands.literal("temperature")
                                .then(
                                    Commands.argument("wert", DoubleArgumentType.doubleArg(0.05, 5.0))
                                        .executes(
                                            context -> {
                                                BotSystemMod.skills().exploreTemperature = DoubleArgumentType.getDouble(context, "wert");
                                                BotSystemMod.skills().touch();
                                                ok(
                                                    context,
                                                    "Neugier beim Erkunden: "
                                                        + BotSystemMod.skills().exploreTemperature
                                                        + " (hoch = probiert mehr aus, niedrig = folgt dem Gelernten)."
                                                );
                                                return 1;
                                            }
                                        )
                                )
                        )
                        .then(
                            Commands.literal("explore")
                                .then(Commands.literal("on").executes(context -> learnExploreToggle(context, true)))
                                .then(Commands.literal("off").executes(context -> learnExploreToggle(context, false)))
                        )
                        .then(Commands.literal("forgetexits").executes(context -> {
                            ExitSense.forgetExits();
                            ok(context, "Bekannte Ausgaenge vergessen (die gelernten Gewichte bleiben).");
                            return 1;
                        }))
                        .then(Commands.literal("reset").executes(context -> {
                            BotSystemMod.skills().explore = null;
                            BotSystemMod.skills().exploreBaseline = 0.0;
                            BotSystemMod.skills().exploreEpisodes = 0;
                            BotSystemMod.skills().exploreEscapes = 0;
                            BotSystemMod.skills().touch();
                            BotSystemMod.skills().save();
                            ok(context, "Erkunden-Gewichte zurueckgesetzt.");
                            return 1;
                        }))
                )
                .then(Commands.literal("trainexplore").then(Commands.argument("anteil", DoubleArgumentType.doubleArg(0.0, 0.5)).executes(context -> {
                    BotDefaults.get().trainExplore = DoubleArgumentType.getDouble(context, "anteil");
                    BotDefaults.get().save();
                    ok(context, "Sie veraendern ihren Stil pro Runde um bis zu " + BotDefaults.get().trainExplore + ".");
                    return 1;
                })))
                .then(
                    Commands.literal("learnalways")
                        .then(Commands.literal("on").executes(context -> learnAlways(context, true)))
                        .then(Commands.literal("off").executes(context -> learnAlways(context, false)))
                )
                .then(Commands.literal("trainspeed").then(Commands.argument("faktor", DoubleArgumentType.doubleArg(1.0, 8.0)).executes(context -> {
                    BotDefaults.get().trainSpeed = DoubleArgumentType.getDouble(context, "faktor");
                    BotDefaults.get().save();
                    ok(context, "Trainingstempo: " + BotDefaults.get().trainSpeed + "x (nur waehrend /bot train).");
                    return 1;
                })))
                .then(
                    Commands.literal("skills")
                        .then(Commands.literal("info").executes(context -> {
                            SkillMemory skills = BotSystemMod.skills();
                            ok(context, "Koennen: " + skills.situations() + " Lagen, " + skills.entries() + " gelernte Handlungen (gilt in jeder Welt).");

                            for (String line : skills.summary()) {
                                info(context, "- " + line, ChatFormatting.GRAY);
                            }

                            return 1;
                        }))
                        .then(Commands.literal("save").executes(context -> {
                            ok(context, BotSystemMod.skills().save() ? "Koennen gespeichert." : "Nichts Neues zu speichern.");
                            return 1;
                        }))
                        .then(Commands.literal("clear").executes(context -> {
                            int removed = BotSystemMod.skills().clear();
                            BotSystemMod.skills().save();
                            ok(context, removed + " gelernte Handlungen vergessen.");
                            return removed;
                        }))
                        .then(
                            Commands.literal("export")
                                .then(
                                    Commands.argument("datei", StringArgumentType.word())
                                        .executes(
                                            context -> {
                                                Path path = BotDefaults.dir().resolve(StringArgumentType.getString(context, "datei") + ".json");
                                                ok(
                                                    context,
                                                    BotSystemMod.skills().exportTo(path)
                                                        ? "Koennen nach " + path.getFileName() + " geschrieben (weitergeben moeglich)."
                                                        : "Konnte nicht schreiben."
                                                );
                                                return 1;
                                            }
                                        )
                                )
                        )
                        .then(Commands.literal("import").then(Commands.argument("datei", StringArgumentType.word()).executes(context -> {
                            Path path = BotDefaults.dir().resolve(StringArgumentType.getString(context, "datei") + ".json");
                            int merged = BotSystemMod.skills().importFrom(path);
                            BotSystemMod.skills().save();
                            ok(context, merged + " Eintraege aus " + path.getFileName() + " uebernommen.");
                            return merged;
                        })))
                        .then(
                            Commands.literal("coach")
                                .executes(
                                    context -> {
                                        String coach = BotSystemMod.skills().coachName();
                                        ok(
                                            context,
                                            coach == null
                                                ? "Noch kein Vorbild - erst ab 20 Trainingsrunden eines Bots."
                                                : "Vorbild: " + coach + " - " + BotSystemMod.skills().profile(coach).summary()
                                        );
                                        return coach == null ? 0 : 1;
                                    }
                                )
                        )
                        .then(Commands.literal("profile").then(Commands.argument("bot", StringArgumentType.word()).executes(context -> {
                            String name = StringArgumentType.getString(context, "bot");
                            if (!BotSystemMod.skills().hasProfile(name)) {
                                throw fail("Kein Profil fuer " + name + " - er hat noch nichts geuebt.");
                            }

                            ok(context, name + ": " + BotSystemMod.skills().profile(name).summary());
                            return 1;
                        })))
                        .then(
                            Commands.literal("reset")
                                .then(Commands.argument("auswahl", SelectorArgumentType.selector()).suggests(SELECTORS).executes(context -> {
                                    List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));

                                    for (BotPlayer bot : targets) {
                                        BotSystemMod.skills().bots.remove(bot.getGameProfile().name());
                                    }

                                    BotSystemMod.skills().touch();
                                    BotSystemMod.skills().save();
                                    ok(context, targets.size() + " Bot(s) fangen beim Training wieder von vorn an.");
                                    return targets.size();
                                }))
                        )
                        .then(Commands.literal("curiosity").then(Commands.argument("anteil", DoubleArgumentType.doubleArg(0.0, 0.5)).executes(context -> {
                            BotSystemMod.skills().curiosity = DoubleArgumentType.getDouble(context, "anteil");
                            BotSystemMod.skills().save();
                            ok(context, "Neugier: " + BotSystemMod.skills().curiosity + " (wie oft sie etwas Neues ausprobieren).");
                            return 1;
                        })))
                )
                .then(
                    Commands.literal("nav")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(Commands.literal("on").executes(context -> nav(context, true)))
                                .then(Commands.literal("off").executes(context -> nav(context, false)))
                        )
                )
                .then(
                    Commands.literal("lookat")
                        .then(
                            withDirections(Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS))
                                .then(Commands.argument("ort", Vec3Argument.vec3()).executes(BotCommands::lookAtPos))
                                .then(Commands.argument("ziel", SelectorArgumentType.selector()).suggests(LOOK_TARGETS).executes(BotCommands::lookAt))
                        )
                )
                .then(
                    Commands.literal("stayin")
                        .then(
                            Commands.literal("stop")
                                .then(Commands.argument("auswahl", SelectorArgumentType.selector()).suggests(SELECTORS).executes(context -> {
                                    List<BotPlayer> targets = select(context, SelectorArgumentType.get(context, "auswahl"));
                                    targets.forEach(bot -> bot.stayArea = null);
                                    ok(context, targets.size() + " Bot(s) duerfen wieder ueberall hin.");
                                    return targets.size();
                                }))
                        )
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(Commands.argument("ecken", StringArgumentType.greedyString()).executes(BotCommands::stayIn))
                        )
                )
                .then(
                    Commands.literal("line")
                        .then(
                            Commands.literal("stop")
                                .then(
                                    Commands.argument("auswahl", SelectorArgumentType.selector())
                                        .suggests(SELECTORS)
                                        .executes(context -> stopLine(context, false))
                                )
                        )
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(Commands.argument("punkte", StringArgumentType.greedyString()).executes(context -> line(context, false)))
                        )
                )
                .then(
                    Commands.literal("patrol")
                        .then(
                            Commands.literal("stop")
                                .then(
                                    Commands.argument("auswahl", SelectorArgumentType.selector())
                                        .suggests(SELECTORS)
                                        .executes(context -> stopLine(context, true))
                                )
                        )
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(Commands.argument("punkte", StringArgumentType.greedyString()).executes(context -> line(context, true)))
                        )
                )
                .then(
                    Commands.literal("occupy")
                        .then(Commands.argument("parteien", StringArgumentType.greedyString()).suggests(PARTIES).executes(BotCommands::occupy))
                )
                .then(
                    Commands.literal("erobern")
                        .then(Commands.argument("parteien", StringArgumentType.greedyString()).suggests(PARTIES).executes(BotCommands::occupy))
                )
                .then(
                    Commands.literal("patrolarea")
                        .then(
                            Commands.literal("stop")
                                .then(Commands.argument("auswahl", SelectorArgumentType.selector()).suggests(SELECTORS).executes(context -> {
                                    List<BotPlayer> targets = select(context, SelectorArgumentType.get(context, "auswahl"));
                                    targets.forEach(bot -> {
                                        bot.patrolArea = null;
                                        bot.walkGoal = null;
                                    });
                                    ok(context, targets.size() + " Bot(s) patrouillieren nicht mehr.");
                                    return targets.size();
                                }))
                        )
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(Commands.argument("ecken", StringArgumentType.greedyString()).executes(context -> patrolArea(context, false)))
                        )
                )
                .then(
                    Commands.literal("patrolspread")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(Commands.argument("ecken", StringArgumentType.greedyString()).executes(context -> patrolArea(context, true)))
                        )
                )
                .then(
                    Commands.literal("defend")
                        .then(
                            Commands.argument("bloecke", IntegerArgumentType.integer(0, 128))
                                .executes(
                                    context -> {
                                        BotDefaults.get().defendRadius = IntegerArgumentType.getInteger(context, "bloecke");
                                        BotDefaults.get().save();
                                        ok(
                                            context,
                                            IntegerArgumentType.getInteger(context, "bloecke") == 0
                                                ? "Verteidigungsmodus aus."
                                                : "Verteidigungsmodus: Kameraden im Umkreis von "
                                                    + IntegerArgumentType.getInteger(context, "bloecke")
                                                    + " Blöcken wird geholfen, danach zurueck auf den Posten."
                                        );
                                        return 1;
                                    }
                                )
                        )
                )
                .then(
                    Commands.literal("mode")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(Commands.argument("haltung", StringArgumentType.word()).suggests((context, builder) -> {
                                    for (String option : List.of("passive", "defensive", "aggressive")) {
                                        if (option.startsWith(builder.getRemainingLowerCase())) {
                                            builder.suggest(option);
                                        }
                                    }

                                    return builder.buildFuture();
                                }).executes(BotCommands::mode))
                        )
                )
                .then(
                    Commands.literal("quiet")
                        .then(Commands.literal("on").executes(context -> {
                            BotDefaults.get().quietChat = true;
                            BotDefaults.get().save();
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(context -> {
                            BotDefaults.get().quietChat = false;
                            BotDefaults.get().save();
                            ok(context, "Bot-Befehle melden sich wieder im Chat.");
                            return 1;
                        }))
                )
                .then(
                    Commands.literal("hunt")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(Commands.literal("stop").executes(context -> hunt(context, null, 0)))
                                .then(
                                    Commands.argument("mob", StringArgumentType.word())
                                        .executes(context -> hunt(context, StringArgumentType.getString(context, "mob"), 48))
                                        .then(
                                            Commands.argument("reichweite", IntegerArgumentType.integer(1, 512))
                                                .executes(context -> hunt(context, StringArgumentType.getString(context, "mob"), IntegerArgumentType.getInteger(context, "reichweite")))
                                        )
                                )
                        )
                )
                .then(
                    Commands.literal("mineskin")
                        .then(Commands.literal("key").then(Commands.argument("key", StringArgumentType.word()).executes(context -> {
                            BotDefaults.get().mineskinKey = StringArgumentType.getString(context, "key");
                            BotDefaults.get().save();
                            ok(context, "MineSkin-Key gespeichert. /bot skin <auswahl> file <datei> signiert Skins jetzt einmalig bei mineskin.org - dann sieht sie jeder, auch ohne Mod.");
                            return 1;
                        })))
                        .then(Commands.literal("off").executes(context -> {
                            BotDefaults.get().mineskinKey = "";
                            BotDefaults.get().save();
                            ok(context, "MineSkin aus. Skin-Dateien sind nur mit der BotSystem-Client-Mod sichtbar.");
                            return 1;
                        }))
                )
                .then(
                    Commands.literal("skinlayers")
                        .then(Commands.literal("on").executes(context -> skinLayers(context, true)))
                        .then(Commands.literal("off").executes(context -> skinLayers(context, false)))
                )
                .then(
                    Commands.literal("neverfight")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(Commands.literal("on").executes(context -> neverFight(context, true)))
                                .then(Commands.literal("off").executes(context -> neverFight(context, false)))
                        )
                )
                .then(Commands.literal("pathtest").then(Commands.argument("koordinaten", StringArgumentType.greedyString()).executes(BotCommands::pathTest)))
                .then(
                    Commands.literal("trace")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(
                                    Commands.argument("sekunden", IntegerArgumentType.integer(1, 600))
                                        .executes(
                                            context -> {
                                                List<BotPlayer> targets = select(context, SelectorArgumentType.get(context, "auswahl"));
                                                int count = BotSystemMod.TRACE
                                                    .start(context.getSource().getServer(), targets, IntegerArgumentType.getInteger(context, "sekunden"));
                                                ok(
                                                    context,
                                                    "Bewegung von "
                                                        + count
                                                        + " Bot(s) wird "
                                                        + IntegerArgumentType.getInteger(context, "sekunden")
                                                        + " s lang nach "
                                                        + BotSystemMod.TRACE.file()
                                                        + " geschrieben."
                                                );
                                                return count;
                                            }
                                        )
                                )
                        )
                )
                .then(Commands.literal("clearitems").executes(context -> {
                    ServerLevel world = context.getSource().getLevel();
                    int removed = 0;

                    for (Entity entity : world.getAllEntities()) {
                        if (entity instanceof ItemEntity
                            || entity instanceof ExperienceOrb
                            || entity instanceof AbstractArrow projectile && projectile.onGround()) {
                            entity.discard();
                            removed++;
                        }
                    }

                    ok(context, removed + " Items/XP/Pfeile vom Boden entfernt.");
                    return removed;
                }))
                .then(
                    // Sent by the BotSystem Companion client mod: BBS films on a Paper server
                    // (BBS itself isn't installed here, so its film commands never reach us otherwise).
                    Commands.literal("film")
                        .then(Commands.literal("begin").executes(context -> {
                            FilmScene.begin();
                            return 1;
                        }))
                        .then(Commands.literal("stop").executes(context -> {
                            FilmScene.restore();
                            return 1;
                        }))
                        .then(
                            Commands.literal("cmd")
                                .then(
                                    Commands.argument("pos", Vec3Argument.vec3(false))
                                        .then(Commands.argument("befehl", StringArgumentType.greedyString()).executes(BotCommands::filmCommand))
                                )
                        )
                )
                .then(Commands.literal("potions").then(Commands.literal("on").executes(context -> {
                    BotDefaults.get().potions = true;
                    BotDefaults.get().save();
                    ok(context, "Bots trinken Traenke (Staerke, Schnelligkeit, Heilung), wenn es passt.");
                    return 1;
                })).then(Commands.literal("off").executes(context -> {
                    BotDefaults.get().potions = false;
                    BotDefaults.get().save();
                    ok(context, "Bots trinken keine Traenke mehr.");
                    return 1;
                })))
                .then(Commands.literal("deathanimation").then(Commands.literal("on").executes(context -> {
                    BotDefaults.get().deathAnimation = true;
                    BotDefaults.get().save();
                    ok(context, "Sterbeanimation an.");
                    return 1;
                })).then(Commands.literal("off").executes(context -> {
                    BotDefaults.get().deathAnimation = false;
                    BotDefaults.get().save();
                    ok(context, "Sterbeanimation aus: Bots verschwinden beim Tod sofort.");
                    return 1;
                })))
                .then(Commands.literal("filmreset").then(Commands.literal("on").executes(context -> {
                    BotDefaults.get().filmReset = true;
                    BotDefaults.get().save();
                    ok(context, "Film-Szene: was BBS-Filme an Bots aendern, wird beim Stopp/Neustart zurueckgenommen.");
                    return 1;
                })).then(Commands.literal("off").executes(context -> {
                    BotDefaults.get().filmReset = false;
                    BotDefaults.get().save();
                    ok(context, "Film-Szene aus: Aenderungen durch Filme bleiben.");
                    return 1;
                })).then(Commands.literal("now").executes(context -> {
                    FilmScene.restore();
                    ok(context, "Film-Szene jetzt zurueckgesetzt.");
                    return 1;
                })))
                .then(Commands.literal("actors").then(Commands.literal("on").executes(context -> {
                    BotDefaults.get().attackActors = true;
                    BotDefaults.get().save();
                    ok(context, "BBS-Schauspieler koennen Ziel sein: /bot attack <auswahl> @actors  (oder @actors:30 = im Umkreis von 30).");
                    return 1;
                })).then(Commands.literal("off").executes(context -> {
                    BotDefaults.get().attackActors = false;
                    BotDefaults.get().save();
                    ok(context, "BBS-Schauspieler werden nicht mehr angegriffen.");
                    return 1;
                })))
                .then(Commands.literal("ignoreplayers").then(Commands.literal("on").executes(context -> {
                    BotDefaults.get().ignorePlayers = true;
                    BotDefaults.get().save();
                    ok(context, "Echte Spieler werden nie angegriffen.");
                    return 1;
                })).then(Commands.literal("off").executes(context -> {
                    BotDefaults.get().ignorePlayers = false;
                    BotDefaults.get().save();
                    ok(context, "Echte Spieler koennen wieder Ziel sein.");
                    return 1;
                })))
                .then(
                    Commands.literal("leader")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(
                                    Commands.argument("anfuehrer", SelectorArgumentType.selector())
                                        .suggests(PLAYERS_AND_SELECTORS)
                                        .executes(BotCommands::leader)
                                )
                        )
                )
                .then(
                    Commands.literal("defect")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(Commands.argument("team", StringArgumentType.word()).suggests(TEAMS).executes(BotCommands::defect))
                        )
                )
                .then(
                    Commands.literal("save")
                        .executes(context -> save(context, "auto"))
                        .then(
                            Commands.argument("name", StringArgumentType.word())
                                .executes(context -> save(context, StringArgumentType.getString(context, "name")))
                        )
                )
                .then(
                    Commands.literal("load")
                        .executes(context -> load(context, "auto", false))
                        .then(
                            Commands.argument("name", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(BotSaves.names(context.getSource().getServer()), builder))
                                .executes(context -> load(context, StringArgumentType.getString(context, "name"), false))
                                .then(Commands.literal("on").executes(context -> load(context, StringArgumentType.getString(context, "name"), true)))
                                .then(Commands.literal("off").executes(context -> load(context, StringArgumentType.getString(context, "name"), false)))
                        )
                )
                .then(
                    Commands.literal("copy")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .executes(context -> copy(context, 1))
                                .then(
                                    Commands.argument("anzahl", IntegerArgumentType.integer(1, 500))
                                        .executes(context -> copy(context, IntegerArgumentType.getInteger(context, "anzahl")))
                                )
                        )
                )
                .then(
                    Commands.literal("saves")
                        .executes(context -> {
                            List<String> names = BotSaves.names(context.getSource().getServer());
                            ok(context, names.isEmpty() ? "Keine Spielstaende. /bot save <name>" : "Spielstaende: " + String.join(", ", names));
                            return names.size();
                        })
                        .then(
                            Commands.literal("delete")
                                .then(
                                    Commands.argument("name", StringArgumentType.word())
                                        .suggests(
                                            (context, builder) -> SharedSuggestionProvider.suggest(BotSaves.names(context.getSource().getServer()), builder)
                                        )
                                        .executes(context -> {
                                            String name = StringArgumentType.getString(context, "name");
                                            if (!BotSaves.delete(context.getSource().getServer(), name)) {
                                                throw fail("Spielstand " + name + " gibt es nicht.");
                                            }

                                            ok(context, "Spielstand " + name + " geloescht.");
                                            return 1;
                                        })
                                )
                        )
                )
                .then(
                    Commands.literal("autosave")
                        .then(Commands.literal("on").executes(context -> autosave(context, true)))
                        .then(Commands.literal("off").executes(context -> autosave(context, false)))
                )
                .then(
                    Commands.literal("halt")
                        .then(Commands.argument("auswahl", SelectorArgumentType.selector()).suggests(SELECTORS).executes(BotCommands::halt))
                )
                .then(
                    Commands.literal("pace")
                        .then(
                            Commands.argument("gangart", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(List.of("walk", "sprint", "sprintjump", "auto"), builder))
                                .executes(context -> {
                                    String pace = StringArgumentType.getString(context, "gangart").toLowerCase(Locale.ROOT);
                                    if (!List.of("walk", "sprint", "sprintjump", "auto").contains(pace)) {
                                        throw fail("Gangart: walk, sprint, sprintjump oder auto.");
                                    }

                                    BotDefaults.get().movePace = pace;
                                    BotDefaults.get().save();

                                    ok(context, switch (pace) {
                                        case "sprint" -> "Bots rennen bei Befehlen (ausser auf den letzten Metern).";
                                        case "sprintjump" -> "Bots rennen und springen dabei wie Spieler.";
                                        case "auto" -> "Bots gehen - und rennen nur, wenn das Ziel weiter als 20 Bloecke weg ist.";
                                        default -> "Bots gehen bei Befehlen (Kaempfe und Flucht: weiter rennen).";
                                    });
                                    return 1;
                                })
                        )
                )
                .then(
                    Commands.literal("runaway")
                        .then(
                            Commands.literal("stop")
                                .then(
                                    Commands.argument("auswahl", SelectorArgumentType.selector())
                                        .suggests(SELECTORS)
                                        .executes(context -> runaway(context, false))
                                )
                        )
                        .then(Commands.argument("auswahl", SelectorArgumentType.selector()).suggests(SELECTORS).executes(context -> runaway(context, true)))
                )
                .then(
                    Commands.literal("minimap")
                        .then(Commands.literal("on").executes(context -> minimap(context, true)))
                        .then(Commands.literal("off").executes(context -> minimap(context, false)))
                )
                .then(
                    Commands.literal("spawnspeed")
                        .then(Commands.literal("normal").executes(context -> spawnSpeed(context, 30.0, "normal")))
                        .then(Commands.literal("fast").executes(context -> spawnSpeed(context, 45.0, "fast")))
                        .then(Commands.literal("max").executes(context -> spawnSpeed(context, 90.0, "max")))
                        .then(Commands.literal("instant").executes(context -> spawnSpeed(context, 0.0, "instant")))
                )
                .then(
                    Commands.literal("mobpush")
                        .then(Commands.literal("fast").executes(context -> mobPush(context, true)))
                        .then(Commands.literal("vanilla").executes(context -> mobPush(context, false)))
                )
                .then(
                    Commands.literal("spawnmobs")
                        .then(Commands.literal("on").executes(context -> spawnMobs(context, true)))
                        .then(Commands.literal("off").executes(context -> spawnMobs(context, false)))
                )
                .then(
                    Commands.literal("tpto")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(Commands.argument("ziel", Vec3Argument.vec3()).executes(BotCommands::teleportTo))
                        )
                )
                .then(
                    Commands.literal("search")
                        .then(
                            Commands.literal("stop")
                                .then(Commands.argument("auswahl", SelectorArgumentType.selector()).suggests(SELECTORS).executes(context -> {
                                    List<BotPlayer> targets = select(context, SelectorArgumentType.get(context, "auswahl"));

                                    for (BotPlayer bot : targets) {
                                        bot.searchId = null;
                                        bot.searchSpotted = false;
                                        bot.patrolArea = null;
                                        bot.clearFight();
                                        bot.fightMode = false;
                                    }

                                    ok(context, targets.size() + " Bot(s) suchen nicht mehr.");
                                    return targets.size();
                                }))
                        )
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(Commands.argument("ziel", SelectorArgumentType.selector()).suggests(PLAYERS_AND_SELECTORS).executes(BotCommands::search))
                        )
                )
                .then(Commands.literal("deaths").executes(context -> {
                    ArrayDeque<String> deaths = bots().recentDeaths;
                    if (deaths.isEmpty()) {
                        ok(context, "Noch kein Bot gestorben.");
                        return 0;
                    } else {
                        info(context, "Letzte Bot-Tode (neueste unten):", ChatFormatting.GOLD);
                        deaths.forEach(line -> info(context, "- " + line, ChatFormatting.YELLOW));
                        return deaths.size();
                    }
                }))
                .then(
                    Commands.literal("deathsound")
                        .then(Commands.literal("on").executes(context -> deathSound(context, "on")))
                        .then(Commands.literal("off").executes(context -> deathSound(context, "off")))
                        .then(
                            Commands.argument("sound", StringArgumentType.greedyString())
                                .executes(context -> deathSound(context, StringArgumentType.getString(context, "sound")))
                        )
                )
                .then(
                    Commands.literal("chat")
                        .then(Commands.literal("on").executes(context -> chat(context, true)))
                        .then(Commands.literal("off").executes(context -> chat(context, false)))
                )
                .then(
                    Commands.literal("respawn")
                        .then(Commands.literal("on").executes(context -> respawn(context, true, -1)))
                        .then(Commands.literal("off").executes(context -> respawn(context, false, -1)))
                        .then(
                            Commands.argument("sekunden", IntegerArgumentType.integer(1, 600))
                                .executes(context -> respawn(context, true, IntegerArgumentType.getInteger(context, "sekunden")))
                        )
                )
                .then(
                    Commands.literal("spawnpoint")
                        .then(
                            Commands.argument("team", StringArgumentType.word())
                                .suggests(TEAMS)
                                .executes(context -> spawnPoint(context, StringArgumentType.getString(context, "team"), null, false))
                                .then(
                                    Commands.literal("clear")
                                        .executes(context -> spawnPoint(context, StringArgumentType.getString(context, "team"), null, true))
                                )
                                .then(
                                    Commands.argument("ort", Vec3Argument.vec3())
                                        .executes(
                                            context -> spawnPoint(
                                                context, StringArgumentType.getString(context, "team"), Vec3Argument.getVec3(context, "ort"), false
                                            )
                                        )
                                )
                        )
                )
                .then(
                    Commands.literal("autowar")
                        .then(Commands.literal("on").executes(context -> autoWar(context, true)))
                        .then(Commands.literal("off").executes(context -> autoWar(context, false)))
                )
                .then(
                    Commands.literal("human")
                        .then(Commands.literal("on").executes(context -> human(context, true)))
                        .then(Commands.literal("off").executes(context -> human(context, false)))
                )
                .then(
                    Commands.literal("emote")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(Commands.argument("art", StringArgumentType.word()).suggests((context, builder) -> {
                                    for (String option : new String[]{"cheer", "nod", "no", "wave", "bow", "point", "clap", "laugh", "shield", "aura", "dance", "salute", "flex", "think", "crouch", "stop"}) {
                                        builder.suggest(option);
                                    }

                                    return builder.buildFuture();
                                }).executes(context -> emote(context, null))
                                    .then(Commands.argument("dauer", StringArgumentType.word()).suggests((context, builder) -> {
                                        for (String option : new String[]{"10s", "30s", "100s", "5m", "inf"}) {
                                            builder.suggest(option);
                                        }

                                        return builder.buildFuture();
                                    }).executes(context -> emote(context, StringArgumentType.getString(context, "dauer")))))
                        )
                )
                .then(
                    Commands.literal("assemble")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(
                                    Commands.argument("ziel", Vec3Argument.vec3())
                                        .executes(context -> assemble(context, 0.0))
                                        .then(
                                            Commands.argument("radius", DoubleArgumentType.doubleArg(2.0, 80.0))
                                                .executes(context -> assemble(context, DoubleArgumentType.getDouble(context, "radius")))
                                        )
                                )
                        )
                )
                .then(
                    Commands.literal("standon")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(Commands.argument("block", StringArgumentType.word()).suggests((context, builder) -> {
                                    for (String option : new String[]{"stone", "grass_block", "oak_planks", "sand", "gold_block", "sea_lantern", "obsidian"}) {
                                        builder.suggest(option);
                                    }

                                    return builder.buildFuture();
                                }).then(Commands.argument("ecken", StringArgumentType.greedyString()).executes(BotCommands::standOn)))
                        )
                )
                .then(
                    Commands.literal("life")
                        .then(
                            Commands.literal("off")
                                .then(
                                    Commands.argument("auswahl", SelectorArgumentType.selector())
                                        .suggests(SELECTORS)
                                        .executes(context -> life(context, false, 0.0))
                                )
                        )
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .executes(context -> life(context, true, 0.0))
                                .then(
                                    Commands.argument("radius", DoubleArgumentType.doubleArg(3.0, 200.0))
                                        .executes(context -> life(context, true, DoubleArgumentType.getDouble(context, "radius")))
                                )
                        )
                )
                .then(
                    Commands.literal("train")
                        .then(
                            Commands.literal("cage")
                                .then(Commands.literal("stop").executes(context -> {
                                    ExitSense.stopCage(bots());
                                    BotSystemMod.skills().save();
                                    ok(context, "Kaefig-Training beendet. Gelerntes bleibt (/bot learn info).");
                                    return 1;
                                }))
                                .then(
                                    Commands.argument("auswahl", SelectorArgumentType.selector())
                                        .suggests(SELECTORS)
                                        .executes(context -> cage(context, 30, 15, 2, 60))
                                        .then(
                                            Commands.argument("episoden", IntegerArgumentType.integer(1, 100000))
                                                .executes(context -> cage(context, IntegerArgumentType.getInteger(context, "episoden"), 15, 2, 60))
                                                .then(
                                                    Commands.argument("groesse", IntegerArgumentType.integer(9, 61))
                                                        .executes(
                                                            context -> cage(
                                                                context,
                                                                IntegerArgumentType.getInteger(context, "episoden"),
                                                                IntegerArgumentType.getInteger(context, "groesse"),
                                                                2,
                                                                60
                                                            )
                                                        )
                                                        .then(
                                                            Commands.argument("ausgaenge", IntegerArgumentType.integer(1, 4))
                                                                .executes(
                                                                    context -> cage(
                                                                        context,
                                                                        IntegerArgumentType.getInteger(context, "episoden"),
                                                                        IntegerArgumentType.getInteger(context, "groesse"),
                                                                        IntegerArgumentType.getInteger(context, "ausgaenge"),
                                                                        60
                                                                    )
                                                                )
                                                                .then(
                                                                    Commands.argument("sekunden", IntegerArgumentType.integer(10, 3600))
                                                                        .executes(
                                                                            context -> cage(
                                                                                context,
                                                                                IntegerArgumentType.getInteger(context, "episoden"),
                                                                                IntegerArgumentType.getInteger(context, "groesse"),
                                                                                IntegerArgumentType.getInteger(context, "ausgaenge"),
                                                                                IntegerArgumentType.getInteger(context, "sekunden")
                                                                            )
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                        .then(
                            Commands.literal("stop")
                                .then(Commands.argument("auswahl", SelectorArgumentType.selector()).suggests(SELECTORS).executes(context -> {
                                    List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
                                    int stopped = 0;

                                    for (BotPlayer bot : targets) {
                                        if (bot.trainRunsLeft > 0 || bot.trainA != null) {
                                            stopped++;
                                        }

                                        bot.trainRunsLeft = 0;
                                        bot.trainA = null;
                                        bot.trainB = null;
                                        bot.trainLegStart = 0L;
                                        bot.walkGoal = null;
                                        bot.orderUntil = 0L;
                                        AttributeInstance speed = bot.getAttribute(Attributes.MOVEMENT_SPEED);
                                        if (speed != null) {
                                            speed.setBaseValue(0.1);
                                        }
                                    }

                                    BotSystemMod.nav().save();
                                    ok(context, stopped + " Bot(s) hoeren mit dem Training auf (Gelerntes bleibt gespeichert).");
                                    return stopped;
                                }))
                        )
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(
                                    Commands.argument("von", Vec3Argument.vec3())
                                        .then(
                                            Commands.argument("nach", Vec3Argument.vec3())
                                                .executes(context -> train(context, 6))
                                                .then(
                                                    Commands.argument("laeufe", IntegerArgumentType.integer(1, 200))
                                                        .executes(context -> train(context, IntegerArgumentType.getInteger(context, "laeufe")))
                                                )
                                        )
                                )
                        )
                )
                .then(Commands.literal("nav").then(Commands.literal("info").executes(context -> {
                    String world = NavMemory.worldId(context.getSource().getLevel());
                    NavMemory nav = BotSystemMod.nav();
                    ok(context, "Wegenetz " + world + ": " + nav.nodeCount(world) + " Punkte, " + nav.edgeCount(world) + " Verbindungen.");
                    return 1;
                })).then(Commands.literal("save").executes(context -> {
                    ok(context, BotSystemMod.nav().save() ? "Wegenetz gespeichert." : "Nichts Neues zu speichern.");
                    return 1;
                })).then(Commands.literal("clear").executes(context -> {
                    String world = NavMemory.worldId(context.getSource().getLevel());
                    int removed = BotSystemMod.nav().clear(world);
                    BotSystemMod.nav().save();
                    ok(context, removed + " gelernte Punkte dieser Welt vergessen.");
                    return removed;
                })))
                .then(
                    Commands.literal("digrestore")
                        .then(
                            Commands.argument("sekunden", IntegerArgumentType.integer(0, 3600))
                                .executes(
                                    context -> {
                                        BotDefaults.get().digRestore = IntegerArgumentType.getInteger(context, "sekunden");
                                        BotDefaults.get().save();
                                        ok(
                                            context,
                                            BotDefaults.get().digRestore == 0
                                                ? "Abgebaute Bloecke bleiben weg."
                                                : "Abgebaute Bloecke kommen nach " + BotDefaults.get().digRestore + " s zurueck."
                                        );
                                        return 1;
                                    }
                                )
                        )
                )
                .then(
                    Commands.literal("formation")
                        .then(Commands.literal("width").then(Commands.argument("bloecke", DoubleArgumentType.doubleArg(2.0, 60.0)).executes(context -> {
                            BotDefaults.get().marchWidth = DoubleArgumentType.getDouble(context, "bloecke");
                            BotDefaults.get().save();
                            ok(context, "Marschbreite: " + (int)BotDefaults.get().marchWidth + " Bloecke.");
                            return 1;
                        })))
                        .then(
                            Commands.argument("art", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    for (String option : new String[]{"loose", "line", "column"}) {
                                        builder.suggest(option);
                                    }

                                    return builder.buildFuture();
                                })
                                .executes(
                                    context -> {
                                        String mode = StringArgumentType.getString(context, "art").toLowerCase(Locale.ROOT);
                                        if (!mode.startsWith("loose")
                                            && !mode.startsWith("line")
                                            && !mode.startsWith("reihe")
                                            && !mode.startsWith("column")
                                            && !mode.startsWith("gaense")) {
                                            throw fail("Formationen: loose (breit verteilt wie Leute), line (Reihe), column (Gaensemarsch).");
                                        }

                                        BotDefaults.get().marchFormation = mode;
                                        BotDefaults.get().save();
                                        ok(
                                            context,
                                            "Gruppen laufen ab jetzt als "
                                                + (
                                                    mode.startsWith("loose")
                                                        ? "lockere Menge"
                                                        : (mode.startsWith("line") ? "Reihe nebeneinander" : "Gaensemarsch")
                                                )
                                                + " (Breite "
                                                + (int)BotDefaults.get().marchWidth
                                                + ")."
                                        );
                                        return 1;
                                    }
                                )
                        )
                )
                .then(
                    Commands.literal("crowd")
                        .then(
                            Commands.literal("mode")
                                .then(
                                    Commands.argument("art", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            for (String option : new String[]{"tour", "line", "ring"}) {
                                                builder.suggest(option);
                                            }

                                            return builder.buildFuture();
                                        })
                                        .executes(
                                            context -> {
                                                String mode = StringArgumentType.getString(context, "art").toLowerCase(Locale.ROOT);
                                                if (!mode.startsWith("tour")
                                                    && !mode.startsWith("line")
                                                    && !mode.startsWith("reihe")
                                                    && !mode.startsWith("ring")
                                                    && !mode.startsWith("kreis")) {
                                                    throw fail("Formen: tour (Pulk hinter dir), line (Reihe), ring (Kreis).");
                                                }

                                                BotDefaults.get().followMode = mode;
                                                BotDefaults.get().save();
                                                ok(
                                                    context,
                                                    "Begleiter laufen als "
                                                        + (
                                                            mode.startsWith("tour")
                                                                ? "Reisegruppe hinter dir"
                                                                : (!mode.startsWith("ring") && !mode.startsWith("kreis") ? "Reihe neben dir" : "Kreis um dich")
                                                        )
                                                        + "."
                                                );
                                                return 1;
                                            }
                                        )
                                )
                        )
                        .then(Commands.literal("spread").then(Commands.argument("bloecke", DoubleArgumentType.doubleArg(2.0, 200.0)).executes(context -> {
                            BotDefaults.get().followSpread = DoubleArgumentType.getDouble(context, "bloecke");
                            BotDefaults.get().save();
                            ok(context, "Der Pulk ist " + (int)BotDefaults.get().followSpread + " Bloecke breit.");
                            return 1;
                        })))
                        .then(Commands.literal("spacing").then(Commands.argument("bloecke", DoubleArgumentType.doubleArg(1.2, 12.0)).executes(context -> {
                            BotDefaults.get().followSpacing = DoubleArgumentType.getDouble(context, "bloecke");
                            BotDefaults.get().save();
                            ok(context, "Abstand zwischen zwei Begleitern: " + BotDefaults.get().followSpacing + " Bloecke.");
                            return 1;
                        })))
                        .then(Commands.literal("slack").then(Commands.argument("bloecke", DoubleArgumentType.doubleArg(1.0, 60.0)).executes(context -> {
                            BotDefaults.get().followSlack = DoubleArgumentType.getDouble(context, "bloecke");
                            BotDefaults.get().save();
                            ok(context, "Begleiter gehen erst los, wenn sie " + (int)BotDefaults.get().followSlack + " Bloecke von ihrem Platz weg sind.");
                            return 1;
                        })))
                        .then(Commands.literal("stagger").then(Commands.argument("ticks", IntegerArgumentType.integer(0, 200)).executes(context -> {
                            BotDefaults.get().followStagger = IntegerArgumentType.getInteger(context, "ticks");
                            BotDefaults.get().save();
                            ok(context, "Losgehen versetzt um bis zu " + BotDefaults.get().followStagger + " Ticks.");
                            return 1;
                        })))
                        .then(
                            Commands.literal("look")
                                .then(
                                    Commands.argument("prozent", IntegerArgumentType.integer(0, 100))
                                        .executes(
                                            context -> {
                                                BotDefaults.get().followLook = IntegerArgumentType.getInteger(context, "prozent");
                                                BotDefaults.get().save();
                                                ok(
                                                    context,
                                                    BotDefaults.get().followLook == 0
                                                        ? "Begleiter schauen dich im Stehen nie an."
                                                        : "Begleiter schauen dich im Stehen zu " + BotDefaults.get().followLook + " % kurz an."
                                                );
                                                return 1;
                                            }
                                        )
                                )
                        )
                        .then(Commands.literal("jitter").then(Commands.argument("anteil", DoubleArgumentType.doubleArg(0.0, 1.5)).executes(context -> {
                            BotDefaults.get().followJitter = DoubleArgumentType.getDouble(context, "anteil");
                            BotDefaults.get().save();
                            ok(context, "Unruhe im Pulk: " + BotDefaults.get().followJitter + " (0 = festes Raster).");
                            return 1;
                        })))
                        .then(Commands.literal("catchup").then(Commands.argument("bloecke", DoubleArgumentType.doubleArg(2.0, 80.0)).executes(context -> {
                            BotDefaults.get().followCatchUp = DoubleArgumentType.getDouble(context, "bloecke");
                            BotDefaults.get().save();
                            ok(context, "Ab " + (int)BotDefaults.get().followCatchUp + " Bloecken Rueckstand wird gesprintet und direkt zum Fuehrer gelaufen.");
                            return 1;
                        })))
                        .then(
                            Commands.literal("headdrift")
                                .then(
                                    Commands.argument("grad", DoubleArgumentType.doubleArg(0.0, 15.0))
                                        .executes(
                                            context -> {
                                                BotDefaults.get().idleHeadDrift = DoubleArgumentType.getDouble(context, "grad");
                                                BotDefaults.get().save();
                                                ok(
                                                    context,
                                                    BotDefaults.get().idleHeadDrift <= 0.0
                                                        ? "Koepfe stehen im Stehen still."
                                                        : "Koepfe wandern im Stehen um bis zu " + BotDefaults.get().idleHeadDrift + " Grad."
                                                );
                                                return 1;
                                            }
                                        )
                                )
                        )
                        .then(
                            Commands.literal("sprint")
                                .then(Commands.literal("on").executes(context -> crowdSprint(context, true)))
                                .then(Commands.literal("off").executes(context -> crowdSprint(context, false)))
                        )
                        .then(
                            Commands.literal("info")
                                .executes(
                                    context -> {
                                        BotDefaults d = BotDefaults.get();
                                        ok(
                                            context,
                                            "Pulk: "
                                                + d.followMode
                                                + ", Breite "
                                                + (int)d.followSpread
                                                + ", Abstand "
                                                + d.followSpacing
                                                + ", Spiel "
                                                + (int)d.followSlack
                                                + ", versetzt "
                                                + d.followStagger
                                                + " Ticks, Blick "
                                                + d.followLook
                                                + " %, Unruhe "
                                                + d.followJitter
                                                + ", Sprint "
                                                + (d.followSprint ? "an" : "aus")
                                                + ", Aufholen ab "
                                                + (int)d.followCatchUp
                                                + ", Kopfbewegung "
                                                + d.idleHeadDrift
                                                + " Grad, Folgeabstand "
                                                + (int)d.followDistance
                                                + "."
                                        );
                                        return 1;
                                    }
                                )
                        )
                )
                .then(
                    Commands.literal("fidget")
                        .then(Commands.literal("on").executes(context -> fidget(context, true)))
                        .then(Commands.literal("off").executes(context -> fidget(context, false)))
                )
                .then(
                    Commands.literal("hostile")
                        .then(Commands.argument("parteien", StringArgumentType.greedyString()).suggests(PARTIES).executes(BotCommands::hostile))
                )
                .then(
                    Commands.literal("enemy")
                        .then(
                            Commands.literal("stop")
                                .then(Commands.argument("auswahl", SelectorArgumentType.selector()).suggests(SELECTORS).executes(BotCommands::enemyStop))
                        )
                        .then(Commands.argument("parteien", StringArgumentType.greedyString()).suggests(PARTIES).executes(BotCommands::enemy))
                )
                .then(
                    Commands.literal("hop")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(Commands.literal("on").executes(context -> hop(context, true)))
                                .then(Commands.literal("off").executes(context -> hop(context, false)))
                        )
                )
                .then(
                    Commands.literal("sight")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(
                                    Commands.argument("weite", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(List.of("off", "5", "10", "20", "40"), builder))
                                        .executes(BotCommands::sight)
                                )
                        )
                )
                .then(
                    Commands.literal("ffa")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .executes(context -> freeForAll(context, 100))
                                .then(
                                    Commands.argument("prozent", StringArgumentType.word())
                                        .executes(context -> freeForAll(context, percentage(StringArgumentType.getString(context, "prozent"))))
                                )
                        )
                )
                .then(
                    Commands.literal("ally")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(
                                    Commands.argument("spieler", SelectorArgumentType.selector())
                                        .suggests(PLAYERS_AND_SELECTORS)
                                        .executes(context -> ally(context, true))
                                        .then(Commands.literal("on").executes(context -> ally(context, true)))
                                        .then(Commands.literal("off").executes(context -> ally(context, false)))
                                )
                        )
                )
                .then(
                    Commands.literal("immortal")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(Commands.literal("on").executes(context -> immortal(context, true)))
                                .then(Commands.literal("off").executes(context -> immortal(context, false)))
                        )
                )
                .then(
                    Commands.literal("give")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(Commands.argument("kit", StringArgumentType.word()).suggests(KITS).executes(BotCommands::give))
                        )
                )
                .then(
                    Commands.literal("skill")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(Commands.argument("stufe", StringArgumentType.word()).suggests(SKILLS).executes(BotCommands::skill))
                        )
                )
                .then(
                    Commands.literal("randomskin")
                        .then(
                            Commands.literal("prefetch")
                                .executes(
                                    context -> {
                                        int queued = bots().prefetchSkins();
                                        ok(
                                            context,
                                            queued
                                                + " Skins werden im Hintergrund geholt (ca. "
                                                + (queued * 0.8 / 60.0 < 1.0 ? "unter einer Minute" : Math.round(queued * 0.8 / 60.0) + " Minuten")
                                                + "), "
                                                + bots().cachedSkins()
                                                + " schon im Cache. Danach spawnen grosse Truppen sofort mit Skin."
                                        );
                                        return queued;
                                    }
                                )
                        )
                        .then(Commands.argument("auswahl", SelectorArgumentType.selector()).suggests(SELECTORS).executes(BotCommands::randomSkin))
                )
                .then(
                    Commands.literal("skin")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(
                                    Commands.literal("color")
                                        .then(Commands.argument("farbe", StringArgumentType.word()).suggests(COLORS).executes(BotCommands::skinColor))
                                )
                                .then(
                                    Commands.literal("farbe")
                                        .then(Commands.argument("farbe", StringArgumentType.word()).suggests(COLORS).executes(BotCommands::skinColor))
                                )
                                .then(
                                    Commands.literal("file")
                                        .then(
                                            Commands.argument("datei", StringArgumentType.string())
                                                .suggests(SKIN_FILES)
                                                .executes(context -> skinFile(context, "auto"))
                                                .then(
                                                    Commands.argument("modell", StringArgumentType.word())
                                                        .suggests((context, builder) -> {
                                                            builder.suggest("auto");
                                                            builder.suggest("classic");
                                                            builder.suggest("slim");
                                                            return builder.buildFuture();
                                                        })
                                                        .executes(context -> skinFile(context, StringArgumentType.getString(context, "modell")))
                                                )
                                        )
                                )
                                .then(Commands.argument("name", StringArgumentType.word()).executes(BotCommands::skin))
                        )
                )
                .then(
                    Commands.literal("nick")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(Commands.argument("name", StringArgumentType.word()).executes(BotCommands::nick))
                        )
                )
                .then(
                    Commands.literal("randomnick")
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .executes(context -> randomNick(context, 4, 12, BotNameStyle.MIX, null))
                                .then(
                                    Commands.argument("min", IntegerArgumentType.integer(3, 16))
                                        .then(
                                            Commands.argument("max", IntegerArgumentType.integer(3, 16))
                                                .executes(
                                                    context -> randomNick(
                                                        context,
                                                        IntegerArgumentType.getInteger(context, "min"),
                                                        IntegerArgumentType.getInteger(context, "max"),
                                                        BotNameStyle.MIX,
                                                        null
                                                    )
                                                )
                                                .then(
                                                    Commands.argument("stil", StringArgumentType.word())
                                                        .executes(
                                                            context -> randomNick(
                                                                context,
                                                                IntegerArgumentType.getInteger(context, "min"),
                                                                IntegerArgumentType.getInteger(context, "max"),
                                                                BotNameStyle.parse(StringArgumentType.getString(context, "stil")),
                                                                null
                                                            )
                                                        )
                                                        .then(
                                                            Commands.argument("wort", StringArgumentType.word())
                                                                .executes(
                                                                    context -> randomNick(
                                                                        context,
                                                                        IntegerArgumentType.getInteger(context, "min"),
                                                                        IntegerArgumentType.getInteger(context, "max"),
                                                                        BotNameStyle.parse(StringArgumentType.getString(context, "stil")),
                                                                        StringArgumentType.getString(context, "wort")
                                                                    )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
                .then(
                    Commands.literal("tp")
                        .then(Commands.argument("auswahl", SelectorArgumentType.selector()).suggests(SELECTORS).executes(BotCommands::teleport))
                )
                .then(
                    Commands.literal("heal")
                        .then(Commands.argument("auswahl", SelectorArgumentType.selector()).suggests(SELECTORS).executes(BotCommands::heal))
                )
                .then(
                    Commands.literal("settings")
                        .executes(context -> settings(context, null))
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .executes(context -> settings(context, SelectorArgumentType.get(context, "auswahl")))
                        )
                )
                .then(
                    Commands.literal("debug")
                        .executes(context -> debug(context, null))
                        .then(Commands.literal("off").executes(context -> {
                            BotSystemMod.debug().clear(context.getSource().getPlayerOrException());
                            ok(context, "Debug-Ausgabe aus.");
                            return 1;
                        }))
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .executes(context -> debug(context, SelectorArgumentType.get(context, "auswahl")))
                        )
                )
                .then(
                    Commands.literal("farm")
                        .then(
                            Commands.literal("stop")
                                .then(Commands.argument("auswahl", SelectorArgumentType.selector()).suggests(SELECTORS).executes(BotCommands::stopFarm))
                        )
                        .then(
                            Commands.argument("auswahl", SelectorArgumentType.selector())
                                .suggests(SELECTORS)
                                .then(
                                    Commands.argument("ziel", StringArgumentType.word())
                                        .suggests(FARM)
                                        .executes(context -> farm(context, 24.0))
                                        .then(
                                            Commands.argument("radius", IntegerArgumentType.integer(2, 64))
                                                .executes(context -> farm(context, IntegerArgumentType.getInteger(context, "radius")))
                                        )
                                )
                        )
                )
                .then(
                    Commands.literal("preset")
                        .then(Commands.literal("list").executes(BotCommands::presetList))
                        .then(
                            Commands.literal("save")
                                .then(
                                    Commands.argument("name", StringArgumentType.word())
                                        .executes(context -> presetSave(context, "-", "-", 1))
                                        .then(
                                            Commands.argument("kit", StringArgumentType.word())
                                                .suggests(KITS)
                                                .executes(context -> presetSave(context, StringArgumentType.getString(context, "kit"), "-", 1))
                                                .then(
                                                    Commands.argument("team", StringArgumentType.word())
                                                        .executes(
                                                            context -> presetSave(
                                                                context,
                                                                StringArgumentType.getString(context, "kit"),
                                                                StringArgumentType.getString(context, "team"),
                                                                1
                                                            )
                                                        )
                                                        .then(
                                                            Commands.argument("anzahl", IntegerArgumentType.integer(1, 100))
                                                                .executes(
                                                                    context -> presetSave(
                                                                        context,
                                                                        StringArgumentType.getString(context, "kit"),
                                                                        StringArgumentType.getString(context, "team"),
                                                                        IntegerArgumentType.getInteger(context, "anzahl")
                                                                    )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                        .then(
                            Commands.literal("delete")
                                .then(Commands.argument("name", StringArgumentType.word()).suggests(PRESETS).executes(BotCommands::presetDelete))
                        )
                        .then(
                            Commands.literal("spawn")
                                .then(
                                    Commands.argument("name", StringArgumentType.word())
                                        .suggests(PRESETS)
                                        .executes(context -> presetSpawn(context, -1))
                                        .then(
                                            Commands.argument("anzahl", IntegerArgumentType.integer(1, 1000))
                                                .executes(context -> presetSpawn(context, IntegerArgumentType.getInteger(context, "anzahl")))
                                        )
                                )
                        )
                )
                .then(
                    Commands.literal("type")
                        .then(Commands.literal("list").executes(BotCommands::typeList))
                        .then(
                            Commands.literal("create")
                                .then(
                                    Commands.argument("name", StringArgumentType.word())
                                        .executes(context -> typeCreate(context, ""))
                                        .then(
                                            Commands.argument("einstellungen", StringArgumentType.greedyString())
                                                .suggests(TYPE_SETTINGS)
                                                .executes(context -> typeCreate(context, StringArgumentType.getString(context, "einstellungen")))
                                        )
                                )
                        )
                        .then(
                            Commands.literal("set")
                                .then(
                                    Commands.argument("name", StringArgumentType.word())
                                        .suggests(TYPES)
                                        .then(
                                            Commands.argument("einstellungen", StringArgumentType.greedyString())
                                                .suggests(TYPE_SETTINGS)
                                                .executes(BotCommands::typeSet)
                                        )
                                )
                        )
                        .then(
                            Commands.literal("info")
                                .then(Commands.argument("name", StringArgumentType.word()).suggests(TYPES).executes(BotCommands::typeInfo))
                        )
                        .then(
                            Commands.literal("delete")
                                .then(Commands.argument("name", StringArgumentType.word()).suggests(TYPES).executes(BotCommands::typeDelete))
                        )
                        .then(
                            Commands.literal("spawn")
                                .then(
                                    Commands.argument("name", StringArgumentType.word())
                                        .suggests(TYPES)
                                        .executes(context -> typeSpawn(context, 1, null))
                                        .then(
                                            Commands.argument("anzahl", IntegerArgumentType.integer(1, 1000))
                                                .executes(context -> typeSpawn(context, IntegerArgumentType.getInteger(context, "anzahl"), null))
                                                .then(
                                                    Commands.argument("team", StringArgumentType.word())
                                                        .suggests(TEAMS)
                                                        .executes(
                                                            context -> typeSpawn(
                                                                context, IntegerArgumentType.getInteger(context, "anzahl"), StringArgumentType.getString(context, "team")
                                                            )
                                                        )
                                                )
                                        )
                                )
                        )
                        .then(
                            Commands.literal("apply")
                                .then(
                                    Commands.argument("auswahl", SelectorArgumentType.selector())
                                        .suggests(SELECTORS)
                                        .then(Commands.argument("name", StringArgumentType.word()).suggests(TYPES).executes(BotCommands::typeApply))
                                )
                        )
                )
                .then(Commands.literal("help").executes(BotCommands::help))
        );
        CommandNode<CommandSourceStack> botNode = dispatcher.getRoot().getChild("bot");
        if (botNode != null) {
            for (String gone : new String[]{"train", "gym", "learn", "skills", "escape", "trainspeed", "trainexplore", "learnalways"}) {
                for (String field : new String[]{"children", "literals", "arguments"}) {
                    try {
                        Field map = CommandNode.class.getDeclaredField(field);
                        map.setAccessible(true);
                        ((Map)map.get(botNode)).remove(gone);
                    } catch (ReflectiveOperationException var11) {
                    }
                }
            }
        }

        dispatcher.register(
            Commands.literal("itemclear")
                .requires(source -> Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS).test(source) || isScriptedSource(source))
                .executes(context -> {
                    int removed = 0;

                    for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
                        List<Entity> items = new ArrayList<>();

                        for (Entity entity : level.getAllEntities()) {
                            if (entity instanceof ItemEntity) {
                                items.add(entity);
                            }
                        }

                        for (Entity entity : items) {
                            entity.discard();
                            removed++;
                        }
                    }

                    ok(context, removed + " Items entfernt.");
                    return removed;
                })
        );
    }

    static boolean isScriptedSource(CommandSourceStack source) {
        Entity entity = source.getEntity();
        if (entity == null) {
            return true;
        } else {
            return !(entity instanceof ServerPlayer player)
                ? true
                : player instanceof BotPlayer || player.getClass() != ServerPlayer.class || player.connection == null;
        }
    }

    private static Bots bots() throws CommandSyntaxException {
        Bots bots = BotSystemMod.bots();
        if (bots == null) {
            throw NO_BOTS.create();
        } else {
            return bots;
        }
    }

    private static void ok(CommandContext<CommandSourceStack> context, String text) {
        if (!BotDefaults.get().quietChat) {
            context.getSource().sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GREEN), false);
        }
    }

    private static void info(CommandContext<CommandSourceStack> context, String text, ChatFormatting colour) {
        if (!BotDefaults.get().quietChat) {
            context.getSource().sendSuccess(() -> Component.literal(text).withStyle(colour), false);
        }
    }

    private static CommandSyntaxException fail(String text) {
        return new SimpleCommandExceptionType(Component.literal(text)).create();
    }

    private static List<BotPlayer> select(CommandContext<CommandSourceStack> context, String selector) throws CommandSyntaxException {
        Bots bots = bots();
        String lower = selector.toLowerCase(Locale.ROOT);
        if (lower.equals("@a") || lower.equals("all") || lower.equals("@all") || lower.equals("@alle") || lower.equals("*")) {
            return new ArrayList<>(bots.all());
        }

        if (isGuardSelector(lower)) {
            ServerPlayer owner = context.getSource().getPlayerOrException();
            List<BotPlayer> guards = bots.all().stream().filter(bot -> owner.getUUID().equals(bot.followTarget)).toList();
            if (guards.isEmpty()) {
                throw fail("Dir folgt gerade kein Bot. /bot guard <auswahl>");
            } else {
                return new ArrayList<>(guards);
            }
        } else if (lower.startsWith("@team:") || lower.startsWith("@group:")) {
            String team = selector.substring(selector.indexOf(58) + 1);
            List<BotPlayer> found = bots.all().stream().filter(bot -> team.equalsIgnoreCase(bot.team)).toList();
            if (found.isEmpty()) {
                throw fail("Team " + team + " ist leer oder existiert nicht.");
            } else {
                return new ArrayList<>(found);
            }
        } else if (lower.startsWith("@near:")) {
            double radius;
            try {
                radius = Double.parseDouble(selector.substring(6));
            } catch (NumberFormatException error) {
                throw fail("Radius muss eine Zahl sein: @near:10 oder near-all 10");
            }

            if (radius > 0.0 && !(radius > 1000.0)) {
                Vec3 centre = context.getSource().getPosition();
                ServerLevel world = context.getSource().getLevel();
                List<BotPlayer> found = bots.all()
                    .stream()
                    .filter(bot -> bot.level() == world && bot.position().distanceToSqr(centre) <= radius * radius)
                    .toList();
                if (found.isEmpty()) {
                    throw fail("Kein Bot im Umkreis von " + (int)radius + " Blöcken.");
                } else {
                    return new ArrayList<>(found);
                }
            } else {
                throw fail("Radius muss zwischen 1 und 1000 liegen.");
            }
        } else {
            BotPlayer single = bots.byName(selector).orElseThrow(() -> fail("Bot " + selector + " nicht gefunden."));
            return new ArrayList<>(List.of(single));
        }
    }

    private static List<MobUnits.Unit> selectUnits(CommandContext<CommandSourceStack> context, String selector) {
        MobUnits mobs = BotSystemMod.mobs();
        if (mobs != null && !mobs.isEmpty()) {
            String lower = selector.toLowerCase(Locale.ROOT);
            if (lower.equals("@a") || lower.equals("all") || lower.equals("@all") || lower.equals("@alle") || lower.equals("*")) {
                return new ArrayList<>(mobs.all());
            }

            if (lower.startsWith("@team:") || lower.startsWith("@group:")) {
                return mobs.byTeam(selector.substring(selector.indexOf(58) + 1));
            }

            if (isGuardSelector(lower)) {
                // "Wachen" on the map / @guards: also the mobs guarding or following you.
                ServerPlayer owner = context.getSource().getPlayer();
                List<MobUnits.Unit> mine = new ArrayList<>();
                if (owner != null) {
                    for (MobUnits.Unit unit : mobs.all()) {
                        if (owner.getUUID().equals(unit.followId)) {
                            mine.add(unit);
                        }
                    }
                }

                return mine;
            }

            if (lower.startsWith("@near:")) {
                try {
                    double radius = Double.parseDouble(selector.substring(6));
                    return mobs.near(context.getSource().getLevel(), context.getSource().getPosition(), radius);
                } catch (NumberFormatException var6) {
                }
            }

            return new ArrayList<>();
        } else {
            return new ArrayList<>();
        }
    }

    private static List<BotPlayer> selectLenient(CommandContext<CommandSourceStack> context, String selector) throws CommandSyntaxException {
        try {
            return select(context, selector);
        } catch (CommandSyntaxException error) {
            if (!selectUnits(context, selector).isEmpty()) {
                return new ArrayList<>();
            } else {
                throw error;
            }
        }
    }

    private static int mob(CommandContext<CommandSourceStack> context, int count, String team) throws CommandSyntaxException {
        MobUnits mobs = BotSystemMod.mobs();
        if (mobs == null) {
            throw NO_BOTS.create();
        }

        String typeName = StringArgumentType.getString(context, "typ");
        EntityType<?> type = EntityType.byString(typeName.contains(":") ? typeName : "minecraft:" + typeName.toLowerCase(Locale.ROOT)).orElse(null);
        if (type == null) {
            throw fail("Unbekannter Mob: " + typeName);
        }

        String teamName = team != null && !noValue(team) ? teamName(team) : null;
        CommandSourceStack source = context.getSource();
        List<MobUnits.Unit> spawned = mobs.spawn(source.getLevel(), type, count, teamName, source.getPosition());
        if (spawned == null) {
            throw fail(typeName + " ist kein Mob (kein Lebewesen mit KI).");
        }

        if (type.getCategory() == MobCategory.MONSTER && source.getLevel().getDifficulty() == Difficulty.PEACEFUL) {
            source.getLevel().getWorld().setDifficulty(org.bukkit.Difficulty.EASY);
            ok(context, "Schwierigkeit war Friedlich - dort loescht Minecraft feindliche Mobs sofort. Auf Leicht gestellt.");
        }

        ok(
            context,
            spawned.size()
                + "x "
                + typeName
                + (teamName == null ? "" : " in Team " + teamName)
                + " gespawnt. Steuern wie Bots: /bot goto|fight|attack|march ... @team:"
                + (teamName == null ? "?" : teamName)
        );
        return spawned.size();
    }

    private static int mobList(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        MobUnits mobs = BotSystemMod.mobs();
        if (mobs != null && !mobs.isEmpty()) {
            Map<String, Map<String, Integer>> perTeam = new LinkedHashMap<>();

            for (MobUnits.Unit unit : mobs.all()) {
                perTeam.computeIfAbsent(unit.team == null ? "ohne Team" : unit.team, key -> new LinkedHashMap<>()).merge(unit.name(), 1, Integer::sum);
            }

            perTeam.forEach((team, kinds) -> {
                StringBuilder line = new StringBuilder("- " + team + ": ");
                kinds.forEach((kind, n) -> line.append(n).append("x ").append(kind).append("  "));
                info(context, line.toString().trim(), ChatFormatting.YELLOW);
            });
            int shown = 0;

            for (MobUnits.Unit unit : mobs.all()) {
                if (shown++ >= 8) {
                    break;
                }

                info(
                    context,
                    "  "
                        + unit.name()
                        + "#"
                        + unit.mob.getId()
                        + " "
                        + unit.state()
                        + " fight="
                        + unit.fightMode
                        + " enemies="
                        + unit.enemyTeams
                        + " target="
                        + (unit.mob.getTarget() == null ? "-" : unit.mob.getTarget().getName().getString())
                        + " goal="
                        + (unit.goal == null ? "-" : (int)unit.goal.x + "/" + (int)unit.goal.z)
                        + " navIdle="
                        + unit.mob.getNavigation().isDone()
                        + " ai="
                        + !unit.mob.isNoAi()
                        + " pos="
                        + (int)unit.mob.getX()
                        + "/"
                        + (int)unit.mob.getZ()
                        + " node="
                        + (
                            unit.mob.getNavigation().getPath() == null
                                ? "-"
                                : unit.mob.getNavigation().getPath().getNextNodePos()
                                    + "/"
                                    + unit.mob.getNavigation().getPath().getNodeCount()
                                    + "/"
                                    + unit.mob.getNavigation().getPath().canReach()
                        )
                        + " age="
                        + unit.mob.tickCount
                        + " approach="
                        + (unit.approach == null ? "-" : unit.approach.getName().getString())
                        + " fwd="
                        + String.format(Locale.ROOT, "%.2f", unit.mob.getSpeed())
                        + " mcSpeed="
                        + String.format(Locale.ROOT, "%.2f", unit.mob.getMoveControl().getSpeedModifier())
                        + " y="
                        + String.format(Locale.ROOT, "%.2f", unit.mob.getY())
                        + " goals="
                        + Nms.goalSelector(unit.mob)
                            .getAvailableGoals()
                            .stream()
                            .filter(WrappedGoal::isRunning)
                            .map(g -> g.getGoal().getClass().getSimpleName())
                            .toList()
                        + " moving="
                        + unit.mob.getMoveControl().hasWanted()
                        + " motion="
                        + String.format(Locale.ROOT, "%.3f", unit.mob.getDeltaMovement().horizontalDistance())
                        + " onGround="
                        + unit.mob.onGround(),
                    ChatFormatting.GRAY
                );
            }

            return mobs.size();
        } else {
            ok(context, "Keine Mob-Einheiten.");
            return 0;
        }
    }

    private static void armUnits(BotCommands.Faction own, List<BotCommands.Faction> enemies, boolean attack, Vec3 goal, boolean marching, long deadline) {
        for (MobUnits.Unit unit : own.units) {
            unit.clearFight();
            boolean anyEnemy = false;

            for (BotCommands.Faction enemy : enemies) {
                anyEnemy |= !enemy.bots.isEmpty() || !enemy.units.isEmpty() || !enemy.playerIds.isEmpty();

                for (String team : enemy.teams) {
                    if (!Bots.allied(unit.team, team)) {
                        (attack ? unit.enemyTeams : unit.hostileTeams).add(team);
                    }
                }

                unit.targetIds.addAll(enemy.looseIds());
            }

            unit.fightMode = attack && anyEnemy;
            unit.followId = null;
            unit.goal = goal;
            unit.goalDeadline = deadline;
            unit.marching = marching;
            unit.post = null;
        }
    }

    private static boolean isGuardSelector(String lower) {
        String bare = lower.startsWith("@") ? lower.substring(1) : lower;
        return bare.equals("guards") || bare.equals("guard") || bare.equals("wachen") || bare.equals("wache") || bare.equals("follower");
    }

    private static boolean isSelector(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.equals("@a")
            || lower.equals("all")
            || isGuardSelector(lower)
            || lower.startsWith("@team:")
            || lower.startsWith("@group:")
            || lower.startsWith("@near:");
    }

    private static boolean noValue(String value) {
        return value == null
            || value.isBlank()
            || value.equals("-")
            || value.equalsIgnoreCase("none")
            || value.equalsIgnoreCase("kein")
            || value.equalsIgnoreCase("null");
    }

    private static String teamName(String raw) throws CommandSyntaxException {
        String team = raw.startsWith("@team:") ? raw.substring(6) : raw;
        if (!team.matches("[A-Za-z0-9_-]{1,16}")) {
            throw fail("Teamnamen: 1-16 Zeichen (A-Z, 0-9, _, -).");
        } else {
            return team;
        }
    }

    private static int percentage(String value) throws CommandSyntaxException {
        try {
            int percent = Integer.parseInt(value.endsWith("%") ? value.substring(0, value.length() - 1) : value);
            if (percent >= 1 && percent <= 100) {
                return percent;
            } else {
                throw fail("Prozent muss zwischen 1 und 100 liegen.");
            }
        } catch (NumberFormatException error) {
            throw fail("Prozent muss eine Zahl sein, z.B. 50 oder 50%.");
        }
    }

    private static Kits kits() throws CommandSyntaxException {
        Kits kits = BotSystemMod.kits();
        if (kits == null) {
            throw NO_BOTS.create();
        } else {
            return kits;
        }
    }

    private static String kitList() {
        Kits kits = BotSystemMod.kits();
        return String.join(", ", kits == null ? Kits.BUILTIN : kits.names());
    }

    private static final String[] TIER_FIELDS = {"reachMin", "reachMax", "accuracy", "reaction", "crit", "wtap", "stun", "shield", "strafe", "timingSlop"};
    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> TIER_SUGGESTIONS = (context, builder) -> {
        for (de.fiden.botsystem.bot.Tiers.Tier tier : de.fiden.botsystem.bot.Tiers.table()) {
            builder.suggest(tier.name.toLowerCase(Locale.ROOT));
        }

        for (String example : new String[]{"lt5-ht5", "lt4-ht4", "ht4-ht3", "random"}) {
            builder.suggest(example);
        }

        return builder.buildFuture();
    };

    private static int tiersShow(CommandContext<CommandSourceStack> context) {
        info(context, "Stufe | Reichweite | Treffer | Reaktion | Krit | W-Tap | Axt-Stun | Schild | Strafen | Takt+", ChatFormatting.GOLD);
        for (de.fiden.botsystem.bot.Tiers.Tier tier : de.fiden.botsystem.bot.Tiers.table()) {
            info(context, String.format(Locale.ROOT, "%s | %.2f-%.2f | %.0f%% | %.0f t | %.0f%% | %.0f%% | %.0f%% | %.0f%% | %.0f%% | %.0f t",
                tier.name, tier.reachMin, tier.reachMax, tier.accuracy * 100, tier.reaction, tier.crit * 100, tier.wtap * 100,
                tier.stun * 100, tier.shield * 100, tier.strafe * 100, tier.timingSlop), ChatFormatting.GRAY);
        }

        info(context, "Aendern: /bot tiers set <stufe> <wert> <zahl> (Anteile 0-1) | reset | reload | Datei botsystem-tiers.json", ChatFormatting.DARK_GRAY);
        return 1;
    }

    private static int tiersSet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        de.fiden.botsystem.bot.Tiers.Tier tier = de.fiden.botsystem.bot.Tiers.byName(StringArgumentType.getString(context, "stufe"));
        if (tier == null) {
            throw fail("Unbekannte Stufe - siehe /bot tiers.");
        }

        String field = StringArgumentType.getString(context, "wert");
        double value = DoubleArgumentType.getDouble(context, "zahl");
        switch (field.toLowerCase(Locale.ROOT)) {
            case "reachmin" -> tier.reachMin = Math.min(3.0, value);
            case "reachmax" -> tier.reachMax = Math.min(3.0, value);
            case "accuracy" -> tier.accuracy = Math.min(1.0, value);
            case "reaction" -> tier.reaction = value;
            case "crit" -> tier.crit = Math.min(1.0, value);
            case "wtap" -> tier.wtap = Math.min(1.0, value);
            case "stun" -> tier.stun = Math.min(1.0, value);
            case "shield" -> tier.shield = Math.min(1.0, value);
            case "strafe" -> tier.strafe = Math.min(1.0, value);
            case "timingslop" -> tier.timingSlop = value;
            default -> throw fail("Werte: " + String.join(", ", TIER_FIELDS));
        }

        de.fiden.botsystem.bot.Tiers.save();
        bots().all().forEach(bot -> bot.skill.setSkill(bot.skill.skill()));
        ok(context, tier.name + ": " + field + " = " + value);
        return 1;
    }

    /**
     * Parade block for n bots: {columns, ranks, side spacing, back spacing, width, length}.
     * width/length < 0 = pick them (1.5 blocks apart, a bit wider than deep).
     */
    private static double[] armyLayout(int n, double width, double length) {
        n = Math.max(1, n);
        int columns;
        if (width < 0.0) {
            columns = Math.min(n, (int)Math.ceil(Math.sqrt(n * 2.0)));
        } else if (length < 0.0) {
            columns = Math.min(n, Math.max(1, (int)Math.round(width / 1.5) + 1));
        } else if (length < 0.01 || n == 1) {
            columns = n;
        } else {
            double spacing = Math.sqrt(Math.max(0.01, width * length) / n);
            columns = Math.min(n, Math.max(1, (int)Math.round(width / Math.max(0.8, spacing)) + 1));
        }

        int ranks = (int)Math.ceil(n / (double)columns);
        double side = columns <= 1 ? 0.0 : (width < 0.0 ? 1.5 : Math.max(0.8, width / (columns - 1)));
        double back;
        if (ranks <= 1) {
            back = 0.0;
        } else if (length < 0.0) {
            back = side > 0.0 ? side : 1.5;
        } else {
            back = Math.max(0.8, length / (ranks - 1));
        }

        return new double[]{columns, ranks, side, back, side * (columns - 1), back * (ranks - 1)};
    }

    private static int armyCount(CommandContext<CommandSourceStack> context) {
        try {
            return selectLenient(context, SelectorArgumentType.get(context, "auswahl")).size();
        } catch (Exception broken) {
            return 0;
        }
    }

    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> ARMY_WIDTH = (context, builder) -> {
        int n = armyCount(context);
        double[] layout = armyLayout(Math.max(1, n), -1.0, -1.0);
        builder.suggest(String.format(Locale.ROOT, "%.1f", layout[4]));
        for (double option : new double[]{5.0, 10.0, 20.0}) {
            builder.suggest(String.format(Locale.ROOT, "%.0f", option));
        }

        return builder.buildFuture();
    };

    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> ARMY_LENGTH = (context, builder) -> {
        int n = armyCount(context);
        double width;
        try {
            width = DoubleArgumentType.getDouble(context, "breite");
        } catch (IllegalArgumentException missing) {
            width = -1.0;
        }

        double[] layout = armyLayout(Math.max(1, n), width, -1.0);
        builder.suggest(String.format(Locale.ROOT, "%.1f", layout[5]));
        return builder.buildFuture();
    };

    private static int armyMarch(CommandContext<CommandSourceStack> context, double width, double length, double tempo) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        List<MobUnits.Unit> marchUnits = selectUnits(context, SelectorArgumentType.get(context, "auswahl"));
        Vec3 goal = Vec3Argument.getVec3(context, "ziel");
        if (targets.isEmpty() && marchUnits.isEmpty()) {
            throw fail("Keine Bots oder Mobs ausgewaehlt.");
        }

        BotBrain brain = BotSystemMod.brain();
        for (BotPlayer bot : targets) {
            if (bot.drill != null) {
                bot.drill.members.remove(bot);
                bot.drill = null;
            }
        }

        for (MobUnits.Unit unit : marchUnits) {
            if (unit.drill != null) {
                unit.drill.mobs.remove(unit);
                unit.drill = null;
            }
        }

        // Bots and mobs march in one block: sort everyone by where they stand.
        List<net.minecraft.world.entity.LivingEntity> everyone = new ArrayList<>(targets);
        for (MobUnits.Unit unit : marchUnits) {
            everyone.add(unit.mob);
        }

        // Stable order: sorted by where they stand across the march line, so nobody has to cross
        // the whole block to reach its place.
        double cx = 0.0;
        double cz = 0.0;
        for (net.minecraft.world.entity.LivingEntity member : everyone) {
            cx += member.getX();
            cz += member.getZ();
        }

        final double centreX = cx / everyone.size();
        final double centreZ = cz / everyone.size();
        cx = centreX;
        cz = centreZ;
        double dx = goal.x - cx;
        double dz = goal.z - cz;
        double distance = Math.max(1.0E-4, Math.sqrt(dx * dx + dz * dz));
        double fx = dx / distance;
        double fz = dz / distance;
        double[] layout = armyLayout(everyone.size(), width, length);
        int columns = (int)layout[0];
        List<net.minecraft.world.entity.LivingEntity> ordered = new ArrayList<>(everyone);
        // Front ranks from those furthest ahead, within a rank left to right.
        ordered.sort(Comparator.comparingDouble(member -> -((member.getX() - centreX) * fx + (member.getZ() - centreZ) * fz)));
        List<net.minecraft.world.entity.LivingEntity> sorted = new ArrayList<>();
        for (int start = 0; start < ordered.size(); start += columns) {
            List<net.minecraft.world.entity.LivingEntity> rank = new ArrayList<>(ordered.subList(start, Math.min(ordered.size(), start + columns)));
            rank.sort(Comparator.comparingDouble(member -> -((member.getX() - centreX) * fz - (member.getZ() - centreZ) * fx)));
            sorted.addAll(rank);
        }

        List<BotPlayer> placed = new ArrayList<>();
        List<MobUnits.Unit> placedUnits = new ArrayList<>();
        for (int slot = 0; slot < sorted.size(); slot++) {
            net.minecraft.world.entity.LivingEntity member = sorted.get(slot);
            if (member instanceof BotPlayer bot) {
                bot.drillSlot = slot;
                placed.add(bot);
            } else {
                MobUnits.Unit unit = MobUnits.unitOf(member);
                unit.drillSlot = slot;
                unit.goal = null;
                unit.post = null;
                unit.patrolArea = null;
                placedUnits.add(unit);
            }
        }

        // The block forms around where the group stands now; its front line then marches to the goal.
        Vec3 start = new Vec3(cx + fx * layout[5] / 2.0, goal.y, cz + fz * layout[5] / 2.0);
        long tick = context.getSource().getServer().getTickCount();
        ArmyDrill drill = new ArmyDrill(placed, placedUnits, start, goal, columns, (int)layout[1], layout[2], layout[3], 0.19 * tempo, tick);
        for (BotPlayer bot : placed) {
            bot.clearFight();
            bot.fightMode = false;
            bot.followTarget = null;
            bot.post = null;
            bot.emoteKind = 0;
        }

        brain.drills.add(drill);
        ok(context, String.format(Locale.ROOT, "%d Bot(s) + %d Mob(s) marschieren: %d x %d, Abstand %.1f / %.1f, Breite %.1f, Laenge %.1f, Tempo %.2f - Stopp: /bot armymarch stop <auswahl>",
            placed.size(), placedUnits.size(), columns, (int)layout[1], layout[2], layout[3], layout[4], layout[5], tempo));
        return placed.size();
    }

    /**
     * Skip the walk: an army march skips its lining-up (bots put straight into the block, then
     * they march); other orders put the bot at its goal - occupy post, walk goal, patrol point, area.
     */
    private static int arrive(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        java.util.Set<ArmyDrill> drills = new java.util.HashSet<>();
        int moved = 0;
        for (BotPlayer bot : targets) {
            ServerLevel level = bot.level();
            Vec3 to = null;
            float yaw = bot.getYRot();
            if (bot.drill != null) {
                // Army march: skip only the lining-up - everyone is put on their exact spot in the
                // block right where it stands now, then they march to the goal as usual.
                ArmyDrill drill = bot.drill;
                if (drills.add(drill) && drill.phase == ArmyDrill.Phase.FORMING) {
                    drill.phase = ArmyDrill.Phase.READY;
                    drill.phaseTick = context.getSource().getServer().getTickCount();
                }

                Vec3 slot = drill.slot(bot);
                to = Ground.find(level, slot.x, slot.z, bot.getY());
                if (to == null) {
                    to = new Vec3(slot.x, bot.getY(), slot.z);
                }

                yaw = drill.yaw;
            } else if (bot.post != null) {
                to = bot.post;
                if (!Float.isNaN(bot.postYaw)) {
                    yaw = bot.postYaw;
                }
            } else if (bot.walkGoal != null) {
                to = bot.walkGoal;
            } else if (bot.patrol != null && !bot.patrol.isEmpty()) {
                to = bot.patrol.get(Math.max(0, Math.min(bot.patrolIndex, bot.patrol.size() - 1)));
            } else {
                double[] area = bot.patrolArea != null ? bot.patrolArea : bot.stayArea;
                if (area != null && area.length >= 4) {
                    double x = area[0] + (area[2] - area[0]) * ThreadLocalRandom.current().nextDouble(0.2, 0.8);
                    double z = area[1] + (area[3] - area[1]) * ThreadLocalRandom.current().nextDouble(0.2, 0.8);
                    to = Ground.find(level, x, z, bot.getY());
                }
            }

            if (to == null) {
                continue;
            }

            bot.teleportTo(level, to.x, to.y, to.z, java.util.Set.of(), yaw, 0.0F, false);
            bot.setDeltaMovement(Vec3.ZERO);
            bot.path = null;
            if (bot.drill == null) {
                bot.walkGoal = null;
            }

            moved++;
        }

        for (MobUnits.Unit unit : selectUnits(context, SelectorArgumentType.get(context, "auswahl"))) {
            ServerLevel level = (ServerLevel)unit.mob.level();
            if (unit.drill != null) {
                if (drills.add(unit.drill) && unit.drill.phase == ArmyDrill.Phase.FORMING) {
                    unit.drill.phase = ArmyDrill.Phase.READY;
                    unit.drill.phaseTick = context.getSource().getServer().getTickCount();
                }

                Vec3 slot = unit.drill.slot(unit);
                Vec3 spot = Ground.find(level, slot.x, slot.z, unit.mob.getY());
                unit.mob.snapTo(spot.x, spot.y, spot.z, unit.drill.yaw, 0.0F);
                unit.mob.getNavigation().stop();
                moved++;
                continue;
            }

            Vec3 to = unit.goal != null ? unit.goal : unit.post;
            if (to == null && unit.patrolArea != null) {
                to = Ground.randomDryLoading(level, unit.patrolArea, unit.mob.getY(), ThreadLocalRandom.current());
            }

            if (to != null) {
                unit.mob.snapTo(to.x, to.y, to.z, unit.mob.getYRot(), 0.0F);
                unit.mob.getNavigation().stop();
                unit.goal = null;
                moved++;
            }
        }

        ok(context, moved + " Bot(s)/Mob(s) sofort in Position.");
        return moved;
    }

    private static int armyStop(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        int stopped = 0;
        for (BotPlayer bot : targets) {
            if (bot.drill != null) {
                bot.drill.members.remove(bot);
                bot.drill = null;
                bot.setMoveInput(0.0F, 0.0F);
                stopped++;
            }
        }

        for (MobUnits.Unit unit : selectUnits(context, SelectorArgumentType.get(context, "auswahl"))) {
            if (unit.drill != null) {
                unit.drill.mobs.remove(unit);
                unit.drill = null;
                unit.mob.getNavigation().stop();
                stopped++;
            }
        }

        ok(context, stopped + " Bot(s)/Mob(s) aus der Formation entlassen.");
        return stopped;
    }

    /** Bots of a big spawn beyond the first SPAWN_NOW join over the next ticks (no multi-second freeze). */
    private static final int SPAWN_NOW = 64;
    private static final java.util.ArrayDeque<Runnable> SPAWN_QUEUE = new java.util.ArrayDeque<>();

    /** Runs queued bot spawns for up to the given time (called every tick). */
    public static void drainSpawns(long budgetNanos) {
        if (SPAWN_QUEUE.isEmpty()) {
            return;
        }

        long start = System.nanoTime();
        while (!SPAWN_QUEUE.isEmpty() && System.nanoTime() - start < budgetNanos) {
            try {
                SPAWN_QUEUE.poll().run();
            } catch (RuntimeException error) {
                BotSystemMod.LOGGER.error("Bot-Spawn aus der Warteschlange fehlgeschlagen", error);
            }
        }
    }

    public static void clearSpawnQueue() {
        SPAWN_QUEUE.clear();
    }

    /** Tier range for the bots being spawned right now ("ht4-ht3"), null = the usual random mix. */
    private static List<Double> spawnTiers;

    private static int spawnTiered(CommandContext<CommandSourceStack> context, String countOrName, String kit, String team, String ally, String tier)
        throws CommandSyntaxException {
        if (spawnType == null && typeNamed(tier) != null) {
            // "/bot spawn 20 iron Rot Blau null" - type after the allied team.
            return spawnWithType(context, typeNamed(tier), countOrName, kit, team, ally, null);
        }

        if (spawnType == null && ally != null && typeNamed(ally) != null) {
            // "/bot spawn 20 iron Rot null ht4" - type, then a tier.
            return spawnWithType(context, typeNamed(ally), countOrName, kit, team, null, tier);
        }

        List<Double> range = de.fiden.botsystem.bot.Tiers.range(tier);
        if (range == null || range.isEmpty()) {
            throw fail("Stufe/Bereich unbekannt: " + tier + " (z.B. lt5, ht4, ht4-ht3, random; Tabelle: /bot tiers).");
        }

        spawnTiers = range;
        try {
            // "-" = no allied team (it used to ally the team with a team literally called "-").
            return ally == null || noValue(ally) ? spawn(context, countOrName, kit, team) : spawnAllied(context, countOrName, kit, team, ally);
        } finally {
            spawnTiers = null;
        }
    }

    /** Bot type for this spawn ("/bot spawn 20 iron Rot null"), null = none. */
    private static de.fiden.botsystem.bot.BotTypes.BotType spawnType;

    private static de.fiden.botsystem.bot.BotTypes.BotType typeNamed(String word) {
        return word == null ? null : BotSystemMod.types().get(word);
    }

    private static int spawnWithType(
        CommandContext<CommandSourceStack> context, de.fiden.botsystem.bot.BotTypes.BotType type, String countOrName, String kit, String team, String ally, String tier
    ) throws CommandSyntaxException {
        de.fiden.botsystem.bot.BotTypes.BotType before = spawnType;
        spawnType = type;
        try {
            if (tier != null) {
                return spawnTiered(context, countOrName, kit, team, ally, tier);
            }
            return ally == null ? spawn(context, countOrName, kit, team) : spawnAllied(context, countOrName, kit, team, ally);
        } finally {
            spawnType = before;
        }
    }

    private static int spawn(CommandContext<CommandSourceStack> context, String countOrName, String second, String third) throws CommandSyntaxException {
        if (spawnType == null && second != null && typeNamed(second) != null && !kits().exists(second)) {
            // "/bot spawn 20 null Rot" - a type where the kit would be.
            return spawnWithType(context, typeNamed(second), countOrName, null, third, null, null);
        }

        if (spawnType == null && third != null && (third.toLowerCase(Locale.ROOT).startsWith("type:") || third.toLowerCase(Locale.ROOT).startsWith("typ:"))) {
            de.fiden.botsystem.bot.BotTypes.BotType type = typeNamed(third);
            if (type == null) {
                throw fail("Typ " + third + " gibt es nicht. /bot type list");
            }
            return spawnWithType(context, type, countOrName, second, null, null, null);
        }

        if (spawnTiers == null && third != null && de.fiden.botsystem.bot.Tiers.range(third) != null && !third.equalsIgnoreCase("random")) {
            // "/bot spawn 20 iron ht4-ht3" - a tier range where the team would be.
            return spawnTiered(context, countOrName, second, null, null, third);
        }

        Bots bots = bots();
        int count = 1;
        String requested = null;
        if (countOrName.matches("\\d+")) {
            count = Integer.parseInt(countOrName);
            if (count < 1 || count > 1000) {
                throw fail("Anzahl muss zwischen 1 und 1000 liegen.");
            }
        } else {
            if (!bots.nameFree(countOrName)) {
                throw fail("Name " + countOrName + " ist ungültig oder schon vergeben (3-16 Zeichen, A-Z 0-9 _).");
            }

            requested = countOrName;
        }

        String kit = null;
        String team = null;
        String skin = null;
        // A kit that really is called "null" (or "-", "none") wins over "no kit".
        if (second != null && (kits().exists(second) || !noValue(second))) {
            if (kits().exists(second)) {
                kit = second;
            } else {
                if (third != null) {
                    throw fail("Kit " + second + " nicht gefunden. Kits: " + kitList());
                }

                if (requested != null) {
                    skin = second;
                } else {
                    team = teamName(second);
                }
            }
        }

        if (third != null && !noValue(third)) {
            team = teamName(third);
        }

        return spawnBots(context, count, requested, kit, team, skin, spawnType);
    }

    private static int spawnBots(CommandContext<CommandSourceStack> context, int count, String requested, String kit, String team, String skin) throws CommandSyntaxException {
        return spawnBots(context, count, requested, kit, team, skin, null);
    }

    private static int spawnBots(
        CommandContext<CommandSourceStack> context, int count, String requested, String kit, String team, String skin, de.fiden.botsystem.bot.BotTypes.BotType type
    ) throws CommandSyntaxException {
        if (type != null) {
            // What the spawn command says wins; the type fills in the rest.
            if (kit == null && type.kit != null) {
                if (!kits().exists(type.kit)) {
                    throw fail("Typ " + type.name + ": Kit " + type.kit + " gibt es nicht mehr.");
                }
                kit = type.kit;
            }
            if (team == null && type.team != null) {
                team = teamName(type.team);
            }
            if (skin == null && type.skin != null && type.skinFile == null) {
                skin = type.skin;
            }
        }

        Bots bots = bots();
        CommandSourceStack source = context.getSource();
        ServerLevel world = source.getLevel();
        Vec3 centre = source.getPosition();
        float yaw = source.getRotation().y;
        Kits.Kit set = kit == null ? null : kits().find(kit).orElse(null);
        Set<String> taken = new HashSet<>();
        List<String> names = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            String name = requested != null ? requested : (type == null ? null : type.freshName(bots, i + 1, count, taken));
            if (name == null) {
                name = bots.freshName(BotNameStyle.MIX, 4, 12);
            }

            while (!taken.add(name.toLowerCase(Locale.ROOT))) {
                name = bots.freshName(BotNameStyle.MIX, 4, 12);
            }

            names.add(name);
        }

        List<BotPlayer> spawnedBots = new ArrayList<>();
        List<Double> tiers = spawnTiers;
        String batchKit = kit;
        String batchTeam = team;
        String batchSkin = skin;
        int immediate = BotDefaults.get().spawnBudgetMs <= 0.0 ? names.size() : Math.min(names.size(), SPAWN_NOW);

        for (int n = 0; n < names.size(); n++) {
            final int i = n;
            Runnable one = () -> {
            String name = names.get(i);
            double angle = i * Math.PI * (3.0 - Math.sqrt(5.0));
            double outer = count == 1 ? 2.0 : Math.max(2.5, Math.sqrt(count) * 1.3);
            double radius = count == 1 ? 2.0 : outer * Math.sqrt((i + 0.5) / count);
            Vec3 spot = centre.add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);
            String skinSource = batchSkin == null ? (requested == null ? "random" : name) : batchSkin;
            GameProfile plain = new GameProfile(Bots.offlineUuid(name), name);
            GameProfile initial = plain;
            String pending = skinSource;
            if (skinSource.equalsIgnoreCase("random")) {
                String cachedName = bots.nextCachedPoolSkin();
                if (cachedName != null) {
                    initial = Bots.withTextures(plain, bots.cachedSkin(cachedName).orElseThrow());
                    pending = null;
                }
            } else {
                Optional<GameProfile> cached = bots.cachedSkin(skinSource);
                if (cached != null) {
                    initial = cached.<GameProfile>map(real -> Bots.withTextures(plain, real)).orElse(plain);
                    pending = null;
                }
            }

            BotPlayer bot;
            try {
                bot = bots.spawn(world, initial, surface(world, spot), yaw);
                bot.team = batchTeam;
                if (tiers != null) {
                    bot.skill.setSkill(de.fiden.botsystem.bot.Tiers.pick(tiers));
                }
                if (type != null) {
                    type.applyStyle(bot, tiers != null);
                }
                if (set != null) {
                    set.applyTo(bot);
                }
                if (!spawnedBots.isEmpty() && i >= immediate) {
                    // Arrived after the command returned: do what the batch is doing by now.
                    BotPlayer leader = spawnedBots.get(0);
                    if (!leader.isRemoved()) {
                        bot.copyOrdersFrom(leader);
                    }
                }
                spawnedBots.add(bot);
            } catch (RuntimeException error) {
                BotSystemMod.LOGGER.error("Bot {} konnte nicht gespawnt werden", name, error);
                return;
            }

            if (pending != null) {
                BotPlayer spawned = bot;
                bots.profileFor(name, pending).thenAccept(profile -> world.getServer().execute(() -> {
                    if (BotSystemMod.bots() == bots) {
                        bots.applySkin(spawned, profile);
                    }
                }));
            }
            };
            if (n < immediate) {
                one.run();
            } else {
                SPAWN_QUEUE.add(one);
            }
        }

        if (immediate < names.size()) {
            SPAWN_QUEUE.add(() -> {
                dressType(context, type, spawnedBots, false);
                source.sendSystemMessage(Component.literal("[BotSystem] Alle " + spawnedBots.size() + " Bots sind da.").withStyle(ChatFormatting.GRAY));
            });
        } else {
            dressType(context, type, spawnedBots, false);
        }
        String extras = (kit == null ? "" : ", Kit " + kit) + (team == null ? "" : ", Team " + team) + (type == null ? "" : ", Typ " + type.name)
            + (immediate < names.size() ? " (" + immediate + " sofort, der Rest in den naechsten Ticks - Befehle gelten auch fuer sie)" : "");
        ok(
            context,
            count
                + " Bot(s) werden gespawnt"
                + extras
                + ": "
                + String.join(", ", names.subList(0, Math.min(6, names.size())))
                + (names.size() > 6 ? " ..." : "")
        );
        return count;
    }

    static List<double[]> distributeByArea(List<double[]> rects, int count) {
        List<double[]> assignment = new ArrayList<>();
        if (rects.isEmpty()) {
            return assignment;
        }

        double total = 0.0;

        for (double[] r : rects) {
            total += Math.max(1.0, (r[2] - r[0]) * (r[3] - r[1]));
        }

        int[] share = new int[rects.size()];
        int given = 0;

        for (int i = 0; i < rects.size(); i++) {
            double[] r = rects.get(i);
            share[i] = Math.max(1, (int)Math.floor(count * Math.max(1.0, (r[2] - r[0]) * (r[3] - r[1])) / total));
            given += share[i];
        }

        for (int i = 0; given < count; i = (i + 1) % rects.size()) {
            share[i]++;
            given++;
        }

        for (int i = 0; i < rects.size(); i++) {
            for (int n = 0; n < share[i] && assignment.size() < count; n++) {
                assignment.add(rects.get(i));
            }
        }

        while (assignment.size() < count) {
            assignment.add(rects.get(assignment.size() % rects.size()));
        }

        return assignment;
    }

    static List<Vec3> spreadAround(ServerLevel world, Vec3 centre, int count) {
        List<Vec3> spots = new ArrayList<>();
        int wanted = Math.max(1, count);
        Vec3 ground = Ground.findBelow(world, centre.x, centre.z, centre.y);
        if (ground == null) {
            ground = Ground.find(world, centre.x, centre.z, centre.y);
        }

        if (ground == null || Math.abs(ground.y - centre.y) > 24.0) {
            ground = centre;
        }

        MutableBlockPos at = new MutableBlockPos();
        int sy = (int)Math.floor(ground.y + 0.2);
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        LongOpenHashSet seen = new LongOpenHashSet();
        int cx = (int)Math.floor(ground.x);
        int cz = (int)Math.floor(ground.z);
        queue.add(new int[]{cx, sy, cz});
        seen.add(BlockPos.asLong(cx, sy, cz));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int limit = wanted * 14 + 64;
        int[][] dirs = new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        while (!queue.isEmpty() && spots.size() < wanted && seen.size() < limit) {
            int[] cell = queue.poll();
            double x = cell[0] + 0.5 + random.nextDouble(-0.2, 0.2);
            double z = cell[2] + 0.5 + random.nextDouble(-0.2, 0.2);
            boolean free = true;

            for (int i = Math.max(0, spots.size() - 60); i < spots.size() && free; i++) {
                Vec3 other = spots.get(i);
                free = (other.x - x) * (other.x - x) + (other.z - z) * (other.z - z) >= 1.6900000000000002 || Math.abs(other.y - cell[1]) > 1.5;
            }

            if (free) {
                spots.add(new Vec3(x, cell[1], z));
            }

            for (int[] dir : dirs) {
                int nx = cell[0] + dir[0];
                int nz = cell[2] + dir[1];

                for (int ny : new int[]{cell[1], cell[1] + 1, cell[1] - 1, cell[1] - 2}) {
                    if (Math.abs(ny - sy) <= 3
                        && spotStandable(world, at, nx, ny, nz)
                        && (ny <= cell[1] || spotFree(world, at, cell[0], cell[1] + 2, cell[2]))
                        && (ny >= cell[1] || spotFree(world, at, nx, cell[1], nz))) {
                        if (seen.add(BlockPos.asLong(nx, ny, nz))) {
                            queue.add(new int[]{nx, ny, nz});
                        }
                        break;
                    }
                }
            }
        }

        if (spots.isEmpty()) {
            spots.add(ground);
        }

        int have = spots.size();

        for (int i = have; i < wanted; i++) {
            spots.add(spots.get(i % have));
        }

        return spots;
    }

    private static boolean spotFree(ServerLevel world, MutableBlockPos at, int x, int y, int z) {
        at.set(x, y, z);
        return world.getBlockState(at).getCollisionShape(world, at).isEmpty();
    }

    private static boolean spotStandable(ServerLevel world, MutableBlockPos at, int x, int y, int z) {
        if (spotFree(world, at, x, y, z) && spotFree(world, at, x, y + 1, z)) {
            at.set(x, y, z);
            if (!world.getFluidState(at).isEmpty()) {
                return false;
            }

            at.set(x, y - 1, z);
            return !world.getBlockState(at).getCollisionShape(world, at).isEmpty() && !world.getFluidState(at).is(FluidTags.LAVA);
        } else {
            return false;
        }
    }

    private static Vec3 surface(ServerLevel world, Vec3 spot) {
        return Ground.find(world, spot.x, spot.z, spot.y);
    }

    private static int spawnSpeed(CommandContext<CommandSourceStack> context, double budget, String label) {
        BotDefaults defaults = BotDefaults.get();
        defaults.spawnBudgetMs = budget;
        defaults.save();
        ok(context, switch (label) {
            case "instant" -> "Spawn-Tempo instant: alle Bots im selben Tick - am schnellsten, aber der Server steht solange (bei 1000 Bots ein paar Sekunden).";
            case "max" -> "Spawn-Tempo max: ~90 ms pro Tick - schnell, TPS sackt waehrend des Spawnens ab.";
            case "fast" -> "Spawn-Tempo fast: ~45 ms pro Tick.";
            default -> "Spawn-Tempo normal: ~30 ms pro Tick - Server bleibt fluessig.";
        });
        return 1;
    }

    private static int mobPush(CommandContext<CommandSourceStack> context, boolean fast) {
        BotDefaults defaults = BotDefaults.get();
        defaults.fastMobPush = fast;
        defaults.save();
        ok(context, fast
            ? "Mob-Armeen schieben sich ueber BotSystems eigenes Raster (schnell)."
            : "Mob-Armeen schieben sich wieder per Vanilla (langsamer bei grossen Armeen).");
        return 1;
    }

    private static int spawnMobs(CommandContext<CommandSourceStack> context, boolean on) throws CommandSyntaxException {
        BotDefaults defaults = BotDefaults.get();
        defaults.botsSpawnMobs = on;
        defaults.save();
        for (BotPlayer bot : bots().all()) {
            bot.affectsSpawning = on;
        }
        ok(context, on
            ? "Bots zaehlen wieder als Spieler fuers Mob-Spawnen (Vanilla): um Armeen spawnen nachts Monster."
            : "Bots zaehlen nicht mehr fuers Mob-Spawnen (wie Citizens-NPCs): keine Extra-Monster um Armeen, weniger Lag.");
        return 1;
    }

    private static int typeList(CommandContext<CommandSourceStack> context) {
        de.fiden.botsystem.bot.BotTypes types = BotSystemMod.types();
        List<String> names = types.names();
        info(context, "Bot-Typen: " + (names.isEmpty() ? "keine - /bot type create <name> randomnick 10 13 chaos skin black" : String.join(", ", names)), ChatFormatting.YELLOW);

        for (String name : names) {
            info(context, "- " + name + ": " + types.get(name).summary(), ChatFormatting.GRAY);
        }

        return names.size();
    }

    private static String typeKey(String raw) throws CommandSyntaxException {
        String name = raw.toLowerCase(Locale.ROOT).startsWith("type:") ? raw.substring(5) : raw;
        if (!name.matches("[A-Za-z0-9_-]{1,24}")) {
            throw fail("Typnamen: 1-24 Zeichen (A-Z, 0-9, _, -).");
        }
        return name;
    }

    private static int typeCreate(CommandContext<CommandSourceStack> context, String settings) throws CommandSyntaxException {
        String name = typeKey(StringArgumentType.getString(context, "name"));
        if (kits().exists(name)) {
            throw fail("Es gibt schon ein Kit namens " + name + " - nimm einen anderen Namen, sonst ist /bot spawn ... " + name + " doppeldeutig.");
        }

        de.fiden.botsystem.bot.BotTypes.BotType type = new de.fiden.botsystem.bot.BotTypes.BotType(name);
        try {
            type.apply(settings);
        } catch (IllegalArgumentException error) {
            throw fail(error.getMessage());
        }

        checkTypeKit(type);
        boolean existed = BotSystemMod.types().get(name) != null;
        BotSystemMod.types().save(type);
        ok(context, "Typ " + name + (existed ? " ersetzt: " : " erstellt: ") + type.summary());
        info(context, "Spawnen: /bot spawn <anzahl> <kit|-> <team> " + name + "   oder   /bot type spawn " + name + " <anzahl> [team]", ChatFormatting.GRAY);
        return 1;
    }

    private static int typeSet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        de.fiden.botsystem.bot.BotTypes.BotType type = needType(StringArgumentType.getString(context, "name"));
        try {
            type.apply(StringArgumentType.getString(context, "einstellungen"));
        } catch (IllegalArgumentException error) {
            throw fail(error.getMessage());
        }

        checkTypeKit(type);
        BotSystemMod.types().save(type);
        ok(context, "Typ " + type.name + ": " + type.summary());
        return 1;
    }

    private static void checkTypeKit(de.fiden.botsystem.bot.BotTypes.BotType type) throws CommandSyntaxException {
        if (type.kit != null && !kits().exists(type.kit)) {
            throw fail("Kit " + type.kit + " nicht gefunden. Kits: " + kitList());
        }
    }

    private static de.fiden.botsystem.bot.BotTypes.BotType needType(String name) throws CommandSyntaxException {
        de.fiden.botsystem.bot.BotTypes.BotType type = BotSystemMod.types().get(name);
        if (type == null) {
            throw fail("Typ " + name + " gibt es nicht. /bot type list");
        }
        return type;
    }

    private static int typeInfo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        de.fiden.botsystem.bot.BotTypes.BotType type = needType(StringArgumentType.getString(context, "name"));
        int alive = 0;
        for (BotPlayer bot : bots().all()) {
            if (type.name.equalsIgnoreCase(bot.typeName)) {
                alive++;
            }
        }
        ok(context, "Typ " + type.name + ": " + type.summary() + " | " + alive + " Bot(s) dieses Typs aktiv.");
        return 1;
    }

    private static int typeDelete(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        if (!BotSystemMod.types().delete(name)) {
            throw fail("Typ " + name + " gibt es nicht.");
        }

        ok(context, "Typ " + name + " gelöscht (schon gespawnte Bots behalten ihn).");
        return 1;
    }

    private static int typeSpawn(CommandContext<CommandSourceStack> context, int count, String team) throws CommandSyntaxException {
        de.fiden.botsystem.bot.BotTypes.BotType type = needType(StringArgumentType.getString(context, "name"));
        return spawnBots(context, count, null, null, team == null || noValue(team) ? null : teamName(team), null, type);
    }

    /** Puts a type on bots that already exist: new names and skin, its fighting style. */
    private static int typeApply(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        de.fiden.botsystem.bot.BotTypes.BotType type = needType(StringArgumentType.getString(context, "name"));
        List<BotPlayer> targets = select(context, SelectorArgumentType.get(context, "auswahl"));
        Bots bots = bots();
        Set<String> taken = new HashSet<>();
        int index = 1;
        for (BotPlayer bot : targets) {
            type.applyStyle(bot, false);
            String name = type.freshName(bots, index++, targets.size(), taken);
            if (name != null) {
                taken.add(name.toLowerCase(Locale.ROOT));
                rename(bot, name);
            }
        }

        // rename() respawns bots: dress the new ones.
        List<BotPlayer> dressed = new ArrayList<>();
        for (BotPlayer bot : bots.all()) {
            if (type.name.equalsIgnoreCase(bot.typeName) && (taken.contains(bot.getGameProfile().name().toLowerCase(Locale.ROOT)) || targets.contains(bot))) {
                dressed.add(bot);
            }
        }
        dressType(context, type, dressed, true);
        ok(context, targets.size() + " Bot(s) sind jetzt Typ " + type.name + ".");
        return targets.size();
    }

    /** Skin colour / file / player skin of a type onto freshly spawned bots. */
    private static void dressType(CommandContext<CommandSourceStack> context, de.fiden.botsystem.bot.BotTypes.BotType type, List<BotPlayer> spawned, boolean playerSkin) {
        if (type == null || spawned.isEmpty()) {
            return;
        }

        Bots bots = BotSystemMod.bots();
        if (type.skinColor != null) {
            for (BotPlayer bot : spawned) {
                bots.applySkinColor(bot, type.skinColor);
            }
        }

        if (playerSkin && type.skin != null && type.skinFile == null) {
            MinecraftServer server = context.getSource().getServer();
            for (BotPlayer bot : spawned) {
                String botName = bot.getGameProfile().name();
                bots.profileFor(botName, type.skin).thenAccept(profile -> server.execute(() -> {
                    if (BotSystemMod.bots() == bots) {
                        bots.applySkin(bot, profile);
                    }
                }));
            }
        }

        if (type.skinFile != null) {
            SkinFiles.Skin skin;
            try {
                skin = SkinFiles.load(type.skinFile, "auto");
            } catch (IOException error) {
                info(context, "Typ " + type.name + ": Skin-Datei " + type.skinFile + " nicht ladbar (" + error.getMessage() + ").", ChatFormatting.RED);
                return;
            }

            String[] signed = SkinFiles.signedFor(skin);
            for (BotPlayer bot : spawned) {
                bots.applySkinFile(bot, skin, signed);
            }

            if (signed == null && SkinFiles.hasApiKey()) {
                MinecraftServer server = context.getSource().getServer();
                SkinFiles.sign(skin).whenComplete((result, error) -> server.execute(() -> {
                    if (error == null) {
                        for (BotPlayer bot : spawned) {
                            if (bots.isBot(bot.getUUID())) {
                                bots.applySkinFile(bot, skin, result);
                            }
                        }
                    }
                }));
            }
        }
    }

    private static int presetList(CommandContext<CommandSourceStack> context) {
        Presets presets = BotSystemMod.presets();
        List<String> names = presets.names();
        info(context, "Vorlagen: " + (names.isEmpty() ? "keine" : String.join(", ", names)), ChatFormatting.YELLOW);

        for (String name : names) {
            info(context, "- " + name + ": " + presets.get(name).summary(), ChatFormatting.GRAY);
        }

        return names.size();
    }

    private static int presetSave(CommandContext<CommandSourceStack> context, String kit, String team, int count) throws CommandSyntaxException {
        String name = teamName(StringArgumentType.getString(context, "name"));
        boolean realKit = kit != null && kits().exists(kit);
        if (!realKit && !noValue(kit) && !kits().exists(kit)) {
            throw fail("Kit " + kit + " nicht gefunden. Kits: " + kitList());
        }

        Presets.Preset preset = new Presets.Preset(name, !realKit && noValue(kit) ? null : kit.toLowerCase(Locale.ROOT), noValue(team) ? null : teamName(team), count);
        BotSystemMod.presets().save(preset);
        ok(context, "Vorlage " + name + " gespeichert: " + preset.summary());
        return 1;
    }

    private static int presetDelete(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        if (!BotSystemMod.presets().delete(name)) {
            throw fail("Vorlage " + name + " gibt es nicht.");
        }

        ok(context, "Vorlage " + name + " gelöscht.");
        return 1;
    }

    private static int presetSpawn(CommandContext<CommandSourceStack> context, int countOverride) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        Presets.Preset preset = BotSystemMod.presets().get(name);
        if (preset == null) {
            throw fail("Vorlage " + name + " gibt es nicht. /bot preset list");
        }

        int count = countOverride > 0 ? countOverride : Math.max(1, Math.min(100, preset.count()));
        return spawnBots(context, count, null, preset.kit(), preset.team(), null);
    }

    private static int remove(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        targets.forEach(bots()::remove);
        List<MobUnits.Unit> units = selectUnits(context, SelectorArgumentType.get(context, "auswahl"));
        units.forEach(BotSystemMod.mobs()::remove);
        ok(context, targets.size() + " Bot(s)" + (units.isEmpty() ? "" : " + " + units.size() + " Mob(s)") + " entfernt.");
        return targets.size() + units.size();
    }

    private static int list(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Bots bots = bots();
        if (bots.all().isEmpty()) {
            ok(context, "Keine Bots vorhanden.");
            return 0;
        }

        for (BotPlayer bot : bots.all()) {
            String state = bot.fightMode && bot.currentTarget != null ? "KÄMPFT gegen " + bot.currentTarget.getName().getString() : bot.state();
            String var10001 = bot.getGameProfile().name();
            String var10002 = bot.team == null ? "-" : bot.team;
            int var10004 = (int)bot.getHealth();
            String var10006 = bot.immortal ? " | unsterblich" : "";
            String var10007 = bot.stance != Stance.AGGRESSIVE ? " | " + bot.stance.label() : "";
            String var10008 = bot.tickErrors > 0 ? " | FEHLER x" + bot.tickErrors + ": " + bot.lastError : "";
            info(
                context,
                "- " + var10001 + " | " + var10002 + " | " + state + " | hp " + var10004 + " | " + de.fiden.botsystem.bot.Tiers.label(bot.skill.skill()) + var10006 + var10007 + var10008,
                bot.tickErrors > 0 ? ChatFormatting.RED : ChatFormatting.YELLOW
            );
        }

        return bots.all().size();
    }

    private static String describePath(BotPlayer bot) {
        net.minecraft.world.level.pathfinder.Path path = bot.path;
        if (path == null) {
            return "-";
        }

        BlockPos node = path.getNextNodeIndex() < path.getNodeCount() ? path.getNode(path.getNextNodeIndex()).asBlockPos() : null;
        BlockPos end = path.getNodeCount() > 0 ? path.getNode(path.getNodeCount() - 1).asBlockPos() : null;
        return path.getNextNodeIndex()
            + "/"
            + path.getNodeCount()
            + (path.canReach() ? " voll" : " TEIL")
            + " node "
            + (node == null ? "-" : node.getX() + "/" + node.getY() + "/" + node.getZ())
            + " ende "
            + (end == null ? "-" : end.getX() + "/" + end.getY() + "/" + end.getZ());
    }

    private static int pathTest(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel world = source.getLevel();
        String[] tokens = StringArgumentType.getString(context, "koordinaten").trim().split(" +");
        if (tokens.length != 4) {
            throw fail("/bot pathtest <x1> <z1> <x2> <z2>");
        }

        Vec3 base = source.getPosition();
        Vec3 from = surface(world, new Vec3(coordinate(tokens[0], base.x), base.y, coordinate(tokens[1], base.z)));
        int cx = (int)Math.floor(from.x) >> 4;
        int cz = (int)Math.floor(from.z) >> 4;
        info(
            context,
            "Chunk "
                + cx
                + "/"
                + cz
                + " loaded="
                + Ground.loaded(world, from.x, from.z)
                + " isChunkLoaded="
                + world.hasChunk(cx, cz)
                + " worldChunk="
                + (world.getChunkSource().getChunkNow(cx, cz) != null)
                + " top="
                + world.getHeight(Types.MOTION_BLOCKING, (int)Math.floor(from.x), (int)Math.floor(from.z)),
            ChatFormatting.GRAY
        );
        Vec3 to = surface(world, new Vec3(coordinate(tokens[2], base.x), base.y, coordinate(tokens[3], base.z)));
        long start = System.nanoTime();
        net.minecraft.world.level.pathfinder.Path path = BotSystemMod.brain().navigator().findPathNow(world, from, to);
        long millis = (System.nanoTime() - start) / 1000000L;
        if (path == null) {
            ok(
                context,
                "Kein Pfad von "
                    + (int)from.x
                    + "/"
                    + (int)from.y
                    + "/"
                    + (int)from.z
                    + " nach "
                    + (int)to.x
                    + "/"
                    + (int)to.y
                    + "/"
                    + (int)to.z
                    + " ("
                    + millis
                    + " ms)."
            );
            return 0;
        }

        StringBuilder nodes = new StringBuilder();

        for (int i = 0; i < path.getNodeCount(); i++) {
            Node node = path.getNode(i);
            nodes.append(node.x).append('/').append(node.y).append('/').append(node.z).append(node.type == PathType.WATER ? "w " : " ");
        }

        ok(context, "Pfad " + path.getNodeCount() + " Knoten, " + (path.canReach() ? "erreicht Ziel" : "TEILPFAD") + ", " + millis + " ms: " + nodes);
        return path.getNodeCount();
    }

    private static int kitCreate(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(context, "name");
        if (!name.matches("[A-Za-z0-9_-]{1,16}")) {
            throw fail("Kit-Namen: 1-16 Zeichen (A-Z, 0-9, _, -).");
        }

        if (Kits.builtin(name) != null) {
            throw fail("Kit " + name + " ist eingebaut - bitte einen anderen Namen.");
        }

        Kits.Kit kit = kits().createFrom(name, player);
        ok(context, "Kit " + name + " gespeichert: " + kit.summary());
        return 1;
    }

    private static int kitList(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        info(context, "Eingebaut: " + String.join(", ", Kits.BUILTIN), ChatFormatting.YELLOW);
        List<String> custom = kits().customNames();
        info(context, "Eigene: " + (custom.isEmpty() ? "keine (/bot kit create <name>)" : String.join(", ", custom)), ChatFormatting.YELLOW);
        return custom.size();
    }

    private static int kitInfo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        Kits.Kit kit = kits().find(name).orElseThrow(() -> fail("Kit " + name + " gibt es nicht."));
        info(context, kit.name() + (kit.builtin() ? " (eingebaut): " : ": ") + kit.summary(), ChatFormatting.YELLOW);
        return 1;
    }

    private static int kitDelete(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        if (!kits().delete(name)) {
            throw fail("Eigenes Kit " + name + " gibt es nicht.");
        }

        ok(context, "Kit " + name + " gelöscht.");
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Bots bots = bots();
        MobUnits mobs = BotSystemMod.mobs();
        if (!bots.all().isEmpty() || mobs != null && !mobs.isEmpty()) {
            Map<String, int[]> perTeam = new LinkedHashMap<>();
            if (mobs != null) {
                for (MobUnits.Unit unit : mobs.all()) {
                    int[] counts = perTeam.computeIfAbsent(unit.team == null ? "ohne Team" : unit.team, key -> new int[4]);
                    counts[0]++;
                    if (unit.mob.isAlive()) {
                        counts[1]++;
                    }

                    if (unit.fightMode) {
                        counts[2]++;
                    }

                    if (unit.followId != null) {
                        counts[3]++;
                    }
                }
            }

            for (BotPlayer bot : bots.all()) {
                int[] counts = perTeam.computeIfAbsent(bot.team == null ? "ohne Team" : bot.team, key -> new int[4]);
                counts[0]++;
                if (!bot.isDeadOrDying()) {
                    counts[1]++;
                }

                if (bot.fightMode) {
                    counts[2]++;
                }

                if (bot.followTarget != null) {
                    counts[3]++;
                }
            }

            int broken = (int)bots.all().stream().filter(botx -> botx.tickErrors > 0).count();
            if (broken > 0) {
                info(
                    context,
                    broken + " Bot(s) mit Tick-Fehlern (stehen still?) - /bot list zeigt sie rot, Details im Log (latest.log: 'Tick-Fehler').",
                    ChatFormatting.RED
                );
            }

            info(
                context,
                "BotSystem 2026-09-24j | Status: "
                    + bots.all().size()
                    + " Bot(s)"
                    + (
                        BotSystemMod.mobs() != null && !BotSystemMod.mobs().isEmpty()
                            ? ", " + BotSystemMod.mobs().size() + " Mob-Einheit(en) - /bot mob list"
                            : ""
                    ),
                ChatFormatting.GOLD
            );

            for (String team : bots.teamStats.keySet()) {
                if (perTeam.keySet().stream().noneMatch(known -> known.equalsIgnoreCase(team))) {
                    perTeam.put(team, new int[4]);
                }
            }

            perTeam.forEach(
                (teamx, counts) -> {
                    int[] stat = bots.teamStats.getOrDefault(teamx.toLowerCase(Locale.ROOT), new int[2]);
                    info(
                        context,
                        "- "
                            + teamx
                            + ": "
                            + counts[0]
                            + " gesamt, "
                            + counts[1]
                            + " lebend, "
                            + counts[2]
                            + " im Kampf, "
                            + counts[3]
                            + " als Begleiter | Kills "
                            + stat[0]
                            + " / Tode "
                            + stat[1],
                        ChatFormatting.YELLOW
                    );
                }
            );
            return bots.all().size();
        } else {
            ok(context, "Keine Bots vorhanden.");
            return 0;
        }
    }

    private static int dump(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> targets = select(context, SelectorArgumentType.get(context, "auswahl"));

        for (BotPlayer bot : targets) {
            LivingEntity target = bot.currentTarget;
            String line = bot.getGameProfile().name()
                + " | "
                + bot.state()
                + " | slot "
                + bot.getInventory().getSelectedSlot()
                + " hand "
                + bot.getMainHandItem().getHoverName().getString()
                + " x"
                + bot.getMainHandItem().getCount()
                + " | offhand "
                + bot.getOffhandItem().getHoverName().getString()
                + " x"
                + bot.getOffhandItem().getCount()
                + " | pfeil "
                + bot.getProjectile(bot.getMainHandItem()).getHoverName().getString()
                + " | using "
                + bot.isUsingItem()
                + " ("
                + bot.getUseItemRemainingTicks()
                + ") | ziel "
                + (
                    target == null
                        ? "-"
                        : target.getName().getString() + String.format(Locale.ROOT, " %.1fm sicht %s", bot.distanceTo(target), bot.hasLineOfSight(target))
                )
                + " | fight "
                + bot.fightMode
                + " liste "
                + bot.targetIds.size()
                + " | input "
                + bot.zza
                + "/"
                + bot.xxa
                + " | draw "
                + bot.drawStartTick
                + " next "
                + bot.nextAttackTick
                + " | pos "
                + (int)bot.getX()
                + " "
                + (int)bot.getY()
                + " "
                + (int)bot.getZ()
                + " yaw "
                + (int)bot.getYRot()
                + " pitch "
                + (int)bot.getXRot()
                + " fall "
                + String.format(Locale.ROOT, "%.1f", bot.fallDistance)
                + " ground "
                + bot.onGround()
                + " logical "
                + bot.isLocalInstanceAuthoritative()
                + " | walk "
                + (bot.walkGoal == null ? "-" : (int)bot.walkGoal.x + "/" + (int)bot.walkGoal.z)
                + " path "
                + describePath(bot)
                + " pending "
                + (bot.pendingPath != null)
                + " stuck "
                + bot.stuckTicks
                + "/"
                + bot.stuckStrikes
                + " detour "
                + (bot.detour == null ? "-" : (int)bot.detour.x + "/" + (int)bot.detour.z)
                + " giveUp "
                + Math.max(0L, bot.giveUpUntil - context.getSource().getServer().getTickCount());
            info(context, line, ChatFormatting.GRAY);
            if (target != null) {
                Vec3 eyes = new Vec3(bot.getX(), bot.getEyeY(), bot.getZ());
                Vec3 other = new Vec3(target.getX(), target.getEyeY(), target.getZ());
                BlockHitResult hit = bot.level().clip(new ClipContext(eyes, other, Block.COLLIDER, Fluid.NONE, bot));
                info(
                    context,
                    "  ray "
                        + eyes
                        + " -> "
                        + other
                        + " = "
                        + hit.getType()
                        + " at "
                        + hit.getBlockPos()
                        + " block "
                        + bot.level().getBlockState(hit.getBlockPos()).getBlock().getName().getString()
                        + " | sameWorld "
                        + (bot.level() == target.level())
                        + " | eyeBlock "
                        + bot.level().getBlockState(BlockPos.containing(eyes)).getBlock().getName().getString(),
                    ChatFormatting.GRAY
                );
            }
        }

        return targets.size();
    }

    private static int profile(CommandContext<CommandSourceStack> context, int seconds) {
        MinecraftServer server = context.getSource().getServer();
        CommandSourceStack source = context.getSource();
        TickProfiler.start(server.getRunningThread(), seconds, line -> server.execute(() -> {
            source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
            LoggerFactory.getLogger("BotSystem").info(line);
        }));
        ok(context, "Profil laeuft " + seconds + " s ...");
        return 1;
    }

    private static int perf(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Bots bots = bots();
        BotBrain brain = BotSystemMod.brain();
        MinecraftServer server = context.getSource().getServer();
        double[] timings = brain.takeTimings();
        BotNavigator navigator = brain.navigator();
        int paths = navigator.pathsComputed.getAndSet(0);
        double pathMs = navigator.pathNanos.getAndSet(0L) / 1000000.0;
        double pathCpuMs = navigator.pathCpuNanos.getAndSet(0L) / 1000000.0;
        int moving = 0;

        for (BotPlayer bot : bots.all()) {
            if (bot.walkGoal != null || bot.fightMode && bot.currentTarget != null || bot.followTarget != null) {
                moving++;
            }
        }

        info(
            context,
            String.format(
                Locale.ROOT,
                "Server: %.1f ms/Tick (Ziel 50) | Bots: %d (%d unterwegs)",
                server.getAverageTickTimeNanos() / 1000000.0,
                bots.all().size(),
                moving
            ),
            ChatFormatting.GOLD
        );
        info(
            context,
            String.format(Locale.ROOT, "Bot-KI seit letztem /bot perf: %.2f ms/Tick im Schnitt, %.1f ms schlimmster Tick", timings[0], timings[1]),
            ChatFormatting.YELLOW
        );
        info(
            context,
            String.format(
                Locale.ROOT,
                "Pfadsuche (asynchron, %d Threads): %d Pfade, %.1f ms Wandzeit (%.2f ms pro Pfad), davon %.1f ms CPU (%.2f ms pro Pfad), %d in der Warteschlange",
                navigator.threads(),
                paths,
                pathMs,
                paths == 0 ? 0.0 : pathMs / paths,
                pathCpuMs,
                paths == 0 ? 0.0 : pathCpuMs / paths,
                navigator.queued.get()
            ),
            ChatFormatting.YELLOW
        );
        long gcCount = 0L;
        long gcMs = 0L;

        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0L, gc.getCollectionCount());
            gcMs += Math.max(0L, gc.getCollectionTime());
        }

        long now = System.currentTimeMillis();
        if (lastGcStamp > 0L) {
            double seconds = Math.max(0.001, (now - lastGcStamp) / 1000.0);
            info(
                context,
                String.format(
                    Locale.ROOT,
                    "GC seit letztem /bot perf: %d Sammlungen, %d ms Pause (%.1f ms/s) | Heap %d/%d MB",
                    gcCount - lastGcCount,
                    gcMs - lastGcMs,
                    (gcMs - lastGcMs) / seconds,
                    Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory() >> 20,
                    Runtime.getRuntime().maxMemory() >> 20
                ),
                ChatFormatting.YELLOW
            );
        }

        lastGcStamp = now;
        lastGcCount = gcCount;
        lastGcMs = gcMs;
        return 1;
    }

    private static int team(CommandContext<CommandSourceStack> context, boolean add) throws CommandSyntaxException {
        String team = teamName(StringArgumentType.getString(context, "team"));
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        int changed = 0;

        for (BotPlayer bot : targets) {
            if (add) {
                bot.team = team;
                changed++;
            } else if (team.equalsIgnoreCase(bot.team)) {
                bot.team = null;
                changed++;
            }
        }

        for (MobUnits.Unit unit : selectUnits(context, SelectorArgumentType.get(context, "auswahl"))) {
            if (add) {
                unit.team = team;
                changed++;
            } else if (team.equalsIgnoreCase(unit.team)) {
                unit.team = null;
                changed++;
            }
        }

        ok(context, add ? changed + " Bot(s)/Mob(s) sind jetzt in Team " + team + "." : changed + " Bot(s)/Mob(s) aus Team " + team + " entfernt.");
        return changed;
    }

    private static int recordStart(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        PathRecorder.start(player);
        ok(context, "Aufnahme laeuft - alles wird mitgenommen: Laufen, Springen, Umschauen, Schleichen, Sprinten, Klicks/Schlaege, Item in der Hand, Blocken/Essen, Bloecke setzen/abbauen. Stopp: /bot record stop <name> (oder Taste K)");
        return 1;
    }

    private static final SuggestionProvider<CommandSourceStack> ACTIONS = (context, builder) -> SharedSuggestionProvider.suggest(
        de.fiden.botsystem.ai.ActionRecorder.names(), builder
    );

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> recordLoadArgs() {
        return Commands.argument("auswahl", StringArgumentType.string())
            .suggests(SELECTORS)
            .then(
                Commands.argument("aufnahme", StringArgumentType.word())
                    .suggests(ACTIONS)
                    .executes(context -> recordLoad(context, ""))
                    .then(
                        Commands.argument("optionen", StringArgumentType.greedyString())
                            .suggests((context, builder) -> SharedSuggestionProvider.suggest(List.of("loop", "blocks", "loop blocks", "stagger"), builder))
                            .executes(context -> recordLoad(context, StringArgumentType.getString(context, "optionen")))
                    )
            );
    }

    /** "/bot record load team1 aurafarm [loop] [blocks] [stagger]" - every bot plays it from where it stands. */
    private static int recordLoad(CommandContext<CommandSourceStack> context, String options) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "aufnahme");
        de.fiden.botsystem.ai.ActionRecorder.Recording rec = de.fiden.botsystem.ai.ActionRecorder.load(name);
        if (rec == null) {
            throw fail("Aufnahme " + name + " gibt es nicht. Aufnahmen: " + String.join(", ", de.fiden.botsystem.ai.ActionRecorder.names()));
        }

        String who = StringArgumentType.getString(context, "auswahl");
        List<BotPlayer> targets;
        try {
            targets = select(context, who);
        } catch (CommandSyntaxException error) {
            // "team1" without @team: - a team of that name
            if (who.startsWith("@") || bots().all().stream().noneMatch(bot -> who.equalsIgnoreCase(bot.team))) {
                throw error;
            }
            targets = select(context, "@team:" + who);
        }

        String flags = options.toLowerCase(Locale.ROOT);
        boolean loop = flags.contains("loop");
        boolean blocks = flags.contains("block");
        boolean stagger = flags.contains("stagger");
        int index = 0;
        for (BotPlayer bot : targets) {
            // stagger: a wave instead of all at once (each bot a few ticks later)
            de.fiden.botsystem.ai.ActionRecorder.play(bot, rec, loop, blocks, stagger ? -2 * index++ : 0);
        }

        ok(context, targets.size() + " Bot(s) spielen " + name + " ab (" + String.format(Locale.ROOT, "%.1f", rec.seconds()) + " s"
            + (loop ? ", Schleife" : "") + (blocks ? ", mit Bloecken" : "") + "). Stoppen: /bot record halt <auswahl>");
        return targets.size();
    }

    private static int recordHalt(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> targets = select(context, SelectorArgumentType.get(context, "auswahl"));
        int stopped = 0;
        for (BotPlayer bot : targets) {
            if (bot.replay != null) {
                de.fiden.botsystem.ai.ActionRecorder.halt(bot);
                stopped++;
            }
        }
        ok(context, stopped + " Bot(s) spielen nichts mehr ab.");
        return stopped;
    }

    private static int recordStop(CommandContext<CommandSourceStack> context, String name) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        RecordedPath path = PathRecorder.stop(player.getUUID());
        if (path == null) {
            path = PathRecorder.last(player.getUUID());
            if (path == null) {
                throw fail("Es laeuft keine Aufnahme (Start: /bot record start oder Taste K).");
            }
        }

        if (name != null) {
            return recordSave(context, name);
        }

        ok(context, "Aufnahme beendet: " + path.size() + " Punkte, " + (int)path.length() + " Bloecke. Speichern: /bot record save <name>");
        return 1;
    }

    private static int recordSave(CommandContext<CommandSourceStack> context, String name) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        if (PathRecorder.recording(player.getUUID())) {
            PathRecorder.stop(player.getUUID());
        }

        RecordedPath path = PathRecorder.last(player.getUUID());
        if (path == null) {
            throw fail("Keine Aufnahme zum Speichern (Start: /bot record start oder Taste K).");
        }

        RecordedPath saved = PathRecorder.save(name, path);
        de.fiden.botsystem.ai.ActionRecorder.Recording actions = de.fiden.botsystem.ai.ActionRecorder.last(player.getUUID());
        if (actions != null) {
            try {
                de.fiden.botsystem.ai.ActionRecorder.save(name, actions);
                info(context, "Aktionen " + name + " gespeichert (" + String.format(Locale.ROOT, "%.1f", actions.seconds()) + " s, "
                    + actions.events.size() + " Klicks/Bloecke). Abspielen: /bot record load <team|auswahl> " + name + " [loop] [blocks] [stagger]", ChatFormatting.GREEN);
            } catch (IOException error) {
                info(context, "Aktionen konnten nicht gespeichert werden: " + error.getMessage(), ChatFormatting.RED);
            }
        }
        ok(
            context,
            "Weg "
                + name
                + " gespeichert ("
                + saved.size()
                + " Punkte, "
                + (int)saved.length()
                + " Bloecke). Bots schicken: /bot path "
                + name
                + " <auswahl> [reverse]"
        );
        return 1;
    }

    private static int recordList(CommandContext<CommandSourceStack> context) {
        List<String> actions = de.fiden.botsystem.ai.ActionRecorder.names();
        if (!actions.isEmpty()) {
            info(context, "Aktions-Aufnahmen (/bot record load <auswahl> <name>): " + String.join(", ", actions), ChatFormatting.YELLOW);
        }
        List<RecordedPath> paths = PathRecorder.all();
        if (paths.isEmpty()) {
            ok(context, "Noch keine Wege gespeichert. Aufnehmen: Taste K (oder /bot record start), ablaufen, nochmal K.");
            return 0;
        }

        StringBuilder text = new StringBuilder("Wege: ");

        for (RecordedPath path : paths) {
            Vec3 start = path.point(0);
            text.append(path.name)
                .append(" (")
                .append((int)path.length())
                .append(" Bl., ab ")
                .append((int)start.x)
                .append(' ')
                .append((int)start.y)
                .append(' ')
                .append((int)start.z)
                .append(")  ");
        }

        ok(context, text.toString().trim());
        return paths.size();
    }

    private static int recordShow(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        RecordedPath path = PathRecorder.get(name);
        if (path == null) {
            throw fail("Kein Weg namens " + name + ".");
        }

        PathRecorder.show(context.getSource().getPlayerOrException(), path);
        ok(context, "Weg " + name + " wird 20 s angezeigt (gruen = Start, rot = Sprung).");
        return 1;
    }

    private static int followPath(CommandContext<CommandSourceStack> context, boolean reverse) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        RecordedPath path = PathRecorder.get(name);
        if (path == null) {
            throw fail("Kein Weg namens " + name + " (Liste: /bot record list).");
        }

        if (reverse) {
            path = path.reverse();
        }

        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        CommandSourceStack source = context.getSource();
        Vec3 end = path.point(path.size() - 1);
        List<Vec3> spots = spreadAround(source.getLevel(), end, targets.size());
        long deadline = source.getServer().getTickCount() + 18000L + (long)(path.length() * 20.0);
        UUID group = UUID.randomUUID();
        List<Double> lanes = new ArrayList<>();

        for (int i = 0; i < targets.size(); i++) {
            lanes.add(targets.size() == 1 ? 0.0 : (i + 0.5) / targets.size() * 2.0 - 1.0);
        }

        Collections.shuffle(lanes);
        int index = 0;
        int skipped = 0;

        for (BotPlayer bot : targets) {
            if (!bot.level().dimension().identifier().toString().equals(path.world)) {
                skipped++;
                index++;
            } else {
                bot.walkGoal = spots.get(index);
                bot.walkDeadline = deadline;
                bot.orderUntil = deadline;
                bot.walkGroup = group;
                bot.marching = false;
                bot.guardHold = bot.followTarget != null;
                bot.post = null;
                bot.patrol = null;
                bot.patrolArea = null;
                bot.returnTo = null;
                bot.recPath = path;
                bot.recGoal = bot.walkGoal;
                bot.recS = -1.0;
                bot.recRejoin = null;
                bot.recJumpDone = -1;
                bot.recLane = lanes.get(index);
                bot.recLat = 0.0;
                if (bot.stance == Stance.DEFENSIVE) {
                    bot.stanceAnchor = bot.walkGoal;
                }

                if (bot.lifeMode) {
                    bot.lifeCentre = bot.walkGoal;
                }

                bot.stayArea = null;
                bot.hesitateUntil = source.getServer().getTickCount() + ThreadLocalRandom.current().nextLong(0L, 16L);
                index++;
            }
        }

        ok(
            context,
            targets.size()
                - skipped
                + " Bot(s) laufen den Weg "
                + name
                + (reverse ? " rueckwaerts" : "")
                + " ("
                + (int)path.length()
                + " Bloecke)."
                + (skipped > 0 ? " " + skipped + " sind in einer anderen Dimension." : "")
        );
        return targets.size() - skipped;
    }

    private static final SuggestionProvider<CommandSourceStack> DIMENSIONS = (context, builder) -> {
        for (String option : List.of("overworld", "nether", "end", "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")) {
            if (option.startsWith(builder.getRemainingLowerCase())) {
                builder.suggest(option);
            }
        }

        return builder.buildFuture();
    };

    /** Dimension by friendly name (overworld/nether/end) or full id (minecraft:the_nether, custom worlds). */
    private static ServerLevel dimension(CommandContext<CommandSourceStack> context, String argument) throws CommandSyntaxException {
        String raw = StringArgumentType.getString(context, argument).toLowerCase(Locale.ROOT);
        String id = switch (raw) {
            case "overworld", "ow", "welt", "oberwelt" -> "minecraft:overworld";
            case "nether", "the_nether", "unterwelt" -> "minecraft:the_nether";
            case "end", "the_end", "ende" -> "minecraft:the_end";
            default -> raw.contains(":") ? raw : "minecraft:" + raw;
        };

        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
            if (level.dimension().identifier().toString().equals(id)) {
                return level;
            }
        }

        throw fail("Unbekannte Dimension: " + raw + " (overworld, nether, end oder z.B. minecraft:the_nether)");
    }

    private static int walkTo(CommandContext<CommandSourceStack> context, ServerLevel goalLevel) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        Vec3 goal = Vec3Argument.getVec3(context, "ziel");
        long deadline = context.getSource().getServer().getTickCount() + 12000L + (long)(goal.distanceTo(context.getSource().getPosition()) * 10.0);
        UUID group = UUID.randomUUID();
        List<MobUnits.Unit> units = selectUnits(context, SelectorArgumentType.get(context, "auswahl"));
        List<Vec3> spots = spreadAround(goalLevel, goal, targets.size() + units.size());
        var goalDimension = goalLevel.dimension();
        int index = 0;

        for (BotPlayer bot : targets) {
            bot.walkGoal = spots.get(index++);
            // Ordered to a spot in another dimension: keep the real destination in crossGoal
            // and head for the nearest portal first, instead of walking to that raw
            // coordinate in the wrong world.
            bot.portalFrom = null;
            bot.nextPortalScan = 0L;
            if (bot.level().dimension() == goalDimension) {
                bot.walkGoalDimension = null;
                bot.crossGoal = null;
            } else {
                bot.walkGoalDimension = goalDimension;
                bot.crossGoal = bot.walkGoal;
            }
            bot.walkDeadline = deadline;
            bot.orderUntil = deadline;
            bot.walkGroup = group;
            bot.marching = false;
            bot.guardHold = bot.followTarget != null;
            bot.post = null;
            bot.patrol = null;
            bot.patrolArea = null;
            bot.returnTo = null;
            if (bot.stance == Stance.DEFENSIVE) {
                bot.stanceAnchor = bot.walkGoal;
            }

            if (bot.lifeMode) {
                bot.lifeCentre = bot.walkGoal;
            }

            if (bot.stayArea != null
                && (
                    bot.walkGoal.x < bot.stayArea[0]
                        || bot.walkGoal.x > bot.stayArea[2]
                        || bot.walkGoal.z < bot.stayArea[1]
                        || bot.walkGoal.z > bot.stayArea[3]
                )) {
                bot.stayArea = null;
            }

            bot.hesitateUntil = context.getSource().getServer().getTickCount() + ThreadLocalRandom.current().nextLong(0L, 16L);
        }

        for (MobUnits.Unit unit : units) {
            unit.followId = null;
            unit.goal = spots.get(index++);
            unit.goalDeadline = deadline;
            unit.orderUntil = deadline;
            unit.approach = null;
            unit.marching = false;
            unit.post = null;
            unit.mob.setTarget(null, TargetReason.CUSTOM);
        }

        ok(
            context,
            targets.size()
                + " Bot(s)"
                + (units.isEmpty() ? "" : " + " + units.size() + " Mob(s)")
                + " laufen zu "
                + (int)goal.x
                + " "
                + (int)goal.y
                + " "
                + (int)goal.z
                + "."
        );
        return targets.size();
    }

    private static int spread(CommandContext<CommandSourceStack> context, boolean teleport) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        int radius = IntegerArgumentType.getInteger(context, "radius");
        CommandSourceStack source = context.getSource();
        ServerLevel world = source.getLevel();
        Vec3 centre = source.getPosition();
        Collections.shuffle(targets);
        int count = targets.size();
        long deadline = source.getServer().getTickCount() + 12000L;
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        int placed = 0;

        for (int i = 0; i < count; i++) {
            double r = radius * Math.sqrt((i + 0.5) / count);
            double angle = i * golden;
            Vec3 spot = solidSurface(world, centre.x + Math.cos(angle) * r, centre.z + Math.sin(angle) * r, centre.y);
            if (spot != null) {
                BotPlayer bot = targets.get(i);
                bot.followTarget = null;
                bot.guardMode = false;
                bot.post = null;
                bot.patrol = null;
                bot.stopFarming();
                if (teleport) {
                    bot.walkGoal = null;
                    bot.teleportTo(world, spot.x, spot.y, spot.z, Collections.emptySet(), bot.getYRot(), bot.getXRot(), false);
                    bot.setDeltaMovement(Vec3.ZERO);
                } else {
                    bot.walkGoal = spot;
                    bot.walkDeadline = deadline;
                    bot.walkGroup = null;
                    bot.marching = false;
                }

                placed++;
            }
        }

        List<MobUnits.Unit> units = selectUnits(context, SelectorArgumentType.get(context, "auswahl"));

        for (int i = 0; i < units.size(); i++) {
            double r = radius * Math.sqrt((i + 0.5) / units.size());
            double angle = i * golden + 1.0;
            Vec3 spot = solidSurface(world, centre.x + Math.cos(angle) * r, centre.z + Math.sin(angle) * r, centre.y);
            if (spot != null) {
                MobUnits.Unit unit = units.get(i);
                if (teleport) {
                    unit.mob.snapTo(spot.x, spot.y, spot.z, unit.mob.getYRot(), 0.0F);
                    unit.goal = null;
                } else {
                    unit.goal = spot;
                    unit.goalDeadline = deadline;
                }

                placed++;
            }
        }

        ok(context, placed + " Bot(s)/Mob(s) " + (teleport ? "verteilt" : "verteilen sich") + " im Umkreis von " + radius + " Blöcken.");
        return placed;
    }

    private static Vec3 solidSurface(ServerLevel world, double x, double z, double fallbackY) {
        for (int attempt = 0; attempt < 12; attempt++) {
            double ox = attempt == 0 ? 0.0 : ThreadLocalRandom.current().nextDouble(-6.0, 6.0);
            double oz = attempt == 0 ? 0.0 : ThreadLocalRandom.current().nextDouble(-6.0, 6.0);
            int bx = (int)Math.floor(x + ox);
            int bz = (int)Math.floor(z + oz);
            int y = world.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
            BlockPos below = new BlockPos(bx, y - 1, bz);
            BlockState ground = world.getBlockState(below);
            if (ground.getFluidState().isEmpty() && !ground.getCollisionShape(world, below).isEmpty() && Math.abs(y - fallbackY) < 40.0) {
                return new Vec3(bx + 0.5, y, bz + 0.5);
            }
        }

        return null;
    }

    private static int follow(CommandContext<CommandSourceStack> context, boolean start, boolean guard) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        List<MobUnits.Unit> units = selectUnits(context, SelectorArgumentType.get(context, "auswahl"));
        if (!start) {
            for (BotPlayer bot : targets) {
                bot.followTarget = null;
                bot.guardMode = false;
                bot.walkGoal = null;
                bot.walkGroup = null;
            }

            for (MobUnits.Unit unit : units) {
                unit.followId = null;
                unit.guardMode = false;
                unit.mob.getNavigation().stop();
            }

            ok(context, targets.size() + " Bot(s) und " + units.size() + " Mob(s) folgen dir nicht mehr.");
            return targets.size() + units.size();
        } else {
            ServerPlayer player = context.getSource().getPlayerOrException();

            for (BotPlayer bot : targets) {
                bot.guardHold = false;
                bot.patrolArea = null;
                bot.stayArea = null;
            }

            for (MobUnits.Unit unit : units) {
                unit.clearFight();
                unit.followId = player.getUUID();
                unit.guardMode = guard;
                unit.patrolArea = null;
                if (unit.mob instanceof net.minecraft.world.entity.animal.wolf.Wolf wolf && !wolf.isTame()) {
                    // A wolf that guards/follows you is your wolf: tamed, collar and all.
                    wolf.tame(player);
                    wolf.setOrderedToSit(false);
                    wolf.setInSittingPose(false);
                }
                unit.goal = null;
                unit.post = null;
                unit.marching = false;
            }

            for (BotPlayer bot : targets) {
                bot.followTarget = player.getUUID();
                bot.guardMode = guard;
                bot.allyIds.add(player.getUUID());
                bot.walkGoal = null;
                bot.post = null;
                bot.patrol = null;
                bot.stopFarming();
                if (guard) {
                    bot.fightMode = false;
                    bot.targetIds.clear();
                    bot.currentTarget = null;
                    bot.nearbyTargets = false;
                }
            }

            String who = targets.size() + " Bot(s)" + (units.isEmpty() ? "" : " und " + units.size() + " Mob(s)");
            ok(context, who + (guard ? " bewachen dich - sie greifen erst an, wenn du angreifst oder angegriffen wirst." : " folgen dir jetzt."));
            return targets.size() + units.size();
        }
    }

    private static List<BotCommands.Faction> parseFactions(CommandContext<CommandSourceStack> context, String raw, Double[] sightOut) throws CommandSyntaxException {
        Bots bots = bots();
        CommandSourceStack source = context.getSource();
        List<List<String>> blocks = new ArrayList<>();

        for (String part : raw.split(",")) {
            List<String> tokens = new ArrayList<>();

            for (String token : part.trim().split("\\s+")) {
                String lower = token.toLowerCase(Locale.ROOT);
                if (lower.startsWith("sight:") || lower.startsWith("sicht:")) {
                    sightOut[0] = parseSight(token.substring(6));
                } else if (!token.isBlank()) {
                    tokens.add(token);
                }
            }

            if (!tokens.isEmpty()) {
                blocks.add(tokens);
            }
        }

        if (blocks.size() == 1 && blocks.get(0).size() > 1) {
            List<String> only = blocks.get(0);
            blocks = new ArrayList<>(List.of(List.of(only.get(0)), only.subList(1, only.size())));
        }

        List<BotCommands.Faction> factions = new ArrayList<>();
        Set<UUID> used = new HashSet<>();

        for (List<String> tokens : blocks) {
            BotCommands.Faction faction = new BotCommands.Faction();

            for (String token : tokens) {
                String lower = token.toLowerCase(Locale.ROOT);
                if (lower.equals("nearby")) {
                    faction.nearby = true;
                    faction.labels.add("nearby");
                } else if (!lower.equals("me") && !lower.equals("ich") && !lower.equals("@s") && !lower.equals("@p")) {
                    if (!lower.equals("@actors") && !lower.startsWith("@actors:") && !lower.equals("@schauspieler") && !lower.startsWith("@schauspieler:")) {
                        if (!isSelector(token) && !bots.byName(token).isPresent()) {
                            ServerPlayer player = source.getServer().getPlayerList().getPlayer(token);
                            if (player == null) {
                                throw fail("Spieler " + token + " ist nicht online.");
                            }

                            faction.playerIds.add(player.getUUID());
                            faction.labels.add(player.getGameProfile().name());
                        } else {
                            for (BotPlayer bot : selectLenient(context, token)) {
                                if (used.add(bot.getUUID())) {
                                    faction.bots.add(bot);
                                }
                            }

                            for (MobUnits.Unit unit : selectUnits(context, token)) {
                                if (used.add(unit.mob.getUUID())) {
                                    faction.units.add(unit);
                                }
                            }

                            if (lower.startsWith("@team:") || lower.startsWith("@group:")) {
                                faction.teams.add(token.substring(token.indexOf(58) + 1).toLowerCase(Locale.ROOT));
                            }

                            faction.labels.add(token.contains(":") ? token.substring(token.indexOf(58) + 1) : token);
                        }
                    } else {
                        if (!Actors.enabled()) {
                            throw fail("Schauspieler-Angriffe sind aus: /bot actors on (oder Einstellungen > Kampf).");
                        }

                        double radius = 0.0;
                        if (token.contains(":")) {
                            try {
                                radius = Double.parseDouble(token.substring(token.indexOf(58) + 1));
                            } catch (NumberFormatException e) {
                                throw fail("Umkreis bei " + token + " ist keine Zahl.");
                            }
                        }

                        int count = 0;

                        for (LivingEntity actor : Actors.near(source.getLevel(), source.getPosition(), radius)) {
                            if (used.add(actor.getUUID())) {
                                faction.playerIds.add(actor.getUUID());
                                count++;
                            }
                        }

                        if (count == 0) {
                            throw fail(radius > 0.0 ? "Kein Schauspieler im Umkreis von " + (int)radius + " Bloecken." : "Kein Schauspieler in dieser Welt.");
                        }

                        faction.labels.add(count + " Schauspieler");
                    }
                } else {
                    ServerPlayer me = source.getPlayerOrException();
                    faction.playerIds.add(me.getUUID());
                    faction.labels.add(me.getGameProfile().name());
                }
            }

            if (faction.bots.isEmpty() && faction.units.isEmpty() && faction.playerIds.isEmpty() && !faction.nearby) {
                throw fail("Eine Partei ist leer: " + String.join(" ", tokens));
            }

            factions.add(faction);
        }

        return factions;
    }

    private static String extractLimits(String raw, double[] limits) throws CommandSyntaxException {
        limits[0] = -1.0;
        limits[1] = -1.0;
        StringBuilder rest = new StringBuilder();

        for (String token : raw.trim().split("\\s+")) {
            String lower = token.toLowerCase(Locale.ROOT);

            try {
                if (lower.startsWith("part:") || lower.startsWith("anteil:") || lower.startsWith("teil:")) {
                    limits[0] = Double.parseDouble(lower.substring(lower.indexOf(58) + 1).replace("%", ""));
                } else if (lower.matches("\\d+(\\.\\d+)?%")) {
                    limits[0] = Double.parseDouble(lower.substring(0, lower.length() - 1));
                } else if (!lower.startsWith("range:") && !lower.startsWith("radius:") && !lower.startsWith("nah:") && !lower.startsWith("umkreis:")) {
                    rest.append(token).append(' ');
                } else {
                    limits[1] = Double.parseDouble(lower.substring(lower.indexOf(58) + 1));
                }
            } catch (NumberFormatException error) {
                throw fail("Zahl erwartet: part:50 oder range:40 (" + token + ")");
            }
        }

        if (limits[0] != 0.0 && !(limits[0] > 100.0)) {
            return rest.toString().trim();
        } else {
            throw fail("part: 1-100 Prozent.");
        }
    }

    private static double parseSight(String value) throws CommandSyntaxException {
        if (!value.equalsIgnoreCase("off") && !value.equalsIgnoreCase("aus") && !value.equals("0")) {
            try {
                double range = Double.parseDouble(value.replace(',', '.'));
                if (!(range < 1.0) && !(range > 128.0)) {
                    return range;
                } else {
                    throw fail("Sichtweite: off oder 1-128 Bloecke.");
                }
            } catch (NumberFormatException error) {
                throw fail("Sichtweite: off oder eine Zahl, z.B. sight:10");
            }
        } else {
            return 0.0;
        }
    }

    private static String summary(List<BotCommands.Faction> factions, String joiner) {
        StringBuilder summary = new StringBuilder();

        for (int i = 0; i < factions.size(); i++) {
            if (i > 0) {
                summary.append(joiner);
            }

            summary.append(String.join(" + ", factions.get(i).labels)).append(" (").append(factions.get(i).bots.size()).append(" Bots)");
        }

        return summary.toString();
    }

    private static int fight(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        double[] limits = new double[2];
        String raw = extractLimits(StringArgumentType.getString(context, "parteien"), limits);
        List<BotCommands.Faction> factions = parseFactions(context, raw, new Double[1]);
        if (factions.size() < 2) {
            throw fail("Komma trennt die Parteien: /bot fight @team:Rot me , @team:Blau [part:50] [range:40]");
        }

        String limitText = "";

        for (BotCommands.Faction faction : factions) {
            List<BotCommands.Faction> others = new ArrayList<>(factions);
            others.remove(faction);
            limitText = faction.limitTo(limits[0], limits[1], others);
        }

        for (int i = 0; i < factions.size(); i++) {
            BotCommands.Faction faction = factions.get(i);

            for (int j = 0; j < factions.size(); j++) {
                if (i != j) {
                    for (String own : faction.teams) {
                        for (String other : factions.get(j).teams) {
                            Bots.setTeamRelation(own, other, true, true);
                        }
                    }
                }
            }

            for (BotPlayer bot : faction.bots) {
                bot.clearFight();
                bot.fightMode = !bot.neverFight;
                bot.nextTargetTick = 0L;
                faction.befriend(bot);

                for (int j = 0; j < factions.size(); j++) {
                    if (i != j) {
                        factions.get(j).markAsEnemyOf(bot, true);
                    }
                }
            }

            List<BotCommands.Faction> others = new ArrayList<>(factions);
            others.remove(faction);
            armUnits(faction, others, true, null, false, 0L);
        }

        ok(context, "Fight gestartet: " + summary(factions, " vs ") + limitText + ".");
        return factions.size();
    }

    private static int attack(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Double[] sight = new Double[1];
        double[] limits = new double[2];
        String raw = extractLimits(StringArgumentType.getString(context, "parteien"), limits);
        List<BotCommands.Faction> factions = parseFactions(context, raw, sight);
        if (factions.size() != 2) {
            throw fail("Genau zwei Parteien: /bot attack @team:Rot , @team:Blau [sight:10|sight:off] [part:50] [range:40]");
        }

        BotCommands.Faction attackers = factions.get(0);
        BotCommands.Faction victims = factions.get(1);
        if (attackers.bots.isEmpty() && attackers.units.isEmpty()) {
            throw fail("Die Angreiferseite braucht mindestens einen Bot oder eine Mob-Einheit.");
        }

        String limitText = attackers.limitTo(limits[0], limits[1], List.of(victims));
        if (attackers.bots.isEmpty() && attackers.units.isEmpty()) {
            throw fail("Nach part:/range: bleibt kein Angreifer uebrig" + limitText + ".");
        }

        for (String own : attackers.teams) {
            for (String other : victims.teams) {
                Bots.setTeamRelation(own, other, true, true);
                Bots.setTeamRelation(other, own, false, true);
            }
        }

        for (BotPlayer bot : attackers.bots) {
            bot.clearFight();
            bot.fightMode = !bot.neverFight;
            bot.nextTargetTick = 0L;
            attackers.befriend(bot);
            victims.markAsEnemyOf(bot, true);
        }

        armUnits(attackers, List.of(victims), true, null, false, 0L);
        armUnits(victims, List.of(attackers), false, null, false, 0L);
        Set<UUID> attackerLoose = attackers.looseIds();

        for (BotPlayer bot : victims.bots) {
            attackers.markAsEnemyOf(bot, false);
            bot.allyIds.removeAll(attackerLoose);
            victims.befriend(bot);
            if (sight[0] != null) {
                bot.sightRange = sight[0];
            }
        }

        String sightText = victims.bots.isEmpty() ? "" : " Sichtweite der Opfer: " + describeSight(victims.bots);
        ok(context, "Angriff: " + summary(factions, " -> ") + limitText + "." + sightText);
        return attackers.bots.size() + attackers.units.size();
    }

    private static int march(CommandContext<CommandSourceStack> context, Vec3 goalFromArgument) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        String raw = StringArgumentType.getString(context, "parteien").trim();
        Vec3 goal = goalFromArgument;
        if (goal == null) {
            String[] tokens = raw.split("\\s+");
            if (tokens.length < 4
                || !looksLikeCoordinate(tokens[tokens.length - 3])
                || !looksLikeCoordinate(tokens[tokens.length - 2])
                || !looksLikeCoordinate(tokens[tokens.length - 1])) {
                throw fail("Wohin? /bot march <x> <y> <z> @team:Rot , @team:Blau  oder  /bot march @team:Rot , @team:Blau <x> <y> <z>");
            }

            Vec3 base = source.getPosition();
            goal = new Vec3(
                coordinate(tokens[tokens.length - 3], base.x), coordinate(tokens[tokens.length - 2], base.y), coordinate(tokens[tokens.length - 1], base.z)
            );
            raw = String.join(" ", Arrays.copyOf(tokens, tokens.length - 3));
        }

        Double[] sight = new Double[1];
        List<BotCommands.Faction> factions = parseFactions(context, raw, sight);
        if (!factions.isEmpty() && factions.size() <= 2) {
            BotCommands.Faction marchers = factions.get(0);
            BotCommands.Faction enemies = factions.size() == 2 ? factions.get(1) : new BotCommands.Faction();
            if (marchers.bots.isEmpty() && marchers.units.isEmpty()) {
                throw fail("Die Marschseite braucht mindestens einen Bot oder eine Mob-Einheit.");
            }

            long deadline = source.getServer().getTickCount() + 12000L;
            UUID group = UUID.randomUUID();
            boolean anyEnemy = factions.size() == 2 && (!enemies.bots.isEmpty() || !enemies.playerIds.isEmpty() || enemies.nearby);
            List<Vec3> marchSpots = spreadAround(source.getLevel(), goal, marchers.bots.size() + marchers.units.size());
            int marchIndex = 0;

            for (BotPlayer bot : marchers.bots) {
                bot.clearFight();
                bot.fightMode = anyEnemy;
                bot.nextTargetTick = 0L;
                marchers.befriend(bot);
                enemies.markAsEnemyOf(bot, true);
                bot.walkGoal = marchSpots.get(marchIndex++);
                bot.walkDeadline = deadline;
                bot.walkGroup = group;
                bot.marching = true;
                bot.followTarget = null;
                bot.guardMode = false;
                bot.post = null;
                bot.patrol = null;
                bot.stopFarming();
            }

            armUnits(marchers, factions.size() == 2 ? List.of(enemies) : List.of(), true, goal, true, deadline);

            for (MobUnits.Unit unit : marchers.units) {
                unit.goal = marchSpots.get(marchIndex++);
            }

            armUnits(enemies, List.of(marchers), false, null, false, 0L);
            Set<UUID> marcherLoose = marchers.looseIds();

            for (BotPlayer bot : enemies.bots) {
                marchers.markAsEnemyOf(bot, false);
                bot.allyIds.removeAll(marcherLoose);
                enemies.befriend(bot);
                if (sight[0] != null) {
                    bot.sightRange = sight[0];
                }
            }

            String enemyText = factions.size() == 2 ? " gegen " + String.join(" + ", enemies.labels) + " (" + enemies.bots.size() + " Bots)" : "";
            String sightText = enemies.bots.isEmpty() ? "" : " Sichtweite der Feinde: " + describeSight(enemies.bots);
            ok(
                context,
                "Marsch: "
                    + String.join(" + ", marchers.labels)
                    + " ("
                    + marchers.bots.size()
                    + " Bots) nach "
                    + (int)goal.x
                    + " "
                    + (int)goal.y
                    + " "
                    + (int)goal.z
                    + enemyText
                    + "."
                    + sightText
            );
            return marchers.bots.size();
        } else {
            throw fail("Marschierer , Feinde: /bot march ~100 ~ ~ @team:Rot , @team:Blau");
        }
    }

    private static int stopMarch(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));

        for (MobUnits.Unit unit : selectUnits(context, SelectorArgumentType.get(context, "auswahl"))) {
            unit.clearFight();
            unit.goal = null;
            unit.post = null;
            unit.marching = false;
            unit.mob.getNavigation().stop();
        }

        for (BotPlayer bot : targets) {
            bot.walkGoal = null;
            bot.walkGroup = null;
            bot.marching = false;
            bot.post = null;
            bot.patrol = null;
            bot.patrolArea = null;
            bot.guardHold = false;
            bot.clearFight();
            bot.fightMode = false;
            bot.setMoveInput(0.0F, 0.0F);
        }

        ok(
            context,
            targets.size()
                + " Bot(s) marschieren nicht mehr"
                + (targets.stream().anyMatch(botx -> botx.followTarget != null) ? " - Begleiter kommen zurueck zu dir." : ".")
        );
        return targets.size();
    }

    private static int line(CommandContext<CommandSourceStack> context, boolean patrol) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        CommandSourceStack source = context.getSource();
        ServerLevel world = source.getLevel();
        String[] tokens = StringArgumentType.getString(context, "punkte").trim().split("\\s+");
        if (tokens.length >= 4 && tokens.length % 2 == 0) {
            List<Vec3> points = new ArrayList<>();
            Vec3 base = source.getPosition();

            for (int i = 0; i < tokens.length; i += 2) {
                double x = coordinate(tokens[i], base.x);
                double z = coordinate(tokens[i + 1], base.z);
                points.add(surface(world, new Vec3(x, base.y, z)));
            }

            long deadline = source.getServer().getTickCount() + 12000L;
            if (patrol) {
                for (BotPlayer bot : targets) {
                    bot.patrol = new ArrayList<>(points);
                    bot.patrolIndex = 0;
                    bot.patrolForward = true;
                    bot.post = null;
                    bot.guardHold = bot.followTarget != null;
                    bot.marching = false;
                    bot.walkGoal = points.get(0);
                    bot.walkDeadline = deadline;
                    bot.walkGroup = null;
                    bot.stopFarming();
                }

                ok(context, targets.size() + " Bot(s) patrouillieren auf " + points.size() + " Wegpunkten.");
                return targets.size();
            } else {
                List<Vec3> posts = spreadAlong(points, targets.size());
                List<BotPlayer> free = new ArrayList<>(targets);

                for (Vec3 post : posts) {
                    BotPlayer nearest = null;
                    double best = Double.MAX_VALUE;

                    for (BotPlayer bot : free) {
                        double distance = bot.position().distanceToSqr(post);
                        if (distance < best) {
                            best = distance;
                            nearest = bot;
                        }
                    }

                    if (nearest == null) {
                        break;
                    }

                    free.remove(nearest);
                    nearest.post = surface(world, post);
                    nearest.postYaw = Float.NaN;
                    nearest.patrol = null;
                    nearest.guardHold = nearest.followTarget != null;
                    nearest.marching = false;
                    nearest.walkGoal = nearest.post;
                    nearest.walkDeadline = deadline;
                    nearest.walkGroup = null;
                    nearest.stopFarming();
                }

                List<MobUnits.Unit> units = selectUnits(context, SelectorArgumentType.get(context, "auswahl"));
                List<Vec3> unitPosts = spreadAlong(points, units.size());

                for (int i = 0; i < units.size(); i++) {
                    units.get(i).post = surface(world, unitPosts.get(i));
                    units.get(i).goal = null;
                }

                ok(
                    context,
                    targets.size()
                        + " Bot(s)"
                        + (units.isEmpty() ? "" : " + " + units.size() + " Mob(s)")
                        + " besetzen die Grenze ("
                        + points.size()
                        + " Punkte, "
                        + (int)polylineLength(points)
                        + " Bloecke)."
                );
                return targets.size();
            }
        } else {
            throw fail("Punkte als x z Paare: /bot " + (patrol ? "patrol" : "line") + " @team:Rot 100 -50 140 -60 180 -55");
        }
    }

    private static int occupy(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel world = source.getLevel();
        String raw = StringArgumentType.getString(context, "parteien").trim();
        Double level = null;
        StringBuilder rest = new StringBuilder();

        for (String token : raw.split("\\s+")) {
            if (token.toLowerCase(Locale.ROOT).startsWith("y:")) {
                try {
                    level = Double.parseDouble(token.substring(2));
                } catch (NumberFormatException ignored) {
                    throw fail("Hoehe unlesbar: " + token);
                }
            } else {
                rest.append(token).append(' ');
            }
        }

        raw = rest.toString().trim();
        String[] tokens = raw.split("\\s+");
        int coords = 0;

        while (coords < tokens.length && looksLikeCoordinate(tokens[tokens.length - 1 - coords])) {
            coords++;
        }

        coords -= coords % 4;
        if (coords >= 4 && tokens.length - coords >= 1) {
            Vec3 base = source.getPosition();
            double referenceY = level != null ? level : base.y;
            List<double[]> rects = new ArrayList<>();

            for (int i = tokens.length - coords; i < tokens.length; i += 4) {
                double rx1 = coordinate(tokens[i], base.x);
                double rz1 = coordinate(tokens[i + 1], base.z);
                double rx2 = coordinate(tokens[i + 2], base.x);
                double rz2 = coordinate(tokens[i + 3], base.z);
                rects.add(new double[]{Math.min(rx1, rx2), Math.min(rz1, rz2), Math.max(rx1, rx2), Math.max(rz1, rz2)});
            }

            double x1 = rects.get(0)[0];
            double z1 = rects.get(0)[1];
            double x2 = rects.get(0)[2];
            double z2 = rects.get(0)[3];
            Double[] sight = new Double[1];
            List<BotCommands.Faction> factions = parseFactions(context, String.join(" ", Arrays.copyOf(tokens, tokens.length - coords)), sight);
            if (!factions.isEmpty() && factions.size() <= 2 && (!factions.get(0).bots.isEmpty() || !factions.get(0).units.isEmpty())) {
                BotCommands.Faction own = factions.get(0);
                BotCommands.Faction enemies = factions.size() == 2 ? factions.get(1) : new BotCommands.Faction();
                boolean anyEnemy = !enemies.bots.isEmpty() || !enemies.units.isEmpty() || !enemies.playerIds.isEmpty() || enemies.nearby;
                double minX = Math.min(x1, x2);
                double maxX = Math.max(x1, x2);
                double minZ = Math.min(z1, z2);
                double maxZ = Math.max(z1, z2);
                int count = own.bots.size() + own.units.size();
                List<Vec3> posts = new ArrayList<>();
                List<double[]> perSlot = distributeByArea(rects, count);

                for (double[] rect : rects) {
                    int share = (int)perSlot.stream().filter(r -> r == rect).count();
                    if (share > 0) {
                        // A perfectly even grid like /bot armymarch: one spacing both ways, the whole
                        // block centred in the area, a shorter last row centred too.
                        double width = Math.max(1.0, rect[2] - rect[0]);
                        double depth = Math.max(1.0, rect[3] - rect[1]);
                        int cols = Math.max(1, Math.min(share, (int)Math.round(Math.sqrt(share * width / depth))));
                        int rows = Math.max(1, (int)Math.ceil((double)share / cols));
                        double spacing = Math.max(1.0, Math.min(width / cols, depth / rows));
                        double centreX = (rect[0] + rect[2]) / 2.0;
                        double centreZ = (rect[1] + rect[3]) / 2.0;

                        for (int i = 0; i < share; i++) {
                            int col = i % cols;
                            int row = i / cols;
                            int inRow = row == rows - 1 ? share - row * cols : cols;
                            double px = Math.floor(centreX + (col - (inRow - 1) / 2.0) * spacing) + 0.5;
                            double pz = Math.floor(centreZ + (row - (rows - 1) / 2.0) * spacing) + 0.5;
                            if (spacing >= 1.0 && Math.abs(spacing - Math.round(spacing)) > 0.01) {
                                // Non-whole spacing: keep exact positions instead of block centres.
                                px = centreX + (col - (inRow - 1) / 2.0) * spacing;
                                pz = centreZ + (row - (rows - 1) / 2.0) * spacing;
                            }

                            Vec3 ground = Ground.findBelow(world, px, pz, referenceY);
                            Vec3 spot = ground != null ? ground : surface(world, new Vec3(px, referenceY, pz));
                            posts.add(new Vec3(px, spot.y, pz));
                        }
                    }
                }

                long deadline = source.getServer().getTickCount() + 12000L;
                List<BotPlayer> free = new ArrayList<>(own.bots);
                // Everyone faces the same way at their post: the direction they came from into the area.
                double fromX = 0.0;
                double fromZ = 0.0;
                for (BotPlayer bot : free) {
                    fromX += bot.getX() / Math.max(1, free.size());
                    fromZ += bot.getZ() / Math.max(1, free.size());
                }

                double areaX = 0.0;
                double areaZ = 0.0;
                for (Vec3 post : posts) {
                    areaX += post.x / Math.max(1, posts.size());
                    areaZ += post.z / Math.max(1, posts.size());
                }

                float facing = (float)Math.toDegrees(Math.atan2(-(areaX - fromX), areaZ - fromZ));

                for (Vec3 post : new ArrayList<>(posts)) {
                    BotPlayer nearest = null;
                    double best = Double.MAX_VALUE;

                    for (BotPlayer bot : free) {
                        double distance = bot.position().distanceToSqr(post);
                        if (distance < best) {
                            best = distance;
                            nearest = bot;
                        }
                    }

                    if (nearest == null) {
                        break;
                    }

                    free.remove(nearest);
                    BotPlayer occupier = nearest;
                    occupier.clearFight();
                    occupier.fightMode = anyEnemy;
                    occupier.nextTargetTick = 0L;
                    own.befriend(occupier);
                    enemies.markAsEnemyOf(occupier, true);
                    enemies.markAsEnemyOf(occupier, false);
                    if (sight[0] != null) {
                        nearest.sightRange = sight[0];
                    } else if (nearest.sightRange <= 0.0) {
                        nearest.sightRange = 24.0;
                    }

                    nearest.post = post;
                    nearest.postYaw = facing;
                    nearest.patrol = null;
                    nearest.walkGoal = post;
                    nearest.walkDeadline = deadline;
                    nearest.walkGroup = null;
                    nearest.marching = true;
                    nearest.guardHold = nearest.followTarget != null;
                    nearest.stopFarming();
                    posts.remove(post);
                }

                List<MobUnits.Unit> freeUnits = new ArrayList<>(own.units);

                for (Vec3 post : new ArrayList<>(posts)) {
                    MobUnits.Unit nearest = null;
                    double best = Double.MAX_VALUE;

                    for (MobUnits.Unit unit : freeUnits) {
                        double distance = unit.mob.position().distanceToSqr(post);
                        if (distance < best) {
                            best = distance;
                            nearest = unit;
                        }
                    }

                    if (nearest == null) {
                        break;
                    }

                    freeUnits.remove(nearest);
                    nearest.clearFight();
                    nearest.fightMode = anyEnemy;
                    nearest.enemyTeams.addAll(enemies.teams);
                    nearest.hostileTeams.addAll(enemies.teams);
                    nearest.targetIds.addAll(enemies.looseIds());
                    if (sight[0] != null) {
                        nearest.sightRange = sight[0];
                    } else if (nearest.sightRange <= 0.0) {
                        nearest.sightRange = 24.0;
                    }

                    nearest.post = post;
                    nearest.patrolArea = null;
                    nearest.goal = post;
                    nearest.goalDeadline = deadline;
                    nearest.marching = true;
                    nearest.followId = null;
                }

                String enemyText = enemies.labels.isEmpty() ? "" : " gegen " + String.join(" + ", enemies.labels);
                ok(
                    context,
                    own.bots.size()
                        + own.units.size()
                        + " Bot(s)/Mob(s) besetzen "
                        + (rects.size() > 1 ? rects.size() + " Gebiete, u.a. " : "das Gebiet ")
                        + (int)minX
                        + "/"
                        + (int)minZ
                        + " bis "
                        + (int)maxX
                        + "/"
                        + (int)maxZ
                        + enemyText
                        + "."
                );
                return own.bots.size();
            } else {
                throw fail("Besetzer , Feinde: /bot occupy @team:Rot , @team:Blau <x1> <z1> <x2> <z2>");
            }
        } else {
            throw fail("/bot occupy @team:Rot , @team:Blau <x1> <z1> <x2> <z2> [weitere Rechtecke ...]");
        }
    }

    private static double polylineLength(List<Vec3> points) {
        double length = 0.0;

        for (int i = 1; i < points.size(); i++) {
            length += horizontal(points.get(i - 1), points.get(i));
        }

        return length;
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static List<Vec3> spreadAlong(List<Vec3> points, int count) {
        List<Vec3> posts = new ArrayList<>();
        double total = polylineLength(points);
        if (count <= 0) {
            return posts;
        }

        if (total < 0.01) {
            for (int i = 0; i < count; i++) {
                posts.add(points.get(0));
            }

            return posts;
        } else {
            for (int i = 0; i < count; i++) {
                double wanted = total * (i + 0.5) / count;
                double walked = 0.0;

                for (int j = 1; j < points.size(); j++) {
                    double segment = horizontal(points.get(j - 1), points.get(j));
                    if (walked + segment >= wanted || j == points.size() - 1) {
                        double t = segment < 0.001 ? 0.0 : Math.max(0.0, Math.min(1.0, (wanted - walked) / segment));
                        posts.add(points.get(j - 1).lerp(points.get(j), t));
                        break;
                    }

                    walked += segment;
                }
            }

            return posts;
        }
    }

    private static int stopLine(CommandContext<CommandSourceStack> context, boolean patrol) throws CommandSyntaxException {
        List<BotPlayer> targets = select(context, SelectorArgumentType.get(context, "auswahl"));

        for (BotPlayer bot : targets) {
            bot.post = null;
            bot.patrol = null;
            bot.walkGoal = null;
        }

        ok(context, targets.size() + " Bot(s) " + (patrol ? "patrouillieren" : "halten die Grenze") + " nicht mehr.");
        return targets.size();
    }

    private static int patrolArea(CommandContext<CommandSourceStack> context, boolean teleport) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        String[] tokens = StringArgumentType.getString(context, "ecken").trim().split("\\s+");
        Vec3 base = context.getSource().getPosition();
        double[] levels = yRange(tokens, 0, base.y);
        int first = levels == null ? 0 : 3;
        if (tokens.length - first >= 4 && (tokens.length - first) % 4 == 0) {
            List<double[]> rects = new ArrayList<>();

            for (int i = first; i < tokens.length; i += 4) {
                double x1 = coordinate(tokens[i], base.x);
                double z1 = coordinate(tokens[i + 1], base.z);
                double x2 = coordinate(tokens[i + 2], base.x);
                double z2 = coordinate(tokens[i + 3], base.z);
                rects.add(box(x1, z1, x2, z2, levels));
            }

            List<MobUnits.Unit> units = selectUnits(context, SelectorArgumentType.get(context, "auswahl"));
            List<double[]> assignment = distributeByArea(rects, targets.size() + units.size());
            int slot = 0;
            double[] area = rects.get(0);
            ServerLevel world = context.getSource().getLevel();
            int teleported = 0;
            Map<double[], ArrayDeque<Vec3>> levelSpots = new IdentityHashMap<>();

            for (BotPlayer bot : targets) {
                bot.guardHold = bot.followTarget != null;
                bot.patrolArea = assignment.get(slot++);
                if (teleport) {
                    Vec3 spot;
                    if (Ground.hasHeight(bot.patrolArea)) {
                        double[] own = bot.patrolArea;
                        spot = levelSpots.computeIfAbsent(
                                own,
                                key -> new ArrayDeque<>(
                                    Ground.spreadOverLevels(
                                        world, key, (int)assignment.stream().filter(other -> other == key).count(), ThreadLocalRandom.current()
                                    )
                                )
                            )
                            .poll();
                    } else {
                        spot = Ground.randomDryLoading(world, bot.patrolArea, bot.getY(), ThreadLocalRandom.current());
                    }

                    if (spot != null) {
                        bot.teleportTo(world, spot.x, spot.y, spot.z, Collections.emptySet(), ThreadLocalRandom.current().nextFloat() * 360.0F, 0.0F, false);
                        bot.path = null;
                        bot.walkGoal = null;
                        teleported++;
                    }
                }

                bot.patrol = null;
                bot.post = null;
                bot.walkGoal = null;
                bot.followTarget = null;
                bot.nextPatrolPick = 0L;
                if (bot.sightRange <= 0.0) {
                    bot.sightRange = 20.0;
                }
            }

            for (MobUnits.Unit unit : units) {
                unit.patrolArea = assignment.get(slot++);
                if (teleport) {
                    Vec3 spot = Ground.randomDryLoading(world, unit.patrolArea, unit.mob.getY(), ThreadLocalRandom.current());
                    if (spot != null) {
                        unit.mob.snapTo(spot.x, spot.y, spot.z, ThreadLocalRandom.current().nextFloat() * 360.0F, 0.0F);
                        unit.mob.getNavigation().stop();
                        unit.goal = null;
                        teleported++;
                    }
                }

                unit.post = null;
                unit.goal = null;
                unit.followId = null;
                unit.nextPatrolPick = 0L;
                if (unit.sightRange <= 0.0) {
                    unit.sightRange = 20.0;
                }
            }

            ok(
                context,
                targets.size()
                    + units.size()
                    + " Bot(s)/Mob(s) patrouillieren in "
                    + rects.size()
                    + " Gebiet(en), z.B. "
                    + (int)area[0]
                    + "/"
                    + (int)area[1]
                    + " bis "
                    + (int)area[2]
                    + "/"
                    + (int)area[3]
                    + (teleport ? " - " + teleported + " verteilt hineingesetzt (trocken, kein Baum)." : ".")
            );
            return targets.size();
        } else {
            throw fail("/bot patrolarea <auswahl> [y <y1> <y2>] <x1> <z1> <x2> <z2> [weitere Rechtecke: x1 z1 x2 z2 ...]  (y = alle Stockwerke dazwischen)");
        }
    }

    private static int spawnAllied(CommandContext<CommandSourceStack> context, String countOrName, String kit, String team, String ally) throws CommandSyntaxException {
        if (spawnType == null && typeNamed(ally) != null) {
            // "/bot spawn 20 iron Rot null" - the type after the team.
            return spawnWithType(context, typeNamed(ally), countOrName, kit, team, null, null);
        }

        if (spawnTiers == null && de.fiden.botsystem.bot.Tiers.range(ally) != null) {
            // "/bot spawn 100 netherite rot ht4-ht3" - tier range instead of an allied team.
            return spawnTiered(context, countOrName, kit, team, null, ally);
        }

        int spawned = spawn(context, countOrName, kit, team);
        if (noValue(ally) || noValue(team)) {
            return spawned;
        }

        String a = teamName(team);
        String b = teamName(ally);
        if (!a.equalsIgnoreCase(b)) {
            Bots.setAllied(a, b, true);
            ok(context, "Team " + a + " und " + b + " sind verbuendet: greifen sich nie an und helfen sich, wenn einer angegriffen wird.");
        }

        return spawned;
    }

    private static int teamAlly(CommandContext<CommandSourceStack> context, boolean ally) throws CommandSyntaxException {
        String a = teamName(StringArgumentType.getString(context, "team"));
        String b = teamName(StringArgumentType.getString(context, "team2"));
        if (a.equalsIgnoreCase(b)) {
            throw fail("Zwei verschiedene Teams angeben.");
        }

        Bots.setAllied(a, b, ally);
        if (ally) {
            ok(
                context,
                "Team "
                    + a
                    + " und "
                    + b
                    + " sind jetzt verbuendet - sie greifen sich nie an, und wird einer angegriffen, ruecken beide Teams aus (Beistandspakt)."
            );
        } else {
            ok(context, "Buendnis zwischen " + a + " und " + b + " aufgehoben.");
        }

        return 1;
    }

    private static void enableAutoWar(CommandContext<CommandSourceStack> context) {
        BotDefaults defaults = BotDefaults.get();
        if (!defaults.autoWar) {
            defaults.autoWar = true;
            defaults.save();
            info(
                context,
                "Krieg aller gegen alle AN: Teams ohne Buendnis greifen sich an, sobald sie sich sehen (Sichtweite "
                    + (defaults.sightRange > 0.0 ? (int)defaults.sightRange : 24)
                    + "; /bot sight). Aus: /bot autowar off",
                ChatFormatting.GOLD
            );
        }
    }

    private static int autoWar(CommandContext<CommandSourceStack> context, boolean on) {
        BotDefaults defaults = BotDefaults.get();
        defaults.autoWar = on;
        defaults.save();
        ok(
            context,
            on
                ? "Krieg aller gegen alle AN: jedes Team ohne Buendnis (/bot team ally) ist Feind, sobald es in Sichtweite kommt."
                : "Krieg aller gegen alle AUS."
        );
        return 1;
    }

    private static int dropLifetime(CommandContext<CommandSourceStack> context, int seconds) {
        BotDefaults defaults = BotDefaults.get();
        defaults.dropLifetime = seconds;
        if (seconds != 0) {
            defaults.drops = true;
        }

        defaults.save();
        ok(
            context,
            seconds < 0
                ? "Todes-Drops bleiben liegen, bis jemand sie aufhebt."
                : (seconds == 0 ? "Todes-Drops verschwinden normal (nach 5 Minuten)." : "Todes-Drops verschwinden nach " + seconds + " s.")
        );
        return 1;
    }

    private static int runaway(CommandContext<CommandSourceStack> context, boolean on) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));

        for (BotPlayer bot : targets) {
            bot.fleeing = on;
            bot.walkGoal = null;
            bot.path = null;
            if (on) {
                bot.fightMode = false;
                bot.currentTarget = null;
                bot.marching = false;
                bot.post = null;
                bot.followTarget = null;
                bot.lastThreatTick = context.getSource().getServer().getTickCount();
            }
        }

        ok(
            context,
            targets.size() + (on ? " Bot(s) fliehen - weg von allen Gegnern, kein Zurueckschlagen, heilen wenn Luft ist." : " Bot(s) hoeren auf zu fliehen.")
        );
        return targets.size();
    }

    private static int halt(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        long now = context.getSource().getServer().getTickCount();
        Set<String> teams = new LinkedHashSet<>();

        for (BotPlayer bot : targets) {
            bot.clearFight();
            bot.fightMode = false;
            bot.fleeing = false;
            bot.walkGoal = null;
            bot.walkGroup = null;
            bot.marching = false;
            bot.post = null;
            bot.patrol = null;
            bot.patrolArea = null;
            bot.returnTo = null;
            bot.searchId = null;
            bot.searchSpotted = false;
            bot.responseTeam = null;
            bot.savedWalkGoal = null;
            bot.detour = null;
            bot.path = null;
            bot.stuckStrikes = 0;
            bot.ceasefireUntil = now + 300L;
            bot.guardHold = false;
            bot.stayArea = null;
            bot.setMoveInput(0.0F, 0.0F);
            bot.setSprinting(false);
            bot.stopUsingItem();
            if (bot.team != null) {
                teams.add(bot.team);
            }
        }

        for (String team : teams) {
            Bots.clearTeamRelations(team);

            for (Set<String> others : Bots.teamEnemies().values()) {
                others.remove(team.toLowerCase(Locale.ROOT));
            }

            for (Set<String> others : Bots.teamHostiles().values()) {
                others.remove(team.toLowerCase(Locale.ROOT));
            }

            for (BotPlayer other : bots().all()) {
                other.enemyTeams.remove(team.toLowerCase(Locale.ROOT));
                other.hostileTeams.remove(team.toLowerCase(Locale.ROOT));
                if (other.responseTeam != null && other.responseTeam.equalsIgnoreCase(team)) {
                    other.responseTeam = null;
                }
            }
        }

        Set<UUID> ids = new HashSet<>();
        targets.forEach(bot -> ids.add(bot.getUUID()));

        for (BotPlayer other : bots().all()) {
            other.targetIds.removeAll(ids);
            other.hostileIds.removeAll(ids);
            if (other.currentTarget != null && ids.contains(other.currentTarget.getUUID())) {
                other.currentTarget = null;
            }
        }

        List<MobUnits.Unit> units = selectUnits(context, SelectorArgumentType.get(context, "auswahl"));

        for (MobUnits.Unit unit : units) {
            unit.clearFight();
            unit.goal = null;
            unit.post = null;
            unit.patrolArea = null;
            unit.marching = false;
            unit.responseTeam = null;
            unit.followId = null;
            unit.guardMode = false;
            unit.orderUntil = 0L;
            unit.mob.getNavigation().stop();
        }

        if (BotSystemMod.mobs() != null) {
            for (MobUnits.Unit unit : BotSystemMod.mobs().all()) {
                unit.targetIds.removeAll(ids);

                for (String team : teams) {
                    unit.enemyTeams.remove(team.toLowerCase(Locale.ROOT));
                    unit.hostileTeams.remove(team.toLowerCase(Locale.ROOT));
                }

                if (unit.mob.getTarget() != null && ids.contains(unit.mob.getTarget().getUUID())) {
                    unit.mob.setTarget(null, TargetReason.CUSTOM);
                }
            }
        }

        ok(context, targets.size() + units.size() + " Bot(s)/Mob(s) angehalten: alle Auftraege und Feindschaften weg, 15 s Waffenstillstand.");
        return targets.size() + units.size();
    }

    private static int leader(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        String wanted = SelectorArgumentType.get(context, "anfuehrer");
        LivingEntity leader;
        if (!wanted.equalsIgnoreCase("me") && !wanted.equalsIgnoreCase("ich")) {
            BotPlayer bot = bots().byName(wanted).orElse(null);
            ServerPlayer player = bot == null ? context.getSource().getServer().getPlayerList().getPlayer(wanted) : null;
            if (bot == null && player == null) {
                throw fail("Kein Bot oder Spieler namens " + wanted + ".");
            }

            leader = bot != null ? bot : player;
        } else {
            leader = context.getSource().getPlayerOrException();
        }

        int count = 0;

        for (BotPlayer bot : targets) {
            if (bot != leader) {
                bot.followTarget = leader.getUUID();
                bot.guardMode = false;
                bot.guardHold = false;
                bot.allyIds.add(leader.getUUID());
                bot.walkGoal = null;
                bot.post = null;
                bot.patrol = null;
                bot.patrolArea = null;
                count++;
            }
        }

        for (MobUnits.Unit unit : selectUnits(context, SelectorArgumentType.get(context, "auswahl"))) {
            unit.followId = leader.getUUID();
            unit.guardMode = false;
            unit.goal = null;
            unit.post = null;
            count++;
        }

        ok(context, count + " Bot(s)/Mob(s) folgen jetzt " + leader.getName().getString() + " als Anfuehrer (locker drumherum, kaempfen mit ihm).");
        return count;
    }

    private static int defect(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        String team = teamName(StringArgumentType.getString(context, "team"));
        Set<String> oldTeams = new LinkedHashSet<>();

        for (BotPlayer bot : targets) {
            if (bot.team != null && !bot.team.equalsIgnoreCase(team)) {
                oldTeams.add(bot.team);
            }

            bot.clearFight();
            bot.fightMode = false;
            bot.followTarget = null;
            bot.guardMode = false;
            bot.team = team;
            bot.hostileTeams.addAll(oldTeams.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList());
        }

        for (MobUnits.Unit unit : selectUnits(context, SelectorArgumentType.get(context, "auswahl"))) {
            if (unit.team != null && !unit.team.equalsIgnoreCase(team)) {
                oldTeams.add(unit.team);
            }

            unit.clearFight();
            unit.team = team;
        }

        for (String old : oldTeams) {
            Bots.setAllied(old, team, false);
            Bots.setTeamRelation(old, team, false, true);
            Bots.setTeamRelation(team, old, false, true);
        }

        if (BotDefaults.get().chat && !targets.isEmpty()) {
            BotPlayer speaker = targets.get(0);
            context.getSource()
                .getServer()
                .getPlayerList()
                .broadcastSystemMessage(
                    Component.translatable(
                        "chat.type.text",
                        speaker.getDisplayName(),
                        Component.literal(ThreadLocalRandom.current().nextBoolean() ? "ich bin jetzt bei " + team : "sorry jungs, " + team + " zahlt besser")
                    ),
                    false
                );
        }

        ok(
            context,
            targets.size()
                + " Bot(s) sind zu "
                + team
                + " uebergelaufen"
                + (oldTeams.isEmpty() ? "." : " - " + String.join(", ", oldTeams) + " und " + team + " sind jetzt verfeindet.")
        );
        return targets.size();
    }

    private static int save(CommandContext<CommandSourceStack> context, String name) throws CommandSyntaxException {
        try {
            int count = BotSaves.save(context.getSource().getServer(), bots(), BotSystemMod.mobs(), name);
            ok(context, "Gespeichert als \"" + name + "\": " + count + " Bots/Mobs mit Position, Inventar, Team, Auftrag, Feindschaften und Buendnissen.");
            return count;
        } catch (IOException error) {
            throw fail("Speichern fehlgeschlagen: " + error.getMessage());
        }
    }

    private static int load(CommandContext<CommandSourceStack> context, String name, boolean replace) throws CommandSyntaxException {
        try {
            int count = BotSaves.load(context.getSource().getServer(), bots(), BotSystemMod.mobs(), name, replace);
            ok(context, "Spielstand \"" + name + "\" geladen: " + count + " Bots/Mobs"
                + (replace ? " - die bisherigen wurden ersetzt." : " kommen zu den vorhandenen dazu (ersetzen: /bot load " + name + " on)."));
            return count;
        } catch (IOException error) {
            throw fail("Laden fehlgeschlagen: " + error.getMessage());
        }
    }

    /** /bot copy <auswahl> [anzahl]: clones (skin, inventory, team, tier, settings) next to you. */
    private static int copy(CommandContext<CommandSourceStack> context, int count) throws CommandSyntaxException {
        List<BotPlayer> originals = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        if (originals.isEmpty()) {
            throw fail("Keine Bots ausgewaehlt.");
        }

        CommandSourceStack source = context.getSource();
        net.minecraft.server.MinecraftServer server = source.getServer();
        ServerLevel world = source.getLevel();
        Vec3 centre = source.getPosition();
        long now = server.getTickCount();
        int total = originals.size() * count;
        int made = 0;
        int index = 0;
        for (BotPlayer original : originals) {
            com.google.gson.JsonObject saved = BotSaves.botJson(server, original, now);
            saved.addProperty("world", world.dimension().identifier().toString());
            for (int i = 0; i < count; i++) {
                double angle = index * Math.PI * (3.0 - Math.sqrt(5.0));
                double radius = total == 1 ? 2.0 : Math.max(2.5, Math.sqrt(total) * 1.3) * Math.sqrt((index + 0.5) / total);
                Vec3 spot = surface(world, centre.add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius));
                index++;
                if (BotSaves.botFromJson(server, bots(), saved, now, bots().freshName(BotNameStyle.MIX, 4, 12), spot) != null) {
                    made++;
                }
            }
        }

        ok(context, made + " Kopie(n) von " + originals.size() + " Bot(s) erstellt - gleicher Skin, Inventar, Team, Stufe und Einstellungen.");
        return made;
    }

    private static int autosave(CommandContext<CommandSourceStack> context, boolean on) {
        BotDefaults.get().autosave = on;
        BotDefaults.get().save();
        ok(context, on ? "Autosave AN: alle 60 s nach world/botsystem/auto.json, beim Betreten der Welt wird der Stand automatisch geladen." : "Autosave AUS.");
        return 1;
    }

    private static int minimap(CommandContext<CommandSourceStack> context, boolean on) throws CommandSyntaxException {
        BotDefaults.get().minimap = on;
        BotDefaults.get().save();
        ServerPlayer player = context.getSource().getPlayerOrException();
        new MapPayloads.MapHud(on).sendTo(player);
        ok(context, on ? "Minikarte AN (oben rechts)." : "Minikarte AUS.");
        return 1;
    }

    private static int teleportTo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        List<MobUnits.Unit> units = selectUnits(context, SelectorArgumentType.get(context, "auswahl"));
        Vec3 centre = Vec3Argument.getVec3(context, "ziel");
        ServerLevel world = context.getSource().getLevel();
        world.getChunk((int)Math.floor(centre.x) >> 4, (int)Math.floor(centre.z) >> 4);
        int total = targets.size() + units.size();
        double radius = Math.max(2.0, Math.sqrt(total) * 1.6);
        double[] area = new double[]{centre.x - radius, centre.z - radius, centre.x + radius, centre.z + radius};
        int done = 0;

        for (BotPlayer bot : targets) {
            Vec3 spot = Ground.randomDryLoading(world, area, centre.y, ThreadLocalRandom.current());
            if (spot == null) {
                spot = Ground.find(world, centre.x, centre.z, centre.y);
            }

            bot.teleportTo(world, spot.x, spot.y, spot.z, Collections.emptySet(), bot.getYRot(), bot.getXRot(), false);
            bot.path = null;
            bot.walkGoal = null;
            bot.guardHold = bot.followTarget != null;
            done++;
        }

        for (MobUnits.Unit unit : units) {
            Vec3 spot = Ground.randomDryLoading(world, area, centre.y, ThreadLocalRandom.current());
            if (spot == null) {
                spot = Ground.find(world, centre.x, centre.z, centre.y);
            }

            unit.mob.snapTo(spot.x, spot.y, spot.z, unit.mob.getYRot(), 0.0F);
            unit.mob.getNavigation().stop();
            unit.goal = null;
            done++;
        }

        ok(context, done + " Bot(s)/Mob(s) nach " + (int)centre.x + " " + (int)centre.z + " teleportiert (verteilt).");
        return done;
    }

    private static int search(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> hunters = select(context, SelectorArgumentType.get(context, "auswahl"));
        String wanted = SelectorArgumentType.get(context, "ziel");
        LivingEntity target;
        if (!wanted.equalsIgnoreCase("me") && !wanted.equalsIgnoreCase("ich")) {
            BotPlayer bot = bots().byName(wanted).orElse(null);
            ServerPlayer player = bot == null ? context.getSource().getServer().getPlayerList().getPlayer(wanted) : null;
            if (bot == null && player == null) {
                throw fail("Kein Spieler oder Bot namens " + wanted + ".");
            }

            target = bot != null ? bot : player;
        } else {
            target = context.getSource().getPlayerOrException();
        }

        int count = 0;

        for (BotPlayer bot : hunters) {
            if (bot != target) {
                bot.clearFight();
                bot.fightMode = false;
                bot.followTarget = null;
                bot.post = null;
                bot.patrol = null;
                bot.walkGoal = null;
                bot.allyIds.remove(target.getUUID());
                bot.searchId = target.getUUID();
                bot.searchSpotted = false;
                bot.searchLastSeen = null;
                bot.patrolArea = null;
                count++;
            }
        }

        ok(
            context,
            count
                + " Bot(s) suchen "
                + target.getName().getString()
                + ": streifen umher und greifen erst an, wenn sie ihn sehen (Blickfeld + Sichtlinie), dann bis in Reichweite."
        );
        return count;
    }

    private static int deathSound(CommandContext<CommandSourceStack> context, String value) throws CommandSyntaxException {
        BotDefaults defaults = BotDefaults.get();
        if (value.equalsIgnoreCase("off")) {
            defaults.deathSound = "";
        } else if (value.equalsIgnoreCase("on")) {
            defaults.deathSound = "minecraft:block.beacon.deactivate";
        } else {
            String id = value.trim().contains(":") ? value.trim() : "minecraft:" + value.trim();
            if (BuiltInRegistries.SOUND_EVENT.get(Identifier.tryParse(id)) == null) {
                throw fail(
                    "Unbekannter Sound: " + id + " (z.B. block.beacon.deactivate, block.beacon.power_select, entity.lightning_bolt.thunder, block.bell.use)"
                );
            }

            defaults.deathSound = id;
        }

        defaults.save();
        ok(context, defaults.deathSound.isEmpty() ? "Todes-Sound AUS." : "Todes-Sound bei Bot-Tod fuer alle Spieler: " + defaults.deathSound);
        return 1;
    }

    private static int chat(CommandContext<CommandSourceStack> context, boolean on) {
        BotDefaults.get().chat = on;
        BotDefaults.get().save();
        ok(context, on ? "Chat AN: Bots kommentieren Kills/Tode und plappern ab und zu (config/botsystem-chat.json)." : "Chat AUS.");
        return 1;
    }

    private static int respawn(CommandContext<CommandSourceStack> context, boolean on, int seconds) {
        BotDefaults defaults = BotDefaults.get();
        defaults.respawn = on;
        if (seconds > 0) {
            defaults.respawnSeconds = seconds;
        }

        defaults.save();
        ok(
            context,
            on
                ? "Respawn AN: tote Bots erscheinen nach "
                    + defaults.respawnSeconds
                    + " s am Spawn (oder /bot spawnpoint <Team>) wieder - mit Kit, Team und Auftrag."
                : "Respawn AUS: tote Bots verschwinden."
        );
        return 1;
    }

    private static int spawnPoint(CommandContext<CommandSourceStack> context, String team, Vec3 where, boolean clear) throws CommandSyntaxException {
        String key = teamName(team).toLowerCase(Locale.ROOT);
        if (clear) {
            bots().spawnPoints.remove(key);
            ok(context, "Spawnpunkt von " + team + " geloescht (Welt-Spawn).");
            return 1;
        } else {
            Vec3 spot = where != null ? where : context.getSource().getPosition();
            bots().spawnPoints.put(key, spot);
            ok(context, "Spawnpunkt von " + team + ": " + (int)spot.x + " " + (int)spot.y + " " + (int)spot.z + ".");
            return 1;
        }
    }

    private static int mode(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Stance stance = Stance.parse(StringArgumentType.getString(context, "haltung"));
        if (stance == null) {
            throw fail("/bot mode <auswahl> passive|defensive|aggressive");
        }

        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));

        for (BotPlayer bot : targets) {
            bot.stance = stance;
            bot.stanceAnchor = stance == Stance.DEFENSIVE ? bot.position() : null;
            if (stance == Stance.PASSIVE) {
                bot.currentTarget = null;
                bot.fightMode = false;
            } else {
                bot.nextTargetTick = 0L;
            }
        }

        List<MobUnits.Unit> units = selectUnits(context, SelectorArgumentType.get(context, "auswahl"));

        for (MobUnits.Unit unit : units) {
            unit.stance = stance;
            unit.stanceAnchor = stance == Stance.DEFENSIVE ? unit.mob.position() : null;
            if (stance == Stance.PASSIVE) {
                unit.mob.setTarget(null, TargetReason.CUSTOM);
                unit.approach = null;
            }
        }
        String what = switch (stance) {
            case PASSIVE -> "passiv - laufen nur herum, wehren sich hoechstens";
            case DEFENSIVE -> "defensiv - halten Posten/Grenze/Gebiet, Bogenschuetzen schiessen von der Stellung, Nahkaempfer nur ein Stueck weit";
            case AGGRESSIVE -> "aggressiv - greifen an, was sie sehen";
        };
        ok(context, targets.size() + units.size() + " Bot(s)/Mob(s) jetzt " + what + ".");
        return targets.size() + units.size();
    }

    /**
     * A BBS film command clip, run from the actor's position like BBS would. Wrapped in
     * FilmScene so whatever it does to bots is undone when the film stops or restarts.
     */
    private static int filmCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource().withPosition(Vec3Argument.getVec3(context, "pos"));
        String command = StringArgumentType.getString(context, "befehl");
        if (command.startsWith("/")) {
            command = command.substring(1);
        }

        if (command.toLowerCase(Locale.ROOT).startsWith("bot film")) {
            throw fail("Film-Befehle koennen nicht verschachtelt werden.");
        }

        // Run it right here: performPrefixedCommand would only queue it behind this command,
        // i.e. after endCommand - and FilmScene would never see what it changed.
        int result;
        FilmScene.beginCommand();
        try {
            result = source.getServer().getCommands().getDispatcher().execute(command, source);
        } finally {
            FilmScene.endCommand();
        }

        return result;
    }

    private static int neverFight(CommandContext<CommandSourceStack> context, boolean on) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));

        for (BotPlayer bot : targets) {
            bot.neverFight = on;
            if (on) {
                bot.fightMode = false;
                bot.currentTarget = null;
                bot.hostileTeams.clear();
                bot.enemyTeams.clear();
                bot.responseTeam = null;
            }
        }

        ok(
            context,
            targets.size()
                + " Bot(s) "
                + (on
                    ? "kaempfen jetzt NIE mehr - egal was passiert, egal welcher Modus/Befehl/Alarm (auch nicht in Notwehr)."
                    : "koennen wieder kaempfen (je nach Modus/Befehl).")
        );
        return targets.size();
    }

    /** "100s", "5m", "200t" (ticks), "30" (seconds), "inf"/"forever" -> ticks; -1 when unreadable. */
    private static long durationTicks(String text) {
        String value = text.toLowerCase(Locale.ROOT).trim();
        if (value.equals("inf") || value.equals("forever") || value.equals("ewig") || value.equals("endlos")) {
            return 20L * 60L * 60L * 24L;
        }

        try {
            if (value.endsWith("t")) {
                return Math.max(1L, Long.parseLong(value.substring(0, value.length() - 1)));
            } else if (value.endsWith("m")) {
                return Math.round(Double.parseDouble(value.substring(0, value.length() - 1)) * 1200.0);
            } else if (value.endsWith("h")) {
                return Math.round(Double.parseDouble(value.substring(0, value.length() - 1)) * 72000.0);
            } else if (value.endsWith("s")) {
                return Math.round(Double.parseDouble(value.substring(0, value.length() - 1)) * 20.0);
            } else {
                return Math.round(Double.parseDouble(value) * 20.0);
            }
        } catch (NumberFormatException broken) {
            return -1L;
        }
    }

    private static int emote(CommandContext<CommandSourceStack> context, String durationText) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        String wanted = StringArgumentType.getString(context, "art").toLowerCase(Locale.ROOT);

        int kind = switch (wanted) {
            case "cheer", "jubeln", "feiern" -> 1;
            case "nod", "nicken", "ja" -> 2;
            case "no", "nein", "schuetteln" -> 3;
            case "wave", "winken", "hallo" -> 4;
            case "bow", "verbeugen", "knien" -> 5;
            case "point", "zeigen" -> 6;
            case "clap", "klatschen", "applaus" -> 7;
            case "laugh", "lachen" -> 8;
            case "shield", "schild", "block", "tuff", "tough" -> 9;
            case "aura", "aurafarm", "aurafarming", "cool" -> 10;
            case "dance", "tanzen" -> 11;
            case "salute", "salutieren", "gruss" -> 12;
            case "flex", "posen", "pose" -> 13;
            case "think", "denken", "nachdenken" -> 14;
            case "crouch", "ducken", "sneak", "tbag" -> 15;
            case "stop", "aus" -> 0;
            default -> -1;
        };
        if (kind < 0) {
            throw fail("Gesten: cheer, nod, no, wave, bow, point, clap, laugh, shield, aura, dance, salute, flex, think, crouch, stop.");
        }

        long chosen = durationText == null ? 0L : durationTicks(durationText);
        if (chosen < 0L) {
            throw fail("Dauer z.B. 30s, 100s, 5m, 200t oder inf.");
        }

        Vec3 look = context.getSource().getPosition();
        long tick = context.getSource().getServer().getTickCount();

        long duration = chosen > 0L ? chosen : switch (kind) {
            case 1 -> 70L;
            case 2, 3 -> 40L;
            case 4 -> 50L;
            case 5 -> 55L;
            case 6 -> 45L;
            case 7 -> 80L;
            case 9, 10, 13, 14 -> 200L;
            case 11 -> 160L;
            case 12 -> 50L;
            default -> 60L;
        };

        for (BotPlayer bot : targets) {
            if (kind == 0) {
                // Keep the kind for one more tick so the emote cleans up (shield back, sneak off).
                bot.emoteUntil = 0L;
                bot.emoteStart = 0L;
            } else {
                if (bot.emoteKind != 0 && bot.emoteKind != kind && bot.emoteOffhand != null) {
                    bot.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, bot.emoteOffhand);
                    bot.emoteOffhand = null;
                    bot.stopUsingItem();
                }

                bot.emoteKind = kind;
                long delay = ThreadLocalRandom.current().nextLong(0L, 16L);
                bot.emoteStart = tick + delay;
                bot.emoteUntil = bot.emoteStart + duration + (chosen > 0L ? 0L : ThreadLocalRandom.current().nextLong(0L, 20L));
                // Pointing/bowing/saluting/shield face whoever gave the order - unless a compass heading is set.
                bot.emoteLook = (kind == 6 || kind == 5 || kind == 9 || kind == 12) && Float.isNaN(bot.lookYaw) ? look : null;
            }
        }

        ok(context, targets.size() + " Bot(s): " + (kind == 0 ? "Geste beendet." : wanted + (chosen > 0L ? " fuer " + (chosen / 20L) + " s." : ".")));
        return targets.size();
    }

    private static int assemble(CommandContext<CommandSourceStack> context, double wanted) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        Vec3 centre = Vec3Argument.getVec3(context, "ziel");
        ServerLevel world = context.getSource().getLevel();
        Random random = ThreadLocalRandom.current();
        double radius = wanted > 0.0 ? wanted : Math.max(3.0, Math.sqrt(Math.max(1, targets.size())) * 1.9);
        int placed = 0;
        int index = 0;
        List<BotPlayer> pool = new ArrayList<>(targets);
        Collections.shuffle(pool, random);

        for (BotPlayer bot : pool) {
            int ring = 0;
            int seen = index;

            int capacity;
            for (capacity = Math.max(6, (int)(radius * 3.0)); seen >= capacity; capacity = Math.max(6, (int)((radius + ++ring * 2.0) * 3.0))) {
                seen -= capacity;
            }

            double ringRadius = radius + ring * 2.0 + random.nextDouble() * 0.9;
            double angle = Math.toRadians(-120.0 + 240.0 * (capacity <= 1 ? 0.5 : (double)seen / (capacity - 1))) + random.nextDouble() * 0.12;
            double x = centre.x + Math.cos(angle) * ringRadius;
            double z = centre.z + Math.sin(angle) * ringRadius;
            Vec3 spot = Ground.findDry(world, x, z, centre.y);
            if (spot == null) {
                spot = Ground.find(world, x, z, centre.y);
            }

            bot.post = spot;
            bot.postYaw = Float.NaN;
            bot.walkGoal = spot;
            bot.walkDeadline = context.getSource().getServer().getTickCount() + 2400L;
            bot.walkGroup = null;
            bot.marching = false;
            bot.lookAtPos = new Vec3(centre.x, centre.y + 1.6, centre.z);
            bot.lookAtId = null;
            bot.lookAtTeam = null;
            index++;
            placed++;
        }

        ok(context, placed + " Bot(s) versammeln sich um " + (int)centre.x + " " + (int)centre.z + " und schauen hin (Radius " + (int)radius + ").");
        return placed;
    }

    private static int standOn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        String blockName = StringArgumentType.getString(context, "block").toLowerCase(Locale.ROOT);
        Identifier id = Identifier.tryParse(blockName.contains(":") ? blockName : "minecraft:" + blockName);
        net.minecraft.world.level.block.Block block = id == null ? null : BuiltInRegistries.BLOCK.getValue(id);
        if (block != null && id != null) {
            String[] tokens = StringArgumentType.getString(context, "ecken").trim().split("\\s+");
            if (tokens.length != 4) {
                throw fail("/bot standon <auswahl> <block> <x1> <z1> <x2> <z2>");
            }

            Vec3 base = context.getSource().getPosition();
            double x1 = coordinate(tokens[0], base.x);
            double z1 = coordinate(tokens[1], base.z);
            double x2 = coordinate(tokens[2], base.x);
            double z2 = coordinate(tokens[3], base.z);
            int minX = (int)Math.floor(Math.min(x1, x2));
            int maxX = (int)Math.floor(Math.max(x1, x2));
            int minZ = (int)Math.floor(Math.min(z1, z2));
            int maxZ = (int)Math.floor(Math.max(z1, z2));
            if ((long)(maxX - minX + 1) * (maxZ - minZ + 1) > 65536L) {
                throw fail("Das Rechteck ist zu gross (hoechstens 256 x 256).");
            }

            ServerLevel world = context.getSource().getLevel();
            List<Vec3> spots = new ArrayList<>();
            MutableBlockPos cursor = new MutableBlockPos();

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (world.hasChunkAt(new BlockPos(x, 64, z))) {
                        int top = world.getHeight(Types.MOTION_BLOCKING, x, z);

                        for (int y = top; y >= Math.max(world.getMinY(), top - 24); y--) {
                            cursor.set(x, y, z);
                            if (world.getBlockState(cursor).getBlock() == block) {
                                cursor.set(x, y + 1, z);
                                boolean freeFeet = world.getBlockState(cursor).getCollisionShape(world, cursor).isEmpty();
                                cursor.set(x, y + 2, z);
                                boolean freeHead = world.getBlockState(cursor).getCollisionShape(world, cursor).isEmpty();
                                if (freeFeet && freeHead) {
                                    spots.add(new Vec3(x + 0.5, y + 1.0, z + 0.5));
                                }
                                break;
                            }
                        }
                    }
                }
            }

            if (spots.isEmpty()) {
                throw fail("Im Rechteck steht kein freier " + blockName + " (zwei Bloecke Platz darueber noetig).");
            }

            Random random = ThreadLocalRandom.current();
            Collections.shuffle(spots, random);
            double[] area = new double[]{minX, minZ, maxX + 1.0, maxZ + 1.0};
            int placed = 0;

            for (BotPlayer bot : targets) {
                Vec3 spot = spots.get(placed % spots.size());
                bot.teleportTo(world, spot.x, spot.y, spot.z, Collections.emptySet(), (float)(random.nextDouble() * 360.0), 0.0F, false);
                bot.path = null;
                bot.walkGoal = null;
                bot.post = spot;
                bot.stayArea = area;
                bot.guardHold = bot.followTarget != null;
                placed++;
            }

            ok(
                context,
                placed
                    + " Bot(s) stehen auf "
                    + blockName
                    + " ("
                    + spots.size()
                    + " passende Stellen gefunden)."
                    + (placed > spots.size() ? " Mehr Bots als Stellen - einige teilen sich einen Platz." : "")
            );
            return placed;
        } else {
            throw fail("Unbekannter Block: " + blockName);
        }
    }

    private static int train(CommandContext<CommandSourceStack> context, int runs) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        Vec3 from = Vec3Argument.getVec3(context, "von");
        Vec3 to = Vec3Argument.getVec3(context, "nach");
        long tick = context.getSource().getServer().getTickCount();

        for (BotPlayer bot : targets) {
            bot.trainA = from;
            bot.trainB = to;
            bot.trainRunsLeft = runs;
            bot.trainToB = true;
            bot.trainLegStart = 0L;
            bot.trainTimes.clear();
            bot.walkGoal = null;
            bot.orderUntil = tick + 36000L;
        }

        ok(
            context,
            targets.size()
                + " Bot(s) ueben "
                + runs
                + " Laeufe zwischen "
                + (int)from.x
                + "/"
                + (int)from.z
                + " und "
                + (int)to.x
                + "/"
                + (int)to.z
                + " - Zeiten stehen im Server-Log ([train]), Gelerntes in botsystem-nav.json."
        );
        return targets.size();
    }

    private static int life(CommandContext<CommandSourceStack> context, boolean on, double radius) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));

        for (BotPlayer bot : targets) {
            bot.lifeMode = on;
            if (on) {
                bot.lifeCentre = bot.position();
                bot.lifeRadius = radius > 0.0 ? radius : bot.lifeRadius;
                bot.nextLifePick = 0L;
                bot.lifePauseUntil = 0L;
                bot.post = null;
            } else {
                bot.lifePartner = null;
                bot.walkGoal = null;
            }
        }

        ok(
            context,
            targets.size()
                + (on ? " Bot(s) leben jetzt vor sich hin (Umkreis " + (int)(radius > 0.0 ? radius : 24.0) + " Bloecke)." : " Bot(s) stehen wieder still.")
        );
        return targets.size();
    }

    private static int gymBuild(CommandContext<CommandSourceStack> context, Vec3 at) throws CommandSyntaxException {
        Vec3 base = at;
        if (base == null) {
            base = context.getSource().getPosition();
            if (context.getSource().getEntity() != null) {
                base = base.add(context.getSource().getEntity().getLookAngle().scale(8.0));
            }
        }

        Gym.Course course = Gym.build(context.getSource().getLevel(), BlockPos.containing(base));
        ok(
            context,
            "Parcours mit "
                + course.lanes.size()
                + " Bahnen gebaut (Mauern 3/6/10, Luecke, Grube, Treppe, Tuer, Tunnel, Zickzack, Wasser). Jetzt: /bot gym start <auswahl>"
        );
        return course.lanes.size();
    }

    private static int gymBuildRandom(CommandContext<CommandSourceStack> context, long seed) throws CommandSyntaxException {
        Vec3 base = context.getSource().getPosition();
        if (context.getSource().getEntity() != null) {
            base = base.add(context.getSource().getEntity().getLookAngle().scale(8.0));
        }

        long used = seed != 0L ? seed : ThreadLocalRandom.current().nextLong() | 1L;
        Gym.Course course = Gym.build(context.getSource().getLevel(), BlockPos.containing(base), used);
        StringBuilder names = new StringBuilder();

        for (Gym.Lane lane : course.lanes) {
            names.append(lane.name).append("  ");
        }

        ok(context, "Zufalls-Parcours (seed " + used + ") mit " + course.lanes.size() + " Bahnen: " + names.toString().trim());
        info(context, "Tipp: /bot gym shuffle <minuten> baut ihn regelmaessig neu - dann lernen sie Hindernisse statt Strecken.", ChatFormatting.GRAY);
        return course.lanes.size();
    }

    private static int gymStart(CommandContext<CommandSourceStack> context, double radius, int minutes) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        String world = NavMemory.worldId(context.getSource().getLevel());
        boolean course = radius <= 0.0 && Gym.course(world) != null;
        long now = context.getSource().getServer().getTickCount();

        for (BotPlayer bot : targets) {
            bot.gymActive = true;
            bot.gymUseCourse = course;
            bot.gymCentre = course ? null : context.getSource().getPosition();
            bot.gymRadius = radius > 0.0 ? radius : 40.0;
            bot.gymUntil = minutes > 0 ? now + minutes * 60L * 20L : -1L;
            bot.walkGoal = null;
            bot.post = null;
            bot.followTarget = null;
            bot.guardHold = false;
        }

        ok(
            context,
            targets.size()
                + " Bot(s) trainieren "
                + (course ? "auf dem Parcours" : "frei im Umkreis von " + (int)(radius > 0.0 ? radius : 40.0) + " Bloecken")
                + (minutes > 0 ? " fuer " + minutes + " Minuten" : " ohne Ende (Stopp: /bot gym stop)")
                + ". Fortschritt kommt jede Minute in den Chat."
        );
        return targets.size();
    }

    private static int cage(CommandContext<CommandSourceStack> context, int episodes, int size, int exits, int seconds) throws CommandSyntaxException {
        return cage(context, episodes, size, exits, seconds, 0L);
    }

    private static int cage(CommandContext<CommandSourceStack> context, int episodes, int size, int exits, int seconds, long seed) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        if (targets.isEmpty()) {
            throw fail("Keine Bots ausgewaehlt.");
        }

        Vec3 at = context.getSource().getPosition();
        if (context.getSource().getEntity() != null) {
            at = at.add(context.getSource().getEntity().getLookAngle().scale(size + 10.0));
        }

        for (BotPlayer bot : targets) {
            bot.gymActive = false;
            bot.followTarget = null;
            bot.post = null;
        }

        ExitSense.startCage(
            context.getSource().getLevel(),
            BlockPos.containing(at),
            targets,
            episodes,
            size,
            exits,
            seconds,
            context.getSource().getServer().getTickCount(),
            seed
        );
        ok(
            context,
            targets.size()
                + " Bot(s) im Kaefig-Training: "
                + episodes
                + " Episoden, Kaefig "
                + size
                + "x"
                + size
                + ", "
                + exits
                + " Ausgang/Ausgaenge, "
                + seconds
                + " s Zeit. Jede Episode ein neuer Kaefig - Ergebnisse kommen in den Chat."
        );
        return targets.size();
    }

    private static int escape(CommandContext<CommandSourceStack> context, double radius) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        long now = context.getSource().getServer().getTickCount();

        for (BotPlayer bot : targets) {
            ExitSense.Episode episode = new ExitSense.Episode();
            episode.start = bot.position();
            episode.radius = radius;
            episode.startTick = now;
            episode.deadline = now + 3600L;
            bot.escape = episode;
            bot.walkGoal = null;
        }

        ok(context, targets.size() + " Bot(s) suchen den Weg hinaus (Radius " + (int)radius + ").");
        return targets.size();
    }

    private static int learnExploreToggle(CommandContext<CommandSourceStack> context, boolean on) {
        BotDefaults.get().learnExplore = on;
        BotDefaults.get().save();
        ok(context, on ? "Ohne Pfad erkunden sie mit gelerntem Gespuer." : "Gelerntes Erkunden aus.");
        return 1;
    }

    private static int learnAlways(CommandContext<CommandSourceStack> context, boolean on) {
        BotDefaults.get().learnAlways = on;
        BotDefaults.get().save();
        ok(context, on ? "Bots lernen aus jedem Laufbefehl (nicht nur im Training)." : "Gelernt wird nur noch in /bot train.");
        return 1;
    }

    private static int crowdSprint(CommandContext<CommandSourceStack> context, boolean on) {
        BotDefaults.get().followSprint = on;
        BotDefaults.get().save();
        ok(context, on ? "Nachzuegler sprinten, um aufzuschliessen." : "Begleiter gehen immer, auch wenn sie weit hinten sind.");
        return 1;
    }

    private static int fidget(CommandContext<CommandSourceStack> context, boolean on) {
        BotDefaults.get().idleFidget = on;
        BotDefaults.get().save();
        ok(
            context,
            on
                ? "Zappeln im Stehen an: Bots schleichen kurz, huepfen, machen einen Schritt, wechseln das Item."
                : "Zappeln im Stehen aus: Bots stehen ruhig (schauen sich nur noch um)."
        );
        return 1;
    }

    private static int human(CommandContext<CommandSourceStack> context, boolean on) {
        BotDefaults.get().humanMovement = on;
        BotDefaults.get().save();
        ok(context, on ? "Menschliche Bewegung AN (Zoegern, Umschauen)." : "Menschliche Bewegung AUS.");
        return 1;
    }

    private static int enemy(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Double[] sight = new Double[1];
        List<BotCommands.Faction> factions = parseFactions(context, StringArgumentType.getString(context, "parteien"), sight);
        if (factions.size() != 2) {
            throw fail("Genau zwei Parteien: /bot enemy @team:Rot , @team:Blau [sight:30]");
        }

        int total = 0;

        for (int i = 0; i < 2; i++) {
            BotCommands.Faction own = factions.get(i);
            BotCommands.Faction other = factions.get(1 - i);

            for (String a : own.teams) {
                for (String b : other.teams) {
                    Bots.setTeamRelation(a, b, false, true);
                }
            }

            Set<UUID> otherLoose = other.looseIds();

            for (BotPlayer bot : own.bots) {
                other.markAsEnemyOf(bot, false);
                bot.allyIds.removeAll(otherLoose);
                own.befriend(bot);
                if (sight[0] != null) {
                    bot.sightRange = sight[0];
                } else if (bot.sightRange <= 0.0) {
                    bot.sightRange = 24.0;
                }
            }

            for (MobUnits.Unit unit : own.units) {
                unit.hostileTeams.addAll(other.teams);
                unit.targetIds.removeAll(otherLoose);
                if (sight[0] != null) {
                    unit.sightRange = sight[0];
                } else if (unit.sightRange <= 0.0) {
                    unit.sightRange = 24.0;
                }
            }

            total += own.bots.size() + own.units.size();
        }

        ok(
            context,
            summary(factions, " <-> ")
                + " sind jetzt Feinde ("
                + total
                + " Bots/Mobs): wer den anderen sieht, greift an - je nach /bot mode; ab 3 gesichteten Feinden rueckt das ganze Team aus. Sichtweite: "
                + (sight[0] != null ? (int)sight[0].doubleValue() + " Bloecke" : "wie eingestellt (mind. 24)")
        );
        return total;
    }

    private static int enemyStop(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));

        for (BotPlayer bot : targets) {
            bot.clearFight();
            bot.fightMode = false;
            bot.hostileIds.clear();
            bot.hostileTeams.clear();
            Bots.clearTeamRelations(bot.team);
        }

        List<MobUnits.Unit> units = selectUnits(context, SelectorArgumentType.get(context, "auswahl"));
        units.forEach(MobUnits.Unit::clearFight);
        ok(context, targets.size() + units.size() + " Bot(s)/Mob(s) haben keine Feinde mehr.");
        return targets.size() + units.size();
    }

    private static int hostile(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Double[] sight = new Double[1];
        List<BotCommands.Faction> factions = parseFactions(context, StringArgumentType.getString(context, "parteien"), sight);
        if (factions.size() != 2) {
            throw fail("Genau zwei Parteien: /bot hostile @team:Rot , @team:Blau @team:Gelb [sight:20]");
        }

        BotCommands.Faction own = factions.get(0);
        BotCommands.Faction enemies = factions.get(1);

        for (String a : own.teams) {
            for (String b : enemies.teams) {
                Bots.setTeamRelation(a, b, false, true);
            }
        }

        Set<UUID> enemyLoose = enemies.looseIds();

        for (BotPlayer bot : own.bots) {
            enemies.markAsEnemyOf(bot, false);
            bot.allyIds.removeAll(enemyLoose);
            own.befriend(bot);
            if (sight[0] != null) {
                bot.sightRange = sight[0];
            }
        }

        for (MobUnits.Unit unit : own.units) {
            unit.hostileTeams.addAll(enemies.teams);
            unit.targetIds.removeAll(enemyLoose);
            if (sight[0] != null) {
                unit.sightRange = sight[0];
            }
        }

        int total = own.bots.size() + own.units.size();
        String sightText = own.bots.isEmpty()
            ? (own.units.get(0).sightRange > 0.0 ? (int)own.units.get(0).sightRange + " Bloecke" : "aus (erst nach Treffer)")
            : describeSight(own.bots);
        ok(context, total + " Bot(s)/Mob(s) kennen jetzt " + String.join(" + ", enemies.labels) + " als Feind. Sichtweite: " + sightText);
        return total;
    }

    private static boolean looksLikeCoordinate(String token) {
        return token.matches("~?-?\\d*(\\.\\d+)?") && !token.isEmpty();
    }

    private static double coordinate(String token, double base) throws CommandSyntaxException {
        if (token.startsWith("~")) {
            String rest = token.substring(1);
            return base + (rest.isEmpty() ? 0.0 : Double.parseDouble(rest));
        }

        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException error) {
            throw fail("Koordinate ungueltig: " + token);
        }
    }

    private static final String[][] COMPASS = {
        {"south", "sued", "s"}, {"south_west", "suedwest", "sw"}, {"west", "west", "w"}, {"north_west", "nordwest", "nw"},
        {"north", "nord", "n"}, {"north_east", "nordost", "ne"}, {"east", "ost", "e"}, {"south_east", "suedost", "se"}
    };

    /**
     * "north", "north_east", ... (also German and short: nord, nordost, n, ne ...) and
     * "forward"/"off" as literals - exact words win over the player-name argument beside them.
     */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ?> withDirections(
        com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ?> node
    ) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (String[] group : COMPASS) {
            names.addAll(List.of(group));
            names.add(group[0].replace("_", ""));
        }

        names.addAll(List.of("forward", "off", "vorne", "aus"));
        for (String name : names) {
            node.then(Commands.literal(name).executes(context -> lookDirection(context, name)));
        }

        return node;
    }

    private static int lookDirection(CommandContext<CommandSourceStack> context, String name) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        String wanted = name.toLowerCase(Locale.ROOT).replace("-", "_");
        if (wanted.equals("off") || wanted.equals("forward") || wanted.equals("aus") || wanted.equals("vorne")) {
            for (BotPlayer bot : targets) {
                bot.lookYaw = Float.NaN;
                bot.lookAtPos = null;
                bot.lookAtId = null;
                bot.lookAtTeam = null;
            }

            ok(context, targets.size() + " Bot(s) schauen wieder frei.");
            return targets.size();
        }

        float yaw = Float.NaN;
        String label = wanted;
        for (int i = 0; i < COMPASS.length; i++) {
            for (String alias : COMPASS[i]) {
                if (alias.equals(wanted) || alias.replace("_", "").equals(wanted.replace("_", ""))) {
                    yaw = i * 45.0F;
                    label = COMPASS[i][0];
                }
            }
        }

        if (Float.isNaN(yaw)) {
            throw fail("Richtung: north, north_east, east, south_east, south, south_west, west, north_west (oder forward/off).");
        }

        for (BotPlayer bot : targets) {
            bot.lookYaw = yaw;
            bot.lookAtId = null;
            bot.lookAtTeam = null;
            bot.lookAtPos = null;
        }

        ok(context, targets.size() + " Bot(s) schauen nach " + label + ".");
        return targets.size();
    }

    private static int lookAtPos(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        Vec3 point = Vec3Argument.getVec3(context, "ort");

        for (BotPlayer bot : targets) {
            bot.lookAtPos = point;
            bot.lookAtId = null;
            bot.lookAtTeam = null;
            bot.lookYaw = Float.NaN;
        }

        ok(context, targets.size() + " Bot(s) schauen jetzt nach " + (int)point.x + " " + (int)point.y + " " + (int)point.z + ".");
        return targets.size();
    }

    private static int stayIn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        List<MobUnits.Unit> stayUnits = selectUnits(context, SelectorArgumentType.get(context, "auswahl"));
        String[] tokens = StringArgumentType.getString(context, "ecken").trim().split("\\s+");
        boolean teleport = tokens.length > 0
            && (tokens[0].equalsIgnoreCase("tp") || tokens[0].equalsIgnoreCase("place") || tokens[0].equalsIgnoreCase("setzen"));
        int offset = teleport ? 1 : 0;
        Vec3 base = context.getSource().getPosition();
        double[] levels = yRange(tokens, offset, base.y);
        if (levels != null) {
            offset += 3;
        }

        if (tokens.length - offset >= 4 && (tokens.length - offset) % 4 == 0) {
            List<double[]> areas = new ArrayList<>();

            for (int i = offset; i + 3 < tokens.length; i += 4) {
                double x1 = coordinate(tokens[i], base.x);
                double z1 = coordinate(tokens[i + 1], base.z);
                double x2 = coordinate(tokens[i + 2], base.x);
                double z2 = coordinate(tokens[i + 3], base.z);
                areas.add(box(x1, z1, x2, z2, levels));
            }

            // Mob units (also mob guards): the area becomes their patrol area - they stay and
            // wander in it (with "tp" they are put in first).
            for (MobUnits.Unit unit : stayUnits) {
                double[] area = areas.get(Math.floorMod(unit.mob.getId(), areas.size()));
                unit.patrolArea = area;
                unit.post = null;
                unit.goal = null;
                unit.nextPatrolPick = 0L;
                if (teleport) {
                    Vec3 spot = Ground.randomDryLoading(context.getSource().getLevel(), area, context.getSource().getPosition().y, ThreadLocalRandom.current());
                    if (spot != null) {
                        unit.mob.snapTo(spot.x, spot.y, spot.z, unit.mob.getYRot(), 0.0F);
                        unit.mob.getNavigation().stop();
                    }
                }
            }

            if (!teleport) {
                for (BotPlayer bot : targets) {
                    double[] best = areas.get(0);
                    double bestDistance = Double.MAX_VALUE;

                    for (double[] area : areas) {
                        double dx = Math.max(0.0, Math.max(area[0] - bot.getX(), bot.getX() - area[2]));
                        double dz = Math.max(0.0, Math.max(area[1] - bot.getZ(), bot.getZ() - area[3]));
                        if (dx * dx + dz * dz < bestDistance) {
                            bestDistance = dx * dx + dz * dz;
                            best = area;
                        }
                    }

                    bot.stayArea = best;
                    bot.guardHold = bot.followTarget != null;
                }

                double[] area = areas.get(0);
                ok(
                    context,
                    targets.size()
                        + " Bot(s)" + (stayUnits.isEmpty() ? "" : " + " + stayUnits.size() + " Mob(s)") + " bleiben in "
                        + (
                            areas.size() == 1
                                ? "dem Gebiet " + (int)area[0] + "/" + (int)area[1] + " bis " + (int)area[2] + "/" + (int)area[3]
                                : areas.size() + " Gebieten (jeder im naechsten)"
                        )
                        + " (Kampf nur in der Naehe, sonst zurueck)."
                );
                return targets.size();
            } else {
                ServerLevel world = context.getSource().getLevel();
                double totalArea = 0.0;

                for (double[] area : areas) {
                    totalArea += Math.max(1.0, (area[2] - area[0]) * (area[3] - area[1]));
                }

                int[] share = new int[areas.size()];
                int assigned = 0;

                for (int i = 0; i < areas.size(); i++) {
                    double[] area = areas.get(i);
                    share[i] = (int)Math.floor(targets.size() * Math.max(1.0, (area[2] - area[0]) * (area[3] - area[1])) / totalArea);
                    assigned += share[i];
                }

                for (int i = 0; assigned < targets.size(); i = (i + 1) % areas.size()) {
                    share[i]++;
                    assigned++;
                }

                Random random = ThreadLocalRandom.current();
                List<BotPlayer> pool = new ArrayList<>(targets);
                Collections.shuffle(pool, random);
                int index = 0;
                int placed = 0;

                for (int i = 0; i < areas.size(); i++) {
                    double[] area = areas.get(i);
                    List<Vec3> spots = Ground.hasHeight(area)
                        ? Ground.spreadOverLevels(world, area, share[i], random)
                        : jitteredSpots(world, area, share[i], context.getSource().getPosition().y, random);

                    for (int k = 0; k < share[i] && index < pool.size(); index++) {
                        BotPlayer bot = pool.get(index);
                        Vec3 spot = k < spots.size() ? spots.get(k) : Ground.randomDryLoading(world, area, context.getSource().getPosition().y, random);
                        if (spot == null) {
                            spot = Ground.find(world, (area[0] + area[2]) / 2.0, (area[1] + area[3]) / 2.0, context.getSource().getPosition().y);
                        }

                        float yaw = (float)(random.nextDouble() * 360.0);
                        bot.teleportTo(world, spot.x, spot.y, spot.z, Collections.emptySet(), yaw, 0.0F, false);
                        bot.path = null;
                        bot.walkGoal = null;
                        bot.post = null;
                        bot.stayArea = area;
                        bot.guardHold = bot.followTarget != null;
                        placed++;
                        k++;
                    }
                }

                ok(
                    context,
                    placed
                        + " Bot(s) in "
                        + areas.size()
                        + " Gebiet(e) gesetzt und daran gebunden (Kampf nur in der Naehe, sonst zurueck)."
                        + (
                            levels != null
                                ? " Hoehe "
                                    + (int)Math.min(levels[0], levels[1])
                                    + " bis "
                                    + (int)Math.max(levels[0], levels[1])
                                    + ": auf alle Stockwerke verteilt."
                                : ""
                        )
                );
                return placed;
            }
        } else {
            throw fail("/bot stayin <auswahl> [tp] [y <y1> <y2>] <x1> <z1> <x2> <z2> [weitere Rechtecke ...]  (y = alle Stockwerke dazwischen)");
        }
    }

    private static double[] yRange(String[] tokens, int at, double baseY) throws CommandSyntaxException {
        return tokens.length <= at + 2 || !tokens[at].equalsIgnoreCase("y") && !tokens[at].equalsIgnoreCase("3d") && !tokens[at].equalsIgnoreCase("hoehe")
            ? null
            : new double[]{coordinate(tokens[at + 1], baseY), coordinate(tokens[at + 2], baseY)};
    }

    private static double[] box(double x1, double z1, double x2, double z2, double[] levels) {
        return levels == null
            ? new double[]{Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2)}
            : new double[]{
                Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2), Math.min(levels[0], levels[1]), Math.max(levels[0], levels[1])
            };
    }

    private static List<Vec3> jitteredSpots(ServerLevel world, double[] area, int count, double referenceY, Random random) {
        List<Vec3> spots = new ArrayList<>();
        if (count <= 0) {
            return spots;
        }

        double width = Math.max(1.0, area[2] - area[0]);
        double depth = Math.max(1.0, area[3] - area[1]);
        double spacing = Math.max(1.2, Math.sqrt(width * depth / count) * 0.92);
        int columns = Math.max(1, (int)Math.floor(width / spacing));
        int rows = Math.max(1, (int)Math.floor(depth / spacing));

        while (columns * rows < count) {
            spacing *= 0.9;
            columns = Math.max(1, (int)Math.floor(width / spacing));
            rows = Math.max(1, (int)Math.floor(depth / spacing));
            if (spacing < 0.8) {
                break;
            }
        }

        double marginX = (width - (columns - 1) * spacing) / 2.0;
        double marginZ = (depth - (rows - 1) * spacing) / 2.0;
        List<double[]> cells = new ArrayList<>();

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                cells.add(new double[]{area[0] + marginX + c * spacing, area[1] + marginZ + r * spacing});
            }
        }

        Collections.shuffle(cells, random);

        for (double[] cell : cells) {
            if (spots.size() >= count) {
                break;
            }

            double jitter = spacing * 0.4;
            double x = Math.min(area[2] - 0.3, Math.max(area[0] + 0.3, cell[0] + (random.nextDouble() * 2.0 - 1.0) * jitter));
            double z = Math.min(area[3] - 0.3, Math.max(area[1] + 0.3, cell[1] + (random.nextDouble() * 2.0 - 1.0) * jitter));
            world.getChunk((int)Math.floor(x) >> 4, (int)Math.floor(z) >> 4);
            Vec3 spot = Ground.findDry(world, x, z, referenceY);
            if (spot == null) {
                spot = Ground.find(world, x, z, referenceY);
            }

            if (spot != null) {
                spots.add(spot);
            }
        }

        return spots;
    }

    private static int lookAt(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        String wanted = SelectorArgumentType.get(context, "ziel");
        String lower = wanted.toLowerCase(Locale.ROOT);
        UUID id = null;
        String team = null;
        String label;
        if (lower.equals("off") || lower.equals("stop") || lower.equals("aus")) {
            label = null;
        } else if (lower.equals("me") || lower.equals("ich") || lower.equals("@s")) {
            ServerPlayer me = context.getSource().getPlayerOrException();
            id = me.getUUID();
            label = me.getGameProfile().name();
        } else if (!lower.startsWith("@team:") && !lower.startsWith("@group:")) {
            BotPlayer bot = bots().byName(wanted).orElse(null);
            ServerPlayer player = bot == null ? context.getSource().getServer().getPlayerList().getPlayer(wanted) : null;
            if (bot == null && player == null) {
                throw fail("Kein Spieler, Bot oder Team namens " + wanted + ".");
            }

            id = bot != null ? bot.getUUID() : player.getUUID();
            label = bot != null ? bot.getGameProfile().name() : player.getGameProfile().name();
        } else {
            team = teamName(wanted.substring(wanted.indexOf(58) + 1));
            label = "Team " + team;
        }

        for (BotPlayer bot : targets) {
            bot.lookAtId = bot.getUUID().equals(id) ? null : id;
            bot.lookAtTeam = team;
            bot.lookAtPos = null;
            bot.lookYaw = Float.NaN;
        }

        List<MobUnits.Unit> units = selectUnits(context, SelectorArgumentType.get(context, "auswahl"));

        for (MobUnits.Unit unit : units) {
            UUID watch = id;
            if (watch == null && team != null) {
                LivingEntity nearest = null;
                double best = Double.MAX_VALUE;

                for (BotPlayer other : bots().all()) {
                    if (team.equalsIgnoreCase(other.team) && other.distanceToSqr(unit.mob) < best) {
                        best = other.distanceToSqr(unit.mob);
                        nearest = other;
                    }
                }

                for (MobUnits.Unit other : BotSystemMod.mobs().byTeam(team)) {
                    if (other != unit && other.mob.distanceToSqr(unit.mob) < best) {
                        best = other.mob.distanceToSqr(unit.mob);
                        nearest = other.mob;
                    }
                }

                watch = nearest == null ? null : nearest.getUUID();
            }

            unit.lookAtId = unit.mob.getUUID().equals(watch) ? null : watch;
        }

        int total = targets.size() + units.size();
        ok(context, label == null ? total + " Bot(s)/Mob(s) schauen nicht mehr gezielt." : total + " Bot(s)/Mob(s) schauen jetzt zu " + label + ".");
        return total;
    }

    private static int archers(CommandContext<CommandSourceStack> context, boolean hold) {
        BotDefaults.get().archersHold = hold;
        BotDefaults.get().save();
        ok(
            context,
            hold ? "Bogenschuetzen halten Stellung (weichen nur etwas zurueck; ohne Pfeile normaler Nahkampf)." : "Bogenschuetzen ruecken auf den Gegner vor."
        );
        return 1;
    }

    private static int drops(CommandContext<CommandSourceStack> context, boolean on, boolean both) {
        BotDefaults.get().drops = on;
        if (both) {
            BotDefaults.get().xpDrops = on;
        }

        BotDefaults.get().save();
        ok(
            context,
            on
                ? "Bots lassen beim Tod Ausruestung" + (both ? " und XP" : "") + " fallen."
                : "Bots lassen beim Tod " + (both ? "nichts" : "keine Ausruestung") + " fallen."
        );
        return 1;
    }

    private static int xpDrops(CommandContext<CommandSourceStack> context, boolean on) {
        BotDefaults.get().xpDrops = on;
        BotDefaults.get().save();
        ok(context, on ? "Bots lassen beim Tod XP fallen." : "Bots lassen beim Tod keine XP fallen.");
        return 1;
    }

    private static int build(CommandContext<CommandSourceStack> context, boolean on) {
        BotDefaults.get().build = on;
        BotDefaults.get().save();
        ok(context, on ? "Bauen AN: Bots bauen hoch und ueberbruecken, wenn sie nicht weiterkommen." : "Bauen AUS.");
        return 1;
    }

    private static int dig(CommandContext<CommandSourceStack> context, boolean on) {
        BotDefaults.get().dig = on;
        BotDefaults.get().save();
        ok(context, on ? "Graben AN: Bots schlagen Bloecke weg, wenn sie feststecken." : "Graben AUS.");
        return 1;
    }

    private static int selectors(CommandContext<CommandSourceStack> context, boolean visible) {
        BotDefaults.get().hideFromSelectors = !visible;
        BotDefaults.get().save();
        ok(
            context,
            visible ? "@a/@e sehen Bots wieder (Achtung: Datapack-Tickfunktionen laufen dann pro Bot)." : "Bots sind fuer @a/@e/@p/@r unsichtbar (Standard)."
        );
        return 1;
    }

    private static int anchors(CommandContext<CommandSourceStack> context, boolean on) {
        BotDefaults.get().anchors = on;
        BotDefaults.get().save();
        ok(context, on ? "Chunk-Anker AN: Bots halten ihre Umgebung geladen." : "Chunk-Anker AUS: Bots laufen nur in Chunks, die du selbst geladen hast.");
        return 1;
    }

    private static int hop(CommandContext<CommandSourceStack> context, boolean on) throws CommandSyntaxException {
        List<BotPlayer> targets = select(context, SelectorArgumentType.get(context, "auswahl"));
        targets.forEach(bot -> bot.sprintJump = on);
        BotDefaults.get().sprintJump = on;
        BotDefaults.get().save();
        ok(context, "Sprint-Springen für " + targets.size() + " Bot(s): " + (on ? "AN" : "AUS"));
        return targets.size();
    }

    private static int nav(CommandContext<CommandSourceStack> context, boolean on) throws CommandSyntaxException {
        List<BotPlayer> targets = select(context, SelectorArgumentType.get(context, "auswahl"));

        for (BotPlayer bot : targets) {
            bot.pathfinding = on;
            bot.path = null;
        }

        BotDefaults.get().pathfinding = on;
        BotDefaults.get().save();
        ok(context, "Wegfindung für " + targets.size() + " Bot(s): " + (on ? "AN (um Hindernisse herum)" : "AUS (geradeaus)"));
        return targets.size();
    }

    private static String describeSight(List<BotPlayer> bots) {
        Set<String> values = new LinkedHashSet<>();
        bots.forEach(bot -> values.add(bot.sightRange > 0.0 ? (int)bot.sightRange + " Bloecke" : "aus (erst nach Treffer)"));
        return String.join(" / ", values);
    }

    private static int sight(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        double range = parseSight(StringArgumentType.getString(context, "weite"));
        targets.forEach(bot -> bot.sightRange = range);
        List<MobUnits.Unit> units = selectUnits(context, SelectorArgumentType.get(context, "auswahl"));
        units.forEach(unit -> unit.sightRange = range);
        BotDefaults.get().sightRange = range;
        BotDefaults.get().save();
        ok(
            context,
            "Sichtweite für "
                + (targets.size() + units.size())
                + " Bot(s)/Mob(s): "
                + (range > 0.0 ? (int)range + " Blöcke" : "AUS - greifen erst nach dem ersten Treffer an")
        );
        return targets.size();
    }

    private static int freeForAll(CommandContext<CommandSourceStack> context, int percent) throws CommandSyntaxException {
        List<BotPlayer> scope = new ArrayList<>(
            selectLenient(context, SelectorArgumentType.get(context, "auswahl")).stream().filter(bot -> !bot.isDeadOrDying()).toList()
        );
        if (scope.size() + selectUnits(context, SelectorArgumentType.get(context, "auswahl")).size() < 2) {
            throw fail("Für ein Free-for-all braucht es mindestens zwei lebende Bots/Mobs.");
        }

        Collections.shuffle(scope);
        int count = Math.max(2, (int)Math.ceil(scope.size() * (percent / 100.0)));
        List<BotPlayer> fighters = new ArrayList<>(scope.subList(0, Math.min(count, scope.size())));

        for (MobUnits.Unit unit : selectUnits(context, SelectorArgumentType.get(context, "auswahl"))) {
            unit.clearFight();
            unit.freeForAll = true;
            unit.fightMode = true;
        }

        Set<BotPlayer> fighting = new HashSet<>(fighters);

        for (BotPlayer member : scope) {
            member.clearFight();
            member.fightMode = fighting.contains(member);
            member.nextTargetTick = 0L;
            member.freeForAll = fighting.contains(member);
        }

        ok(context, "Free-for-all gestartet: " + fighters.size() + " Bot(s) kämpfen jeder gegen jeden.");
        return fighters.size();
    }

    private static int stopFight(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));

        for (BotPlayer bot : targets) {
            bot.clearFight();
            bot.fightMode = false;
            bot.setMoveInput(0.0F, 0.0F);
            Bots.clearTeamRelations(bot.team);
        }

        selectUnits(context, SelectorArgumentType.get(context, "auswahl")).forEach(MobUnits.Unit::clearFight);
        ok(context, targets.size() + " Bot(s) kämpfen nicht mehr.");
        return targets.size();
    }

    private static int ally(CommandContext<CommandSourceStack> context, boolean on) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        List<MobUnits.Unit> targetUnits = selectUnits(context, SelectorArgumentType.get(context, "auswahl"));
        String name = SelectorArgumentType.get(context, "spieler");
        ServerPlayer friend;
        if (!name.equalsIgnoreCase("me") && !name.equalsIgnoreCase("ich")) {
            friend = context.getSource().getServer().getPlayerList().getPlayer(name);
            if (friend == null || friend instanceof BotPlayer || isSelector(name)) {
                return allyWithSelection(context, targets, targetUnits, name, on);
            }
        } else {
            friend = context.getSource().getPlayerOrException();
        }

        for (BotPlayer bot : targets) {
            if (on) {
                bot.allyIds.add(friend.getUUID());
                bot.targetIds.remove(friend.getUUID());
                if (bot.currentTarget == friend) {
                    bot.currentTarget = null;
                    bot.nextTargetTick = 0L;
                }
            } else {
                bot.allyIds.remove(friend.getUUID());
            }
        }

        ok(context, friend.getGameProfile().name() + (on ? " ist jetzt Verbündeter von " : " ist kein Verbündeter mehr von ") + targets.size() + " Bot(s).");
        return targets.size();
    }

    private static int allyWithSelection(
        CommandContext<CommandSourceStack> context, List<BotPlayer> targets, List<MobUnits.Unit> targetUnits, String other, boolean on
    ) throws CommandSyntaxException {
        List<BotPlayer> others = selectLenient(context, other);
        List<MobUnits.Unit> otherUnits = selectUnits(context, other);
        Set<String> teamsA = new LinkedHashSet<>();
        Set<String> teamsB = new LinkedHashSet<>();
        targets.forEach(botx -> {
            if (botx.team != null) {
                teamsA.add(botx.team);
            }
        });
        targetUnits.forEach(unit -> {
            if (unit.team != null) {
                teamsA.add(unit.team);
            }
        });
        others.forEach(botx -> {
            if (botx.team != null) {
                teamsB.add(botx.team);
            }
        });
        otherUnits.forEach(unit -> {
            if (unit.team != null) {
                teamsB.add(unit.team);
            }
        });
        int pairs = 0;

        for (String a : teamsA) {
            for (String b : teamsB) {
                if (!a.equalsIgnoreCase(b)) {
                    Bots.setAllied(a, b, on);
                    pairs++;
                }
            }
        }

        for (BotPlayer bot : targets) {
            for (BotPlayer mate : others) {
                if (bot != mate) {
                    if (on) {
                        bot.allyIds.add(mate.getUUID());
                        mate.allyIds.add(bot.getUUID());
                        bot.targetIds.remove(mate.getUUID());
                        mate.targetIds.remove(bot.getUUID());
                        if (bot.currentTarget == mate) {
                            bot.currentTarget = null;
                        }

                        if (mate.currentTarget == bot) {
                            mate.currentTarget = null;
                        }
                    } else {
                        bot.allyIds.remove(mate.getUUID());
                        mate.allyIds.remove(bot.getUUID());
                    }
                }
            }
        }

        String teams = pairs > 0 ? " Teams: " + String.join("+", teamsA) + " <-> " + String.join("+", teamsB) + "." : "";
        ok(
            context,
            (on ? "Verbuendet: " : "Buendnis aufgehoben: ")
                + (targets.size() + targetUnits.size())
                + " mit "
                + (others.size() + otherUnits.size())
                + " Bot(s)/Mob(s)."
                + teams
        );
        return targets.size() + others.size();
    }

    private static int immortal(CommandContext<CommandSourceStack> context, boolean on) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        targets.forEach(bot -> bot.immortal = on);
        selectUnits(context, SelectorArgumentType.get(context, "auswahl")).forEach(unit -> unit.immortal = on);
        ok(context, "Unsterblich für " + targets.size() + " Bot(s): " + (on ? "AN" : "AUS"));
        return targets.size();
    }

    private static int give(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        String kit = StringArgumentType.getString(context, "kit");
        Kits.Kit set = kits().find(kit).orElseThrow(() -> fail("Kits: " + kitList()));
        targets.forEach(set::applyTo);

        for (MobUnits.Unit unit : selectUnits(context, SelectorArgumentType.get(context, "auswahl"))) {
            set.applyTo(unit.mob);
        }

        int total = targets.size() + selectUnits(context, SelectorArgumentType.get(context, "auswahl")).size();
        ok(context, "Kit " + kit + " an " + total + " Bot(s)/Mob(s) gegeben.");
        return total;
    }

    private static int skill(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> targets = select(context, SelectorArgumentType.get(context, "auswahl"));
        String value = StringArgumentType.getString(context, "stufe");
        List<Double> tierRange = value.contains("-") || BotSkill.Rank.parse(value) == null ? de.fiden.botsystem.bot.Tiers.range(value) : null;
        if (tierRange != null && !tierRange.isEmpty() && !value.equalsIgnoreCase("random") && !value.equalsIgnoreCase("zufall")) {
            for (BotPlayer bot : targets) {
                bot.skill.setSkill(de.fiden.botsystem.bot.Tiers.pick(tierRange));
            }

            ok(context, targets.size() + " Bot(s): Stufe " + value.toUpperCase(Locale.ROOT) + ".");
            return targets.size();
        }

        BotSkill.Rank rank = BotSkill.Rank.parse(value);
        Double fixed = null;
        if (rank == null && !value.equalsIgnoreCase("random") && !value.equalsIgnoreCase("zufall")) {
            try {
                fixed = Double.parseDouble(value.replace("%", "")) / 100.0;
            } catch (NumberFormatException error) {
                throw fail("Skill: Noob, Okay, LT5, HT5, LT4, HT4, LT3, HT3, 0-100 oder random.");
            }

            if (fixed < 0.0 || fixed > 1.0) {
                throw fail("Skill muss zwischen 0 und 100 liegen.");
            }
        }

        for (BotPlayer bot : targets) {
            if (rank != null) {
                bot.skill.setRank(rank);
            } else if (fixed != null) {
                bot.skill.setSkill(fixed);
            } else {
                bot.skill.setSkill(ThreadLocalRandom.current().nextDouble(0.55, 0.9));
            }
        }

        String label = rank != null ? rank.label : (fixed != null ? Math.round(fixed * 100.0) + "%" : "zufällig");
        ok(context, "Skill für " + targets.size() + " Bot(s): " + label);
        return targets.size();
    }

    private static int randomSkin(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Bots bots = bots();
        List<BotPlayer> targets = select(context, SelectorArgumentType.get(context, "auswahl"));

        for (BotPlayer old : targets) {
            bots.profileFor(old.getGameProfile().name(), bots.nextPoolSkin()).thenAccept(profile -> old.level().getServer().execute(() -> {
                if (bots.isBot(old.getUUID())) {
                    bots.applySkin(old, profile);
                }
            }));
        }

        ok(context, targets.size() + " Bot(s) bekommen zufaellige, moeglichst verschiedene Skins.");
        return targets.size();
    }

    private static int skin(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Bots bots = bots();
        List<BotPlayer> targets = select(context, SelectorArgumentType.get(context, "auswahl"));
        String skinName = StringArgumentType.getString(context, "name");

        for (BotPlayer old : targets) {
            bots.profileFor(old.getGameProfile().name(), skinName).thenAccept(profile -> old.level().getServer().execute(() -> {
                if (bots.isBot(old.getUUID())) {
                    bots.applySkin(old, profile);
                }
            }));
        }

        ok(context, targets.size() + " Bot(s) bekommen den Skin " + skinName + ".");
        return targets.size();
    }

    private static final SuggestionProvider<CommandSourceStack> SKIN_FILES = (context, builder) -> {
        for (String name : SkinFiles.names()) {
            String bare = name.replaceAll("(?i)\\.png$", "");
            if (bare.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                builder.suggest(bare.matches("[A-Za-z0-9_.+-]+") ? bare : "\"" + bare + "\"");
            }
        }

        return builder.buildFuture();
    };

    private static int skinFile(CommandContext<CommandSourceStack> context, String model) throws CommandSyntaxException {
        Bots bots = bots();
        List<BotPlayer> targets = select(context, SelectorArgumentType.get(context, "auswahl"));
        SkinFiles.Skin skin;
        try {
            skin = SkinFiles.load(StringArgumentType.getString(context, "datei"), model);
        } catch (IOException error) {
            throw fail(error.getMessage());
        }

        String[] signed = SkinFiles.signedFor(skin);
        for (BotPlayer bot : targets) {
            bots.applySkinFile(bot, skin, signed);
        }

        String model2 = skin.slim() ? "schmale Arme" : "breite Arme";
        if (signed != null) {
            ok(context, targets.size() + " Bot(s) tragen " + skin.name() + " (" + model2 + ") - fuer alle sichtbar.");
        } else if (SkinFiles.hasApiKey()) {
            ok(context, targets.size() + " Bot(s) tragen " + skin.name() + " (" + model2 + "). Wird bei MineSkin signiert, dann fuer alle sichtbar ...");
            CommandSourceStack source = context.getSource();
            MinecraftServer server = source.getServer();
            SkinFiles.sign(skin).whenComplete((result, error) -> server.execute(() -> {
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    source.sendSystemMessage(Component.literal("[BotSystem] Signieren fehlgeschlagen: " + cause.getMessage() + " - Skin bleibt nur mit der Client-Mod sichtbar.").withStyle(ChatFormatting.RED));
                } else {
                    int applied = 0;
                    for (BotPlayer bot : targets) {
                        if (bots.isBot(bot.getUUID())) {
                            bots.applySkinFile(bot, skin, result);
                            applied++;
                        }
                    }

                    source.sendSystemMessage(Component.literal("[BotSystem] " + skin.name() + " signiert - jetzt fuer alle sichtbar (" + applied + " Bot(s)).").withStyle(ChatFormatting.GREEN));
                }
            }));
        } else {
            ok(context, targets.size() + " Bot(s) tragen " + skin.name() + " (" + model2 + ") - sichtbar mit der BotSystem-Client-Mod. Fuer alle Spieler: /bot mineskin key <key> (gratis auf mineskin.org).");
        }

        return targets.size();
    }

    /** Bots attack every mob of this type within the chosen range (up to 512 blocks), until stopped. */
    private static int hunt(CommandContext<CommandSourceStack> context, String mob, int range) throws CommandSyntaxException {
        EntityType<?> type = null;
        if (mob != null) {
            type = EntityType.byString(mob.contains(":") ? mob : "minecraft:" + mob.toLowerCase(Locale.ROOT)).orElse(null);
            if (type == null) {
                throw fail("Unbekannter Mob: " + mob);
            }
        }

        List<BotPlayer> targets = select(context, SelectorArgumentType.get(context, "auswahl"));
        for (BotPlayer bot : targets) {
            bot.huntType = type;
            bot.huntRange = range;
            if (type != null) {
                bot.fightMode = true;
                bot.nextTargetTick = 0L;
            }
        }

        ok(context, targets.size() + " Bot(s) " + (type == null ? "jagen nicht mehr." : "jagen " + mob + " im Umkreis von " + range + " Bloecken."));
        return targets.size();
    }

    private static int skinLayers(CommandContext<CommandSourceStack> context, boolean on) throws CommandSyntaxException {
        BotDefaults.get().skinLayers = on;
        BotDefaults.get().save();
        for (BotPlayer bot : bots().all()) {
            bot.applySkinLayers();
        }

        ok(context, on ? "Zweite Skin-Ebene (Hut, Jacke, Aermel, Hose) bei allen Bots AN." : "Zweite Skin-Ebene AUS.");
        return 1;
    }

    private static int skinColor(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Bots bots = bots();
        List<BotPlayer> targets = select(context, SelectorArgumentType.get(context, "auswahl"));
        String input = StringArgumentType.getString(context, "farbe");
        String hex = SkinColors.parse(input);
        if (hex == null) {
            throw fail("Unbekannte Farbe: " + input + ". Farben: " + String.join(", ", SkinColors.NAMES) + " oder #rrggbb.");
        }

        for (BotPlayer bot : targets) {
            bots.applySkinColor(bot, hex.equals("off") ? null : hex);
        }

        ok(
            context,
            targets.size()
                + " Bot(s) "
                + (
                    hex.equals("off")
                        ? "haben wieder ihren normalen Skin."
                        : "tragen jetzt die Farbe " + input + " (#" + hex + ") - sichtbar mit der BotSystem-Mod auf dem Client."
                )
        );
        return targets.size();
    }

    private static int nick(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> targets = select(context, SelectorArgumentType.get(context, "auswahl"));
        String base = StringArgumentType.getString(context, "name");
        int index = 1;
        List<String> names = new ArrayList<>();

        for (BotPlayer bot : targets) {
            String name = targets.size() == 1 ? base : base + index++;

            while (targets.size() > 1 && !bots().nameFree(name)) {
                name = base + index++;
            }

            if (!bots().nameFree(name)) {
                throw fail("Name " + name + " ist ungültig oder vergeben.");
            }

            names.add(name);
            rename(bot, name);
        }

        ok(context, targets.size() + " Bot(s) umbenannt: " + String.join(", ", names.subList(0, Math.min(6, names.size()))));
        return targets.size();
    }

    private static int randomNick(CommandContext<CommandSourceStack> context, int min, int max, BotNameStyle style, String tag) throws CommandSyntaxException {
        List<BotPlayer> targets = select(context, SelectorArgumentType.get(context, "auswahl"));
        int lo = Math.min(min, max);
        int hi = Math.max(min, max);
        List<String> names = new ArrayList<>();

        for (BotPlayer bot : targets) {
            String name = tag == null ? bots().freshName(style, lo, hi) : bots().freshNameWith(tag, style, lo, hi);
            names.add(name);
            rename(bot, name);
        }

        ok(
            context,
            targets.size()
                + " Bot(s) umbenannt ("
                + lo
                + "-"
                + hi
                + ", "
                + style.label()
                + "): "
                + String.join(", ", names.subList(0, Math.min(6, names.size())))
        );
        return targets.size();
    }

    private static void rename(BotPlayer old, String name) {
        GameProfile profile = Bots.withTextures(
            new GameProfile(UUID.nameUUIDFromBytes(("BotSystem:" + name.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8)), name),
            old.getGameProfile()
        );
        respawnAs(old, profile);
    }

    private static void respawnAs(BotPlayer old, GameProfile profile) {
        Bots bots = BotSystemMod.bots();
        if (bots != null) {
            List<ItemStack> gear = new ArrayList<>();

            for (EquipmentSlot slot : EquipmentSlot.values()) {
                gear.add(old.getItemBySlot(slot).copy());
            }

            List<ItemStack> inventory = new ArrayList<>();

            for (int slot = 0; slot < old.getInventory().getContainerSize(); slot++) {
                inventory.add(old.getInventory().getItem(slot).copy());
            }

            int selected = old.getInventory().getSelectedSlot();
            String kitName = old.kitName;
            ServerLevel world = old.level();
            Vec3 pos = old.position();
            float yaw = old.getYRot();
            bots.remove(old);
            BotPlayer fresh = bots.spawn(world, profile, pos, yaw);
            fresh.team = old.team;
            fresh.immortal = old.immortal;
            fresh.followTarget = old.followTarget;
            fresh.followDistance = old.followDistance;
            fresh.guardMode = old.guardMode;
            fresh.assistOwner = old.assistOwner;
            fresh.defendOwner = old.defendOwner;
            fresh.defendSelf = old.defendSelf;
            fresh.neverFight = old.neverFight;
            fresh.allyIds.addAll(old.allyIds);
            fresh.skill.copyTraitsFrom(old.skill);
            fresh.typeName = old.typeName;

            for (int slot = 0; slot < Math.min(inventory.size(), fresh.getInventory().getContainerSize()); slot++) {
                fresh.getInventory().setItem(slot, inventory.get(slot));
            }

            fresh.getInventory().setSelectedSlot(selected);
            fresh.kitName = kitName;
            int index = 0;

            for (EquipmentSlot slot : EquipmentSlot.values()) {
                if (slot.getType() == Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) {
                    fresh.setItemSlot(slot, gear.get(index));
                }

                index++;
            }
        }
    }

    private static int teleport(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerLevel world = player.level();
        Set<BlockPos> used = new HashSet<>();
        used.add(player.blockPosition());

        for (MobUnits.Unit unit : selectUnits(context, SelectorArgumentType.get(context, "auswahl"))) {
            double angle = ThreadLocalRandom.current().nextDouble(0.0, Math.PI * 2);
            Vec3 spot = Ground.find(world, player.getX() + Math.cos(angle) * 3.0, player.getZ() + Math.sin(angle) * 3.0, player.getY());
            unit.mob.snapTo(spot.x, spot.y, spot.z, unit.mob.getYRot(), 0.0F);
            unit.mob.getNavigation().stop();
            unit.goal = null;
        }

        for (BotPlayer bot : targets) {
            Vec3 spot = freeSpotNear(world, player.position(), bot, used);
            used.add(BlockPos.containing(spot));
            bot.teleportTo(world, spot.x, spot.y, spot.z, Collections.emptySet(), bot.getYRot(), bot.getXRot(), false);
            bot.setDeltaMovement(Vec3.ZERO);
            bot.walkGoal = null;
        }

        ok(context, targets.size() + " Bot(s) zu dir teleportiert.");
        return targets.size();
    }

    private static Vec3 freeSpotNear(ServerLevel world, Vec3 centre, BotPlayer bot, Set<BlockPos> used) {
        EntityDimensions size = bot.getDimensions(Pose.STANDING);
        Vec3 floating = null;
        int[] heights = new int[]{0, 1, -1};

        for (int ring = 1; ring <= 4; ring++) {
            int steps = ring * 8;

            for (int dy : heights) {
                for (int i = 0; i < steps; i++) {
                    double angle = (Math.PI * 2) * i / steps;
                    BlockPos feet = BlockPos.containing(centre.add(Math.cos(angle) * ring, dy, Math.sin(angle) * ring));
                    if (!used.contains(feet)) {
                        Vec3 spot = new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                        if (world.noCollision(bot, size.makeBoundingBox(spot).deflate(0.01))) {
                            boolean floor = !world.getBlockState(feet.below()).getCollisionShape(world, feet.below()).isEmpty();
                            if (floor) {
                                return spot;
                            }

                            if (floating == null) {
                                floating = spot;
                            }
                        }
                    }
                }
            }
        }

        return floating != null ? floating : centre;
    }

    private static int heal(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> targets = selectLenient(context, SelectorArgumentType.get(context, "auswahl"));

        for (BotPlayer bot : targets) {
            bot.setHealth(bot.getMaxHealth());
            bot.getFoodData().setFoodLevel(20);
            bot.setRemainingFireTicks(0);
        }

        for (MobUnits.Unit unit : selectUnits(context, SelectorArgumentType.get(context, "auswahl"))) {
            unit.mob.setHealth(unit.mob.getMaxHealth());
            unit.mob.setRemainingFireTicks(0);
        }

        ok(context, targets.size() + " Bot(s) geheilt.");
        return targets.size();
    }

    private static int settings(CommandContext<CommandSourceStack> context, String selector) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        // Without bots the menu still opens: guards/combat/world are global rules.
        List<BotPlayer> targets = selector == null ? new ArrayList<>(bots().all()) : select(context, selector);

        SettingsMenu.open(player, targets);
        return targets.size();
    }

    private static int debug(CommandContext<CommandSourceStack> context, String selector) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        List<BotPlayer> targets = selector == null ? new ArrayList<>(bots().all()) : select(context, selector);
        if (targets.isEmpty()) {
            throw fail("Keine Bots ausgewählt.");
        }

        int changed = BotSystemMod.debug().toggle(player, targets);
        long tick = context.getSource().getServer().getTickCount();
        targets.forEach(bot -> info(context, DebugWatch.line(bot, player, tick), ChatFormatting.GRAY));
        ok(context, "Debug umgeschaltet für " + changed + " Bot(s). Beenden mit /bot debug off.");
        return changed;
    }

    private static int farm(CommandContext<CommandSourceStack> context, double radius) throws CommandSyntaxException {
        List<BotPlayer> targets = select(context, SelectorArgumentType.get(context, "auswahl"));
        String wanted = StringArgumentType.getString(context, "ziel");
        FarmTarget target = FarmTarget.parse(wanted);
        if (target == null) {
            throw fail(
                "Unbekannt: " + wanted + ". Erlaubt sind Blöcke (oak_log, stone, iron_ore, wheat), Drops (beef, leather, wool, bone) oder Tiere (cow, pig)."
            );
        }

        for (BotPlayer bot : targets) {
            bot.farmBlock = target.block();
            bot.farmEntity = target.entity();
            bot.farmRadius = radius;
            bot.farmBlockPos = null;
            bot.nextFarmScanTick = 0L;
            bot.walkGoal = null;
            if (!bot.fightMode) {
                bot.clearFight();
            }
        }

        ok(context, targets.size() + " Bot(s) farmen jetzt " + target.label() + " (Umkreis " + (int)radius + ").");
        return targets.size();
    }

    private static int stopFarm(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        List<BotPlayer> targets = select(context, SelectorArgumentType.get(context, "auswahl"));

        for (BotPlayer bot : targets) {
            boolean hunting = bot.farmEntity != null;
            bot.stopFarming();
            if (hunting) {
                bot.clearFight();
                bot.fightMode = false;
            }
        }

        ok(context, targets.size() + " Bot(s) farmen nicht mehr.");
        return targets.size();
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        String[] lines = new String[]{
            "/bot mob <typ> [anzahl] [team]   (JEDER Mob als Truppe: wolf, cow, villager, zombie, warden ... - alles wie Bots: fight/attack/march/goto/follow/guard/occupy/patrolarea/line/give/team/lookat/sight/tp/heal/immortal per @team:X; Tiere schlagen nach Groesse zu)  |  mob list",
            "/bot spawn [anzahl|name] [kit|-] [team|-] [typ]  |  remove <auswahl>  |  removeall  |  list  |  status",
            "/bot type create <name> randomnick 10 13 chaos skin black [kit|team|tier|personality|aggression|range|strafe|retreat|reaction|accuracy|crit|shield|hop|target ...]  |  type set|info|list|delete|spawn <name> [anzahl] [team]|apply <auswahl> <name>",
            "/bot spawnmobs on|off   (zaehlen Bots fuers natuerliche Mob-Spawnen - aus = weniger Lag)  |  /bot mobpush fast|vanilla   (Schieben in Mob-Armeen)",
            "/bot record   (nimmt ALLES auf: Laufen, Umschauen, Schleichen, Klicks, Items, Bloecke)  |  record stop <name>  |  record load <team|auswahl> <name> [loop] [blocks] [stagger]  |  record halt <auswahl>",
            "/bot spawnspeed normal|fast|max|instant   (grosse Spawns: wie viel pro Tick - instant = alles sofort, Server steht kurz)",
            "   /bot spawn 20 diamond Rot = 20 Bots mit Kit diamond in Team Rot; /bot spawn Fiden Notch = Bot Fiden mit Notch-Skin",
            "/bot preset save <name> [kit|-] [team|-] [anzahl]  |  preset spawn <name> [anzahl]  |  preset list  |  preset delete <name>",
            "/bot team add|remove <team> <auswahl>  |  team list",
            "/bot fight <partei> , <partei> ...   (Bots, @team:X, Spieler, me, nearby gemischt)  |  fight stop <auswahl>",
            "/bot attack <angreifer> , <opfer> [sight:10|sight:off]   (einseitig: Opfer wehren sich erst nach Treffer oder in Sichtweite)",
            "/bot line <auswahl> <x z x z ...>   (Grenze: gleichmaessig verteilen und halten)  |  line stop  |  patrol <auswahl> <x z ...>  |  patrol stop",
            "/bot occupy | erobern <besetzer> , <feinde> [y:<hoehe>] <x1> <z1> <x2> <z2>   (Gebiet einnehmen und halten; y: waehlt die Ebene)",
            "/bot clearitems   (alle Items, XP-Kugeln und liegende Pfeile aus der Welt - auch per Karte)",
            "/bot ignoreplayers on|off   (Zuschauer-Schutz: echte Spieler werden nie angegriffen)",
            "/bot potions on|off   (Traenke: Staerke vor dem Kampf, Schnelligkeit beim Verfolgen, Heilung bei wenig Leben)",
            "/bot deathanimation on|off   (aus = kein roter Koerper beim Tod, Bot verschwindet sofort)",
            "/bot filmreset on|off|now   (Film-Szene: vom BBS-Film gespawnte Bots weg, bewegte zurueck, wenn der Film stoppt/neu startet)",
            "/bot actors on|off   (BBS-Schauspieler als Ziel: /bot attack <auswahl> @actors | @actors:<umkreis>)",
            "/bot leader <auswahl> <bot|spieler|me>   (Truppe folgt einem Anfuehrer - auch einem Bot oder BBS-Schauspieler)  |  defect <auswahl> <team>   (Verrat: Seite wechseln)",
            "/bot save [name]  |  load [name]  |  saves  |  autosave on|off   (alles ueber Bots/Mobs sichern und wiederherstellen - Position, Inventar, Teams, Kriege, Buendnisse)",
            "/bot halt <auswahl>   (NOTAUS: alles anhalten und alle Feindschaften loeschen, 15 s Waffenstillstand - Karte: Stopp)",
            "/bot minimap on|off   (kleine Karte oben rechts: Gelaende, Bots, Wachen)",
            "/bot tpto <auswahl> <x y z>   (verteilt dorthin teleportieren - auch per Karte: Teleport / Taste T)",
            "/bot search <auswahl> <spieler|bot|me>   (suchen und erledigen: streifen umher, greifen erst bei Sichtkontakt im Blickfeld an)  |  search stop <auswahl>",
            "/bot deaths   (die letzten 30 Bot-Tode mit Ursache und Angreifer - warum sind meine Wachen tot?)",
            "/bot deathsound on|off|<sound>   (Beacon-Klang fuer alle, wenn ein Bot stirbt - wie auf SMPs; Mobs nicht)",
            "/bot chat on|off   (Bots plappern: gg / f / wo seid ihr ... - Sprueche in config/botsystem-chat.json)",
            "/bot respawn on|off|<sekunden>   (tote Bots kommen wie Spieler wieder)  |  spawnpoint <Team> [x y z|clear]",
            "/bot team ally|unally <Team> <Team>  |  team allies   (Buendnis: greifen sich nie an; schaltet Krieg aller gegen alle ein)",
            "/bot autowar on|off   (Krieg aller gegen alle: Teams ohne Buendnis greifen sich in Sichtweite an - unabhaengig davon gilt immer: wird ein Bot angegriffen, ruecken sein Team und seine Verbuendeten aus)",
            "/bot spawn <n> <kit> <team> [verbuendetes Team]",
            "/bot enemy <partei> , <partei> [sight:N]   (dauerhafte Feindschaft beidseitig: wer den anderen sieht, greift an - ab 3 Gesichteten rueckt das ganze Team aus)  |  enemy stop <auswahl>",
            "/bot mode <auswahl> passive|defensive|aggressive   (Haltung: nur herumlaufen / Posten+Grenze halten mit kurzer Leine / alles angreifen)",
            "/bot attack ... [part:50] [range:40]   (nur ein Teil greift an: N Prozent bzw. nur wer hoechstens N Bloecke von den Opfern weg steht; gilt auch fuer fight)",
            "/bot lookat <auswahl> <x y z>   (auf einen Punkt schauen)  |  stayin <auswahl> [tp] <x1 z1 x2 z2> [weitere Rechtecke]   (Gebiet nie verlassen; tp = hineinsetzen, locker verteilt)  |  stayin stop <auswahl>",
            "/bot patrolspread <auswahl> <x1> <z1> <x2> <z2> [...]   (wie patrolarea, aber die Truppe wird erst verteilt ins Gebiet gesetzt - trockene Stellen, keine Baeume)",
            "/bot patrolarea <auswahl> <x1> <z1> <x2> <z2>   (Gebiet abstreifen, Feinde in Sichtweite erledigen)  |  patrolarea stop",
            "/bot defend <bloecke>   (Kameraden im Umkreis helfen, danach zurueck; Standard 20, 0 = aus)  |  human on|off",
            "/bot hostile <auswahl> , <feinde> [sight:N]   (Feinde kennen, in Sichtweite angreifen, sonst passiv)",
            "/bot march <x> <y> <z> <marschierer> , <feinde> [sight:N]   (Marsch zum Punkt, gesehene Feinde unterwegs erledigen)  |  march stop <auswahl>",
            "/bot sight <auswahl> <off|bloecke>   (Sichtweite der Opfer nachtraeglich aendern; auch im settings-Menue)",
            "/bot ffa <auswahl> [prozent]   (jeder gegen jeden)",
            "/bot follow <auswahl>  |  guard <auswahl>  |  jeweils mit stop   (guard = passiv, greift erst ein wenn du angreifst/angegriffen wirst)",
            "/bot ally <auswahl> <spieler|me> [on|off]   (den greifen die Bots nie an)",
            "/bot settings [auswahl]   Menue: Folgen, Wache, Mitkaempfen, Verteidigen, Selbstverteidigung, Fightmode, Unsterblich, Abstand",
            "/bot goto <auswahl> <x> <y> <z>   (auch ~ ~ ~)  |  tp <auswahl>  |  heal <auswahl>",
            "/bot hop <auswahl> on|off   (Sprint-Springen auf langen Strecken wie ein Spieler, Standard an)",
            "/bot nav <auswahl> on|off   (Wegfindung: an = um Waende herum / aus dem Wasser, aus = stur geradeaus; auch im settings-Menue)",
            "/bot spread <auswahl> <radius> [tp]   (gleichmaessig auf festem Boden verteilen; ohne tp laufen sie hin)",
            "/bot farm <auswahl> <oak_log|stone|beef|cow|...> [radius]  |  farm stop <auswahl>",
            "/bot give <auswahl> <kit>  |  immortal <auswahl> on|off   Kits: " + kitList(),
            "/bot kit create <name> (aus deinem Inventar: Hotbar, Ruestung, Nebenhand)  |  kit list  |  kit info <name>  |  kit delete <name>",
            "/bot map   (Strategiekarte: Team anklicken -> Bewegen / Kaempfen / Angreifen / Erobern / Genozid)",
            "/bot skill <auswahl> <Noob|Okay|LT5|HT5|LT4|HT4|LT3|HT3|0-100|random>",
            "/bot randomnick <auswahl> [min max [stil [Wort]]]   (Wort = steckt in jedem Namen, z.B. Guard -> Guard_x92, NovaGuard)",
            "/bot randomskin prefetch   (alle 200 Pool-Skins einmal vorab laden - Mojang drosselt, deshalb dauert der erste grosse Spawn sonst Minuten)",
            "/bot randomskin <auswahl>   (jedem einen anderen Skin aus 100 Accounts)",
            "/bot skin <auswahl> <name|random>  |  skin <auswahl> color <schwarz|#rrggbb|off>  |  nick <auswahl> <name>  |  randomnick <auswahl> [min max [mix|wort|gamer|silben|zufall|chaos]]",
            "/bot saves delete <name>   (Spielstand loeschen; Presets: /bot preset delete, Kits: /bot kit delete)",
            "/bot lookat <auswahl> <me|spieler|bot|@team:X|off>   (im Stehen dorthin schauen)",
            "/bot archers hold|advance   (Bogenschuetzen: Stellung halten, Standard, oder vorruecken)",
            "/bot drops on|off | drops items on|off | drops xp on|off   (Standard: nichts)",
            "/bot build on|off | build lifetime <s> | build max <n> | build block <id> | build clear   (hochbauen/ueberbruecken, Standard aus)",
            "/bot dig on|off   (feststeckende Bots schlagen Bloecke weg, Standard aus)",
            "/bot selectors on|off   (Standard aus: Bots sind fuer @a/@e unsichtbar - sonst laufen Datapack-Funktionen pro Bot)",
            "/bot anchors on|off|<chunks pro sekunde>   (Chunks um Bots geladen halten; aus = Bots ausserhalb deiner Chunks frieren ein)",
            "/bot lod <0|bloecke>   (ab wann Bots ohne Ruestung gerendert werden, 0 = nie, Standard 0)  |  nametags <0|bloecke|1000000=immer> (Standard 64)",
            "/bot fidget on|off   (Zappeln im Stehen: schleichen, huepfen, Schritt zur Seite, Item wechseln)",
            "/bot emote <auswahl> cheer|nod|no|wave|bow|point|clap|laugh|stop   (Gesten fuer Publikum, Reden, Feiern)",
            "/bot assemble <auswahl> <x y z> [radius]   (Halbkreis um einen Punkt bilden und hinschauen - Rede, Kroenung, Hinrichtung)",
            "/bot life <auswahl> [radius] | life off <auswahl>   (ziviles Leben: bummeln, stehenbleiben, sich unterhalten)",
            "/bot standon <auswahl> <block> <x1> <z1> <x2> <z2>   (nur auf diesem Block stehen - verteilt im Rechteck)",
            "/bot formation loose|line|column | formation width <n>   (wie eine Gruppe unterwegs laeuft: breite Menge, Reihe, Gaensemarsch)",
            "/bot digrestore <sekunden>   (abgebaute Bloecke kommen nach dieser Zeit zurueck, 0 = aus)",
            "/bot detour <faktor> | climbcost <bloecke>   (wann herumgelaufen und wann hochgebaut wird)",
            "/bot crowd mode tour|line|ring | spread <n> | spacing <n> | slack <n> | catchup <n> | stagger <ticks> | look <%> | jitter <0-1.5> | headdrift <grad> | sprint on|off | info",
            "/bot render <0|16-512>   (bis zu welcher Entfernung Bots gerendert werden, Standard 160, 0 = immer - weniger = mehr FPS)",
            "/bot debug [auswahl] | off   (Statuszeile pro Sekunde)  |  perf   (ms pro Tick: Server / Bot-KI / Pfadsuche)",
            "Auswahl: <name> | @a | @guards | @team:X | @near:R | near-all R"
        };

        for (String line : lines) {
            info(context, line, ChatFormatting.GRAY);
        }

        return 1;
    }

    private static final class Faction {
        final List<BotPlayer> bots = new ArrayList<>();
        final List<MobUnits.Unit> units = new ArrayList<>();
        final Set<UUID> playerIds = new LinkedHashSet<>();
        final List<String> labels = new ArrayList<>();
        final Set<String> teams = new LinkedHashSet<>();
        boolean nearby;

        List<Vec3> positions(MinecraftServer server) {
            List<Vec3> spots = new ArrayList<>();

            for (BotPlayer bot : this.bots) {
                spots.add(bot.position());
            }

            for (MobUnits.Unit unit : this.units) {
                spots.add(unit.mob.position());
            }

            for (UUID id : this.playerIds) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) {
                    spots.add(player.position());
                } else {
                    for (ServerLevel world : server.getAllLevels()) {
                        Entity entity = world.getEntity(id);
                        if (entity != null) {
                            spots.add(entity.position());
                            break;
                        }
                    }
                }
            }

            return spots;
        }

        String limitTo(double percent, double range, List<BotCommands.Faction> enemies) {
            MinecraftServer server = null;
            if (!this.bots.isEmpty()) {
                server = this.bots.get(0).level().getServer();
            } else if (!this.units.isEmpty()) {
                server = this.units.get(0).mob.level().getServer();
            }

            StringBuilder text = new StringBuilder();
            if (range > 0.0 && server != null) {
                List<Vec3> spots = new ArrayList<>();

                for (BotCommands.Faction enemy : enemies) {
                    spots.addAll(enemy.positions(server));
                }

                if (!spots.isEmpty()) {
                    double limit = range * range;
                    int before = this.bots.size() + this.units.size();
                    this.bots.removeIf(bot -> spots.stream().noneMatch(spot -> spot.distanceToSqr(bot.position()) <= limit));
                    this.units.removeIf(unit -> spots.stream().noneMatch(spot -> spot.distanceToSqr(unit.mob.position()) <= limit));
                    text.append(", nur im Umkreis von ")
                        .append((int)range)
                        .append(" Bloecken: ")
                        .append(this.bots.size() + this.units.size())
                        .append(" von ")
                        .append(before);
                }
            }

            if (percent > 0.0 && percent < 100.0) {
                int before = this.bots.size() + this.units.size();
                Collections.shuffle(this.bots);
                Collections.shuffle(this.units);
                int keepBots = (int)Math.ceil(this.bots.size() * percent / 100.0);
                int keepUnits = (int)Math.ceil(this.units.size() * percent / 100.0);
                this.bots.subList(keepBots, this.bots.size()).clear();
                this.units.subList(keepUnits, this.units.size()).clear();
                text.append(", nur ").append((int)percent).append(" %: ").append(this.bots.size() + this.units.size()).append(" von ").append(before);
            }

            return text.toString();
        }

        Set<UUID> looseIds() {
            Set<UUID> ids = new LinkedHashSet<>();

            for (BotPlayer bot : this.bots) {
                if (bot.team == null || !this.teams.contains(bot.team.toLowerCase(Locale.ROOT))) {
                    ids.add(bot.getUUID());
                }
            }

            for (MobUnits.Unit unit : this.units) {
                if (unit.team == null || !this.teams.contains(unit.teamKey())) {
                    ids.add(unit.mob.getUUID());
                }
            }

            ids.addAll(this.playerIds);
            return ids;
        }

        boolean mixed() {
            String first = null;

            for (BotPlayer bot : this.bots) {
                if (bot.team == null) {
                    return true;
                }

                if (first == null) {
                    first = bot.team;
                } else if (!first.equalsIgnoreCase(bot.team)) {
                    return true;
                }
            }

            return false;
        }

        void befriend(BotPlayer bot) {
            bot.allyIds.addAll(this.playerIds);
            if (this.mixed()) {
                bot.allyTeams.addAll(this.teams);

                for (BotPlayer mate : this.bots) {
                    if (mate.team == null || !this.teams.contains(mate.team.toLowerCase(Locale.ROOT))) {
                        bot.allyIds.add(mate.getUUID());
                    }
                }
            }
        }

        void markAsEnemyOf(BotPlayer bot, boolean attack) {
            for (String team : this.teams) {
                if (!Bots.allied(bot.team, team)) {
                    (attack ? bot.enemyTeams : bot.hostileTeams).add(team);
                }
            }

            for (UUID id : this.looseIds()) {
                BotPlayer other = BotSystemMod.bots() == null ? null : BotSystemMod.bots().byId(id);
                if (other == null || !Bots.allied(bot.team, other.team)) {
                    (attack ? bot.targetIds : bot.hostileIds).add(id);
                }
            }

            if (attack) {
                bot.nearbyTargets = bot.nearbyTargets | this.nearby;
            }
        }
    }
}
