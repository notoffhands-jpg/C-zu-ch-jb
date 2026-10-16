package de.fiden.botsystem.bot;

import ca.spottedleaf.moonrise.common.misc.NearbyPlayers.NearbyMapType;
import com.mojang.authlib.GameProfile;
import de.fiden.botsystem.BotSystemMod;
import de.fiden.botsystem.ai.ExitSense;
import de.fiden.botsystem.ai.LongRoute;
import de.fiden.botsystem.ai.RecordedPath;
import io.papermc.paper.event.entity.EntityKnockbackEvent.Cause;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

public final class BotPlayer extends ServerPlayer {
    public String team;
    public boolean fightMode;
    public boolean fleeing;
    public BlockPos clutchWater;
    public long clutchTick;
    public int clutchPrevSlot = -1;
    public long fleeRepick;
    public long lastThreatTick;
    public boolean immortal;
    public boolean nearbyTargets;
    public boolean freeForAll;
    public final Set<UUID> targetIds = new HashSet<>();
    public final Set<UUID> allyIds = new HashSet<>();
    public final Set<UUID> hostileIds = new HashSet<>();
    public final Set<String> enemyTeams = new HashSet<>();
    public final Set<String> hostileTeams = new HashSet<>();
    public final Set<String> allyTeams = new HashSet<>();
    private String teamKeySource;
    private String teamKey;
    public double sightRange;
    public LivingEntity currentTarget;
    public UUID followTarget;
    public double followDistance = 3.0;
    public Vec3 walkGoal;
    /** Set when a walk order targets another dimension than the bot is currently in.
     *  The real destination waits in crossGoal while walkGoal points at the nearest
     *  portal; once the bot arrives in this dimension, crossGoal becomes walkGoal again. */
    public ResourceKey<Level> walkGoalDimension;
    public Vec3 crossGoal;
    /** Dimension the bot was in when it started walking to a portal (null = not chasing one). */
    public ResourceKey<Level> portalFrom;
    public BlockPos portalTarget;
    /** Stable pathing goal while chasing a far, moving target (see BotBrain.fight). */
    public Vec3 chaseGoal;
    /** /bot hunt: attack every mob of this type within huntRange (null = off). */
    public EntityType<?> huntType;
    public int huntRange;
    /** Block being mined like a player does it (tool, break time, crack animation); null = not mining. */
    public BlockPos miningPos;
    /** Digging out of a cave to reach a target above ground: step direction (0,0 = not surfacing). */
    public int surfaceDx;
    public int surfaceDz;
    public int surfaceTurns;
    /** Digging a staircase DOWN to a target below instead of up. */
    public boolean surfaceDown;
    public long surfaceUntil;
    public long nextSurfaceCheck;
    public BlockPos surfacePillarFrom;
    /** Last tick an archer had its target in sight - short gaps keep the bow drawn. */
    public long lastSawTarget;
    public float miningProgress;
    public long miningLastSwing;
    public long miningUntil;
    public long nextTunnelCheck;
    /** Bridging across a gap or lava toward bridgeGoal (null dir = not bridging). */
    public net.minecraft.core.Direction bridgeDir;
    public int bridgeFloorY;
    public int bridgePlaced;
    public Vec3 bridgeGoal;
    public long bridgeEnd;
    /** Existing bridge found beside the bot: walk over there first instead of building a second one. */
    public BlockPos bridgeShift;
    /** Progress watch: closest the bot got to progressGoal, and when. No progress for long -> reroute. */
    public Vec3 progressGoal;
    public double progressBest;
    public long progressBestTick;
    public int progressEscalations;
    public long walkDeadline;
    public UUID walkGroup;
    public boolean marching;
    public UUID lookAtId;
    public Vec3 post;
    public List<Vec3> patrol;
    public int patrolIndex;
    public boolean patrolForward = true;
    public long drawStartTick = -1L;
    public long nextWeaponCheck;
    public String lookAtTeam;
    public boolean pathfinding = true;
    public Path path;
    public CompletableFuture<Path> pendingPath;
    public long pathRequestedTick = -100L;
    public long lastDoorTick;
    public boolean lastStepAhead;
    public Vec3 returnTo;
    public Stance stance = Stance.AGGRESSIVE;
    public Vec3 stanceAnchor;
    public double[] patrolArea;
    public long nextPatrolPick;
    public long hesitateUntil;
    public long nextGlance;
    public float glanceYaw;
    public long pathTick = -100L;
    public Vec3 pathGoal;
    public Vec3 lastPos = Vec3.ZERO;
    public int stuckTicks;
    public int stuckStrikes;
    public long giveUpUntil;
    public Vec3 detour;
    public int pillarBaseY = Integer.MIN_VALUE;
    public double pillarTargetY;
    public long nextDigTick;
    public boolean sprintJump = true;
    public long nextAttackTick;
    public long nextTargetTick;
    public long nextFarScanTick;
    /** Throttle for re-scanning nearby nether portals while chasing an owner who changed dimension. */
    public long nextPortalScan;
    public int strafeDirection;
    public long strafeUntil;
    public long removeAtTick = -1L;
    public boolean guardMode;
    public boolean assistOwner = true;
    public boolean defendOwner = true;
    public boolean defendSelf = true;
    /** Hard combat kill-switch: overrides stance, retaliation, ally alarms and every other
     *  trigger. A bot with this on never fights, no matter what happens to it or its team. */
    public boolean neverFight = false;
    public final BotSkill skill = BotSkill.random(0.55, 0.9);
    public Block farmBlock;
    public EntityType<?> farmEntity;
    public double farmRadius = 24.0;
    public BlockPos farmBlockPos;
    public long nextFarmScanTick;
    public long nextFarmTick;
    private Vec3 keptKnockback;
    private int keptKnockbackAge = -1;
    private long activationSeen = Long.MIN_VALUE;
    public int laneOffUntil;
    public boolean guardHold;
    public long orderUntil;
    public double walkBest = Double.MAX_VALUE;
    public long walkProgressTick;
    public Vec3 walkTracked;
    public long crossUntil;
    public boolean preferClimb;
    public long pillarDoneUntil;
    public long nextPlaceTick;
    public long bridgeUntil;
    public Vec3 progressPos;
    public long progressTick;
    public Vec3 crumb;
    public long crumbTick;
    public List<Vec3> navRoute;
    public int navRouteIndex;
    public long navRouteUntil;
    public int pathFails;
    public Vec3 trainA;
    public Vec3 trainB;
    public int trainRunsLeft;
    public boolean trainToB = true;
    public long trainLegStart;
    public final List<Long> trainTimes = new ArrayList<>();
    public boolean gymActive;
    public Vec3 gymCentre;
    public double gymRadius = 40.0;
    public boolean gymUseCourse = true;
    public long gymUntil = -1L;
    public String gymLane;
    public boolean gymRun;
    public int gymRepsLeft;
    public final List<double[]> lapPath = new ArrayList<>();
    public long lapStart = -1L;
    public String lapKey;
    private transient BotProfile profileRef;
    public ExitSense.Episode escape;
    public final List<double[]> crumbTrail = new ArrayList<>();
    public boolean trailTainted;
    public boolean noWayVerdict;
    public CompletableFuture<LongRoute.Route> pendingLong;
    public Vec3 longGoal;
    public long longRequested;
    public long nextLongPlan;
    public boolean longFailed;
    public boolean longRoute;
    public Vec3 stuckSamplePos;
    public long stuckSampleTick;
    public long nextStuckReport;
    public boolean forceRepath;
    public long lastWalkTick = -1000000L;
    public long lastMoveToTick = -1000000L;
    public Vec3 pendingGoal;
    public Vec3 longFrom;
    public Vec3 climbGoal;
    public long pillarStartTick;
    public double pillarStartY;
    public long detourUntil;
    public String skillTrial;
    public long skillStart;
    public Vec3 skillFrom;
    public long queueUntil;
    public int jumpCooldown;
    public int knockbackTicks;
    public int marchSlot = -1;
    public int marchCount = 1;
    public Vec3 aimPoint;
    public LivingEntity aimTarget;
    public long nextAimTick;
    public long lookAwayUntil;
    public long nextLookAway;
    public int ringIndex;
    public int ringCount = 1;
    /** Whose attacker ring ringIndex/ringCount belong to, and which ring (0 = front) this bot is in. */
    public net.minecraft.world.entity.LivingEntity ringTarget;
    public int ringNumber;
    public long chaseCheckTick;
    public boolean chaseDirect;
    public Vec3 relaxedCheckPos;
    public long relaxedCheckTick;
    public RecordedPath recPath;
    public Vec3 recGoal;
    public double recS = -1.0;
    public double recBest;
    public long recProgressTick;
    public double recLane;
    public double recLat;
    public int recRank;
    public Vec3 recRejoin;
    public double recRejoinS;
    public long recRejoinTick;
    public int recJumpDone = -1;
    public boolean recSneaking;
    public int avoidDirection;
    public int avoidTicks;
    public long ceasefireUntil;
    public Vec3 lookAtPos;
    /** /bot lookat <compass direction>: fixed heading in degrees (NaN = off); follows the bot around. */
    public float lookYaw = Float.NaN;
    /** Next tick a cobweb may be thrown at a running target. */
    public long nextCobweb;
    /** /bot armymarch formation this bot marches in (null = none) and its place in it. */
    public de.fiden.botsystem.ai.ArmyDrill drill;
    public int drillSlot = -1;
    public double drillBestGap = Double.MAX_VALUE;
    public long drillProgressTick;
    /** Heading everyone at an occupy post faces (NaN = look around freely). */
    public float postYaw = Float.NaN;
    /** Reach (eye to hitbox) for the next melee swing, rolled per swing from the skill tier. */
    public double swingReach;
    /** Eye-to-hitbox gap to the target last tick (closing speed) and when predictive blocking may happen again. */
    public double lastReach;
    public long nextReadBlock;
    /** Per raised enemy shield: 1 = go for the axe stun, -1 = not this time, 0 = not decided. */
    public int stunDecision;
    /** Whether the last hit was followed by a quick sprint reset (W-tap). */
    public boolean wtapped = true;
    /** Off-hand item put aside while an emote (shield) borrows the off hand. */
    public net.minecraft.world.item.ItemStack emoteOffhand;
    public double[] stayArea;
    public long nextHuntPick;
    public String responseTeam;
    public long responseUntil;
    public Vec3 savedWalkGoal;
    public long savedWalkDeadline;
    public boolean savedMarching;
    public UUID searchId;
    public Vec3 searchLastSeen;
    public long searchLastSeenTick;
    public boolean searchSpotted;
    public int eatSlot = -1;
    public int eatPreviousSlot;
    public long nextEatCheck;
    public boolean eatWasGolden;
    public int drinkSlot = -1;
    /** Splash potion waiting to be thrown at the bot's own feet once it has looked down. */
    public int splashSlot = -1;
    public long splashThrowAt;
    public int drinkPreviousSlot;
    public long nextDrinkCheck;
    public int drinkSwapSlot = -1;
    public ItemStack drinkSwapStack = ItemStack.EMPTY;
    public double guardOffsetX;
    public double guardOffsetZ;
    public long guardOffsetUntil;
    public long guardMoveAt;
    public double crowdJitterX;
    public double crowdJitterZ;
    public long crowdJitterUntil;
    public int crowdSlot = -1;
    public long crowdLookUntil;
    public int emoteKind;
    public long emoteStart;
    public long emoteUntil;
    public Vec3 emoteLook;
    public boolean lifeMode;
    public Vec3 lifeCentre;
    public double lifeRadius = 24.0;
    public long nextLifePick;
    public long lifePauseUntil;
    public UUID lifePartner;
    public int waterTicks;
    public UUID glanceAt;
    public long glanceAtUntil;
    public String kitName;
    /** Action recording being played back (ActionRecorder.Playback), null = none. */
    public Object replay;
    /** Name of the bot type it was spawned from (/bot type), null = none. */
    public String typeName;
    public boolean critPending;
    public long critRolledFor = -1L;
    public boolean critWanted;
    public long hopStreakUntil;
    public long nextHopDecision;
    public double lastTargetDistance = Double.MAX_VALUE;
    public long retreatUntil;
    /** Tick of the last melee hit this bot took (jump reset, strafe change). */
    public long hurtByMeleeTick = -100L;
    public boolean jumpResetWanted;
    /** Swings held back because the target was still in its hit immunity. */
    public int iframeHolds;
    /** Smoothed movement of the current target (blocks/tick), for leading a chase. */
    public net.minecraft.world.phys.Vec3 trackedVel = net.minecraft.world.phys.Vec3.ZERO;
    public net.minecraft.world.phys.Vec3 trackedPos;
    public net.minecraft.world.entity.LivingEntity trackedTarget;
    public long trackedTick;
    public long nextOddsCheck;
    public boolean oddsRetreat;
    public long lastThinkTick;
    public long webSince = -1L;
    public long nextShoreSearch;
    public boolean healAfterRetreat;
    public boolean healNow;
    public long nextHealPlan;
    public long shieldUntil;
    public long lastHitTick;
    public long nextFidget;
    public long fidgetUntil;
    public int fidgetKind;
    public int fidgetSlot = -1;
    public Vec3 fidgetSpot;
    public long nextWalkGlance;
    public long walkGlanceUntil;
    public Vec3 walkGlanceAt;
    public int tickErrors;
    public String lastError;
    private int lastErrorAge = -1000;
    private static Field inventoryMenuField;

