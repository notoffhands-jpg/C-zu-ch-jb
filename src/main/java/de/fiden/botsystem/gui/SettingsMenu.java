package de.fiden.botsystem.gui;

import de.fiden.botsystem.BotSystemMod;
import de.fiden.botsystem.bot.BotDefaults;
import de.fiden.botsystem.bot.BotPlayer;
import de.fiden.botsystem.bot.BotSkill;
import de.fiden.botsystem.bot.Bots;
import de.fiden.botsystem.bot.Ground;
import de.fiden.botsystem.bot.Stance;
import de.fiden.botsystem.net.MapPayloads;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.Vec3;

public final class SettingsMenu extends ChestMenu {
    private static final int ROWS = 6;
    private static final int SIZE = 54;
    private static final int TAB_ROW = 0;
    private static final int CLOSE = 53;
    private final ServerPlayer viewer;
    private final List<BotPlayer> bots;
    private SettingsMenu.Tab tab = SettingsMenu.Tab.BOTS;
    private final Runnable[] actions = new Runnable[54];

    public static void open(ServerPlayer viewer, List<BotPlayer> bots) {
        List<BotPlayer> copy = new ArrayList<>(bots);
        String title = copy.size() == 1 ? "BotSystem: " + copy.get(0).getGameProfile().name() : "BotSystem: " + copy.size() + " Bots";
        viewer.openMenu(
            new SimpleMenuProvider(
                (syncId, inventory, player) -> new SettingsMenu(syncId, inventory, viewer, copy), Component.literal(title).withStyle(ChatFormatting.DARK_GRAY)
            )
        );
    }

    private SettingsMenu(int syncId, Inventory inventory, ServerPlayer viewer, List<BotPlayer> bots) {
        super(MenuType.GENERIC_9x6, syncId, inventory, new SimpleContainer(54), 6);
        this.viewer = viewer;
        this.bots = bots;
        this.render();
    }

    private void render() {
        for (int slot = 0; slot < 54; slot++) {
            this.getContainer()
                .setItem(
                    slot, named(slot / 9 != 0 && slot / 9 != 5 ? Items.BLACK_STAINED_GLASS_PANE : Items.GRAY_STAINED_GLASS_PANE, Component.literal(" "), null)
                );
            this.actions[slot] = null;
        }

        int tabSlot = 1;

        for (SettingsMenu.Tab candidate : SettingsMenu.Tab.values()) {
            boolean active = candidate == this.tab;
            ItemStack icon = named(
                active ? Items.LIME_STAINED_GLASS_PANE : candidate.icon,
                Component.literal((active ? "» " : "") + candidate.label).withStyle(active ? ChatFormatting.GREEN : ChatFormatting.WHITE),
                List.of(gray(active ? "Aktueller Reiter" : "Klicken zum Wechseln"))
            );
            this.getContainer().setItem(tabSlot, icon);
            SettingsMenu.Tab target = candidate;
            this.actions[tabSlot] = () -> this.tab = target;
            tabSlot += 2;
        }

        this.getContainer().setItem(53, named(Items.BARRIER, Component.literal("Schliessen").withStyle(ChatFormatting.RED), null));
        this.actions[53] = this.viewer::closeContainer;
        this.getContainer()
            .setItem(
                45,
                named(
                    Items.BOOK,
                    Component.literal("Auswahl: " + this.bots.size() + " Bot(s)").withStyle(ChatFormatting.AQUA),
                    List.of(gray("Bots-Reiter wirkt nur auf diese Auswahl."), gray("Wachen/Kampf/Welt sind Regeln fuer alle."), gray("Alles wird gespeichert."))
                )
            );
        switch (this.tab) {
            case BOTS:
                this.renderBots();
                break;
            case GUARDS:
                this.renderGuards();
                break;
            case COMBAT:
                this.renderCombat();
                break;
            case WORLD:
                this.renderWorld();
        }
    }

