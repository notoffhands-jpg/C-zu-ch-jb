package dev.armyfps.parallel;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.armyfps.ArmyFps;
import dev.armyfps.FastSort;
import dev.armyfps.mixin.BufferBuilderAccess;
import dev.armyfps.mixin.BuiltBufferAccess;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.class_11899;
import net.minecraft.class_8251;
import net.minecraft.class_9799;
import net.minecraft.class_9801;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

/**
 * Multi-core back-to-front sort of a finished see-through buffer, with exactly vanilla's result
 * (BuiltBuffer.sortQuads): vanilla's own quad centres, the key is the camera distance squared
 * (perspective) or -z (orthographic), quads are ordered by descending key with ties in buffer
 * order, and every quad becomes the indices 0,1,2,2,3,0. Keys and the index list are computed on
 * all cores; the sort itself is the tested radix FastSort.
 */
public final class ParallelSort {
    private static final Vector3f ORIGIN = new Vector3f();
    private static final ThreadLocal<Vector3f> CENTROID = ThreadLocal.withInitial(Vector3f::new);
    private static final MethodHandle SHADERS_ON = irisShadersOn();
    private static float[] keys = new float[0];

    private ParallelSort() {
    }

    /** Take over this layer's sort? Only big quad buffers, only without a shader pack. */
    public static boolean wants(BufferBuilderAccess builder) {
        return ArmyFps.enabled && ArmyFps.parallel && ArmyFps.parallelSort
            && Thread.currentThread() == ArmyFps.renderThread
            && builder.armyfps$vertexCount() >= ArmyFps.sortMinQuads * 4
            && builder.armyfps$drawMode() == VertexFormat.class_5596.field_27382
            && !shadersOn();
    }

    /** BuiltBuffer.sortQuads(allocator, current projection's sorter), multi-core. */
    public static void sortQuads(class_9801 built, class_9799 allocator) {
        class_9801.class_4574 draw = built.method_60822();
        if (draw.comp_752() != VertexFormat.class_5596.field_27382) {
            return;
        }

        class_8251 sorter = RenderSystem.getProjectionType().method_65045();
        boolean byDistance = sorter == class_8251.field_43360;
        if (!byDistance && sorter != class_8251.field_43361) {
            // Unknown sorter: let vanilla do it exactly.
            built.method_60819(allocator, sorter);
            return;
        }

        // Vanilla's own centres (exact same floats), then the keys on all cores.
        class_11899 centroids = BuiltBufferAccess.armyfps$collectCentroids(built.method_60818(), draw.comp_750(), draw.comp_749());
        int quads = centroids.method_74170();
        if (keys.length < quads) {
            keys = new float[Math.max(quads, keys.length * 2)];
        }

        float[] k = keys;
        boolean ok;
        try {
            ok = Pool.runShared(quads, (worker, q) -> {
                Vector3f c = CENTROID.get();
                centroids.method_74173(q, c);
                k[q] = byDistance ? ORIGIN.distanceSquared(c) : -c.z();
            });
        } catch (InterruptedException interrupted) {
            ok = false;
        }

        if (!ok) {
            built.method_60819(allocator, sorter);
            return;
        }

        int[] order = FastSort.sortDescending(k, quads);
        VertexFormat.class_5595 indexType = draw.comp_753();
        boolean shorts = indexType == VertexFormat.class_5595.field_27372;
        int indexBytes = shorts ? 2 : 4;
        long out = allocator.method_60808(quads * 6 * indexBytes);
        try {
            ok = Pool.runShared(quads, (worker, rank) -> {
                int first = order[rank] * 4;
                long at = out + (long)rank * 6 * indexBytes;
                if (shorts) {
                    MemoryUtil.memPutShort(at, (short)first);
                    MemoryUtil.memPutShort(at + 2L, (short)(first + 1));
                    MemoryUtil.memPutShort(at + 4L, (short)(first + 2));
                    MemoryUtil.memPutShort(at + 6L, (short)(first + 2));
                    MemoryUtil.memPutShort(at + 8L, (short)(first + 3));
                    MemoryUtil.memPutShort(at + 10L, (short)first);
                } else {
                    MemoryUtil.memPutInt(at, first);
                    MemoryUtil.memPutInt(at + 4L, first + 1);
                    MemoryUtil.memPutInt(at + 8L, first + 2);
                    MemoryUtil.memPutInt(at + 12L, first + 2);
                    MemoryUtil.memPutInt(at + 16L, first + 3);
                    MemoryUtil.memPutInt(at + 20L, first);
                }
            });
        } catch (InterruptedException interrupted) {
            ok = false;
        }

        if (!ok) {
            // The reserved bytes are written by the render thread instead - same result.
            for (int rank = 0; rank < quads; rank++) {
                int first = order[rank] * 4;
                long at = out + (long)rank * 6 * indexBytes;
                int[] six = {first, first + 1, first + 2, first + 2, first + 3, first};
                for (int i = 0; i < 6; i++) {
                    if (shorts) {
                        MemoryUtil.memPutShort(at + i * 2L, (short)six[i]);
                    } else {
                        MemoryUtil.memPutInt(at + i * 4L, six[i]);
                    }
                }
            }
        }

        ((BuiltBufferAccess)(Object)built).armyfps$setSortedBuffer(allocator.method_60807());
    }

    private static boolean shadersOn() {
        if (SHADERS_ON == null) {
            return false;
        }

        try {
            return (boolean)SHADERS_ON.invoke();
        } catch (Throwable error) {
            return true;
        }
    }

    /** Iris' public API, if Iris is installed: is a shader pack in use? */
    private static MethodHandle irisShadersOn() {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            return MethodHandles.publicLookup().findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class)).bindTo(instance);
        } catch (Throwable notInstalled) {
            return null;
        }
    }
}
