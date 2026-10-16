package dev.armyfps.mixin;

import dev.armyfps.ArmyFps;
import dev.armyfps.Frames;
import net.minecraft.class_10055;
import net.minecraft.class_1007;
import net.minecraft.class_11890;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts the players (bots) whose render state is filled each frame, for the frame recording. */
@Mixin(value = class_1007.class, remap = false)
public class PlayerCountMixin {
    @Inject(method = "method_62604(Lnet/minecraft/class_11890;Lnet/minecraft/class_10055;F)V", at = @At("RETURN"), remap = false)
    private void armyfps$count(class_11890 entity, class_10055 state, float tickDelta, CallbackInfo ci) {
        if (ArmyFps.inWorld) {
            Frames.players++;
        }
    }
}