    private void renderBots() {
        if (this.bots.isEmpty()) {
            this.getContainer().setItem(22, named(Items.PAPER, Component.literal("Keine Bots da").withStyle(ChatFormatting.GRAY),
                List.of(gray("Hier stehen Einstellungen fuer ausgewaehlte Bots."), gray("Die anderen Reiter gelten fuer alle und gehen auch ohne Bots."))));
        }

        if (!this.bots.isEmpty()) {
            BotPlayer first = this.bots.get(0);
            this.put(10, toggle(Items.COMPASS, "Folgen", this.all(bot -> bot.followTarget != null), "Laufen hinter dir her (Begleiter)."), () -> {
                boolean on = !this.all(botx -> botx.followTarget != null);

                for (BotPlayer bot : this.bots) {
                    bot.followTarget = on ? this.viewer.getUUID() : null;
                    if (on) {
                        bot.allyIds.add(this.viewer.getUUID());
                    } else {
                        bot.guardMode = false;
                    }
                }
            });
            this.put(
                11,
                toggle(Items.SHIELD, "Wache", this.all(bot -> bot.guardMode), "Wie Folgen, aber greifen nur an, wenn du kaempfst oder angegriffen wirst."),
                () -> {
                    boolean on = !this.all(botx -> botx.guardMode);

                    for (BotPlayer bot : this.bots) {
                        bot.guardMode = on;
                        if (on) {
                            bot.followTarget = this.viewer.getUUID();
                            bot.allyIds.add(this.viewer.getUUID());
                            bot.fightMode = false;
                            bot.targetIds.clear();
                            bot.currentTarget = null;
                            bot.nearbyTargets = false;
                        }
                    }
                }
            );
            this.put(12, toggle(Items.DIAMOND_SWORD, "Kampfmodus", this.all(bot -> bot.fightMode), "Suchen und greifen Feinde an."), () -> {
                boolean on = !this.all(botx -> botx.fightMode);

                for (BotPlayer bot : this.bots) {
                    bot.fightMode = on;
                    if (!on) {
                        bot.targetIds.clear();
                        bot.currentTarget = null;
                        bot.nearbyTargets = false;
                    }
                }
            });
            this.put(13, toggle(Items.NETHER_STAR, "Unsterblich", this.all(bot -> bot.immortal), "Nehmen keinen Schaden."), () -> {
                boolean on = !this.all(bot -> bot.immortal);
                this.bots.forEach(bot -> bot.immortal = on);
            });
            this.put(
                14, toggle(Items.MAP, "Wegfindung", this.all(bot -> bot.pathfinding), "AN: um Waende herum, ueber Bruecken. AUS: stur geradeaus."), () -> {
                    boolean on = !this.all(bot -> bot.pathfinding);
                    this.bots.forEach(bot -> {
                        bot.pathfinding = on;
                        bot.path = null;
                    });
                }
            );
            Stance stance = first.stance;
            this.put(
                15,
                named(
                    stance == Stance.PASSIVE ? Items.WHITE_WOOL : (stance == Stance.DEFENSIVE ? Items.IRON_CHESTPLATE : Items.TNT),
                    Component.literal("Haltung: " + stance.label()).withStyle(ChatFormatting.AQUA),
                    List.of(
                        gray("passiv: laufen nur herum, wehren sich hoechstens"),
                        gray("defensiv: halten Posten/Grenze, kurze Leine"),
                        gray("aggressiv: greifen an, was sie sehen"),
                        gray("Klicken: wechseln")
                    )
                ),
                () -> {
                    Stance next = switch (first.stance) {
                        case PASSIVE -> Stance.DEFENSIVE;
                        case DEFENSIVE -> Stance.AGGRESSIVE;
                        case AGGRESSIVE -> Stance.PASSIVE;
                    };
                    this.bots.forEach(bot -> {
                        bot.stance = next;
                        bot.stanceAnchor = next == Stance.DEFENSIVE ? bot.position() : null;
                    });
                }
            );
            double sight = first.sightRange;
            this.put(
                16,
                named(
                    sight > 0.0 ? Items.SPYGLASS : Items.GRAY_DYE,
                    Component.literal("Sichtweite: " + (sight > 0.0 ? (int)sight + " Bloecke" : "AUS"))
                        .withStyle(sight > 0.0 ? ChatFormatting.GREEN : ChatFormatting.RED),
                    List.of(
                        gray("Bekannte Feinde ohne Treffer angreifen, sobald so nah."),
                        gray("AUS = erst nach dem ersten Schlag."),
                        gray("Klicken: aus -> 10 -> 20 -> 30 -> 45 -> aus")
                    )
                ),
                () -> {
                    double next = switch ((int)first.sightRange) {
                        case 0 -> 10.0;
                        case 10 -> 20.0;
                        case 20 -> 30.0;
                        case 30 -> 45.0;
                        default -> 0.0;
                    };
                    this.bots.forEach(bot -> bot.sightRange = next);
                    BotDefaults.get().rememberFrom(this.bots.get(0));
                }
            );
            this.put(
                17,
                toggle(
                    Items.TOTEM_OF_UNDYING,
                    "Nie kaempfen",
                    this.all(bot -> bot.neverFight),
                    "Absolute Ausnahme ueber jede Haltung: dieser Bot greift NIE an und schlaegt NIE zurueck - "
                        + "egal welcher Modus, Befehl, Team-Alarm oder Notwehr."
                ),
                () -> {
                    boolean on = !this.all(bot -> bot.neverFight);
                    this.bots.forEach(bot -> {
                        bot.neverFight = on;
                        if (on) {
                            bot.fightMode = false;
                            bot.currentTarget = null;
                            bot.hostileTeams.clear();
                            bot.enemyTeams.clear();
                            bot.responseTeam = null;
                        }
                    });
                }
            );
            this.put(
                19,
                named(
                    Items.LEAD,
                    Component.literal("Folgeabstand: " + (int)first.followDistance + " Bloecke").withStyle(ChatFormatting.AQUA),
                    List.of(gray("Wie nah Begleiter bleiben."), gray("Klicken: 2 -> 3 -> 5 -> 8 -> 12 -> 2"))
                ),
                () -> {
                    double next = switch ((int)first.followDistance) {
                        case 2 -> 3.0;
                        case 3 -> 5.0;
                        default -> 2.0;
                        case 5 -> 8.0;
                        case 8 -> 12.0;
                    };
                    this.bots.forEach(bot -> bot.followDistance = next);
                    BotDefaults.get().rememberFrom(this.bots.get(0));
                }
            );
            this.put(
                20,
                named(
                    Items.EXPERIENCE_BOTTLE,
                    Component.literal("Skill: " + de.fiden.botsystem.bot.Tiers.label(first.skill.skill())).withStyle(ChatFormatting.AQUA),
                    List.of(gray("Trefferquote, Reaktion, Schlagtakt."), gray("Klicken: Noob -> Okay -> LT5 -> HT5 -> LT4 -> HT4 -> LT3 -> HT3 -> Noob"))
                ),
                () -> {
                    BotSkill.Rank[] ranks = BotSkill.Rank.values();
                    int index = (first.skill.rank().ordinal() + 1) % ranks.length;
                    this.bots.forEach(bot -> bot.skill.setRank(ranks[index]));
                }
            );
            this.put(
                21,
                toggle(Items.RABBIT_FOOT, "Sprint-Huepfen", this.all(bot -> bot.sprintJump), "Auf freier Strecke springen wie Spieler (je nach Typ)."),
                () -> {
                    boolean on = !this.all(bot -> bot.sprintJump);
                    this.bots.forEach(bot -> bot.sprintJump = on);
                }
            );
            this.put(
                22,
                named(Items.RED_DYE, Component.literal("Kampf beenden").withStyle(ChatFormatting.RED), List.of(gray("Feinde vergessen, stehen bleiben."))),
                () -> {
                    for (BotPlayer bot : this.bots) {
                        bot.clearFight();
                        bot.fightMode = false;
                        bot.walkGoal = null;
                        bot.setMoveInput(0.0F, 0.0F);
                    }
                }
            );
            this.put(
                23,
                named(
                    Items.ENDER_PEARL,
                    Component.literal("Zu mir teleportieren").withStyle(ChatFormatting.LIGHT_PURPLE),
                    List.of(gray("Alle ausgewaehlten Bots neben dich."))
                ),
                () -> {
                    for (BotPlayer bot : this.bots) {
                        double angle = Math.random() * Math.PI * 2.0;
                        double radius = 1.5 + Math.random() * 2.5;
                        Vec3 spot = Ground.find(
                            this.viewer.level(),
                            this.viewer.getX() + Math.cos(angle) * radius,
                            this.viewer.getZ() + Math.sin(angle) * radius,
                            this.viewer.getY()
                        );
                        bot.teleportTo(this.viewer.level(), spot.x, spot.y, spot.z, Collections.emptySet(), bot.getYRot(), bot.getXRot(), false);
                    }
                }
            );
            this.put(
                24,
                named(Items.GOLDEN_APPLE, Component.literal("Heilen").withStyle(ChatFormatting.GREEN), List.of(gray("Volle Leben fuer die Auswahl."))),
                () -> this.bots.forEach(bot -> bot.setHealth(bot.getMaxHealth()))
            );
            String pace = BotDefaults.get().movePace == null ? "walk" : BotDefaults.get().movePace;

            String paceLabel = switch (pace) {
                case "sprint" -> "sprinten";
                case "sprintjump" -> "sprint-springen";
                case "auto" -> "auto (weit = sprinten)";
                default -> "gehen";
            };
            this.put(
                25,
                named(
                    Items.FEATHER,
                    Component.literal("Tempo bei Befehlen: " + paceLabel).withStyle(ChatFormatting.GOLD),
                    List.of(gray("Klick: gehen -> sprinten -> sprint-springen -> auto."), gray("Kaempfe und Flucht rennen immer."))
                ),
                () -> {
                    String next = switch (pace) {
                        case "walk" -> "sprint";
                        case "sprint" -> "sprintjump";
                        case "sprintjump" -> "auto";
                        default -> "walk";
                    };
                    BotDefaults.get().movePace = next;
                    BotDefaults.get().save();
                }
            );
        }
    }

