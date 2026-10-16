package dev.batchsplit.mixin;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.class_287;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VulkanMod writes model vertices straight to memory only for the standard entity format; every
 * other format (above all the enchantment glint every enchanted armour piece is drawn into a
 * second time) goes element by element, each with a mask check and an offset lookup - 21 % of the
 * frame with 600 enchanted netherite players. This writes those formats directly too, at the
 * exact offsets of the format: same bytes in the same places, just without the per-element dance.
 * Applied after VulkanMod (priority) because the method is VulkanMod's.
 */
@Mixin(value = class_287.class, priority = 1500, remap = false)
public abstract class FastVertexMixin {
    @Shadow(remap = false)
    private VertexFormat field_1565;
    @Shadow(remap = false)
    private int field_52077;

    @Shadow(remap = false)
    protected abstract long method_60805();

    @Inject(method = "vertex(FFFIFFIII)V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void batchsplit$directVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, int normal, CallbackInfo ci) {
        VertexFormat format = this.field_1565;
        if (format == net.minecraft.class_290.field_1580) {
            return; // VulkanMod's own fast path
        }

        int colorAt = format.getOffset(VertexFormatElement.COLOR);
        int uv0At = format.getOffset(VertexFormatElement.UV0);
        int uv1At = format.getOffset(VertexFormatElement.UV1);
        int uv2At = format.getOffset(VertexFormatElement.UV2);
        int normalAt = format.getOffset(VertexFormatElement.NORMAL);
        int positionAt = format.getOffset(VertexFormatElement.POSITION);
        if (positionAt < 0) {
            return;
        }

        long ptr = this.method_60805();
        MemoryUtil.memPutFloat(ptr + positionAt, x);
        MemoryUtil.memPutFloat(ptr + positionAt + 4L, y);
        MemoryUtil.memPutFloat(ptr + positionAt + 8L, z);
        if (colorAt >= 0) {
            MemoryUtil.memPutInt(ptr + colorAt, color);
        }
        if (uv0At >= 0) {
            MemoryUtil.memPutFloat(ptr + uv0At, u);
            MemoryUtil.memPutFloat(ptr + uv0At + 4L, v);
        }
        if (uv1At >= 0) {
            MemoryUtil.memPutInt(ptr + uv1At, overlay);
        }
        if (uv2At >= 0) {
            MemoryUtil.memPutInt(ptr + uv2At, light);
        }
        if (normalAt >= 0) {
            MemoryUtil.memPutInt(ptr + normalAt, normal);
        }
        this.field_52077 = 0; // every element of this vertex is written
        ci.cancel();
    }
}
