package dev.armyfps.mixin;

import dev.armyfps.ArmyFps;
import dev.armyfps.FastSort;
import net.minecraft.class_11899;
import net.minecraft.class_8251;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * BuiltBuffer.SortState#sortAndStore: vanilla's two built-in translucent sorters (by distance /
 * by depth) are replaced by the same computation with a radix sort - identical order, a fraction
 * of the time. Any other sorter runs unchanged. Optional (require = 0): if another mod already
 * replaces this method, ArmyFPS simply leaves it alone.
 */
@Mixin(targets = "net.minecraft.class_9801$class_9802", remap = false)
public class FastSortMixin {
    @Unique
    private static final Vector3f ORIGIN = new Vector3f();
    @Unique
    private static final ThreadLocal<Vector3f> CENTROID = ThreadLocal.withInitial(Vector3f::new);
    @Unique
    private static final ThreadLocal<float[][]> DISTANCES = ThreadLocal.withInitial(() -> new float[][]{new float[0]});

    @Redirect(method = "method_60824", remap = false, require = 0, at = @At(value = "INVOKE", remap = false,
            target = "Lnet/minecraft/class_8251;sort(Lnet/minecraft/class_11899;)[I"))
    private int[] armyfps$fastSort(class_8251 sorter, class_11899 centroids) {
        boolean byDistance = sorter == class_8251.field_43360;
        boolean byDepth = sorter == class_8251.field_43361;
        if (!ArmyFps.enabled || !ArmyFps.fastSort || !byDistance && !byDepth) {
            return sorter.sort(centroids);
        }

        int n = centroids.method_74170();
        float[][] holder = DISTANCES.get();
        if (holder[0].length < n) {
            holder[0] = new float[Math.max(n, holder[0].length * 2)];
        }

        float[] keys = holder[0];
        Vector3f centroid = CENTROID.get();
        for (int i = 0; i < n; i++) {
            centroids.method_74173(i, centroid);
            // Exactly vanilla's keys: origin.distanceSquared(c) / -c.z()
            keys[i] = byDistance ? ORIGIN.distanceSquared(centroid) : -centroid.z();
        }

        return FastSort.sortDescending(keys, n);
    }
}
