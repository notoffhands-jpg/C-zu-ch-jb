package dev.batchsplit.mixin;

import dev.batchsplit.FastSort;
import net.minecraft.class_11899;
import net.minecraft.class_8251;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * MeshData.SortState#buildSortedIndexBuffer: vanilla's two built-in sorters (perspective = by
 * distance to the camera, orthographic = by depth) are replaced by the same computation with a
 * radix sort - identical order, a fraction of the time. Any other sorter runs unchanged.
 */
@Mixin(targets = "net.minecraft.class_9801$class_9802", remap = false)
public class FastSortMixin {
    @Unique
    private static final Vector3f ORIGIN = new Vector3f();
    @Unique
    private static final ThreadLocal<Vector3f> CENTROID = ThreadLocal.withInitial(Vector3f::new);
    @Unique
    private static final ThreadLocal<float[][]> DISTANCES = ThreadLocal.withInitial(() -> new float[][]{new float[0]});

    @Redirect(method = "method_60824", remap = false, at = @At(value = "INVOKE", remap = false,
            target = "Lnet/minecraft/class_8251;sort(Lnet/minecraft/class_11899;)[I"))
    private int[] batchsplit$fastSort(class_8251 sorter, class_11899 centroids) {
        boolean perspective = sorter == class_8251.field_43360;
        boolean orthographic = sorter == class_8251.field_43361;
        if (!perspective && !orthographic) {
            return sorter.sort(centroids);
        }

        int n = centroids.method_74170();
        float[][] holder = DISTANCES.get();
        if (holder[0].length < n) {
            holder[0] = new float[Math.max(n, holder[0].length * 2)];
        }
        float[] distances = holder[0];
        Vector3f centroid = CENTROID.get();
        for (int i = 0; i < n; i++) {
            centroids.method_74173(i, centroid);
            // exactly vanilla's functions: origin.distanceSquared(c) / -c.z()
            distances[i] = perspective ? ORIGIN.distanceSquared(centroid) : -centroid.z();
        }
        return FastSort.sortDescending(distances, n);
    }
}