    private void renderGuards() {
        BotDefaults defaults = BotDefaults.get();
        this.put(10, toggle(Items.IRON_SWORD, "Mitkaempfen", defaults.assistOwner, "Wachen greifen an, wen DU angreifst."), () -> {
            defaults.assistOwner = !defaults.assistOwner;
            this.applyGuardFlags();
        });
        this.put(11, toggle(Items.GOLDEN_APPLE, "Mich verteidigen", defaults.defendOwner, "Wachen greifen an, wer DICH angreift."), () -> {
            defaults.defendOwner = !defaults.defendOwner;
            this.applyGuardFlags();
        });
        this.put(
            12,
            toggle(
                Items.LEATHER_CHESTPLATE,
                "Selbstverteidigung (normale Bots)",
                defaults.defendSelf,
                "Bots schlagen zurueck, wenn sie getroffen werden. Wachen wehren sich immer (nie gegen dich)."
            ),
            () -> {
                defaults.defendSelf = !defaults.defendSelf;
                this.applyGuardFlags();
            }
        );
        this.put(
            13,
            toggle(
                Items.BELL,
                "Team-Kriege betreffen Wachen",
                defaults.guardsJoinWars,
                "AUS (empfohlen): Wachen werden von Team-Feindschaften, Autowar und Alarm nie angegriffen und ruecken nie mit aus. AN: Wachen sind normale Teammitglieder."
            ),
            () -> defaults.guardsJoinWars = !defaults.guardsJoinWars
        );
        this.put(
            14,
            named(
                Items.LEAD,
                Component.literal("Folgeabstand (neue Bots): " + (int)defaults.followDistance).withStyle(ChatFormatting.AQUA),
                List.of(gray("Klicken: 2 -> 3 -> 5 -> 8 -> 12 -> 2"))
            ),
            () -> {
                defaults.followDistance = switch ((int)defaults.followDistance) {
                    case 2 -> 3.0;
                    case 3 -> 5.0;
                    default -> 2.0;
                    case 5 -> 8.0;
                    case 8 -> 12.0;
                };
            }
        );
        this.put(
            15,
            named(
                Items.TOTEM_OF_UNDYING,
                Component.literal("Verteidigungsmodus: " + (defaults.defendRadius > 0.0 ? (int)defaults.defendRadius + " Bloecke" : "AUS"))
                    .withStyle(ChatFormatting.AQUA),
                List.of(gray("Normale Bots helfen Kameraden im Umkreis und gehen danach zurueck."), gray("Klicken: aus -> 10 -> 20 -> 30 -> aus"))
            ),
            () -> {
                defaults.defendRadius = switch ((int)defaults.defendRadius) {
                    case 0 -> 10.0;
                    case 10 -> 20.0;
                    case 20 -> 30.0;
                    default -> 0.0;
                };
            }
        );
        this.put(
            16,
            toggle(Items.FEATHER, "Menschliche Bewegung", defaults.humanMovement, "Zoegern, Umschauen, Zappeln, Blick zur Seite beim Laufen."),
            () -> defaults.humanMovement = !defaults.humanMovement
        );
        this.put(
            17,
            toggle(
                Items.LEATHER_BOOTS,
                "Zappeln im Stehen",
                defaults.idleFidget,
                "Kurz schleichen, huepfen, Schritt zur Seite, Item wechseln - wenn nichts zu tun ist."
            ),
            () -> defaults.idleFidget = !defaults.idleFidget
        );
        this.put(
            21,
            named(
                Items.GOAT_HORN,
                Component.literal("Beistand: " + (defaults.responseRadius > 0 ? defaults.responseRadius + " Bloecke" : "ganzes Team"))
                    .withStyle(ChatFormatting.AQUA),
                List.of(
                    gray("Wird ein Teammitglied angegriffen, kommen Kameraden aus"),
                    gray("diesem Umkreis - je groesser der Feind, desto mehr -"),
                    gray("und gehen danach zurueck an ihre Arbeit."),
                    gray("Klicken: 32 -> 64 -> 128 -> 256 -> ganzes Team -> 32")
                )
            ),
            () -> {
                defaults.responseRadius = switch (defaults.responseRadius) {
                    case 32 -> 64;
                    case 64 -> 128;
                    case 128 -> 256;
                    case 256 -> 0;
                    default -> 32;
                };
            }
        );
        int guards = (int)this.bots.stream().filter(BotPlayer::isGuard).count();
        String crowdMode = defaults.followMode == null ? "tour" : defaults.followMode;
        this.put(
            18,
            named(
                Items.OAK_SIGN,
                Component.literal(
                        "Begleit-Form: "
                            + (
                                crowdMode.startsWith("tour")
                                    ? "Reisegruppe"
                                    : (!crowdMode.startsWith("ring") && !crowdMode.startsWith("kreis") ? "Reihe" : "Kreis")
                            )
                    )
                    .withStyle(ChatFormatting.AQUA),
                List.of(
                    gray("Reisegruppe: lockerer Pulk hinter dir (wie eine Fuehrung)."),
                    gray("Reihe: nebeneinander. Kreis: rundherum (alt)."),
                    gray("Klicken: Reisegruppe -> Reihe -> Kreis")
                )
            ),
            () -> defaults.followMode = crowdMode.startsWith("tour")
                ? "line"
                : (!crowdMode.startsWith("line") && !crowdMode.startsWith("reihe") ? "tour" : "ring")
        );
        this.put(
            20,
            named(
                Items.STRING,
                Component.literal("Pulk-Breite: " + (int)defaults.followSpread + " Bloecke").withStyle(ChatFormatting.AQUA),
                List.of(gray("Wie breit die Gruppe hinter dir auffaechert."), gray("Klicken: 5 -> 8 -> 11 -> 16 -> 24 -> 40 -> 5"))
            ),
            () -> {
                defaults.followSpread = switch ((int)defaults.followSpread) {
                    case 5 -> 8.0;
                    case 8 -> 11.0;
                    case 11 -> 16.0;
                    case 16 -> 24.0;
                    case 24 -> 40.0;
                    default -> 5.0;
                };
            }
        );
        this.put(
            22,
            named(
                Items.LEATHER_BOOTS,
                Component.literal("Personenabstand: " + defaults.followSpacing + " Bloecke").withStyle(ChatFormatting.AQUA),
                List.of(gray("Abstand zwischen zwei Begleitern im Pulk."), gray("Klicken: 1.6 -> 2.0 -> 2.4 -> 3.0 -> 4.0 -> 1.6"))
            ),
            () -> {
                defaults.followSpacing = switch ((int)Math.round(defaults.followSpacing * 10.0)) {
                    case 16 -> 2.0;
                    case 20 -> 2.4;
                    case 24 -> 3.0;
                    case 30 -> 4.0;
                    default -> 1.6;
                };
            }
        );
        this.put(
            23,
            named(
                Items.CLOCK,
                Component.literal("Spiel bis zum Losgehen: " + (int)defaults.followSlack + " Bloecke, versetzt " + defaults.followStagger + " Ticks")
                    .withStyle(ChatFormatting.AQUA),
                List.of(
                    gray("Wie weit sie vom Platz abkommen duerfen, bevor sie nachruecken."),
                    gray("Versetzt = nicht alle gehen im selben Moment los."),
                    gray("Klicken: 2 -> 3 -> 5 -> 8 -> 14 -> 2")
                )
            ),
            () -> {
                defaults.followSlack = switch ((int)defaults.followSlack) {
                    case 2 -> 3.0;
                    case 3 -> 5.0;
                    default -> 2.0;
                    case 5 -> 8.0;
                    case 8 -> 14.0;
                };
                defaults.followStagger = (int)(defaults.followSlack * 6.0);
            }
        );
        this.put(
            24,
            named(
                Items.ENDER_EYE,
                Component.literal("Blick zu dir: " + defaults.followLook + " %").withStyle(ChatFormatting.AQUA),
                List.of(
                    gray("Wie oft ein Wartender kurz zu dir schaut."),
                    gray("0 = nie anstarren, wie echte Leute in einer Gruppe."),
                    gray("Klicken: 0 -> 6 -> 12 -> 25 -> 50 -> 0")
                )
            ),
            () -> {
                defaults.followLook = switch (defaults.followLook) {
                    case 0 -> 6;
                    case 6 -> 12;
                    case 12 -> 25;
                    case 25 -> 50;
                    default -> 0;
                };
            }
        );
        this.put(
            25,
            toggle(
                Items.FEATHER, "Unruhe im Pulk", defaults.followJitter > 0.0, "Plaetze sind nicht exakt: jeder steht etwas anders und schiebt sich ab und zu."
            ),
            () -> defaults.followJitter = defaults.followJitter > 0.0 ? 0.0 : 0.45
        );
        this.put(
            19,
            named(
                Items.PLAYER_HEAD,
                Component.literal("Deine Begleiter: " + guards + " in der Auswahl").withStyle(ChatFormatting.YELLOW),
                List.of(
                    gray("/bot guard <auswahl> = Wache, /bot follow <auswahl> = Begleiter"),
                    gray("Wachen greifen nur an, wen du angreifst / wer dich angreift.")
                )
            ),
            null
        );
    }