    public String teamKey() {
        if (this.teamKeySource != this.team) {
            this.teamKeySource = this.team;
            this.teamKey = this.team == null ? null : this.team.toLowerCase(Locale.ROOT);
        }

        return this.teamKey;
    }

    private BotPlayer(MinecraftServer server, ServerLevel world, GameProfile profile) {
        super(server, world, profile, ClientInformation.createDefault());
    }

    public static BotPlayer spawn(MinecraftServer server, ServerLevel world, GameProfile profile, Vec3 position, float yaw) {
        BotPlayer bot = new BotPlayer(server, world, profile);
        bot.snapTo(position.x, position.y, position.z, yaw, 0.0F);
        // Mark as NPC before the join event, like Citizens does: per-player plugins skip it.
        // "unstable_npc" makes UnstableEssentials leave bots out of its every-player-vs-every-player
        // visibility loop (4 million show/hide calls every 5 s with 2000 bots) and its inventory
        // backups; the "NPC" metadata is the general convention many plugins check.
        bot.addTag("unstable_npc");
        bot.getBukkitEntity().setMetadata("NPC", new org.bukkit.metadata.FixedMetadataValue(de.fiden.botsystem.BotSystemPlugin.get(), true));
        FakeConnections.FakeConnection connection = FakeConnections.create();
        server.getPlayerList().placeNewPlayer(connection, bot, CommonListenerCookie.createInitial(profile, false));
        ServerLevel spawnLevel = bot.level();
        if (bot.isRealPlayer) {
            // (only if the join didn't go through createCommonSpawnInfo as expected)
            spawnLevel.getChunkSource().removeEntity(bot);
            bot.isRealPlayer = false;
            spawnLevel.getChunkSource().addEntity(bot);
        }
        Nms.hideFromNearbyPlayers(spawnLevel, bot);
        if (System.getProperty("botsystem.debugspawn") != null) {
            BotSystemMod.LOGGER
                .info(
                    "[spawn] {} real={} loader={} nearby={}",
                    profile.name(),
                    bot.isRealPlayer,
                    bot.moonrise$getChunkLoader() != null,
                    spawnLevel.moonrise$getNearbyPlayers().getPlayers(bot.chunkPosition(), NearbyMapType.VIEW_DISTANCE) == null
                        ? "none"
                        : spawnLevel.moonrise$getNearbyPlayers().getPlayers(bot.chunkPosition(), NearbyMapType.VIEW_DISTANCE).size()
                );
        }

        bot.teleportTo(world, position.x, position.y, position.z, Set.of(), yaw, 0.0F, false);
        Nms.setTrackingRange(bot);
        bot.getBukkitEntity().setPersistent(false);
        bot.quietMenus();
        bot.applySkinLayers();
        bot.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.setInvulnerable(false);
        return bot;
    }

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /** Called right from isSpectator(): is its caller Paper's activation range? (getCallerClass is far cheaper than a walk) */
    private static boolean calledFromActivationRange(Class<?> caller) {
        return caller.getName().endsWith(".ActivationRange");
    }

