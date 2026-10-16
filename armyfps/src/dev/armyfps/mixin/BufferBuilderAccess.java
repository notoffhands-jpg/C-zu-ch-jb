package dev.armyfps.mixin;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.class_287;
import net.minecraft.class_9799;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** BufferBuilder internals, to append a block of finished vertices in one copy. */
@Mixin(value = class_287.class, remap = false)
public interface BufferBuilderAccess {
    @Accessor(value = "field_52071", remap = false)
    class_9799 armyfps$allocator();

    @Accessor(value = "field_1565", remap = false)
    VertexFormat armyfps$format();

    @Accessor(value = "field_52073", remap = false)
    VertexFormat.class_5596 armyfps$drawMode();

    @Accessor(value = "field_21594", remap = false)
    boolean armyfps$fastFormat();

    @Accessor(value = "field_1554", remap = false)
    int armyfps$vertexCount();

    @Accessor(value = "field_1554", remap = false)
    void armyfps$setVertexCount(int count);

    @Accessor(value = "field_52072", remap = false)
    void armyfps$setVertexPointer(long pointer);

    /** ensureBuilding */
    @Invoker(value = "method_60802", remap = false)
    void armyfps$ensureBuilding();

    /** endLastVertex: checks the previous vertex is complete */
    @Invoker(value = "method_60806", remap = false)
    void armyfps$endLastVertex();
}
