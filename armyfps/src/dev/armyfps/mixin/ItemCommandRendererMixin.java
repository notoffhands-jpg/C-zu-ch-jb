package dev.armyfps.mixin;

import dev.armyfps.parallel.ParallelItems;
import java.util.List;
import net.minecraft.class_10444;
import net.minecraft.class_11661;
import net.minecraft.class_11687;
import net.minecraft.class_11788;
import net.minecraft.class_4587;
import net.minecraft.class_4597;
import net.minecraft.class_4618;
import net.minecraft.class_918;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ItemCommandRenderer.render: the same loop as vanilla (push, copy pose, main pass, outline pass,
 * pop), except that items built on the worker threads are copied in instead of drawn quad by quad.
 * Only takes over when there is something prepared; otherwise vanilla runs untouched.
 */
@Mixin(value = class_11687.class, remap = false)
public class ItemCommandRendererMixin {
    @Shadow(remap = false)
    @Final
    private class_4587 field_61844;

    @Inject(method = "method_73010", at = @At("HEAD"), cancellable = true, remap = false)
    private void armyfps$render(class_11788 queue, class_4597.class_4598 buffers, class_4618 outlines, CallbackInfo ci) {
        List<class_11661.class_11668> items = queue.method_73522();
        ParallelItems.prepare(items);
        if (!ParallelItems.prepared()) {
            return;
        }

        try {
            for (class_11661.class_11668 item : items) {
                this.field_61844.method_22903();
                this.field_61844.method_23760().method_66521(item.comp_4498());
                if (!ParallelItems.draw(item, buffers)) {
                    class_918.method_62476(item.comp_4638(), this.field_61844, buffers, item.comp_4500(), item.comp_4501(), item.comp_4639(), item.comp_4640(), item.comp_4641(), item.comp_4642());
                }

                if (item.comp_4661() != 0) {
                    outlines.method_23286(item.comp_4661());
                    class_918.method_62476(item.comp_4638(), this.field_61844, outlines, item.comp_4500(), item.comp_4501(), item.comp_4639(), item.comp_4640(), item.comp_4641(), class_10444.class_10445.field_55341);
                }

                this.field_61844.method_22909();
            }
        } finally {
            ParallelItems.finish();
        }

        ci.cancel();
    }
}