    @Override
    public net.minecraft.stats.ServerStatsCounter getStats() {
        return EmptyStats.INSTANCE != null ? EmptyStats.INSTANCE : super.getStats();
    }

    /**
     * Paper calls this in the middle of the join, after marking the player "real" and before
     * adding it to the world: a bot is added as not real right away (it used to be added, removed
     * and added again afterwards - each time through the chunk system's player maps).
     */
    @Override
    public net.minecraft.network.protocol.game.CommonPlayerSpawnInfo createCommonSpawnInfo(ServerLevel level) {
        this.isRealPlayer = false;
        return super.createCommonSpawnInfo(level);
    }

    /** Shared empty tracker (see EmptyAdvancements); also what the ServerPlayer constructor picks up. */
    @Override
    public net.minecraft.server.PlayerAdvancements getAdvancements() {
        return EmptyAdvancements.INSTANCE != null ? EmptyAdvancements.INSTANCE : super.getAdvancements();
    }

    /**
     * The bot's own sounds (steps, swings, hurt ...) only when a real player can hear them: each
     * sound is checked against every player online, so 2000 walking bots were 2000 x 2000 checks a
     * tick - a tenth of the server's work in a big swarm.
     */
    @Override
    public void playSound(net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        if (this.nearRealPlayer(MinecraftServer.currentTick, 1024.0)) {
            super.playSound(sound, volume, pitch);
        }
    }

