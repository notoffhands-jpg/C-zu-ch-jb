package dev.armyfps.mixin;

import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import net.minecraft.class_11899;
import net.minecraft.class_9799;
import net.minecraft.class_9801;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** BuiltBuffer: vanilla's quad centres and the sorted index buffer that sortQuads fills in. */
@Mixin(value = class_9801.class, remap = false)
public interface BuiltBufferAccess {
    @Accessor(value = "field_52094", remap = false)
    void armyfps$setSortedBuffer(class_9799.class_9800 indices);

    /** collectCentroids */
    @Invoker(value = "method_60820", remap = false)
    static class_11899 armyfps$collectCentroids(ByteBuffer vertices, int vertexCount, VertexFormat format) {
        throw new AssertionError();
    }
}