    private void renderCombat() {
        BotDefaults defaults = BotDefaults.get();
        this.put(
            10,
            toggle(Items.BOW, "Bogenschuetzen halten", defaults.archersHold, "AN: Stellung halten, nur zurueckweichen. AUS: auf den Gegner zulaufen."),
            () -> defaults.archersHold = !defaults.archersHold
        );
        this.put(
            11,
            toggle(Items.TNT, "Krieg aller gegen alle", defaults.autoWar, "Jedes Team ohne Buendnis (/bot team ally) ist Feind, sobald in Sichtweite."),
            () -> defaults.autoWar = !defaults.autoWar
        );
        this.put(
            12,
            named(
                defaults.respawn ? Items.RED_BED : Items.GRAY_DYE,
                Component.literal("Respawn: " + (defaults.respawn ? "AN, " + defaults.respawnSeconds + " s" : "AUS"))
                    .withStyle(defaults.respawn ? ChatFormatting.GREEN : ChatFormatting.RED),
                List.of(gray("Tote Bots kommen wie Spieler wieder (Spawn oder /bot spawnpoint)."), gray("Klicken: aus -> 3 s -> 5 s -> 10 s -> 30 s -> aus"))
            ),
            () -> {
                if (!defaults.respawn) {
                    defaults.respawn = true;
                    defaults.respawnSeconds = 3;
                } else {
                    defaults.respawnSeconds = switch (defaults.respawnSeconds) {
                        case 3 -> 5;
                        case 5 -> 10;
                        case 10 -> 30;
                        default -> 0;
                    };
                    if (defaults.respawnSeconds == 0) {
                        defaults.respawn = false;
                        defaults.respawnSeconds = 5;
                    }
                }
            }
        );
        String sound = defaults.deathSound == null ? "" : defaults.deathSound;
        this.put(
            13,
            named(
                sound.isEmpty() ? Items.GRAY_DYE : Items.BEACON,
                Component.literal("Todes-Sound: " + (sound.isEmpty() ? "AUS" : sound.replace("minecraft:", "")))
                    .withStyle(sound.isEmpty() ? ChatFormatting.RED : ChatFormatting.GREEN),
                List.of(gray("Fuer alle hoerbar, wenn ein Bot stirbt (Mobs nicht)."), gray("Klicken: aus -> Beacon -> Glocke -> Donner -> aus"))
            ),
            () -> {
                defaults.deathSound = switch (sound) {
                    case "" -> "minecraft:block.beacon.deactivate";
                    case "minecraft:block.beacon.deactivate" -> "minecraft:block.bell.use";
                    case "minecraft:block.bell.use" -> "minecraft:entity.lightning_bolt.thunder";
                    default -> "";
                };
            }
        );
        this.put(
            14,
            toggle(Items.WRITABLE_BOOK, "Chat", defaults.chat, "Bots plappern: gg, f, wo seid ihr ... (config/botsystem-chat.json)."),
            () -> defaults.chat = !defaults.chat
        );
        this.put(15, toggle(Items.IRON_CHESTPLATE, "Item-Drops", defaults.drops, "Ausruestung beim Tod fallen lassen."), () -> defaults.drops = !defaults.drops);
        this.put(
            16, toggle(Items.EXPERIENCE_BOTTLE, "XP-Drops", defaults.xpDrops, "XP-Kugeln beim Tod fallen lassen."), () -> defaults.xpDrops = !defaults.xpDrops
        );
        this.put(
            19,
            named(
                Items.SPYGLASS,
                Component.literal("Sichtweite (neue Bots): " + (defaults.sightRange > 0.0 ? (int)defaults.sightRange : "AUS")).withStyle(ChatFormatting.AQUA),
                List.of(gray("Klicken: aus -> 10 -> 20 -> 30 -> 45 -> aus"))
            ),
            () -> {
                defaults.sightRange = switch ((int)defaults.sightRange) {
                    case 0 -> 10.0;
                    case 10 -> 20.0;
                    case 20 -> 30.0;
                    case 30 -> 45.0;
                    default -> 0.0;
                };
            }
        );
        this.put(
            24,
            toggle(
                Items.POTION,
                "Traenke trinken",
                defaults.potions,
                "Bots trinken Staerke, Schnelligkeit und Heilung aus dem Inventar, wenn es passt (Ruestungs-Kits bringen welche mit)."
            ),
            () -> defaults.potions = !defaults.potions
        );
        this.put(
            25,
            toggle(
                Items.RED_DYE,
                "Sterbeanimation",
                defaults.deathAnimation,
                "AN: roter umkippender Koerper wie bei Spielern. AUS: Bot verschwindet sofort (wie NoDeathAnimation)."
            ),
            () -> defaults.deathAnimation = !defaults.deathAnimation
        );
        this.put(
            23,
            toggle(
                Items.PAINTING,
                "Film-Szene zuruecksetzen",
                defaults.filmReset,
                "Bots, die ein BBS-Film per Befehl spawnt/bewegt, verschwinden bzw. gehen zurueck, wenn der Film stoppt oder neu startet."
            ),
            () -> defaults.filmReset = !defaults.filmReset
        );
        this.put(
            22,
            toggle(
                Items.ARMOR_STAND,
                "Schauspieler angreifbar",
                defaults.attackActors,
                "BBS-Schauspieler koennen Ziel sein: /bot attack <auswahl> @actors (oder @actors:30 im Umkreis)."
            ),
            () -> defaults.attackActors = !defaults.attackActors
        );
        this.put(
            21,
            toggle(
                Items.SPECTRAL_ARROW,
                "Spieler ignorieren",
                defaults.ignorePlayers,
                "Echte Spieler (du, Kamera, Zuschauer) werden nie angegriffen - egal welcher Befehl."
            ),
            () -> defaults.ignorePlayers = !defaults.ignorePlayers
        );
        this.put(
            20,
            named(
                Items.PAPER,
                Component.literal("Buendnisse: " + Bots.teamAllies().values().stream().mapToInt(Set::size).sum() / 2).withStyle(ChatFormatting.YELLOW),
                List.of(gray("/bot team ally <A> <B>, /bot team allies"), gray("Verbuendete greifen sich nie an und helfen sich."))
            ),
            null
        );
    }