    /** Set between Player.aiStep's setSpeed and handleShoulderEntities: answer its pickup check. */
    private boolean skipPickup;
    /** Packed in a crowd of bots (set by the brain's separation): pickup scan even rarer. */
    public boolean crowded;

    @Override
    public void setSpeed(float speed) {
        super.setSpeed(speed);
        // Player.aiStep calls this right before its item pickup scan (all entities within a block,
        // every tick - in a crowd of bots the biggest single cost). Bots scan every 4th tick.
        if (this.inAiStep && BotDefaults.get().liteIdle && (this.tickCount + this.getId()) % (this.crowded ? 12 : 4) != 0) {
            this.skipPickup = true;
        }
    }

    private boolean inAiStep;

    @Override
    public void aiStep() {
        // Player.aiStep is the only place that sets a player's speed (right before its pickup scan).
        this.inAiStep = true;
        try {
            super.aiStep();
        } finally {
            this.inAiStep = false;
            this.skipPickup = false;
        }
    }

    @Override
    public void handleShoulderEntities() {
        this.skipPickup = false;
        super.handleShoulderEntities();
    }

    @Override
    public boolean isSpectator() {
        if (this.skipPickup) {
            this.skipPickup = false;
            return true;
        }

        long now = MinecraftServer.currentTick;
        // Bots shouldn't keep mobs around them awake (Paper's activation range skips spectators),
        // so answer "spectator" to exactly that check - and only that one: answering it to the
        // first random caller of the tick (often the entity tracker) hid the bot from players for
        // a moment, which looked like guards flickering / turning invisible.
        if (this.activatedTick == now && this.activationSeen != now) {
            if (this.nearRealPlayer(now)) {
                // A real player close by keeps the mobs here awake anyway: no need for the
                // (expensive) stack check - that alone was ~4% of the tick with 2000 bots.
                this.activationSeen = now;
                return super.isSpectator();
            }

            if (calledFromActivationRange(WALKER.getCallerClass())) {
                this.activationSeen = now;
                return true;
            }
        }

        return super.isSpectator();
    }

    private static long realPlayersTick = Long.MIN_VALUE;
    private static final java.util.ArrayList<ServerPlayer> REAL_PLAYERS = new java.util.ArrayList<>();

    private boolean nearRealPlayer(long now) {
        return this.nearRealPlayer(now, 576.0);
    }

