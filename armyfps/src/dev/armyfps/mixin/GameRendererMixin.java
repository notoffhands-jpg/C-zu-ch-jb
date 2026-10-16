package dev.armyfps.mixin;

import dev.armyfps.ArmyFps;
import dev.armyfps.Frames;
import net.minecraft.class_310;
import net.minecraft.class_757;
import net.minecraft.class_9779;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GameRenderer: the toggle key once per frame, the world-pass flag (LOD only applies there) and
 * the frame timing for Flight Recordings.
 */
@Mixin(value = class_757.class, remap = false)
public class GameRendererMixin {
    @Inject(method = "method_3192", at = @At("HEAD"), remap = false)
    private void armyfps$frameStart(class_9779 tick, boolean tickWorld, CallbackInfo ci) {
        ArmyFps.renderThread = Thread.currentThread();
        Frames.frameStart();
        ArmyFps.pollToggle(class_310.method_1551());
    }

    @Inject(method = "method_3192", at = @At("RETURN"), remap = false)
    private void armyfps$frameEnd(class_9779 tick, boolean tickWorld, CallbackInfo ci) {
        Frames.frameEnd();
    }

    @Inject(method = "method_3188", at = @At("HEAD"), remap = false)
    private void armyfps$worldStart(class_9779 tick, CallbackInfo ci) {
        ArmyFps.inWorld = true;
        Frames.worldStart();
    }

    @Inject(method = "method_3188", at = @At("RETURN"), remap = false)
    private void armyfps$worldEnd(class_9779 tick, CallbackInfo ci) {
        ArmyFps.inWorld = false;
        Frames.worldEnd();
    }
}
