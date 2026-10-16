package dev.armyfps.parallel;

import com.mojang.blaze3d.vertex.VertexFormat;
import dev.armyfps.ArmyFps;
import dev.armyfps.mixin.BufferBuilderAccess;
import dev.armyfps.mixin.ModelCommandsAccess;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.class_11661;
import net.minecraft.class_155;
import net.minecraft.class_1921;
import net.minecraft.class_287;
import net.minecraft.class_290;
import net.minecraft.class_4588;
import net.minecraft.class_572;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Multi-core army rendering.
 *
 * Before ModelCommandRenderer draws the frame's model commands, every humanoid model command
 * (players/bots, their armour and trims) is posed and turned into finished vertices on worker
 * threads, each with its own copy of the models. When the render thread then reaches a command,
 * it copies that command's vertices into the vertex buffer in one block, in exactly the order and
 * with exactly the bytes vanilla would have written. Anything else - other models, outlines,
 * other vertex formats, anything that failed - is drawn by vanilla as usual.
 */
public final class ParallelModels {
    private static final Logger LOGGER = LoggerFactory.getLogger("ArmyFPS");
    private static final int CHUNK = 8;

    private static Worker[] workers;
    private static Thread[] threads;
    private static final Object LOCK = new Object();
    private static volatile Job job;
    private static final ThreadLocal<Worker> MINE = new ThreadLocal<>();

    /** Results of the current ModelCommandRenderer.render call: command -> index. */
    private static final IdentityHashMap<Object, Integer> INDEX = new IdentityHashMap<>();
    private static class_11661.class_11670<?>[] commands = new class_11661.class_11670<?>[0];
    private static int[] worker = new int[0];
    private static long[] offset = new long[0];
    private static int[] count = new int[0];
    private static int size;
    private static int errors;
    /** Commands drawn from the parallel path this frame (for the frame recording). */
    public static int drawnThisFrame;

    private ParallelModels() {
    }

    private record Job(int size, AtomicInteger next, CountDownLatch done) {
    }

    /** ModelCommandRenderer.render HEAD: build every suitable command of this call in parallel. */
    public static void prepare(ModelCommandsAccess frame) {
        INDEX.clear();
        size = 0;
        if (!ArmyFps.enabled || !ArmyFps.parallel || class_155.field_61885) {
            return;
        }

        try {
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

            if (size < ArmyFps.parallelMinCommands) {
                INDEX.clear();
                size = 0;
                return;
            }

            run();
        } catch (Throwable error) {
            fail("preparing", error);
        }
    }

    /** ModelCommandRenderer.render RETURN. */
    public static void finish() {
        INDEX.clear();
        size = 0;
    }

    /**
     * ModelCommandRenderer's per-command draw: copies the prepared vertices of this command into
     * the buffer. False = not prepared (or the buffer can't take it), vanilla draws it.
     */
    public static boolean draw(class_11661.class_11670<?> command, class_4588 consumer) {
        if (size == 0) {
            return false;
        }

        Integer index = INDEX.get(command);
        if (index == null || worker[index] < 0 || !(consumer instanceof class_287)) {
            return false;
        }

        BufferBuilderAccess buffer = (BufferBuilderAccess)consumer;
        if (buffer.armyfps$format() != class_290.field_1580 || !buffer.armyfps$fastFormat()
            || buffer.armyfps$drawMode() == VertexFormat.class_5596.field_27377) {
            return false;
        }

        int vertices = count[index];
        if (vertices == 0) {
            return true;
        }

        if (buffer.armyfps$vertexCount() + vertices > 16777215) {
            return false;
        }

        buffer.armyfps$ensureBuilding();
        buffer.armyfps$endLastVertex();
        long bytes = (long)vertices * Worker.STRIDE;
        long to = buffer.armyfps$allocator().method_60808((int)bytes);
        MemoryUtil.memCopy(workers[worker[index]].arena + offset[index], to, bytes);
        buffer.armyfps$setVertexCount(buffer.armyfps$vertexCount() + vertices);
        buffer.armyfps$setVertexPointer(to + bytes - Worker.STRIDE);
        drawnThisFrame++;
        return true;
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
            commands = java.util.Arrays.copyOf(commands, grown);
            worker = java.util.Arrays.copyOf(worker, grown);
            offset = java.util.Arrays.copyOf(offset, grown);
            count = java.util.Arrays.copyOf(count, grown);
        }

        INDEX.put(command, size);
        commands[size++] = command;
    }

    private static void run() throws InterruptedException {
        start();
        for (Worker w : workers) {
            w.reset();
        }

        Job current = new Job(size, new AtomicInteger(), new CountDownLatch(workers.length));
        job = current;
        synchronized (LOCK) {
            LOCK.notifyAll();
        }

        // The render thread works too (it is workers[0]).
        work(workers[0], current);
        if (!current.done().await(2, TimeUnit.SECONDS)) {
            // A stuck worker: use nothing from this frame, vanilla draws it all.
            job = null;
            fail("waiting", new IllegalStateException("workers did not finish within 2 s"));
            INDEX.clear();
            size = 0;
            return;
        }

        job = null;
    }

    private static void work(Worker me, Job current) {
        try {
            int from;
            while ((from = current.next().getAndAdd(CHUNK)) < current.size()) {
                int to = Math.min(from + CHUNK, current.size());
                for (int i = from; i < to; i++) {
                    long start = me.used;
                    try {
                        int vertices = me.build(commands[i]);
                        worker[i] = me.id;
                        offset[i] = start;
                        count[i] = vertices;
                    } catch (Throwable error) {
                        worker[i] = -1;
                        me.used = start;
                        error(error);
                    }
                }
            }
        } finally {
            current.done().countDown();
        }
    }

    private static synchronized void error(Throwable error) {
        if (errors++ < 3) {
            LOGGER.warn("ArmyFPS: a model could not be built in parallel, vanilla draws it instead", error);
        }

        if (errors > 200) {
            ArmyFps.parallel = false;
            LOGGER.warn("ArmyFPS: too many errors, multi-core model rendering switched off for this session");
        }
    }

    private static void fail(String what, Throwable error) {
        ArmyFps.parallel = false;
        INDEX.clear();
        size = 0;
        LOGGER.warn("ArmyFPS: multi-core model rendering switched off ({} failed)", what, error);
    }

    private static void start() {
        if (workers != null) {
            return;
        }

        int extra = Math.max(0, ArmyFps.parallelThreads - 1);
        workers = new Worker[extra + 1];
        workers[0] = new Worker(0);
        threads = new Thread[extra];
        for (int i = 1; i <= extra; i++) {
            Worker w = new Worker(i);
            workers[i] = w;
            Thread thread = new Thread(() -> loop(w), "ArmyFPS-Worker-" + i);
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY + 1);
            threads[i - 1] = thread;
            thread.start();
        }

        LOGGER.info("ArmyFPS: multi-core model rendering on {} threads", workers.length);
    }

    private static void loop(Worker me) {
        Job last = null;
        while (true) {
            Job current;
            synchronized (LOCK) {
                while ((current = job) == null || current == last) {
                    try {
                        LOCK.wait();
                    } catch (InterruptedException stop) {
                        return;
                    }
                }
            }

            last = current;
            work(me, current);
        }
    }
}
