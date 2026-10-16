package dev.armyfps;

/**
 * Same result as vanilla's VertexSorting (stable sort, farthest first, Float.compare order) but
 * with a radix sort on the float bits instead of a merge sort calling a comparator - several
 * times faster on the big translucent batches an army produces. Order and therefore image are
 * identical, ties included.
 */
public final class FastSort {
    private static final ThreadLocal<int[][]> SCRATCH = ThreadLocal.withInitial(() -> new int[][]{new int[0], new int[0], new int[0]});

    private FastSort() {
    }

    /** Indices 0..n-1 ordered by descending key value, equal values keep their index order. */
    public static int[] sortDescending(float[] values, int n) {
        int[][] scratch = SCRATCH.get();
        if (scratch[0].length < n) {
            int size = Math.max(n, scratch[0].length * 2);
            scratch[0] = new int[size];
            scratch[1] = new int[size];
            scratch[2] = new int[size];
        }

        int[] keys = scratch[0];
        int[] idx = new int[n];
        int[] tmpIdx = scratch[1];
        int[] tmpKeys = scratch[2];
        for (int i = 0; i < n; i++) {
            int bits = Float.floatToIntBits(values[i]);
            int ascending = bits ^ (bits >> 31 | 0x80000000); // unsigned order == Float.compare
            keys[i] = ~ascending;                             // descending
            idx[i] = i;
        }

        int[] srcIdx = idx;
        int[] srcKeys = keys;
        int[] dstIdx = tmpIdx;
        int[] dstKeys = tmpKeys;
        int[] count = new int[257];
        for (int shift = 0; shift < 32; shift += 8) {
            java.util.Arrays.fill(count, 0);
            for (int i = 0; i < n; i++) {
                count[(srcKeys[i] >>> shift & 0xFF) + 1]++;
            }
            for (int i = 0; i < 256; i++) {
                count[i + 1] += count[i];
            }
            for (int i = 0; i < n; i++) {
                int bucket = srcKeys[i] >>> shift & 0xFF;
                int to = count[bucket]++;
                dstIdx[to] = srcIdx[i];
                dstKeys[to] = srcKeys[i];
            }
            int[] t = srcIdx; srcIdx = dstIdx; dstIdx = t;
            t = srcKeys; srcKeys = dstKeys; dstKeys = t;
        }

        // 4 passes: the result is back in the array it started in (idx)
        return srcIdx == idx ? idx : java.util.Arrays.copyOf(srcIdx, n);
    }
}
