package de.fiden.botsystem.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import de.fiden.botsystem.BotSystemPlugin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Map.Entry;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

public final class Kits {
    /** Main inventory size: hotbar 0-8 plus the 27 backpack slots 9-35. */
    public static final int INVENTORY_SLOTS = 36;
    public static final List<String> BUILTIN = List.of("netherite", "diamond", "iron", "gold", "chain", "leather", "sword", "axe", "archer", "none");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = BotSystemPlugin.configDir().resolve("botsystem-kits.json");
    private final MinecraftServer server;
    private final Map<String, Kits.Kit> custom = new LinkedHashMap<>();

    public Kits(MinecraftServer server) {
        this.server = server;
        this.load();
    }

    private static Kits.Kit armour(String name, Item weapon, Item head, Item chest, Item legs, Item feet, Item offhand) {
        List<ItemStack> hotbar = new ArrayList<>();
        hotbar.add(new ItemStack(weapon));
        if (weapon != Items.AIR) {
            hotbar.add(new ItemStack(Items.COOKED_BEEF, 12));
            hotbar.add(new ItemStack(Items.GOLDEN_APPLE, 2));
            if (head != Items.AIR && head != Items.LEATHER_HELMET) {
                hotbar.add(PotionContents.createItemStack(Items.POTION, Potions.STRONG_STRENGTH));
                hotbar.add(PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS));
                ItemStack healing = PotionContents.createItemStack(Items.POTION, Potions.STRONG_HEALING);
                hotbar.add(healing);
                hotbar.add(healing.copy());
                if (head == Items.NETHERITE_HELMET || head == Items.DIAMOND_HELMET) {
                    hotbar.add(PotionContents.createItemStack(Items.SPLASH_POTION, Potions.STRONG_HEALING));
                    hotbar.add(PotionContents.createItemStack(Items.SPLASH_POTION, Potions.STRONG_HARMING));
                    hotbar.add(new ItemStack(Items.WATER_BUCKET));
                }
            }

            if (weapon != Items.BOW && !new ItemStack(weapon).is(ItemTags.AXES)) {
                hotbar.add(
                    new ItemStack(
                        weapon == Items.NETHERITE_SWORD
                            ? Items.NETHERITE_AXE
                            : (weapon == Items.DIAMOND_SWORD ? Items.DIAMOND_AXE : (weapon == Items.IRON_SWORD ? Items.IRON_AXE : Items.STONE_AXE))
                    )
                );
            }
        }

