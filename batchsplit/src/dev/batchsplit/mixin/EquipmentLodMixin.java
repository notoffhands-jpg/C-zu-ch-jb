package dev.batchsplit.mixin;

import dev.batchsplit.Lod;
import net.minecraft.class_10186;
import net.minecraft.class_10197;
import net.minecraft.class_10394;
import net.minecraft.class_11659;
import net.minecraft.class_1799;
import net.minecraft.class_2960;
import net.minecraft.class_3879;
import net.minecraft.class_4587;
import net.minecraft.class_5321;
import net.minecraft.class_9331;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Armour: no glint pass beyond Lod.GLINT, no trim pass beyond Lod.TRIM (both from the camera). */
@Mixin(value = class_10197.class, remap = false)
public class EquipmentLodMixin {
    @Redirect(method = "method_64078", remap = false,
        at = @At(value = "INVOKE", remap = false, target = "Lnet/minecraft/class_1799;method_7958()Z"))
    private boolean batchsplit$farGlint(class_1799 stack, class_10186.class_10190 layerType, class_5321<class_10394> asset, class_3879<?> model, Object state,
        class_1799 sameStack, class_4587 poses, class_11659 queue, int light, class_2960 texture, int outline, int order) {
        return !Lod.beyond(poses, Lod.GLINT) && stack.method_7958();
    }

    @Redirect(method = "method_64078", remap = false,
        at = @At(value = "INVOKE", remap = false, target = "Lnet/minecraft/class_1799;method_58694(Lnet/minecraft/class_9331;)Ljava/lang/Object;"))
    private Object batchsplit$farTrim(class_1799 stack, class_9331<?> type, class_10186.class_10190 layerType, class_5321<class_10394> asset, class_3879<?> model, Object state,
        class_1799 sameStack, class_4587 poses, class_11659 queue, int light, class_2960 texture, int outline, int order) {
        return Lod.beyond(poses, Lod.TRIM) ? null : stack.method_58694(type);
    }
}