    private void renderWorld() {
        BotDefaults defaults = BotDefaults.get();
        this.put(
            10,
            toggle(Items.COBBLESTONE, "Bauen", defaults.build, "Hochbauen und ueberbruecken, wenn sie nicht weiterkommen."),
            () -> defaults.build = !defaults.build
        );
        this.put(11, toggle(Items.IRON_PICKAXE, "Graben", defaults.dig, "Bloecke wegschlagen, wenn sie feststecken."), () -> defaults.dig = !defaults.dig);
        this.put(
            21,
            toggle(Items.LEATHER_HELMET, "Zweite Skin-Ebene", defaults.skinLayers, "Hut, Jacke, Aermel und Hose der Skins zeigen (die aeussere Ebene)."),
            () -> {
                defaults.skinLayers = !defaults.skinLayers;
                BotSystemMod.bots().all().forEach(BotPlayer::applySkinLayers);
            }
        );
        this.put(
            12,
            named(
                Items.CLOCK,
                Component.literal(
                        "Baukloetze: "
                            + (defaults.buildLifetime > 0 ? defaults.buildLifetime + " s" : "bleiben")
                            + ", max "
                            + (defaults.buildMax > 0 ? defaults.buildMax : "unbegrenzt")
                    )
                    .withStyle(ChatFormatting.AQUA),
                List.of(gray("Klicken: 30 -> 60 -> 120 -> 300 -> bleiben -> 30"), gray("Max per /bot build max <n>, Block per /bot build block <id>"))
            ),
            () -> {
                defaults.buildLifetime = switch (defaults.buildLifetime) {
                    case 30 -> 60;
                    case 60 -> 120;
                    case 120 -> 300;
                    case 300 -> 0;
                    default -> 30;
                };
            }
        );
        this.put(
            13,
            named(
                Items.NAME_TAG,
                Component.literal(
                        "Namensschilder: "
                            + (defaults.nameTagDistance >= 1000000 ? "immer" : (defaults.nameTagDistance > 0 ? defaults.nameTagDistance + " Bloecke" : "AUS"))
                    )
                    .withStyle(ChatFormatting.AQUA),
                List.of(gray("Bis zu welcher Entfernung Bot-Namen gezeigt werden (FPS)."), gray("Klicken: aus -> 16 -> 32 -> 64 -> 128 -> 256 -> immer -> aus"))
            ),
            () -> {
                defaults.nameTagDistance = switch (defaults.nameTagDistance) {
                    case 0 -> 16;
                    case 16 -> 32;
                    case 32 -> 64;
                    case 64 -> 128;
                    case 128 -> 256;
                    case 256 -> 1000000;
                    default -> 0;
                };
            }
        );
        this.put(
            14,
            named(
                Items.ARMOR_STAND,
                Component.literal("Ruestung ausblenden ab: " + (defaults.lodDistance > 0 ? defaults.lodDistance + " Bloecke" : "nie"))
                    .withStyle(ChatFormatting.AQUA),
                List.of(gray("Weit entfernte Bots ohne Ruestung/Items rendern (FPS)."), gray("Klicken: nie -> 32 -> 48 -> 64 -> 96 -> nie"))
            ),
            () -> {
                defaults.lodDistance = switch (defaults.lodDistance) {
                    case 0 -> 32;
                    case 32 -> 48;
                    case 48 -> 64;
                    case 64 -> 96;
                    default -> 0;
                };
            }
        );
        this.put(
            15,
            named(
                Items.ENDER_EYE,
                Component.literal("Sichtbar bis: " + (defaults.trackDistance == 0 ? "immer" : defaults.trackDistance + " Bloecke"))
                    .withStyle(ChatFormatting.AQUA),
                List.of(
                    gray("Ab hier werden Bots dem Client gar nicht mehr geschickt."),
                    gray("Klicken: 64 -> 96 -> 128 -> 160 -> 256 -> immer -> 64"),
                    gray("immer = bis zur Sichtweite des Servers (view-distance).")
                )
            ),
            () -> {
                defaults.trackDistance = switch (defaults.trackDistance) {
                    case 64 -> 96;
                    case 96 -> 128;
                    case 128 -> 160;
                    case 160 -> 256;
                    case 256 -> 0;
                    default -> 64;
                };
            }
        );
        this.put(
            16,
            toggle(Items.LODESTONE, "Chunk-Anker", defaults.anchors, "Bots halten ihre Umgebung geladen (sonst frieren sie fern von dir ein)."),
            () -> defaults.anchors = !defaults.anchors
        );
        this.put(
            20,
            toggle(Items.FILLED_MAP, "Minikarte", defaults.minimap, "Kleine Karte oben rechts: Gelaende, Bots als Punkte in Teamfarbe, Wachen weiss."),
            () -> {
                defaults.minimap = !defaults.minimap;
                new MapPayloads.MapHud(defaults.minimap).sendTo(this.viewer);
            }
        );
        this.put(
            19,
            toggle(
                Items.COMMAND_BLOCK,
                "Vor @a/@e verstecken",
                defaults.hideFromSelectors,
                "AN: Datapacks/Commands sehen Bots nicht (Waffen-Datapacks liefen sonst pro Bot). Per Name weiter erreichbar."
            ),
            () -> defaults.hideFromSelectors = !defaults.hideFromSelectors
        );
    }