        return new Kits.Kit(name, hotbar, new ItemStack(head), new ItemStack(chest), new ItemStack(legs), new ItemStack(feet), new ItemStack(offhand), true);
    }

    public static Kits.Kit builtin(String kit) {
        return switch (kit.toLowerCase(Locale.ROOT)) {
            case "netherite" -> armour(
                "netherite",
                Items.NETHERITE_SWORD,
                Items.NETHERITE_HELMET,
                Items.NETHERITE_CHESTPLATE,
                Items.NETHERITE_LEGGINGS,
                Items.NETHERITE_BOOTS,
                Items.SHIELD
            );
            case "diamond", "diamant" -> armour(
                "diamond", Items.DIAMOND_SWORD, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS, Items.SHIELD
            );
            case "iron", "eisen" -> armour(
                "iron", Items.IRON_SWORD, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS, Items.SHIELD
            );
            case "gold" -> armour(
                "gold", Items.GOLDEN_SWORD, Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS, Items.SHIELD
            );
            case "chain", "kette" -> armour(
                "chain", Items.IRON_SWORD, Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS, Items.SHIELD
            );
            case "leather", "leder" -> armour(
                "leather", Items.STONE_SWORD, Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS, Items.SHIELD
            );
            case "sword", "schwert" -> armour("sword", Items.DIAMOND_SWORD, Items.AIR, Items.AIR, Items.AIR, Items.AIR, Items.AIR);
            case "axe", "axt" -> armour("axe", Items.DIAMOND_AXE, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS, Items.SHIELD);
            case "archer", "bogen", "bogenschuetze" -> {
                Kits.Kit archer = armour(
                    "archer", Items.BOW, Items.CHAINMAIL_HELMET, Items.LEATHER_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.LEATHER_BOOTS, Items.ARROW
                );
                archer.hotbar().add(new ItemStack(Items.IRON_SWORD));
                archer.hotbar().add(new ItemStack(Items.ARROW, 64));
                archer.hotbar().add(new ItemStack(Items.ARROW, 64));
                archer.offhand().setCount(64);
                yield archer;
            }
            case "none", "nichts", "-" -> armour("none", Items.AIR, Items.AIR, Items.AIR, Items.AIR, Items.AIR, Items.AIR);
            default -> null;
        };
    }

    public Optional<Kits.Kit> find(String name) {
        Kits.Kit own = this.custom.get(name.toLowerCase(Locale.ROOT));
        return own != null ? Optional.of(own) : Optional.ofNullable(builtin(name));
    }

    public boolean exists(String name) {
        return this.find(name).isPresent();
    }

    public List<String> names() {
        List<String> names = new ArrayList<>(BUILTIN);
        this.custom.values().forEach(kit -> names.add(kit.name()));
        return names;
    }

    public List<String> customNames() {
        return this.custom.values().stream().map(Kits.Kit::name).toList();
    }

    public Kits.Kit createFrom(String name, ServerPlayer player) {
        List<ItemStack> hotbar = new ArrayList<>();

        // Whole inventory, not just the hotbar - potions and spares usually live in slots 9-35.
        for (int slot = 0; slot < INVENTORY_SLOTS; slot++) {
            hotbar.add(player.getInventory().getItem(slot).copy());
        }

        Kits.Kit kit = new Kits.Kit(
            name,
            hotbar,
            player.getItemBySlot(EquipmentSlot.HEAD).copy(),
            player.getItemBySlot(EquipmentSlot.CHEST).copy(),
            player.getItemBySlot(EquipmentSlot.LEGS).copy(),
            player.getItemBySlot(EquipmentSlot.FEET).copy(),
            player.getItemBySlot(EquipmentSlot.OFFHAND).copy(),
            false
        );
        this.custom.put(name.toLowerCase(Locale.ROOT), kit);
        this.store();
        return kit;
    }

    public boolean delete(String name) {
        boolean removed = this.custom.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (removed) {
            this.store();
        }

        return removed;
    }

    private DynamicOps<JsonElement> ops() {
        return RegistryOps.create(JsonOps.INSTANCE, this.server.registryAccess());
    }

    private JsonElement encode(ItemStack stack) {
        return ItemStack.OPTIONAL_CODEC.encodeStart(this.ops(), stack).result().orElse(new JsonObject());
    }

    private ItemStack decode(JsonElement json) {
        return json == null ? ItemStack.EMPTY : ItemStack.OPTIONAL_CODEC.parse(this.ops(), json).result().orElse(ItemStack.EMPTY);
    }

    private void store() {
        JsonObject root = new JsonObject();

        for (Kits.Kit kit : this.custom.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", kit.name());
            JsonArray hotbar = new JsonArray();
            kit.hotbar().forEach(stack -> hotbar.add(this.encode(stack)));
            entry.add("hotbar", hotbar);
            entry.add("head", this.encode(kit.head()));
            entry.add("chest", this.encode(kit.chest()));
            entry.add("legs", this.encode(kit.legs()));
            entry.add("feet", this.encode(kit.feet()));
            entry.add("offhand", this.encode(kit.offhand()));
            root.add(kit.name().toLowerCase(Locale.ROOT), entry);
        }

        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException var6) {
        }
    }

    private void load() {
        if (Files.exists(FILE)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();

                for (Entry<String, JsonElement> entry : root.entrySet()) {
                    JsonObject json = entry.getValue().getAsJsonObject();
                    List<ItemStack> hotbar = new ArrayList<>();
                    if (json.has("hotbar")) {
                        json.getAsJsonArray("hotbar").forEach(element -> hotbar.add(this.decode(element)));
                    }

                    String name = json.has("name") ? json.get("name").getAsString() : entry.getKey();
                    this.custom
                        .put(
                            entry.getKey().toLowerCase(Locale.ROOT),
                            new Kits.Kit(
                                name,
                                hotbar,
                                this.decode(json.get("head")),
                                this.decode(json.get("chest")),
                                this.decode(json.get("legs")),
                                this.decode(json.get("feet")),
                                this.decode(json.get("offhand")),
                                false
                            )
                        );
                }
            } catch (IOException | RuntimeException var7) {
            }
        }
    }

    public record Kit(String name, List<ItemStack> hotbar, ItemStack head, ItemStack chest, ItemStack legs, ItemStack feet, ItemStack offhand, boolean builtin) {
        public void applyTo(Mob mob) {
            mob.setItemSlot(EquipmentSlot.HEAD, this.head.copy());
            mob.setItemSlot(EquipmentSlot.CHEST, this.chest.copy());
            mob.setItemSlot(EquipmentSlot.LEGS, this.legs.copy());
            mob.setItemSlot(EquipmentSlot.FEET, this.feet.copy());
            mob.setItemSlot(EquipmentSlot.OFFHAND, this.offhand.copy());
            ItemStack hand = ItemStack.EMPTY;

            for (ItemStack stack : this.hotbar) {
                if (!stack.isEmpty()) {
                    hand = stack.copy();
                    break;
                }
            }

            mob.setItemSlot(EquipmentSlot.MAINHAND, hand);

            for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND
            }) {
                mob.setDropChance(slot, 0.0F);
            }
        }

        public void applyTo(BotPlayer bot) {
            bot.kitName = this.name;

            for (int slot = 0; slot < INVENTORY_SLOTS; slot++) {
                bot.getInventory().setItem(slot, slot < this.hotbar.size() ? this.hotbar.get(slot).copy() : ItemStack.EMPTY);
            }

            bot.getInventory().setSelectedSlot(0);
            bot.setItemSlot(EquipmentSlot.HEAD, this.head.copy());
            bot.setItemSlot(EquipmentSlot.CHEST, this.chest.copy());
            bot.setItemSlot(EquipmentSlot.LEGS, this.legs.copy());
            bot.setItemSlot(EquipmentSlot.FEET, this.feet.copy());
            bot.setItemSlot(EquipmentSlot.OFFHAND, this.offhand.copy());
        }

        public String summary() {
            List<String> parts = new ArrayList<>();

            for (ItemStack stack : this.hotbar) {
                if (!stack.isEmpty()) {
                    parts.add(stack.getCount() > 1 ? stack.getCount() + "x " + stack.getHoverName().getString() : stack.getHoverName().getString());
                }
            }

            for (ItemStack stack : List.of(this.head, this.chest, this.legs, this.feet, this.offhand)) {
                if (!stack.isEmpty()) {
                    parts.add(stack.getHoverName().getString());
                }
            }

            return parts.isEmpty() ? "leer" : String.join(", ", parts);
        }
    }
}
