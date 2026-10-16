package dev.armyfps.parallel;

import com.mojang.blaze3d.vertex.VertexFormat;
import dev.armyfps.ArmyFps;
import dev.armyfps.mixin.ModelCommandsAccess;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.class_11661;
import net.minecraft.class_155;
import net.minecraft.class_1921;
import net.minecraft.class_290;
import net.minecraft.class_4588;
import net.minecraft.class_572;

/**
 * Multi-core models.
 *
 * Before ModelCommandRenderer draws the frame's model commands, every humanoid model command
 * (players/bots, their armour and trims) is posed and turned into finished vertices on the worker
 * threads, each with its own copy of the models. When the render thread then reaches a command,
 * it copies that command's vertices into the vertex buffer in one block, in exactly the order and
 * with exactly the bytes vanilla would have written. Anything else - other models, outlines,
 * other vertex formats, anything that failed - is drawn by vanilla as usual.
 */
public final class ParallelModels {
    private static final IdentityHashMap<Object, Integer> INDEX = new IdentityHashMap<>();
    private static class_11661.class_11670<?>[] commands = new class_11661.class_11670<?>[0];
    private static int[] worker = new int[0];
    private static long[] offset = new long[0];
    private static int[] count = new int[0];
    private static int size;
    private static Layout entity;
    /** Commands drawn from the parallel path this frame (for the frame recording). */
    public static int drawnThisFrame;

    private ParallelModels() {
    }

    /** ModelCommandRenderer.render HEAD: build every suitable command of this call in parallel. */
    public static void prepare(ModelCommandsAccess frame) {
        INDEX.clear();
        size = 0;
        if (!ArmyFps.enabled || !ArmyFps.parallel || class_155.field_61885) {
            return;
        }

        try {
            if (entity == null) {
                entity = Layout.of(class_290.field_1580);
            }

            for (Map.Entry<class_1921, List<class_11661.class_11670<?>>> entry : frame.armyfps$opaque().entrySet()) {
                if (suitableLayer(entry.getKey())) {
                    for (class_11661.class_11670<?> command : entry.getValue()) {
                        add(command);
                    }
                }
            }

            for (class_11661.class_11744<?> blended : frame.armyfps$blended()) {
                if (suitableLayer(blended.comp_4616())) {
                    add(blended.comp_4615());
                }
            }

            if (size < ArmyFps.parallelMinCommands || !Pool.run(size, ParallelModels::build)) {
                INDEX.clear();
                size = 0;
            }
        } catch (Throwable error) {
            Pool.fail("preparing models", error);
            INDEX.clear();
            size = 0;
        }
    }

    /** ModelCommandRenderer.render RETURN. */
    public static void finish() {
        INDEX.clear();
        size = 0;
    }

    /** ModelCommandRenderer's per-command draw. False = not prepared (or the buffer can't take it), vanilla draws it. */
    public static boolean draw(class_11661.class_11670<?> command, class_4588 consumer) {
        if (size == 0) {
            return false;
        }

        Integer index = INDEX.get(command);
        if (index == null || worker[index] < 0 || !Append.fits(consumer, entity, count[index])) {
            return false;
        }

        Append.copy(consumer, Pool.workers[worker[index]].arena + offset[index], entity, count[index]);
        drawnThisFrame++;
        return true;
    }

    private static void build(Worker me, int i) {
        long start = me.used;
        try {
            count[i] = me.buildModel(commands[i], entity);
            offset[i] = start;
            worker[i] = me.id;
        } catch (Throwable error) {
            worker[i] = -1;
            me.used = start;
            Pool.error(error);
        }
    }

    private static boolean suitableLayer(class_1921 layer) {
        return layer.method_23031() == class_290.field_1580 && layer.method_23033() != VertexFormat.class_5596.field_27377;
    }

    private static void add(class_11661.class_11670<?> command) {
        // Humanoid models only (players, bots, armour): their posing is plain math on the model's
        // own parts. No outlines (glowing) and no block-breaking overlay - vanilla draws those.
        if (!(command.comp_4505() instanceof class_572) || command.comp_4511() != 0 || command.comp_4651() != null || INDEX.containsKey(command)) {
            return;
        }

        if (size == commands.length) {
            int grown = Math.max(1024, size * 2);
            commands = Arrays.copyOf(commands, grown);
            worker = Arrays.copyOf(worker, grown);
            offset = Arrays.copyOf(offset, grown);
            count = Arrays.copyOf(count, grown);
        }

        INDEX.put(command, size);
        commands[size++] = command;
    }
}
