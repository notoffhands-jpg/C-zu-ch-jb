package dev.armyfps.parallel;

import dev.armyfps.mixin.ModelPartAccess;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.class_1058;
import net.minecraft.class_11661;
import net.minecraft.class_3532;
import net.minecraft.class_3879;
import net.minecraft.class_4587;
import net.minecraft.class_630;
import net.minecraft.class_9848;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

/**
 * One thread's workspace: its own copies of the models, its own matrix stack and an off-heap
 * arena it writes finished vertices into - byte for byte what BufferBuilder writes for the
 * entity vertex format (36 bytes: position, colour, uv, overlay, light, normal).
 */
final class Worker {
    static final int STRIDE = 36;
    private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

    final int id;
    private final IdentityHashMap<class_3879<?>, class_3879<?>> clones = new IdentityHashMap<>();
    private final class_4587 poses = new class_4587();
    private final Vector3f position = new Vector3f();
    private final Vector3f normal = new Vector3f();
    long arena;
    private long capacity;
    long used;

    Worker(int id) {
        this.id = id;
    }

    void reset() {
        this.used = 0L;
        // Models get replaced on resource reload; old copies must not pile up.
        if (this.clones.size() > 256) {
            this.clones.clear();
        }
    }

    /**
     * Poses this thread's copy of the command's model and writes its vertices. Returns the
     * vertex count; the vertices start at the returned offset recorded by the caller.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    int build(class_11661.class_11670<?> command) throws ReflectiveOperationException {
        class_3879 model = this.clones.get(command.comp_4505());
        if (model == null) {
            model = (class_3879)ModelCloner.cloneModel(command.comp_4505());
            this.clones.put(command.comp_4505(), model);
        }

        // Exactly what ModelCommandRenderer does: copy the submitted pose, pose the model, render.
        model.method_2819(command.comp_4506());
        this.poses.method_22903();
        this.poses.method_23760().method_66521(command.comp_4504());
        int start = this.vertices();
        this.part(model.method_63512(), command.comp_4510(), class_9848.method_61337(command.comp_4509()), command.comp_4508(), command.comp_4507());
        this.poses.method_22909();
        return this.vertices() - start;
    }

    int vertices() {
        return (int)(this.used / STRIDE);
    }

    /** ModelPart.render, without the GPU-side consumer: same visibility rules, transform and order. */
    private void part(class_630 part, class_1058 sprite, int abgr, int overlay, int light) {
        if (!part.field_3665) {
            return;
        }

        ModelPartAccess access = (ModelPartAccess)(Object)part;
        List<class_630.class_628> cuboids = access.armyfps$cuboids();
        Map<String, class_630> children = access.armyfps$children();
        if (cuboids.isEmpty() && children.isEmpty()) {
            return;
        }

        this.poses.method_22903();
        part.method_22703(this.poses);
        if (!part.field_38456) {
            class_4587.class_4665 entry = this.poses.method_23760();
            Matrix4f matrix = entry.method_23761();
            for (class_630.class_628 cuboid : cuboids) {
                this.cuboid(cuboid, entry, matrix, sprite, abgr, overlay, light);
            }
        }

        for (class_630 child : children.values()) {
            this.part(child, sprite, abgr, overlay, light);
        }

        this.poses.method_22909();
    }

    /** ModelPart.Cuboid.renderCuboid, writing straight into the arena. */
    private void cuboid(class_630.class_628 cuboid, class_4587.class_4665 entry, Matrix4f matrix, class_1058 sprite, int abgr, int overlay, int light) {
        for (class_630.class_593 quad : cuboid.field_3649) {
            Vector3f n = entry.method_56821(quad.comp_3185(), this.normal);
            byte nx = normalByte(n.x());
            byte ny = normalByte(n.y());
            byte nz = normalByte(n.z());
            class_630.class_618[] vertices = quad.comp_3184();
            long at = this.reserve(vertices.length);
            for (class_630.class_618 vertex : vertices) {
                Vector3f p = matrix.transformPosition(vertex.method_74250(), vertex.method_74251(), vertex.method_74252(), this.position);
                float u = vertex.comp_3187();
                float v = vertex.comp_3188();
                if (sprite != null) {
                    u = sprite.method_4580(u);
                    v = sprite.method_4570(v);
                }

                MemoryUtil.memPutFloat(at, p.x());
                MemoryUtil.memPutFloat(at + 4L, p.y());
                MemoryUtil.memPutFloat(at + 8L, p.z());
                MemoryUtil.memPutInt(at + 12L, LITTLE_ENDIAN ? abgr : Integer.reverseBytes(abgr));
                MemoryUtil.memPutFloat(at + 16L, u);
                MemoryUtil.memPutFloat(at + 20L, v);
                putShorts(at + 24L, overlay);
                putShorts(at + 28L, light);
                MemoryUtil.memPutByte(at + 32L, nx);
                MemoryUtil.memPutByte(at + 33L, ny);
                MemoryUtil.memPutByte(at + 34L, nz);
                MemoryUtil.memPutByte(at + 35L, (byte)0);
                at += STRIDE;
            }
        }
    }

    /** BufferBuilder's packing of overlay/light coordinates (two shorts). */
    private static void putShorts(long at, int packed) {
        if (LITTLE_ENDIAN) {
            MemoryUtil.memPutInt(at, packed);
        } else {
            MemoryUtil.memPutShort(at, (short)(packed & 0xFFFF));
            MemoryUtil.memPutShort(at + 2L, (short)(packed >> 16 & 0xFFFF));
        }
    }

    /** BufferBuilder's normal packing. */
    private static byte normalByte(float value) {
        return (byte)((int)(class_3532.method_15363(value, -1.0F, 1.0F) * 127.0F) & 0xFF);
    }

    /** Space for n vertices; returns their address. Grows the arena (offsets stay valid). */
    private long reserve(int count) {
        long need = this.used + (long)count * STRIDE;
        if (need > this.capacity) {
            long grown = Math.max(need, Math.max(1L << 20, this.capacity * 2L));
            this.arena = this.arena == 0L ? MemoryUtil.nmemAlloc(grown) : MemoryUtil.nmemRealloc(this.arena, grown);
            if (this.arena == 0L) {
                throw new OutOfMemoryError("ArmyFPS vertex arena");
            }

            this.capacity = grown;
        }

        long at = this.arena + this.used;
        this.used = need;
        return at;
    }
}