    private boolean nearRealPlayer(long now, double rangeSq) {
        if (realPlayersTick != now) {
            realPlayersTick = now;
            REAL_PLAYERS.clear();
            for (ServerPlayer player : this.level().getServer().getPlayerList().getPlayers()) {
                if (!(player instanceof BotPlayer)) {
                    REAL_PLAYERS.add(player);
                }
            }
        }

        for (int i = 0; i < REAL_PLAYERS.size(); i++) {
            ServerPlayer player = REAL_PLAYERS.get(i);
            if (player.level() == this.level()) {
                double dx = player.getX() - this.getX();
                double dz = player.getZ() - this.getZ();
                if (dx * dx + dz * dz < rangeSq) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Last-line safety, whatever the AI decided: never walk into lava or off a ledge into lava.
     * Bridging still works - it only steps onto blocks it has placed.
     */
    /**
     * Called by the brain right after it decided this tick's input (applied on the next entity
     * tick): veto steps into lava or off deadly drops, and get out when already in lava.
     */
    public void lavaGuard() {
        if (this.isInLava()) {
            this.escapeLava();
            return;
        }

        if (this.zza == 0.0F && this.xxa == 0.0F) {
            Vec3 drift = this.getDeltaMovement();
            if (drift.horizontalDistanceSqr() > 0.01 && this.lavaAt(this.getX() + drift.x * 4.0, this.getZ() + drift.z * 4.0)) {
                // Sliding toward lava with no input: brake by pushing back.
                this.setDeltaMovement(drift.x * 0.3, drift.y, drift.z * 0.3);
            }

            return;
        }

        float yaw = this.getYRot() * (float)(Math.PI / 180.0);
        double mx = -Mth.sin(yaw) * this.zza + Mth.cos(yaw) * this.xxa;
        double mz = Mth.cos(yaw) * this.zza + Mth.sin(yaw) * this.xxa;
        double length = Math.sqrt(mx * mx + mz * mz);
        if (length < 0.001) {
            return;
        }

        // Look further ahead the faster the bot goes (a sprint-jump covers ~2 blocks).
        double reach = 0.75 + Math.sqrt(this.getDeltaMovement().horizontalDistanceSqr()) * 6.0 + (this.isSprinting() ? 0.8 : 0.0);
        double fx = mx / length;
        double fz = mz / length;
        if (this.onGround() && (this.jumping || this.isSprinting())) {
            // Narrow bridge / cliff edge: no sprint-jumping when a deadly drop is right beside.
            for (int side = 0; side < 4; side++) {
                double sx = side == 0 ? 1.0 : side == 1 ? -1.0 : 0.0;
                double sz = side == 2 ? 1.0 : side == 3 ? -1.0 : 0.0;
                if (this.deepDropAt(this.getX() + sx * 1.0, this.getZ() + sz * 1.0)) {
                    this.jumping = false;
                    this.setSprinting(false);
                    break;
                }
            }
        }

        // Where the body will be: straight ahead, and a bit to each side of the heading.
        boolean edgeAhead = this.deepDropAt(this.getX() + fx * 0.75, this.getZ() + fz * 0.75)
            && this.deepDropAt(this.getX() + fx * 1.9, this.getZ() + fz * 1.9);
        boolean slidingOff = this.deepDropAt(this.getX() + fx * 0.5 - fz * 0.35, this.getZ() + fz * 0.5 + fx * 0.35)
            && this.deepDropAt(this.getX() + fx * 0.5 + fz * 0.35, this.getZ() + fz * 0.5 - fx * 0.35)
            && this.deepDropAt(this.getX() + fx * 0.6, this.getZ() + fz * 0.6);
        // A long route that plans a drop here (goal below, next route point far down) may jump.
        boolean plannedDrop = false;
        if (this.navRoute != null && this.navRouteIndex < this.navRoute.size()) {
            Vec3 next = this.navRoute.get(Math.min(this.navRouteIndex + 1, this.navRoute.size() - 1));
            plannedDrop = next.y < this.getY() - 3.0;
        }

        if (this.onGround() && (edgeAhead || slidingOff) && !plannedDrop) {
            // Walking off a cliff (more than 4 blocks, no water) - a 1-block gap may still be jumped.
            this.zza = 0.0F;
            this.xxa = 0.0F;
            this.setSprinting(false);
            this.jumping = false;
            return;
        }

        for (double ahead = 0.75; ahead <= reach + 0.01; ahead += 0.6) {
            if (this.lavaAt(this.getX() + fx * ahead, this.getZ() + fz * ahead)) {
                this.zza = 0.0F;
                this.xxa = 0.0F;
                this.setSprinting(false);
                this.setJumping(false);
                this.jumping = false;
                return;
            }
        }
    }

    /** No floor and no water within 4 blocks below the feet in this column (a harmful fall). */
    private boolean deepDropAt(double x, double z) {
        int feet = Mth.floor(this.getY() + 0.2);
        for (int dy = 0; dy >= -5; dy--) {
            BlockPos at = BlockPos.containing(x, feet + dy, z);
            BlockState state = this.level().getBlockState(at);
            if (!state.getCollisionShape(this.level(), at).isEmpty() || state.getFluidState().is(FluidTags.WATER) || state.is(net.minecraft.world.level.block.Blocks.COBWEB)) {
                return false;
            }
        }

        return true;
    }

    /** Lava at this column at feet level or up to 3 blocks below (before any solid floor). */
    private boolean lavaAt(double x, double z) {
        int feet = Mth.floor(this.getY() + 0.2);
        for (int dy = 0; dy >= -3; dy--) {
            BlockPos at = BlockPos.containing(x, feet + dy, z);
            BlockState state = this.level().getBlockState(at);
            if (state.getFluidState().is(FluidTags.LAVA)) {
                return true;
            }

            if (!state.getCollisionShape(this.level(), at).isEmpty()) {
                return false;
            }
        }

        return false;
    }

    /** In lava: swim up and head for the closest solid, lava-free ground instead of sinking. */
    private void escapeLava() {
        this.jumping = true;
        this.setJumping(true);
        BlockPos base = this.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    at.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                    BlockState floor = this.level().getBlockState(at);
                    if (floor.getCollisionShape(this.level(), at).isEmpty() || floor.getFluidState().is(FluidTags.LAVA)) {
                        continue;
                    }

                    BlockPos stand = at.above();
                    if (!this.level().getFluidState(stand).is(FluidTags.LAVA)
                        && this.level().getBlockState(stand).getCollisionShape(this.level(), stand).isEmpty()
                        && this.level().getBlockState(stand.above()).getCollisionShape(this.level(), stand.above()).isEmpty()) {
                        double distance = dx * dx + dz * dz + dy * dy * 0.5;
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = stand.immutable();
                        }
                    }
                }
            }
        }

