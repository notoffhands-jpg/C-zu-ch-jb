package dev.armyfps.parallel;

import dev.armyfps.ArmyFps;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The worker threads. The render thread hands out a batch (index 0..size-1), works on it itself as
 * worker 0, and waits until all are done. One batch at a time; each batch starts with empty arenas.
 */
final class Pool {
    static final Logger LOGGER = LoggerFactory.getLogger("ArmyFPS");
    private static final int CHUNK = 4;

    interface Task {
        void run(Worker worker, int index);
    }

    private record Job(int size, Task task, AtomicInteger next, CountDownLatch done) {
    }

    static Worker[] workers;
    private static final Object LOCK = new Object();
    private static volatile Job job;
    private static int errors;

    private Pool() {
    }

    /** False if the workers didn't finish in time (then none of the batch may be used). */
    static boolean run(int size, Task task) throws InterruptedException {
        start();
        for (Worker w : workers) {
            w.reset();
        }

        Job current = new Job(size, task, new AtomicInteger(), new CountDownLatch(workers.length));
        job = current;
        synchronized (LOCK) {
            LOCK.notifyAll();
        }

        work(workers[0], current);
        boolean finished = current.done().await(2, TimeUnit.SECONDS);
        job = null;
        if (!finished) {
            fail("waiting for the worker threads", new IllegalStateException("not finished within 2 s"));
        }

        return finished;
    }

    private static void work(Worker me, Job current) {
        try {
            int from;
            while ((from = current.next().getAndAdd(CHUNK)) < current.size()) {
                int to = Math.min(from + CHUNK, current.size());
                for (int i = from; i < to; i++) {
                    current.task().run(me, i);
                }
            }
        } finally {
            current.done().countDown();
        }
    }

    /** One command failed: vanilla draws it. Too many failures switch the multi-core path off. */
    static synchronized void error(Throwable error) {
        if (errors++ < 3) {
            LOGGER.warn("ArmyFPS: something could not be built in parallel, vanilla draws it instead", error);
        }

        if (errors > 200) {
            ArmyFps.parallel = false;
            LOGGER.warn("ArmyFPS: too many errors, multi-core rendering switched off for this session");
        }
    }

    static void fail(String what, Throwable error) {
        ArmyFps.parallel = false;
        LOGGER.warn("ArmyFPS: multi-core rendering switched off ({} failed)", what, error);
    }

    private static void start() {
        if (workers != null) {
            return;
        }

        int extra = Math.max(0, ArmyFps.parallelThreads - 1);
        Worker[] all = new Worker[extra + 1];
        all[0] = new Worker(0);
        for (int i = 1; i <= extra; i++) {
            Worker w = new Worker(i);
            all[i] = w;
            Thread thread = new Thread(() -> loop(w), "ArmyFPS-Worker-" + i);
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY + 1);
            thread.start();
        }

        workers = all;
        LOGGER.info("ArmyFPS: multi-core rendering on {} threads", all.length);
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
