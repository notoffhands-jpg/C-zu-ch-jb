package dev.armyfps.mixin;

import dev.armyfps.parallel.ParallelModels;
import net.minecraft.class_11661;
import net.minecraft.class_11683;
import net.minecraft.class_11788;
import net.minecraft.class_1921;
import net.minecraft.class_4588;
import net.minecraft.class_4597;
import net.minecraft.class_4618;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ModelCommandRenderer: build the frame's humanoid models on all cores before drawing
 * (render HEAD), then let each prepared command copy its finished vertices instead of being
 * posed and transformed on the render thread (the per-command draw).
 */
@Mixin(value = class_11683.class, remap = false)
public class ModelCommandRendererMixin {
    @Inject(method = "method_73001", at = @At("HEAD"), remap = false)
    private void armyfps$prepare(class_11788 queue, class_4597.class_4598 buffers, class_4618 outlines, class_4597.class_4598 crumbling, CallbackInfo ci) {
        ParallelModels.prepare((ModelCommandsAccess)queue.method_74771());
    }

    @Inject(method = "method_73001", at = @At("RETURN"), remap = false)
    private void armyfps$finish(class_11788 queue, class_4597.class_4598 buffers, class_4618 outlines, class_4597.class_4598 crumbling, CallbackInfo ci) {
        ParallelModels.finish();
    }

    @Inject(method = "method_73000", at = @At("HEAD"), cancellable = true, remap = false)
    private void armyfps$draw(class_11661.class_11670<?> command, class_1921 layer, class_4588 consumer, class_4618 outlines, class_4597.class_4598 crumbling, CallbackInfo ci) {
        if (ParallelModels.draw(command, consumer)) {
            ci.cancel();
        }
    }
}
