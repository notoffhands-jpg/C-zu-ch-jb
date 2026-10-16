package de.fiden.botsystem.bot;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import org.bukkit.Bukkit;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Cheap pushing for mob armies.
 *
 * Vanilla lets every mob ask the world "who overlaps me?" every tick. In a packed army that query
 * walks every entity of the whole 16x16x16 chunk section (hundreds), for every mob - about a
 * fifth of the server's work with 400 mobs. Army mobs therefore sit in an internal scoreboard team
 * with collisions off (vanilla skips its query entirely), and this class pushes them apart itself
 * on a 1-block grid, with vanilla's own push formula and strength. Bots and real players near
 * them are part of the grid, so walking into an army still shoves both ways.
 */
public final class UnitPush {
    private static final String PREFIX = "bsnp_";
    /** Same cap per entity and tick as Paper's max-entity-collisions default. */
    private static final int MAX_PUSHES = 8;
    private static final Long2ObjectOpenHashMap<List<LivingEntity>> GRID = new Long2ObjectOpenHashMap<>();
    private static final Map<String, Team> TEAMS = new HashMap<>();
    private static final List<List<LivingEntity>> SPARE = new ArrayList<>();

    private UnitPush() {
    }

    public static boolean enabled() {
        return BotDefaults.get().fastMobPush;
    }

    private static long key(int x, int z) {
        return (long)x << 32 ^ z & 0xFFFFFFFFL;
    }

    private static Team team(String army) {
        String name = PREFIX + (army == null ? "_" : army.toLowerCase(Locale.ROOT));
        if (name.length() > 64) {
            name = PREFIX + Integer.toHexString(army.hashCode());
        }

        Team team = TEAMS.get(name);
        if (team == null) {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            team = board.getTeam(name);
            if (team == null) {
                team = board.registerNewTeam(name);
            }

            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
            TEAMS.put(name, team);
        }

        return team;
    }

    /** Puts the unit in its army's no-collision team (again, if its army changed). */
    private static void enlist(MobUnits.Unit unit) {
        String wanted = unit.team == null ? "_" : unit.team.toLowerCase(Locale.ROOT);
        if (!wanted.equals(unit.pushTeam)) {
            release(unit);
            team(unit.team).addEntry(unit.mob.getUUID().toString());
            unit.pushTeam = wanted;
        }
    }

    public static void release(MobUnits.Unit unit) {
        if (unit.pushTeam != null) {
            String entry = unit.mob.getUUID().toString();
            Team team = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(entry);
            if (team != null && team.getName().startsWith(PREFIX)) {
                team.removeEntry(entry);
            }

            unit.pushTeam = null;
        }
    }

    /** Removes our teams completely (plugin stop / switching back to vanilla pushing). */
    public static void releaseAll(Collection<MobUnits.Unit> units) {
        for (MobUnits.Unit unit : units) {
            unit.pushTeam = null;
        }

        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team team : new ArrayList<>(board.getTeams())) {
            if (team.getName().startsWith(PREFIX)) {
                team.unregister();
            }
        }

        TEAMS.clear();
    }

    private static void add(LivingEntity entity) {
        long cell = key(entity.getBlockX(), entity.getBlockZ());
        List<LivingEntity> list = GRID.get(cell);
        if (list == null) {
            list = SPARE.isEmpty() ? new ArrayList<>(4) : SPARE.remove(SPARE.size() - 1);
            GRID.put(cell, list);
        }

        list.add(entity);
    }

    public static void tick(Collection<MobUnits.Unit> units, Collection<BotPlayer> bots) {
        if (!enabled()) {
            if (!TEAMS.isEmpty()) {
                releaseAll(units);
            }
            return;
        }

        for (List<LivingEntity> list : GRID.values()) {
            list.clear();
            SPARE.add(list);
        }
        GRID.clear();

        ServerLevel level = null;
        boolean oneLevel = true;
        for (MobUnits.Unit unit : units) {
            Mob mob = unit.mob;
            if (mob.isRemoved() || !mob.isAlive()) {
                continue;
            }

            enlist(unit);
            if (level == null) {
                level = (ServerLevel)mob.level();
            } else if (mob.level() != level) {
                oneLevel = false;
            }

            add(mob);
        }

        if (GRID.isEmpty()) {
            return;
        }

        // Whoever can walk into the army: bots and real players (only on the army's level when
        // there is just one - the usual case).
        for (BotPlayer bot : bots) {
            if (!bot.isRemoved() && bot.isAlive() && (!oneLevel || bot.level() == level) && GRID.containsKey(key(bot.getBlockX(), bot.getBlockZ()))) {
                add(bot);
            }
        }
        if (level != null) {
            for (ServerPlayer player : Bots.realPlayers(level.players())) {
                if (!player.isSpectator() && player.isAlive()) {
                    add(player);
                }
            }
        }

        for (MobUnits.Unit unit : units) {
            Mob mob = unit.mob;
            if (mob.isRemoved() || !mob.isAlive() || mob.isPassenger() || mob.isVehicle()) {
                continue;
            }

            AABB box = mob.getBoundingBox();
            int x = mob.getBlockX();
            int z = mob.getBlockZ();
            int reach = box.getXsize() > 1.0 ? 2 : 1;
            int pushes = 0;
            for (int dx = -reach; dx <= reach && pushes < MAX_PUSHES; dx++) {
                for (int dz = -reach; dz <= reach && pushes < MAX_PUSHES; dz++) {
                    List<LivingEntity> cell = GRID.get(key(x + dx, z + dz));
                    if (cell == null) {
                        continue;
                    }

                    for (int i = 0; i < cell.size() && pushes < MAX_PUSHES; i++) {
                        LivingEntity other = cell.get(i);
                        // Every unit pair once (lower id does it); bots/players always by the unit.
                        // (every Mob in the grid is an army unit)
                        if (other == mob || other instanceof Mob && other.getId() < mob.getId()) {
                            continue;
                        }
                        if (other.level() != mob.level() || other.isPassenger() || !box.intersects(other.getBoundingBox())) {
                            continue;
                        }

                        push(mob, other);
                        pushes++;
                    }
                }
            }
        }
    }

    /**
     * Vanilla Entity.push(Entity), doubled: vanilla runs it from both sides' pushEntities each
     * tick, here every pair is visited once.
     */
    private static void push(LivingEntity a, LivingEntity b) {
        if (a.noPhysics || b.noPhysics) {
            return;
        }

        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double max = Math.max(Math.abs(dx), Math.abs(dz));
        if (max < 0.01) {
            // Exactly on top of each other: split them in a fixed direction per pair.
            double angle = (a.getId() * 31 + b.getId()) * 0.7;
            dx = Math.cos(angle) * 0.02;
            dz = Math.sin(angle) * 0.02;
            max = 0.02;
        }

        max = Math.sqrt(max);
        dx /= max;
        dz /= max;
        double scale = Math.min(1.0, 1.0 / max);
        dx *= scale * 0.1;
        dz *= scale * 0.1;
        if (!a.isVehicle() && a.isPushable()) {
            a.push(-dx, 0.0, -dz);
        }
        if (!b.isVehicle() && b.isPushable()) {
            b.push(dx, 0.0, dz);
        }
    }
}
