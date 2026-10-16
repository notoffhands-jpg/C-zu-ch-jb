package dev.batchsplit.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** BBS custom-size capture blits raw GL framebuffers, which VulkanMod cannot emulate: record at window size instead. */
@Mixin(targets = "mchorse.bbs_mod.client.BBSRendering", remap = false)
public class BbsVulkanSizeMixin {
    private static final boolean VULKAN = FabricLoader.getInstance().isModLoaded("vulkanmod");

    @ModifyVariable(method = "setCustomSize(ZII)V", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private static boolean batchsplit$noCustomSize(boolean customSize) {
        return customSize && !VULKAN;
    }
}
