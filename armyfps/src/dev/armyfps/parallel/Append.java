package dev.armyfps.parallel;

import com.mojang.blaze3d.vertex.VertexFormat;
import dev.armyfps.mixin.BufferBuilderAccess;
import net.minecraft.class_287;
import net.minecraft.class_4588;
import org.lwjgl.system.MemoryUtil;

/** Copies finished vertices into a BufferBuilder in one block - what n vertex() calls would leave behind. */
final class Append {
    private Append() {
    }

    /** Can this consumer take vertices encoded with this layout? */
    static boolean fits(class_4588 consumer, Layout layout, int vertices) {
        if (!(consumer instanceof class_287)) {
            return false;
        }

        BufferBuilderAccess buffer = (BufferBuilderAccess)consumer;
        return buffer.armyfps$format() == layout.format
            && buffer.armyfps$drawMode() != VertexFormat.class_5596.field_27377
            && buffer.armyfps$vertexCount() + vertices <= 16777215;
    }

    /** Call only after fits(). */
    static void copy(class_4588 consumer, long from, Layout layout, int vertices) {
        if (vertices == 0) {
            return;
        }

        BufferBuilderAccess buffer = (BufferBuilderAccess)consumer;
        buffer.armyfps$ensureBuilding();
        buffer.armyfps$endLastVertex();
        long bytes = (long)vertices * layout.size;
        long to = buffer.armyfps$allocator().method_60808((int)bytes);
        MemoryUtil.memCopy(from, to, bytes);
        buffer.armyfps$setVertexCount(buffer.armyfps$vertexCount() + vertices);
        buffer.armyfps$setVertexPointer(to + bytes - layout.size);
    }
}
