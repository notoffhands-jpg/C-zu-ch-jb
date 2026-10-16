package dev.batchsplit.mixin;

import dev.batchsplit.Lod;
import net.minecraft.class_757;
import net.minecraft.class_9779;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks the world render pass (LOD only applies there, never to GUI items or the inventory doll). */
@Mixin(value = class_757.class, remap = false)
public class WorldRenderFlagMixin {
    @Inject(method = "method_3188", at = @At("HEAD"), remap = false)
    private void batchsplit$worldStart(class_9779 tick, CallbackInfo ci) {
        Lod.inWorld = true;
    }

    @Inject(method = "method_3188", at = @At("RETURN"), remap = false)
    private void batchsplit$worldEnd(class_9779 tick, CallbackInfo ci) {
        Lod.inWorld = false;
    }
}
