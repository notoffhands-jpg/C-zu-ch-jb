package dev.batchsplit;

import net.minecraft.class_4587;
import org.joml.Matrix4f;

/**
 * Distance level of detail for details that are invisible far away: enchantment glint and armour
 * trims on bots/players beyond a few dozen blocks (a player there is only a few dozen pixels tall).
 * Only while the world is rendered - never in the GUI, inventory preview or first person.
 * Distances in blocks via -Dbatchsplit.glint=32 / -Dbatchsplit.trim=24 (0 = always draw).
 */
public final class Lod {
    public static final double GLINT = Double.parseDouble(System.getProperty("batchsplit.glint", "32"));
    public static final double TRIM = Double.parseDouble(System.getProperty("batchsplit.trim", "24"));
    /** True while GameRenderer.renderWorld runs. */
    public static boolean inWorld;

    private Lod() {
    }

    /** Distance of the current pose from the camera (entity poses are camera-relative). */
    public static boolean beyond(class_4587 poses, double distance) {
        if (!inWorld || distance <= 0.0) {
            return false;
        }
        Matrix4f pose = poses.method_23760().method_23761();
        float x = pose.m30();
        float y = pose.m31();
        float z = pose.m32();
        return x * x + y * y + z * z > distance * distance;
    }
}
