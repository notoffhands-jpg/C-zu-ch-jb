package dev.armyfps;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.class_310;
import net.minecraft.class_3675;
import net.minecraft.class_4587;
import org.joml.Matrix4f;

/**
 * Settings and the distance test shared by all ArmyFPS hooks.
 *
 * Everything here only applies while the world is drawn (never the GUI, the inventory doll or
 * first person) and only to things far from the camera, where a player is a few dozen pixels tall
 * and the skipped detail is invisible. Distances are in blocks, set in config/armyfps.properties.
 */
public final class ArmyFps {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("armyfps.properties");

    /** Armour enchantment glint is drawn up to this distance. */
    public static double armorGlint = 32.0;
    /** Armour trims are drawn up to this distance. */
    public static double trim = 24.0;
    /** Glint on held and dropped items is drawn up to this distance. */
    public static double itemGlint = 32.0;
    /** Faster translucent sort with an identical result. */
    public static boolean fastSort = true;
    /** Pose and build players, bots and their armour on several CPU cores. */
    public static boolean parallel = true;
    /** Threads for that, the render thread included. */
    public static int parallelThreads = Math.max(2, Math.min(12, Runtime.getRuntime().availableProcessors() / 2));
    /** Below this many humanoid models in a frame, the render thread does it alone (not worth it). */
    public static int parallelMinCommands = 48;
    /** GLFW key code of the on/off key (297 = F8). */
    public static int toggleKey = 297;

    /** Switched by the toggle key, for comparing FPS with and without. */
    public static boolean enabled = true;
    /** True while GameRenderer.renderWorld runs. */
    public static boolean inWorld;
    private static boolean keyWasDown;

    static {
        load();
    }

    private ArmyFps() {
    }

    /** Is the current pose (entity poses are camera-relative) farther away than this distance? */
    public static boolean beyond(class_4587 poses, double distance) {
        if (!enabled || !inWorld || distance <= 0.0) {
            return false;
        }

        Matrix4f pose = poses.method_23760().method_23761();
        float x = pose.m30();
        float y = pose.m31();
        float z = pose.m32();
        return x * x + y * y + z * z > distance * distance;
    }

    /** Once per frame: the toggle key (only while no screen is open, so typing doesn't trigger it). */
    public static void pollToggle(class_310 client) {
        boolean down = client.field_1755 == null && class_3675.method_15987(client.method_22683(), toggleKey);
        if (down && !keyWasDown) {
            enabled = !enabled;
            if (client.field_1705 != null) {
                client.field_1705.method_1758(net.minecraft.class_2561.method_43470("ArmyFPS " + (enabled ? "ON" : "OFF")), false);
            }
        }

        keyWasDown = down;
    }

    private static void load() {
        Properties properties = new Properties();
        if (Files.exists(FILE)) {
            try (Reader reader = Files.newBufferedReader(FILE)) {
                properties.load(reader);
            } catch (IOException | IllegalArgumentException ignored) {
            }
        }

        armorGlint = number(properties, "armorGlintDistance", armorGlint);
        trim = number(properties, "trimDistance", trim);
        itemGlint = number(properties, "itemGlintDistance", itemGlint);
        fastSort = !"false".equalsIgnoreCase(properties.getProperty("fastSort", "true").trim());
        toggleKey = (int)number(properties, "toggleKey", toggleKey);
        parallel = !"false".equalsIgnoreCase(properties.getProperty("parallel", "true").trim());
        parallelThreads = Math.max(1, (int)number(properties, "parallelThreads", parallelThreads));
        parallelMinCommands = Math.max(1, (int)number(properties, "parallelMinCommands", parallelMinCommands));
        save();
    }

    private static double number(Properties properties, String key, double fallback) {
        try {
            return Double.parseDouble(properties.getProperty(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException wrong) {
            return fallback;
        }
    }

    /** Writes the file back with every key, so all settings are visible and editable. */
    private static void save() {
        Properties properties = new Properties();
        properties.setProperty("armorGlintDistance", String.valueOf(armorGlint));
        properties.setProperty("trimDistance", String.valueOf(trim));
        properties.setProperty("itemGlintDistance", String.valueOf(itemGlint));
        properties.setProperty("fastSort", String.valueOf(fastSort));
        properties.setProperty("toggleKey", String.valueOf(toggleKey));
        properties.setProperty("parallel", String.valueOf(parallel));
        properties.setProperty("parallelThreads", String.valueOf(parallelThreads));
        properties.setProperty("parallelMinCommands", String.valueOf(parallelMinCommands));
        try (Writer writer = Files.newBufferedWriter(FILE)) {
            properties.store(writer, "ArmyFPS - distances in blocks (0 = never skip). toggleKey is a GLFW key code, 297 = F8. Restart the game after editing.");
        } catch (IOException ignored) {
        }
    }
}
