package dev.armyfps.mixin;

import java.util.List;
import java.util.Map;
import net.minecraft.class_630;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** ModelPart's private cuboid list and child map, for the parallel model walk. */
@Mixin(value = class_630.class, remap = false)
public interface ModelPartAccess {
    @Accessor(value = "field_3663", remap = false)
    List<class_630.class_628> armyfps$cuboids();

    @Accessor(value = "field_3661", remap = false)
    Map<String, class_630> armyfps$children();
}
