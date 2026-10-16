package dev.armyfps.mixin;

import dev.armyfps.ArmyFps;
import net.minecraft.class_10444;
import net.minecraft.class_4587;
import net.minecraft.class_811;
import net.minecraft.class_918;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** ItemRenderer.renderItem: held and dropped items in the world lose their glint beyond itemGlintDistance. GUI and first person untouched. */
@Mixin(value = class_918.class, remap = false)
public class ItemGlintLodMixin {
    @ModifyVariable(method = "method_62476", at = @At("HEAD"), argsOnly = true, remap = false)
    private static class_10444.class_10445 armyfps$farItemGlint(class_10444.class_10445 glint, class_811 context, class_4587 poses) {
        if (glint != class_10444.class_10445.field_55341 && context != class_811.field_4317 && !context.method_29998() && ArmyFps.beyond(poses, ArmyFps.itemGlint)) {
            return class_10444.class_10445.field_55341;
        }

        return glint;
    }
}