    private void applyGuardFlags() {
        BotDefaults defaults = BotDefaults.get();
        if (BotSystemMod.bots() != null) {
            for (BotPlayer bot : BotSystemMod.bots().all()) {
                bot.assistOwner = defaults.assistOwner;
                bot.defendOwner = defaults.defendOwner;
                bot.defendSelf = defaults.defendSelf;
            }
        }
    }

    private void put(int slot, ItemStack stack, Runnable action) {
        this.getContainer().setItem(slot, stack);
        this.actions[slot] = action;
    }

    private boolean all(Predicate<BotPlayer> check) {
        return !this.bots.isEmpty() && this.bots.stream().allMatch(check);
    }

    private static ItemStack toggle(Item icon, String name, boolean on, String description) {
        Component title = Component.literal(name + ": " + (on ? "AN" : "AUS")).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
        return named(on ? icon : Items.GRAY_DYE, title, List.of(gray(description)));
    }

    private static Component gray(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    private static ItemStack named(Item item, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, name.copy().withStyle(style -> style.withItalic(false)));
        if (lore != null) {
            stack.set(DataComponents.LORE, new ItemLore(lore.stream().<Component>map(line -> line.copy().withStyle(style -> style.withItalic(false))).toList()));
        }

        return stack;
    }

    @Override
    public void clicked(int slot, int button, ClickType actionType, Player player) {
        if (slot >= 0 && slot < 54 && actionType != ClickType.QUICK_CRAFT) {
            this.bots.removeIf(bot -> BotSystemMod.bots() == null || !BotSystemMod.bots().isBot(bot.getUUID()) || bot.isRemoved());
            Runnable action = this.actions[slot];
            if (action != null) {
                action.run();
                BotDefaults.get().save();
                Bots.applyTrackDistance(this.viewer.level().getServer());
                MapPayloads.RenderSettings.broadcast(this.viewer.level().getServer());
            }

            this.render();
        }

        this.broadcastFullState();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    private enum Tab {
        BOTS("Bots", Items.PLAYER_HEAD),
        GUARDS("Wachen", Items.SHIELD),
        COMBAT("Kampf", Items.DIAMOND_SWORD),
        WORLD("Welt", Items.GRASS_BLOCK);

        final String label;
        final Item icon;

        Tab(String label, Item icon) {
            this.label = label;
            this.icon = icon;
        }
    }
}
