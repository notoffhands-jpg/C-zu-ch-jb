package dev.armyfps.mixin;

import dev.armyfps.ArmyFps;
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
import net.minecraft.class_9334;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * EquipmentRenderer.render: worn armour gets no glint pass beyond armorGlintDistance and no trim
 * pass beyond trimDistance (both measured from the camera). Closer, it is drawn as usual.
 */
@Mixin(value = class_10197.class, remap = false)
public class EquipmentLodMixin {
    @Redirect(method = "method_64078", remap = false,
        at = @At(value = "INVOKE", remap = false, target = "Lnet/minecraft/class_1799;method_7958()Z"))
    private boolean armyfps$farGlint(class_1799 stack, class_10186.class_10190 layerType, class_5321<class_10394> asset, class_3879<?> model, Object state,
        class_1799 sameStack, class_4587 poses, class_11659 queue, int light, class_2960 texture, int outline, int order) {
        return stack.method_7958() && !ArmyFps.beyond(poses, ArmyFps.armorGlint);
    }

    @Redirect(method = "method_64078", remap = false,
        at = @At(value = "INVOKE", remap = false, target = "Lnet/minecraft/class_1799;method_58694(Lnet/minecraft/class_9331;)Ljava/lang/Object;"))
    private Object armyfps$farTrim(class_1799 stack, class_9331<?> type, class_10186.class_10190 layerType, class_5321<class_10394> asset, class_3879<?> model, Object state,
        class_1799 sameStack, class_4587 poses, class_11659 queue, int light, class_2960 texture, int outline, int order) {
        // Only the trim component; anything else this method might read stays untouched.
        if (type == class_9334.field_49607 && ArmyFps.beyond(poses, ArmyFps.trim)) {
            return null;
        }

        return stack.method_58694(type);
    }
}
