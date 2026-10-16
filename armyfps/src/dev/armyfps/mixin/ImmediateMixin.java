package dev.armyfps.mixin;

import dev.armyfps.parallel.ParallelSort;
import java.util.SequencedMap;
import net.minecraft.class_1921;
import net.minecraft.class_287;
import net.minecraft.class_4597;
import net.minecraft.class_9799;
import net.minecraft.class_9801;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VertexConsumerProvider.Immediate's per-layer draw. For big see-through layers (an army's skins
 * and held items) the same steps as vanilla - end the buffer, sort its quads back to front, draw,
 * forget the current layer - but the sort runs on all cores. Small layers, and everything while
 * a shader pack is active, go the usual way (Sodium's sort).
 */
@Mixin(value = class_4597.class_4598.class, remap = false)
public class ImmediateMixin {
    @Shadow(remap = false)
    @Final
    protected class_9799 field_52156;

    @Shadow(remap = false)
    @Final
    protected SequencedMap<class_1921, class_9799> field_20953;

    @Shadow(remap = false)
    protected class_1921 field_52158;

    @Inject(method = "method_60893", at = @At("HEAD"), cancellable = true, remap = false)
    private void armyfps$drawSorted(class_1921 layer, class_287 builder, CallbackInfo ci) {
        if (!layer.method_60894() || !ParallelSort.wants((BufferBuilderAccess)builder)) {
            return;
        }

        class_9801 built = builder.method_60794();
        if (built != null) {
            class_9799 allocator = this.field_20953.getOrDefault(layer, this.field_52156);
            ParallelSort.sortQuads(built, allocator);
            layer.method_60895(built);
        }

        if (layer.equals(this.field_52158)) {
            this.field_52158 = null;
        }

        ci.cancel();
    }
}
