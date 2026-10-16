package dev.armyfps.mixin;

import dev.armyfps.ArmyFps;
import dev.armyfps.TextCache;
import net.minecraft.class_2561;
import net.minecraft.class_327;
import net.minecraft.class_4597;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TextRenderer.draw(Text, ...) - the one name tags use: draw the kept layout instead of laying it out again. */
@Mixin(value = class_327.class, remap = false)
public class TextRendererMixin {
    @Inject(method = "method_27522(Lnet/minecraft/class_2561;FFIZLorg/joml/Matrix4f;Lnet/minecraft/class_4597;Lnet/minecraft/class_327$class_6415;II)V",
        at = @At("HEAD"), cancellable = true, remap = false)
    private void armyfps$cachedText(class_2561 text, float x, float y, int color, boolean shadow, Matrix4f matrix, class_4597 buffers,
        class_327.class_6415 layerType, int background, int light, CallbackInfo ci) {
        if (!ArmyFps.enabled || !ArmyFps.textCache) {
            return;
        }

        class_327.class_11465 prepared = TextCache.get((class_327)(Object)this, text, x, y, color, shadow, background);
        if (prepared != null) {
            prepared.method_71801(class_327.class_11464.method_71799(buffers, matrix, layerType, light));
            ci.cancel();
        }
    }
}
