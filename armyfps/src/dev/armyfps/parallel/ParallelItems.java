package dev.armyfps.parallel;

import com.mojang.blaze3d.vertex.VertexFormat;
import dev.armyfps.ArmyFps;
import dev.armyfps.mixin.DualAccess;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.class_10444;
import net.minecraft.class_11661;
import net.minecraft.class_12249;
import net.minecraft.class_1921;
import net.minecraft.class_4588;
import net.minecraft.class_4597;
import net.minecraft.class_918;

/**
 * Multi-core held/dropped items: the baked quads of every item command (swords, axes, ... with or
 * without enchantment glint) are transformed and written on the worker threads, then copied into
 * the same buffers vanilla would write to, in the same order. Special glint (compasses etc.),
 * outlines stay with vanilla; so does anything that doesn't fit.
 */
public final class ParallelItems {
    private static final IdentityHashMap<Object, Integer> INDEX = new IdentityHashMap<>();
    private static class_11661.class_11668[] commands = new class_11661.class_11668[0];
    private static Layout[] layouts = new Layout[0];
    private static Layout[] glints = new Layout[0];
    private static int[] worker = new int[0];
    private static long[] offset = new long[0];
    private static int[] count = new int[0];
    private static int size;
    public static int drawnThisFrame;

    private ParallelItems() {
    }

    /** ItemCommandRenderer.render, before the loop. */
    public static void prepare(List<class_11661.class_11668> items) {
        INDEX.clear();
        size = 0;
        if (!ArmyFps.enabled || !ArmyFps.parallel || !ArmyFps.parallelItems || items.size() < ArmyFps.parallelMinCommands) {
            return;
        }

        try {
            // Both glint layers vanilla may pick (see ItemRenderer.getItemGlintConsumer) must share a format.
            VertexFormat a = class_12249.method_75993().method_23031();
            VertexFormat b = class_12249.method_75995().method_23031();
            Layout glint = a == b ? Layout.of(a) : null;
            for (class_11661.class_11668 item : items) {
                class_10444.class_10445 type = item.comp_4642();
                class_1921 layer = item.comp_4641();
                if (type == class_10444.class_10445.field_55343 || type == class_10444.class_10445.field_55342 && glint == null
                    || layer.method_23033() == VertexFormat.class_5596.field_27377 || INDEX.containsKey(item)) {
                    continue;
                }

                add(item, Layout.of(layer.method_23031()), type == class_10444.class_10445.field_55342 ? glint : null);
            }

            if (size < ArmyFps.parallelMinCommands || !Pool.run(size, ParallelItems::build)) {
                INDEX.clear();
                size = 0;
            }
        } catch (Throwable error) {
            Pool.fail("preparing items", error);
            INDEX.clear();
            size = 0;
        }
    }

    /** Is anything of the current item batch prepared (else ItemCommandRenderer runs as vanilla)? */
    public static boolean prepared() {
        return size > 0;
    }

    public static void finish() {
        INDEX.clear();
        size = 0;
    }

    /**
     * The main (non-outline) part of one item command. Gets its buffers exactly the way
     * ItemRenderer does, then copies. False = vanilla must draw it.
     */
    public static boolean draw(class_11661.class_11668 item, class_4597.class_4598 buffers) {
        if (size == 0) {
            return false;
        }

        Integer index = INDEX.get(item);
        if (index == null || worker[index] < 0) {
            return false;
        }

        int vertices = count[index];
        long from = Pool.workers[worker[index]].arena + offset[index];
        Layout layout = layouts[index];
        Layout glint = glints[index];
        if (glint == null) {
            class_4588 consumer = buffers.method_73477(item.comp_4641());
            if (!Append.fits(consumer, layout, vertices)) {
                return false;
            }

            Append.copy(consumer, from, layout, vertices);
        } else {
            class_4588 consumer = class_918.method_23181(buffers, item.comp_4641(), true, true);
            if (!(consumer instanceof DualAccess dual)) {
                return false;
            }

            class_4588 glintBuffer = dual.armyfps$first();
            class_4588 itemBuffer = dual.armyfps$second();
            if (!Append.fits(glintBuffer, glint, vertices) || !Append.fits(itemBuffer, layout, vertices)) {
                return false;
            }

            Append.copy(glintBuffer, from + (long)vertices * layout.size, glint, vertices);
            Append.copy(itemBuffer, from, layout, vertices);
        }

        drawnThisFrame++;
        return true;
    }

    private static void build(Worker me, int i) {
        long start = me.used;
        try {
            count[i] = me.buildItem(commands[i], layouts[i], glints[i]);
            offset[i] = start;
            worker[i] = me.id;
        } catch (Throwable error) {
            worker[i] = -1;
            me.used = start;
            Pool.error(error);
        }
    }

    private static void add(class_11661.class_11668 item, Layout layout, Layout glint) {
        if (size == commands.length) {
            int grown = Math.max(1024, size * 2);
            commands = Arrays.copyOf(commands, grown);
            layouts = Arrays.copyOf(layouts, grown);
            glints = Arrays.copyOf(glints, grown);
            worker = Arrays.copyOf(worker, grown);
            offset = Arrays.copyOf(offset, grown);
            count = Arrays.copyOf(count, grown);
        }

        INDEX.put(item, size);
        commands[size] = item;
        layouts[size] = layout;
        glints[size] = glint;
        size++;
    }
}
