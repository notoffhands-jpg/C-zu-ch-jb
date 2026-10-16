package dev.armyfps.mixin;

import net.minecraft.class_4588;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** VertexConsumers.Dual: the two buffers an enchanted item writes to (glint first, item second). */
@Mixin(targets = "net.minecraft.class_4720$class_4589", remap = false)
public interface DualAccess {
    @Accessor(value = "field_21685", remap = false)
    class_4588 armyfps$first();

    @Accessor(value = "field_21686", remap = false)
    class_4588 armyfps$second();
}
