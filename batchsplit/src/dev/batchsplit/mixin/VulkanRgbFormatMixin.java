package dev.batchsplit.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** BBS film capture allocates GL_RGB / GL_RGB8 textures; VulkanMod only knows RGBA. Store them as RGBA8. */
@Mixin(targets = "net.vulkanmod.gl.GlUtil", remap = false)
public class VulkanRgbFormatMixin {
    @Inject(method = "vulkanFormat(II)I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void batchsplit$rgbAsRgba(int glFormat, int type, CallbackInfoReturnable<Integer> cir) {
        if (glFormat == 6407 || glFormat == 32849) {
            cir.setReturnValue(37); // VK_FORMAT_R8G8B8A8_UNORM
        }
    }
}
