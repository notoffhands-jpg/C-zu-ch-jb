package dev.armyfps.mixin;

import java.util.List;
import java.util.Map;
import net.minecraft.class_11661;
import net.minecraft.class_1921;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** ModelCommandRenderer.Commands: the frame's opaque (by layer) and blended model commands. */
@Mixin(targets = "net.minecraft.class_11683$class_12048", remap = false)
public interface ModelCommandsAccess {
    @Accessor(value = "field_62982", remap = false)
    Map<class_1921, List<class_11661.class_11670<?>>> armyfps$opaque();

    @Accessor(value = "field_62983", remap = false)
    List<class_11661.class_11744<?>> armyfps$blended();
}
