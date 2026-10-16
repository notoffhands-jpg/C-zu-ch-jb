package dev.batchsplit.mixin;

import net.minecraft.class_287;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** BufferBuilder's vertex counter. */
@Mixin(value = class_287.class, remap = false)
public interface BufferBuilderAccess {
    @Accessor(value = "field_1554", remap = false)
    int batchsplit$vertexCount();
}
