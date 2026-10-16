package dev.batchsplit.mixin;

import java.util.Map;
import net.minecraft.class_1921;
import net.minecraft.class_287;
import net.minecraft.class_4588;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * VertexConsumerProvider.Immediate collects everything of one render layer into one draw call
 * (all shield banner patterns on screen share one layer, so do all players with the same skin).
 * With hundreds of bots such a draw passes 65536 vertices - more than 16-bit indices can address -
 * and under VulkanMod the whole draw then breaks: blank white shields, invisible bots.
 *
 * Before a layer's batch gets that big it is drawn and a fresh one started. Each draw stays far
 * below the limit; nothing changes visually (it's the same as when two different layers alternate).
 */
@Mixin(targets = "net.minecraft.class_4597$class_4598", remap = false)
public abstract class SplitLargeBatchesMixin {
    /** Vertices per draw before splitting: leaves room for a whole model added after the check. */
    private static final int LIMIT = Integer.getInteger("batchsplit.limit", 49152);

    @Shadow(remap = false)
    @Final
    protected Map<class_1921, class_287> field_52157;

    @Shadow(remap = false)
    public abstract void method_22994(class_1921 layer);

    @Inject(method = "method_73477", at = @At("HEAD"), remap = false)
    private void batchsplit$splitBeforeOverflow(class_1921 layer, CallbackInfoReturnable<class_4588> cir) {
        class_287 builder = this.field_52157.get(layer);
        if (builder != null && ((BufferBuilderAccess)builder).batchsplit$vertexCount() >= LIMIT) {
            this.method_22994(layer);
        }
    }
}
