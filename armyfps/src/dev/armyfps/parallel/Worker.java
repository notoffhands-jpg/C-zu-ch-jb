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
import net.minecraft.class_5611;
import net.minecraft.class_630;
import net.minecraft.class_765;
import net.minecraft.class_777;
import net.minecraft.class_9848;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;

/**
 * One thread's workspace: its own copies of the models, its own matrix stack and an off-heap
 * arena it writes finished vertices into - byte for byte what BufferBuilder writes for the
 * target vertex format.
 */
final class Worker {
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

    // ------------------------------------------------------------------ models

    /**
     * ModelCommandRenderer's per-command draw on this thread's copy of the model: copy the
     * submitted pose, pose the model, walk its parts. Returns the vertex count.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    int buildModel(class_11661.class_11670<?> command, Layout layout) throws ReflectiveOperationException {
        class_3879 model = this.clones.get(command.comp_4505());
        if (model == null) {
            model = (class_3879)ModelCloner.cloneModel(command.comp_4505());
            this.clones.put(command.comp_4505(), model);
        }

        model.method_2819(command.comp_4506());
        this.poses.method_22903();
        this.poses.method_23760().method_66521(command.comp_4504());
        long start = this.used;
        this.part(model.method_63512(), layout, command.comp_4510(), abgr(command.comp_4509()), command.comp_4508(), command.comp_4507());
        this.poses.method_22909();
        return (int)((this.used - start) / layout.size);
    }

    /** ModelPart.render without a consumer: same visibility rules, transform and order. */
    private void part(class_630 part, Layout layout, class_1058 sprite, int abgr, int overlay, int light) {
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
                for (class_630.class_593 quad : cuboid.field_3649) {
                    Vector3f n = entry.method_56821(quad.comp_3185(), this.normal);
                    float nx = n.x();
                    float ny = n.y();
                    float nz = n.z();
                    class_630.class_618[] vertices = quad.comp_3184();
                    long at = this.reserve((long)vertices.length * layout.size);
                    for (class_630.class_618 vertex : vertices) {
                        Vector3f p = matrix.transformPosition(vertex.method_74250(), vertex.method_74251(), vertex.method_74252(), this.position);
                        float u = vertex.comp_3187();
                        float v = vertex.comp_3188();
                        if (sprite != null) {
                            u = sprite.method_4580(u);
                            v = sprite.method_4570(v);
                        }

                        put(layout, at, p.x(), p.y(), p.z(), abgr, u, v, overlay, light, nx, ny, nz);
                        at += layout.size;
                    }
                }
            }
        }

        for (class_630 child : children.values()) {
            this.part(child, layout, sprite, abgr, overlay, light);
        }

        this.poses.method_22909();
    }

    // ------------------------------------------------------------------ items

    /**
     * ItemRenderer.renderBakedItemQuads + VertexConsumer.quad for one item command. Writes the
     * item stream (layout) and, for enchanted items, the glint stream right after it (same
     * vertices, glint layout). Returns the vertex count of one stream.
     */
    int buildItem(class_11661.class_11668 command, Layout layout, Layout glint) {
        List<class_777> quads = command.comp_4640();
        int vertices = quads.size() * 4;
        long base = this.reserve((long)vertices * (layout.size + (glint == null ? 0 : glint.size)));
        long glintBase = base + (long)vertices * layout.size;
        class_4587.class_4665 entry = command.comp_4498();
        Matrix4f matrix = entry.method_23761();
        int[] tints = command.comp_4639();
        int light = command.comp_4500();
        int overlay = command.comp_4501();
        int index = 0;
        for (class_777 quad : quads) {
            float r = 1.0F;
            float g = 1.0F;
            float b = 1.0F;
            float a = 1.0F;
            if (quad.method_3360()) {
                int tintIndex = quad.comp_3722();
                int tint = tintIndex >= 0 && tintIndex < tints.length ? tints[tintIndex] : -1;
                a = class_9848.method_61320(tint) / 255.0F;
                r = class_9848.method_61327(tint) / 255.0F;
                g = class_9848.method_61329(tint) / 255.0F;
                b = class_9848.method_61331(tint) / 255.0F;
            }

            Vector3f n = entry.method_56821(quad.comp_3723().method_68072(), this.normal);
            float nx = n.x();
            float ny = n.y();
            float nz = n.z();
            int color = abgr(class_9848.method_61318(a, 1.0F * r, 1.0F * g, 1.0F * b));
            int quadLight = class_765.method_62228(light, quad.comp_3726());
            for (int corner = 0; corner < 4; corner++) {
                Vector3fc corner3 = quad.method_76648(corner);
                long uv = quad.method_76649(corner);
                Vector3f p = matrix.transformPosition(corner3, this.position);
                float u = class_5611.method_76641(uv);
                float v = class_5611.method_76642(uv);
                put(layout, base + (long)index * layout.size, p.x(), p.y(), p.z(), color, u, v, overlay, quadLight, nx, ny, nz);
                if (glint != null) {
                    put(glint, glintBase + (long)index * glint.size, p.x(), p.y(), p.z(), color, u, v, overlay, quadLight, nx, ny, nz);
                }

                index++;
            }
        }

        return vertices;
    }

    // ------------------------------------------------------------------ encoding

    /** One vertex the way BufferBuilder writes it: each element the format has, at its offset. */
    private static void put(Layout layout, long at, float x, float y, float z, int abgr, float u, float v, int overlay, int light, float nx, float ny, float nz) {
        long p = at + layout.position;
        MemoryUtil.memPutFloat(p, x);
        MemoryUtil.memPutFloat(p + 4L, y);
        MemoryUtil.memPutFloat(p + 8L, z);
        if (layout.color >= 0) {
            MemoryUtil.memPutInt(at + layout.color, LITTLE_ENDIAN ? abgr : Integer.reverseBytes(abgr));
        }

        if (layout.uv0 >= 0) {
            MemoryUtil.memPutFloat(at + layout.uv0, u);
            MemoryUtil.memPutFloat(at + layout.uv0 + 4L, v);
        }

        if (layout.uv1 >= 0) {
            putShorts(at + layout.uv1, overlay);
        }

        if (layout.uv2 >= 0) {
            putShorts(at + layout.uv2, light);
        }

        if (layout.normal >= 0) {
            MemoryUtil.memPutByte(at + layout.normal, normalByte(nx));
            MemoryUtil.memPutByte(at + layout.normal + 1L, normalByte(ny));
            MemoryUtil.memPutByte(at + layout.normal + 2L, normalByte(nz));
        }
    }

    /** BufferBuilder's colour packing: ARGB -> ABGR. */
    private static int abgr(int argb) {
        return class_9848.method_61337(argb);
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

    /** Space for some bytes; returns their address. Grows the arena (offsets stay valid). */
    private long reserve(long bytes) {
        long need = this.used + bytes;
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
