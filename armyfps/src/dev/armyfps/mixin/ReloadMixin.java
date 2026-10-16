package dev.armyfps.mixin;

import dev.armyfps.TextCache;
import net.minecraft.class_310;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Resource reload (fonts may change): forget kept text layouts. */
@Mixin(value = class_310.class, remap = false)
public class ReloadMixin {
    @Inject(method = "method_36561", at = @At("HEAD"), remap = false)
    private void armyfps$reload(CallbackInfoReturnable<?> cir) {
        TextCache.clear();
    }
}