        if (best != null) {
            double tx = best.getX() + 0.5 - this.getX();
            double tz = best.getZ() + 0.5 - this.getZ();
            float yaw = (float)(Mth.atan2(tz, tx) * (180.0 / Math.PI)) - 90.0F;
            this.setYRot(yaw);
            this.setYHeadRot(yaw);
            this.zza = 1.0F;
            this.xxa = 0.0F;
        }
    }

    /**
     * Outer skin layer (hat, jacket, sleeves, trousers): all on or all off, per BotDefaults.skinLayers.
     * Always off for a bot with a skin colour: the client mod draws a coloured outer layer
     * see-through up close, and it hid the whole bot behind it.
     */
    public void applySkinLayers() {
        ClientInformation info = this.clientInformation();
        boolean coloured = !this.getGameProfile().properties().get(SkinColors.PROPERTY).isEmpty();
        int parts = BotDefaults.get().skinLayers && !coloured ? 0x7F : 0;
        if (info.modelCustomisation() != parts) {
            this.updateOptionsNoEvents(new ClientInformation(
                info.language(), info.viewDistance(), info.chatVisibility(), info.chatColors(), parts,
                info.mainHand(), info.textFilteringEnabled(), info.allowsListing(), info.particleStatus()
            ));
        }
    }

    @Override
    public void tick() {
        // A real client acknowledges a dimension change; bots never do, which left isChangingDimension
        // stuck on and froze the portal cooldown forever - every bot could use a portal exactly once.
        if (this.isChangingDimension) {
            this.hasChangedDimension();
        }
        // End exit portal would show the credits and wait for a client respawn that never comes.
        this.seenCredits = true;
        if (this.replay != null && de.fiden.botsystem.ai.ActionRecorder.step(this)) {
            return;
        }

        if (this.liteTick()) {
            return;
        }

        super.tick();
        if ((this.tickCount & 63) == 0 && !this.isDeadOrDying()) {
            this.getFoodData().setFoodLevel(20);
            this.getFoodData().setSaturation(Math.max(this.getFoodData().getSaturationLevel(), 3.0F));
        }

        try {
            this.doTick();
        } catch (RuntimeException error) {
            this.tickErrors++;
            this.lastError = error.getCause() != null ? error.getCause().toString() : error.toString();
            if (this.tickCount - this.lastErrorAge > 600 || this.tickErrors == 1) {
                this.lastErrorAge = this.tickCount;
                BotSystemMod.LOGGER.warn("Bot {}: Tick-Fehler #{} - {}", this.getGameProfile().name(), this.tickErrors, this.lastError, error);
            }
        }
    }

    /** Since when this bot has been standing perfectly still (lite tick), -1 = moving. */
    private int stillSince = -1;
    public static int liteSkips;

    /**
     * Citizens-style cheap tick for bots that stand still: the full vanilla player tick (physics,
     * item pickup, pose, stats, equipment checks ...) only every 4th tick; in between just the
     * attack timer. Anything happening - an order or target (move input), a hit, a push, fire,
     * water, effects, missing health - and it's full ticks again at once. With 1000+ idle bots
     * this vanilla part is most of the server's work.
     */
    private boolean liteTick() {
        if (!BotDefaults.get().liteIdle) {
            return false;
        }

        Vec3 motion = this.getDeltaMovement();
        boolean still = this.onGround()
            && this.zza == 0.0F
            && this.xxa == 0.0F
            && !this.jumping
            && Math.abs(motion.x) < 1.0E-4
            && Math.abs(motion.z) < 1.0E-4
            && motion.y <= 0.0
            && motion.y > -0.2
            && this.hurtTime == 0
            && this.invulnerableTime == 0
            && this.deathTime == 0
            && !this.isDeadOrDying()
            && this.getRemainingFireTicks() <= 0
            && !this.isInWater()
            && !this.isInLava()
            && !this.isUsingItem()
            && !this.swinging
            && !this.isPassenger()
            && !this.isSleeping()
            && this.fallDistance == 0.0
            && this.getActiveEffectsMap().isEmpty()
            && this.getHealth() >= this.getMaxHealth()
            && !this.isChangingDimension;
        if (!still) {
            this.stillSince = -1;
            return false;
        }

        if (this.stillSince < 0) {
            this.stillSince = this.tickCount;
        }

        if (this.tickCount - this.stillSince < 20 || (this.tickCount + this.getId()) % 4 == 0) {
            return false;
        }

        this.attackStrengthTicker++;
        liteSkips++;
        return true;
    }

    /**
     * What a batch-mate is doing right now (fight/attack/goto/follow/patrol ... orders), for bots
     * of a big spawn that arrive a few ticks after the order was given.
     */
    public void copyOrdersFrom(BotPlayer other) {
        this.fightMode = other.fightMode;
        this.nearbyTargets = other.nearbyTargets;
        this.freeForAll = other.freeForAll;
        this.immortal = other.immortal;
        this.neverFight = other.neverFight;
        this.guardMode = other.guardMode;
        this.assistOwner = other.assistOwner;
        this.defendOwner = other.defendOwner;
        this.defendSelf = other.defendSelf;
        this.targetIds.addAll(other.targetIds);
        this.allyIds.addAll(other.allyIds);
        this.hostileIds.addAll(other.hostileIds);
        this.enemyTeams.addAll(other.enemyTeams);
        this.hostileTeams.addAll(other.hostileTeams);
        this.allyTeams.addAll(other.allyTeams);
        this.sightRange = other.sightRange;
        this.followTarget = other.followTarget;
        this.followDistance = other.followDistance;
        this.stance = other.stance;
        this.huntType = other.huntType;
        this.huntRange = other.huntRange;
        this.walkGoal = other.walkGoal;
        this.walkGoalDimension = other.walkGoalDimension;
        this.walkDeadline = other.walkDeadline;
        this.walkGroup = other.walkGroup;
        this.orderUntil = other.orderUntil;
        this.patrolArea = other.patrolArea;
        this.stayArea = other.stayArea;
        this.patrol = other.patrol == null ? null : new java.util.ArrayList<>(other.patrol);
    }

    public BotProfile profile() {
        if (this.profileRef == null) {
            this.profileRef = BotSystemMod.skills().profile(this.getGameProfile().name());
        }

        return this.profileRef;
    }

    public boolean isJumpingDebug() {
        return this.jumping;
    }

    @Override
    public void knockback(double strength, double x, double z, Entity attacker, Cause cause) {
        super.knockback(strength, x, z, attacker, cause);
        this.keptKnockback = this.getDeltaMovement();
        this.keptKnockbackAge = this.tickCount;
    }

    @Override
    public void setDeltaMovement(Vec3 velocity) {
        if (this.keptKnockback != null && !this.hurtMarked && this.keptKnockbackAge == this.tickCount) {
            Vec3 kept = this.keptKnockback;
            this.keptKnockback = null;
            super.setDeltaMovement(kept);
        } else {
            super.setDeltaMovement(velocity);
        }
    }

    @Override
    public void move(MoverType type, Vec3 movement) {
        if (type == MoverType.SELF
            && this.onGround()
            && this.zza == 0.0F
            && this.xxa == 0.0F
            && !this.jumping
            && Math.abs(movement.x) < 0.003
            && Math.abs(movement.z) < 0.003
            && movement.y <= 0.0
            && movement.y > -0.5
            && (this.tickCount + this.getId()) % 20 != 0
            && !this.level().getBlockState(this.blockPosition().below()).getCollisionShape(this.level(), this.blockPosition().below()).isEmpty()) {
            this.setDeltaMovement(this.getDeltaMovement().x, -0.0784, this.getDeltaMovement().z);
        } else {
            super.move(type, movement);
        }
    }

    @Override
    public boolean isTransmittingWaypoint() {
        return false;
    }

    @Override
    public boolean isReceivingWaypoints() {
        return false;
    }

    /** Bot-vs-bot pushing is done by the brain on its crowd grid (BotBrain.pushBots) this tick. */
    public long gridPushTick = -1L;

    @Override
    protected void pushEntities() {
        if (this.gridPushTick == MinecraftServer.currentTick) {
            // Bots were pushed apart on the grid; mobs and real players push the bot from their side.
            return;
        }

        if (this.post == null || this.walkGoal != null) {
            boolean moving = this.zza != 0.0F || this.xxa != 0.0F;
            if ((this.tickCount + this.getId()) % (moving ? 2 : 4) == 0) {
                super.pushEntities();
            }
        }
    }

    @Override
    public boolean isChangingDimension() {
        return false;
    }

    public boolean isControlledByPlayer() {
        return false;
    }

    public boolean targets(LivingEntity entity) {
        if (entity instanceof BotPlayer other) {
            return this.targets(other);
        } else if (this.targetIds.contains(entity.getUUID())) {
            return true;
        } else {
            MobUnits.Unit unit = MobUnits.unitOf(entity);
            if (unit == null) {
                return false;
            } else {
                return this.freeForAll && unit.freeForAll
                    ? true
                    : unit.team != null && (this.enemyTeams.contains(unit.teamKey()) || Bots.teamRelation(this.team, unit.teamKey(), true));
            }
        }
    }

    public boolean sameSide(LivingEntity entity) {
        if (entity instanceof BotPlayer other) {
            return this.sameSide(other);
        } else {
            MobUnits.Unit unit = MobUnits.unitOf(entity);
            return unit != null && unit.team != null && this.team != null && !this.freeForAll && !unit.freeForAll && Bots.allied(unit.team, this.team);
        }
    }

    public boolean strangerTeam(String otherTeam) {
        return BotDefaults.get().autoWar
            && this.team != null
            && otherTeam != null
            && !this.freeForAll
            && !Bots.allied(this.team, otherTeam)
            && !this.allyTeams.contains(otherTeam);
    }

    public double effectiveSight() {
        return this.sightRange > 0.0 ? this.sightRange : (BotDefaults.get().autoWar ? 24.0 : 0.0);
    }

    public boolean isGuard() {
        return this.followTarget != null && !BotDefaults.get().guardsJoinWars;
    }

    @Override
    protected void dropEquipment(ServerLevel world) {
        if (BotDefaults.get().drops) {
            super.dropEquipment(world);
        }
    }

    @Override
    protected int getBaseExperienceReward(ServerLevel world) {
        return BotDefaults.get().xpDrops ? super.getBaseExperienceReward(world) : 0;
    }

    @Override
    protected void dropExperience(ServerLevel level, Entity attacker) {
        if (BotDefaults.get().xpDrops) {
            super.dropExperience(level, attacker);
        }
    }

    private void quietMenus() {
        try {
            if (inventoryMenuField == null) {
                inventoryMenuField = Player.class.getDeclaredField("inventoryMenu");
                inventoryMenuField.setAccessible(true);
            }

            BotPlayer.QuietInventoryMenu menu = new BotPlayer.QuietInventoryMenu(this.getInventory(), this);
            inventoryMenuField.set(this, menu);
            this.containerMenu = menu;
        } catch (ReflectiveOperationException | RuntimeException error) {
            BotSystemMod.LOGGER.debug("Inventar-Menue nicht getauscht: {}", error.toString());
        }
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        Bots.noteDrops(this.level(), this.getX(), this.getY(), this.getZ(), this.level().getServer().getTickCount());
        if (BotSystemMod.bots() != null) {
            BotSystemMod.bots().recordDeath(this, source);
            BotSystemMod.bots().noteDeath(this, source);
            long now = this.level().getServer().getTickCount();
            BotSystemMod.CHAT.onDeath(this.level().getServer(), this, now);
            String soundId = BotDefaults.get().deathSound;
            if (soundId != null && !soundId.isEmpty()) {
                SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.tryParse(soundId));
                if (sound != null) {
                    for (ServerPlayer player : Bots.realPlayers(this.level().getServer().getPlayerList().getPlayers())) {
                        player.connection
                            .send(
                                new ClientboundSoundPacket(
                                    Holder.direct(sound),
                                    SoundSource.MASTER,
                                    player.getX(),
                                    player.getY(),
                                    player.getZ(),
                                    1.0F,
                                    1.0F,
                                    player.getRandom().nextLong()
                                )
                            );
                    }
                }
            }

            if (source.getEntity() instanceof BotPlayer killer) {
                BotSystemMod.CHAT.onKill(this.level().getServer(), killer, now);
            }
        }

        this.removeAtTick = this.level().getServer().getTickCount() + (BotDefaults.get().deathAnimation ? 25L : 1L);
    }

    public void despawn() {
        if (this.connection != null) {
            this.connection.onDisconnect(new DisconnectionDetails(Component.literal("Bot entfernt")));
        }
    }

    public boolean isAlly(UUID id) {
        return id != null && (this.allyIds.contains(id) || id.equals(this.followTarget));
    }

    public boolean sameSide(BotPlayer other) {
        if (other == this) {
            return true;
        } else if (!this.freeForAll && !other.freeForAll && this.team != null && this.team.equalsIgnoreCase(other.team)) {
            return true;
        } else if (this.followTarget != null && this.followTarget.equals(other.followTarget)) {
            return true;
        } else if (!this.freeForAll && other.team != null && this.allyTeams.contains(other.teamKey())) {
            return true;
        } else if (!this.freeForAll && !other.freeForAll && Bots.allied(this.team, other.team)) {
            return true;
        } else {
            return !other.freeForAll && this.team != null && other.allyTeams.contains(this.teamKey())
                ? true
                : this.isAlly(other.getUUID()) || other.isAlly(this.getUUID());
        }
    }

    public boolean wantsToHurt(LivingEntity entity) {
        if (entity == this.currentTarget || this.targetIds.contains(entity.getUUID())) {
            return true;
        } else if (this.targets(entity)) {
            return true;
        } else {
            return this.nearbyTargets && entity instanceof Player && !(entity instanceof BotPlayer)
                ? true
                : this.farmEntity != null && entity.getType() == this.farmEntity;
        }
    }

    public boolean targets(BotPlayer other) {
        if (other == this) {
            return false;
        } else if (this.freeForAll && other.freeForAll) {
            return true;
        } else {
            return this.targetIds.contains(other.getUUID())
                ? true
                : other.team != null && (this.enemyTeams.contains(other.teamKey()) || Bots.teamRelation(this.team, other.teamKey(), true));
        }
    }

    public boolean knowsHostile(BotPlayer other) {
        return !this.hostileIds.contains(other.getUUID()) && !this.targets(other)
            ? other.team != null
                && (
                    this.hostileTeams.contains(other.teamKey())
                        || this.strangerTeam(other.teamKey())
                        || Bots.teamRelation(this.team, other.teamKey(), false)
                        || Bots.teamRelation(this.team, other.teamKey(), true)
                )
            : true;
    }

    public boolean knowsHostile(LivingEntity other) {
        if (other instanceof BotPlayer bot) {
            return this.knowsHostile(bot);
        } else if (!this.hostileIds.contains(other.getUUID()) && !this.targets(other)) {
            String team = MobUnits.teamOf(other);
            return team != null
                && (
                    this.hostileTeams.contains(team)
                        || this.strangerTeam(team)
                        || Bots.teamRelation(this.team, team, false)
                        || Bots.teamRelation(this.team, team, true)
                );
        } else {
            return true;
        }
    }

    public boolean hasEnemies() {
        return !this.targetIds.isEmpty() || !this.enemyTeams.isEmpty() || this.freeForAll || Bots.hasTeamEnemies(this.team);
    }

    public boolean isFarming() {
        return this.farmBlock != null || this.farmEntity != null;
    }

    public void stopFarming() {
        this.farmBlock = null;
        this.farmEntity = null;
        this.farmBlockPos = null;
    }

    public String state() {
        if (this.isDeadOrDying()) {
            return "TOT";
        } else if (this.fightMode && this.currentTarget != null) {
            return "KAMPF";
        } else if (this.walkGoal != null) {
            return this.marching ? "MARSCH" : "LAUFEN";
        } else if ((!this.hostileIds.isEmpty() || !this.hostileTeams.isEmpty()) && !this.fightMode) {
            return "WACHSAM";
        } else if (this.isFarming()) {
            return "FARMEN";
        } else if (this.followTarget != null) {
            return this.guardMode ? "WACHE" : "FOLGEN";
        } else {
            return "IDLE";
        }
    }

    public void clearFight() {
        this.targetIds.clear();
        this.allyIds.clear();
        this.hostileIds.clear();
        this.enemyTeams.clear();
        this.hostileTeams.clear();
        this.allyTeams.clear();
        this.nearbyTargets = false;
        this.freeForAll = false;
        this.currentTarget = null;
    }

    public void setMoveInput(float forward, float sideways) {
        this.zza = forward;
        this.xxa = sideways;
    }

    public boolean wantsJump() {
        return this.jumping;
    }

    public void wantJump(boolean jump) {
        this.jumping = jump;
    }

    private static final class QuietInventoryMenu extends InventoryMenu {
        QuietInventoryMenu(Inventory inventory, Player owner) {
            super(inventory, true, owner);
        }

        @Override
        public void broadcastChanges() {
        }

        @Override
        public void broadcastFullState() {
        }
    }
}
