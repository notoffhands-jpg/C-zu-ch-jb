package de.fiden.botsystem.bot;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import de.fiden.botsystem.BotSystemMod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.stream.Stream;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

public final class BotSaves {
    private static final int VERSION = 1;

    private BotSaves() {
    }

    public static Path folder(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("botsystem");
    }

    public static Path file(MinecraftServer server, String name) {
        return folder(server).resolve(name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_") + ".json");
    }

    public static List<String> names(MinecraftServer server) {
        List<String> names = new ArrayList<>();
        Path folder = folder(server);
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                files.filter(path -> path.toString().endsWith(".json")).forEach(path -> names.add(path.getFileName().toString().replace(".json", "")));
            } catch (IOException var8) {
            }
        }

        Collections.sort(names);
        return names;
    }

    private static DynamicOps<JsonElement> ops(MinecraftServer server) {
        return RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
    }

    private static JsonElement item(MinecraftServer server, ItemStack stack) {
        return ItemStack.OPTIONAL_CODEC.encodeStart(ops(server), stack).result().orElse(new JsonObject());
    }

    private static ItemStack item(MinecraftServer server, JsonElement json) {
        return json == null ? ItemStack.EMPTY : ItemStack.OPTIONAL_CODEC.parse(ops(server), json).result().orElse(ItemStack.EMPTY);
    }

    private static JsonArray vec(Vec3 v) {
        JsonArray array = new JsonArray();
        array.add(v.x);
        array.add(v.y);
        array.add(v.z);
        return array;
    }

    private static Vec3 vec(JsonElement json) {
        if (json != null && json.isJsonArray() && json.getAsJsonArray().size() >= 3) {
            JsonArray a = json.getAsJsonArray();
            return new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
        } else {
            return null;
        }
    }

    private static JsonArray doubles(double[] values) {
        JsonArray array = new JsonArray();

        for (double value : values) {
            array.add(value);
        }

        return array;
    }

    private static double[] doubles(JsonElement json) {
        if (json != null && json.isJsonArray()) {
            JsonArray a = json.getAsJsonArray();
            double[] values = new double[a.size()];

            for (int i = 0; i < values.length; i++) {
                values[i] = a.get(i).getAsDouble();
            }

            return values;
        } else {
            return null;
        }
    }

    private static JsonArray strings(Set<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static void strings(JsonElement json, Set<String> into) {
        if (json != null && json.isJsonArray()) {
            json.getAsJsonArray().forEach(element -> into.add(element.getAsString()));
        }
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        return o.has(key) ? o.get(key).getAsBoolean() : fallback;
    }

    private static double num(JsonObject o, String key, double fallback) {
        return o.has(key) ? o.get(key).getAsDouble() : fallback;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    public static boolean delete(MinecraftServer server, String name) {
        try {
            return Files.deleteIfExists(file(server, name));
        } catch (IOException error) {
            return false;
        }
    }

    /** One bot as saved in a Spielstand (also used by /bot copy). */
    public static JsonObject botJson(MinecraftServer server, BotPlayer bot, long now) {
        JsonObject o = new JsonObject();
        o.addProperty("name", bot.getGameProfile().name());
        Iterator ox = bot.getGameProfile().properties().get(SkinColors.PROPERTY).iterator();
        if (ox.hasNext()) {
            Property property = (Property)ox.next();
            o.addProperty("skinColor", property.value());
        }

        ox = bot.getGameProfile().properties().get(SkinFiles.FILE_PROPERTY).iterator();
        if (ox.hasNext()) {
            o.addProperty("skinFile", ((Property)ox.next()).value());
        }

        ox = bot.getGameProfile().properties().get("textures").iterator();
        if (ox.hasNext()) {
            Property property = (Property)ox.next();
            o.addProperty("skinValue", property.value());
            if (property.signature() != null) {
                o.addProperty("skinSignature", property.signature());
            }
        }

        o.addProperty("world", bot.level().dimension().identifier().toString());
        o.add("pos", vec(bot.position()));
        o.addProperty("yaw", bot.getYRot());
        o.addProperty("pitch", bot.getXRot());
        o.addProperty("health", bot.getHealth());
        o.addProperty("team", bot.team);
        o.addProperty("kit", bot.kitName);
        o.addProperty("selectedSlot", bot.getInventory().getSelectedSlot());
        JsonArray inventory = new JsonArray();

        for (int slot = 0; slot < bot.getInventory().getContainerSize(); slot++) {
            inventory.add(item(server, bot.getInventory().getItem(slot)));
        }

        o.add("inventory", inventory);
        JsonObject skill = new JsonObject();
        skill.addProperty("skill", bot.skill.skill());
        skill.addProperty("hopHabit", bot.skill.hopHabit);
        skill.addProperty("pace", bot.skill.pace);
        skill.addProperty("lane", bot.skill.lane);
        skill.addProperty("curiosity", bot.skill.curiosity);
        skill.addProperty("fidgetiness", bot.skill.fidgetiness);
        skill.addProperty("critHabit", bot.skill.critHabit);
        skill.addProperty("shieldHabit", bot.skill.shieldHabit);
        skill.addProperty("retreatHealth", bot.skill.retreatHealth);
        skill.addProperty("personality", bot.skill.personality.name());
        skill.addProperty("aggression", bot.skill.aggression);
        skill.addProperty("rangeBias", bot.skill.rangeBias);
        skill.addProperty("strafeScale", bot.skill.strafeScale);
        skill.addProperty("reactionScale", bot.skill.reactionScale);
        skill.addProperty("accuracyBonus", bot.skill.accuracyBonus);
        skill.addProperty("targetPref", bot.skill.targetPref.name());
        if (bot.typeName != null) {
            o.addProperty("type", bot.typeName);
        }
        o.add("skill", skill);
        o.addProperty("stance", bot.stance.name());
        o.addProperty("immortal", bot.immortal);
        o.addProperty("sightRange", bot.sightRange);
        o.addProperty("followDistance", bot.followDistance);
        o.addProperty("pathfinding", bot.pathfinding);
        o.addProperty("sprintJump", bot.sprintJump);
        o.addProperty("assistOwner", bot.assistOwner);
        o.addProperty("defendOwner", bot.defendOwner);
        o.addProperty("defendSelf", bot.defendSelf);
        o.addProperty("neverFight", bot.neverFight);
        o.addProperty("fightMode", bot.fightMode);
        o.addProperty("freeForAll", bot.freeForAll);
        o.addProperty("nearbyTargets", bot.nearbyTargets);
        o.addProperty("marching", bot.marching);
        o.addProperty("guardMode", bot.guardMode);
        o.add("enemyTeams", strings(bot.enemyTeams));
        o.add("hostileTeams", strings(bot.hostileTeams));
        o.add("allyTeams", strings(bot.allyTeams));
        if (bot.followTarget != null) {
            o.addProperty("followTarget", bot.followTarget.toString());
        }

        if (bot.post != null) {
            o.add("post", vec(bot.post));
            if (bot.gymActive) {
                JsonObject gym = new JsonObject();
                gym.addProperty("radius", bot.gymRadius);
                gym.addProperty("course", bot.gymUseCourse);
                if (bot.gymCentre != null) {
                    gym.add("centre", vec(bot.gymCentre));
                }

                o.add("gym", gym);
            }
        }

        if (bot.patrol != null && !bot.patrol.isEmpty()) {
            JsonArray patrol = new JsonArray();
            bot.patrol.forEach(point -> patrol.add(vec(point)));
            o.add("patrol", patrol);
            o.addProperty("patrolIndex", bot.patrolIndex);
        }

        if (bot.patrolArea != null) {
            o.add("patrolArea", doubles(bot.patrolArea));
        }

        if (bot.stayArea != null) {
            o.add("stayArea", doubles(bot.stayArea));
        }

        if (bot.walkGoal != null) {
            o.add("walkGoal", vec(bot.walkGoal));
            o.addProperty("walkTicksLeft", Math.max(200L, bot.walkDeadline - now));
        }

        if (bot.lookAtTeam != null) {
            o.addProperty("lookAtTeam", bot.lookAtTeam);
        }

        if (bot.lookAtPos != null) {
            o.add("lookAtPos", vec(bot.lookAtPos));
        }

        if (bot.stanceAnchor != null) {
            o.add("stanceAnchor", vec(bot.stanceAnchor));
        }
        return o;
    }

    /**
     * Spawns one saved bot. newName: null = its saved name, "" = saved name or a fresh one if
     * taken (loading next to existing bots), else that name. at: null = saved position.
     */
    public static BotPlayer botFromJson(MinecraftServer server, Bots bots, JsonObject o, long now, String newName, Vec3 at) {
        ServerLevel world = worldOf(server, str(o, "world"));
        Vec3 pos = at != null ? at : vec(o.get("pos"));
        if (world == null || pos == null) {
            return null;
        }

        String botName = str(o, "name");
        if (newName != null && !newName.isEmpty()) {
            botName = newName;
        } else if (newName != null && !bots.nameFree(botName)) {
            botName = bots.freshName(BotNameStyle.MIX, 4, 12);
        }
        GameProfile profile = new GameProfile(Bots.offlineUuid(botName), botName);
        if (o.has("skinValue") || o.has("skinColor") || o.has("skinFile")) {
            Multimap<String, Property> map = LinkedHashMultimap.create();
            // A coloured bot goes without its real skin: the client mod only colours the plain skin.
            if (o.has("skinValue") && !o.has("skinColor")) {
                map.put("textures", new Property("textures", o.get("skinValue").getAsString(), str(o, "skinSignature")));
            }

            if (o.has("skinColor")) {
                map.put(SkinColors.PROPERTY, new Property(SkinColors.PROPERTY, o.get("skinColor").getAsString()));
            }

            if (o.has("skinFile")) {
                String[] file = o.get("skinFile").getAsString().split("\\|");
                try {
                    SkinFiles.Skin skin = SkinFiles.load(file[0], file.length > 1 ? file[1] : "auto");
                    map.put(SkinFiles.PROPERTY, new Property(SkinFiles.PROPERTY, skin.propertyValue()));
                    map.put(SkinFiles.FILE_PROPERTY, new Property(SkinFiles.FILE_PROPERTY, o.get("skinFile").getAsString()));
                } catch (java.io.IOException missing) {
                    BotSystemMod.LOGGER.warn("Skin-Datei fuer {} fehlt: {}", botName, missing.getMessage());
                }
            }

            profile = new GameProfile(profile.id(), botName, new PropertyMap(map));
        }

        world.getChunk((int)Math.floor(pos.x) >> 4, (int)Math.floor(pos.z) >> 4);
        BotPlayer bot = bots.spawn(world, profile, pos, (float)num(o, "yaw", 0.0));
        bot.setXRot((float)num(o, "pitch", 0.0));
        bot.team = str(o, "team");
        bot.kitName = str(o, "kit");
        if (o.has("inventory")) {
            JsonArray inventory = o.getAsJsonArray("inventory");

            for (int slot = 0; slot < Math.min(inventory.size(), bot.getInventory().getContainerSize()); slot++) {
                bot.getInventory().setItem(slot, item(server, inventory.get(slot)));
            }
        }

        bot.getInventory().setSelectedSlot((int)num(o, "selectedSlot", 0.0));
        if (o.has("skill")) {
            JsonObject skill = o.getAsJsonObject("skill");
            bot.skill.setSkill(num(skill, "skill", bot.skill.skill()));
            bot.skill.hopHabit = num(skill, "hopHabit", bot.skill.hopHabit);
            bot.skill.pace = num(skill, "pace", bot.skill.pace);
            bot.skill.lane = num(skill, "lane", bot.skill.lane);
            bot.skill.curiosity = num(skill, "curiosity", bot.skill.curiosity);
            bot.skill.fidgetiness = num(skill, "fidgetiness", bot.skill.fidgetiness);
            bot.skill.critHabit = num(skill, "critHabit", bot.skill.critHabit);
            bot.skill.shieldHabit = num(skill, "shieldHabit", bot.skill.shieldHabit);
            bot.skill.retreatHealth = num(skill, "retreatHealth", bot.skill.retreatHealth);
            if (skill.has("personality")) {
                try {
                    bot.skill.personality = BotSkill.Personality.valueOf(skill.get("personality").getAsString());
                } catch (IllegalArgumentException ignored) {
                }
            }
            bot.skill.aggression = num(skill, "aggression", bot.skill.aggression);
            bot.skill.rangeBias = num(skill, "rangeBias", bot.skill.rangeBias);
            bot.skill.strafeScale = num(skill, "strafeScale", bot.skill.strafeScale);
            bot.skill.reactionScale = num(skill, "reactionScale", bot.skill.reactionScale);
            bot.skill.accuracyBonus = num(skill, "accuracyBonus", bot.skill.accuracyBonus);
            if (skill.has("targetPref")) {
                try {
                    bot.skill.targetPref = BotSkill.TargetPref.valueOf(skill.get("targetPref").getAsString());
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        if (o.has("type")) {
            bot.typeName = o.get("type").getAsString();
        }

        String stance = str(o, "stance");
        if (stance != null) {
            bot.stance = Stance.valueOf(stance);
        }

        bot.immortal = bool(o, "immortal", false);
        bot.sightRange = num(o, "sightRange", bot.sightRange);
        bot.followDistance = num(o, "followDistance", bot.followDistance);
        bot.pathfinding = bool(o, "pathfinding", true);
        bot.sprintJump = bool(o, "sprintJump", bot.sprintJump);
        bot.assistOwner = bool(o, "assistOwner", bot.assistOwner);
        bot.defendOwner = bool(o, "defendOwner", bot.defendOwner);
        bot.defendSelf = bool(o, "defendSelf", bot.defendSelf);
        bot.neverFight = bool(o, "neverFight", bot.neverFight);
        bot.fightMode = bool(o, "fightMode", false);
        bot.freeForAll = bool(o, "freeForAll", false);
        bot.nearbyTargets = bool(o, "nearbyTargets", false);
        bot.marching = bool(o, "marching", false);
        bot.guardMode = bool(o, "guardMode", false);
        strings(o.get("enemyTeams"), bot.enemyTeams);
        strings(o.get("hostileTeams"), bot.hostileTeams);
        strings(o.get("allyTeams"), bot.allyTeams);
        String follow = str(o, "followTarget");
        if (follow != null) {
            bot.followTarget = UUID.fromString(follow);
            bot.allyIds.add(bot.followTarget);
        }

        bot.post = vec(o.get("post"));
        if (o.has("gym")) {
            JsonObject gym = o.getAsJsonObject("gym");
            bot.gymActive = true;
            bot.gymRadius = gym.has("radius") ? gym.get("radius").getAsDouble() : 40.0;
            bot.gymUseCourse = !gym.has("course") || gym.get("course").getAsBoolean();
            bot.gymCentre = gym.has("centre") ? vec(gym.get("centre")) : null;
        }

        if (o.has("patrol")) {
            List<Vec3> patrol = new ArrayList<>();
            o.getAsJsonArray("patrol").forEach(point -> patrol.add(vec(point)));
            bot.patrol = patrol;
            bot.patrolIndex = (int)num(o, "patrolIndex", 0.0);
        }

        bot.patrolArea = doubles(o.get("patrolArea"));
        bot.stayArea = doubles(o.get("stayArea"));
        Vec3 walkGoal = vec(o.get("walkGoal"));
        if (walkGoal != null) {
            bot.walkGoal = walkGoal;
            bot.walkDeadline = now + (long)num(o, "walkTicksLeft", 6000.0);
        }

        bot.lookAtTeam = str(o, "lookAtTeam");
        bot.lookAtPos = vec(o.get("lookAtPos"));
        bot.stanceAnchor = vec(o.get("stanceAnchor"));
        float health = (float)num(o, "health", 20.0);
        bot.setHealth(Math.max(1.0F, Math.min(bot.getMaxHealth(), health)));
        return bot;
    }

    public static int save(MinecraftServer server, Bots bots, MobUnits mobs, String name) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("tick", server.getTickCount());
        root.addProperty("build", "2026-09-24j");
        JsonObject teams = new JsonObject();
        teams.add("allies", mapOfSets(Bots.teamAllies()));
        teams.add("enemies", mapOfSets(Bots.teamEnemies()));
        teams.add("hostiles", mapOfSets(Bots.teamHostiles()));
        JsonObject spawns = new JsonObject();
        bots.spawnPoints.forEach((team, spot) -> spawns.add(team, vec(spot)));
        teams.add("spawnPoints", spawns);
        JsonObject stats = new JsonObject();
        bots.teamStats.forEach((team, kd) -> {
            JsonArray array = new JsonArray();
            array.add(kd[0]);
            array.add(kd[1]);
            stats.add(team, array);
        });
        teams.add("stats", stats);
        root.add("teams", teams);
        JsonArray botArray = new JsonArray();
        long now = server.getTickCount();

        for (BotPlayer bot : bots.all()) {
            if (!bot.isRemoved() && !bot.isDeadOrDying()) {
                JsonObject o = botJson(server, bot, now);
                botArray.add(o);
            }
        }

        root.add("bots", botArray);
        JsonArray mobArray = new JsonArray();
        if (mobs != null) {
            for (MobUnits.Unit unit : mobs.all()) {
                if (unit.mob.isAlive()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(unit.mob.getType()).toString());
                    o.addProperty("world", unit.mob.level().dimension().identifier().toString());
                    o.add("pos", vec(unit.mob.position()));
                    o.addProperty("yaw", unit.mob.getYRot());
                    o.addProperty("health", unit.mob.getHealth());
                    o.addProperty("team", unit.team);
                    o.addProperty("immortal", unit.immortal);
                    o.addProperty("stance", unit.stance.name());
                    o.addProperty("fightMode", unit.fightMode);
                    o.addProperty("freeForAll", unit.freeForAll);
                    o.add("enemyTeams", strings(unit.enemyTeams));
                    o.add("hostileTeams", strings(unit.hostileTeams));
                    if (unit.mob.hasCustomName()) {
                        o.addProperty("customName", unit.mob.getCustomName().getString());
                    }

                    JsonObject gear = new JsonObject();

                    for (EquipmentSlot slot : EquipmentSlot.values()) {
                        ItemStack stack = unit.mob.getItemBySlot(slot);
                        if (!stack.isEmpty()) {
                            gear.add(slot.getName(), item(server, stack));
                        }
                    }

                    o.add("gear", gear);
                    if (unit.post != null) {
                        o.add("post", vec(unit.post));
                    }

                    if (unit.patrolArea != null) {
                        o.add("patrolArea", doubles(unit.patrolArea));
                    }

                    if (unit.goal != null) {
                        o.add("goal", vec(unit.goal));
                    }

                    if (unit.followId != null) {
                        o.addProperty("followTarget", unit.followId.toString());
                        o.addProperty("guardMode", unit.guardMode);
                    }

                    mobArray.add(o);
                }
            }
        }

        root.add("mobs", mobArray);
        Path path = file(server, name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, new GsonBuilder().create().toJson(root));
        return botArray.size() + mobArray.size();
    }

    private static JsonObject mapOfSets(Map<String, Set<String>> map) {
        JsonObject o = new JsonObject();
        map.forEach((key, values) -> {
            if (!values.isEmpty()) {
                o.add(key, strings((Set<String>)values));
            }
        });
        return o;
    }

    public static int load(MinecraftServer server, Bots bots, MobUnits mobs, String name) throws IOException {
        return load(server, bots, mobs, name, true);
    }

    /** replace = remove everything first (classic load); otherwise the saved bots are added to the current ones. */
    public static int load(MinecraftServer server, Bots bots, MobUnits mobs, String name, boolean replace) throws IOException {
        Path path = file(server, name);
        if (!Files.exists(path)) {
            throw new IOException("Kein Spielstand " + name);
        }

        JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        if (replace) {
            bots.removeAll();
            if (mobs != null) {
                mobs.removeAll();
            }

            Bots.clearAllTeamRelations();
            Bots.teamAllies().clear();
            bots.spawnPoints.clear();
            bots.teamStats.clear();
        }
        JsonObject teams = root.has("teams") ? root.getAsJsonObject("teams") : new JsonObject();
        readMapOfSets(teams.get("allies"), (a, b) -> Bots.setAllied(a, b, true));
        readMapOfSets(teams.get("enemies"), (a, b) -> Bots.setTeamRelation(a, b, true, true));
        readMapOfSets(teams.get("hostiles"), (a, b) -> Bots.setTeamRelation(a, b, false, true));
        if (teams.has("spawnPoints")) {
            for (Entry<String, JsonElement> entry : teams.getAsJsonObject("spawnPoints").entrySet()) {
                Vec3 spot = vec(entry.getValue());
                if (spot != null) {
                    bots.spawnPoints.put(entry.getKey(), spot);
                }
            }
        }

        if (teams.has("stats")) {
            for (Entry<String, JsonElement> entry : teams.getAsJsonObject("stats").entrySet()) {
                JsonArray kd = entry.getValue().getAsJsonArray();
                bots.teamStats.put(entry.getKey(), new int[]{kd.get(0).getAsInt(), kd.get(1).getAsInt()});
            }
        }

        long now = server.getTickCount();
        int count = 0;

        for (JsonElement element : root.getAsJsonArray("bots")) {
            JsonObject o = element.getAsJsonObject();

            try {
                if (botFromJson(server, bots, o, now, replace ? null : "", null) != null) {
                    count++;
                }
            } catch (RuntimeException error) {
                BotSystemMod.LOGGER.warn("Bot aus Spielstand {} nicht geladen: {}", name, error.toString());
            }
        }

        if (mobs != null && root.has("mobs")) {
            for (JsonElement element : root.getAsJsonArray("mobs")) {
                JsonObject o = element.getAsJsonObject();

                try {
                    ServerLevel world = worldOf(server, str(o, "world"));
                    Vec3 pos = vec(o.get("pos"));
                    EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.tryParse(str(o, "type"))).orElse(null);
                    if (world != null && pos != null && type != null) {
                        world.getChunk((int)Math.floor(pos.x) >> 4, (int)Math.floor(pos.z) >> 4);
                        List<MobUnits.Unit> spawned = mobs.spawn(world, type, 1, str(o, "team"), pos);
                        if (spawned != null && !spawned.isEmpty()) {
                            MobUnits.Unit unit = spawned.get(0);
                            unit.mob.snapTo(pos.x, pos.y, pos.z, (float)num(o, "yaw", 0.0), 0.0F);
                            unit.immortal = bool(o, "immortal", false);
                            String stance = str(o, "stance");
                            if (stance != null) {
                                unit.stance = Stance.valueOf(stance);
                            }

                            unit.fightMode = bool(o, "fightMode", false);
                            unit.freeForAll = bool(o, "freeForAll", false);
                            strings(o.get("enemyTeams"), unit.enemyTeams);
                            strings(o.get("hostileTeams"), unit.hostileTeams);
                            String customName = str(o, "customName");
                            if (customName != null) {
                                unit.mob.setCustomName(Component.literal(customName));
                            }

                            if (o.has("gear")) {
                                for (Entry<String, JsonElement> entry : o.getAsJsonObject("gear").entrySet()) {
                                    EquipmentSlot slot = EquipmentSlot.byName(entry.getKey());
                                    if (slot != null) {
                                        unit.mob.setItemSlot(slot, item(server, entry.getValue()));
                                        unit.mob.setDropChance(slot, 0.0F);
                                    }
                                }
                            }

                            unit.post = vec(o.get("post"));
                            unit.patrolArea = doubles(o.get("patrolArea"));
                            unit.goal = vec(o.get("goal"));
                            if (unit.goal != null) {
                                unit.goalDeadline = now + 6000L;
                            }

                            String follow = str(o, "followTarget");
                            if (follow != null) {
                                unit.followId = UUID.fromString(follow);
                                unit.guardMode = bool(o, "guardMode", false);
                            }

                            float health = (float)num(o, "health", unit.mob.getMaxHealth());
                            unit.mob.setHealth(Math.max(1.0F, Math.min(unit.mob.getMaxHealth(), health)));
                            count++;
                        }
                    }
                } catch (RuntimeException error) {
                    BotSystemMod.LOGGER.warn("Mob aus Spielstand {} nicht geladen: {}", name, error.toString());
                }
            }
        }

        return count;
    }

    private static void readMapOfSets(JsonElement json, BotSaves.Pair consumer) {
        if (json != null && json.isJsonObject()) {
            for (Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
                entry.getValue().getAsJsonArray().forEach(value -> consumer.accept(entry.getKey(), value.getAsString()));
            }
        }
    }

    private static ServerLevel worldOf(MinecraftServer server, String id) {
        if (id == null) {
            return server.overworld();
        }

        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) {
            return server.overworld();
        }

        ServerLevel world = server.getLevel(ResourceKey.create(Registries.DIMENSION, identifier));
        return world != null ? world : server.overworld();
    }

    private interface Pair {
        void accept(String var1, String var2);
    }
}
