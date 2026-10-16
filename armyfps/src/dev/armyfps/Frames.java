package dev.armyfps;

/**
 * Frame timing for Java Flight Recordings (see FrameEvent). Guarded so a Java runtime without the
 * jdk.jfr module just skips it instead of crashing.
 */
public final class Frames {
    private static final boolean AVAILABLE = available();
    private static FrameEvent frame;
    private static long worldStart;
    private static long worldNanos;
    /** Player render states filled this frame (bots and real players in view). */
    public static int players;

    private Frames() {
    }

    private static boolean available() {
        try {
            Class.forName("jdk.jfr.Event");
            return true;
        } catch (ClassNotFoundException | LinkageError missing) {
            return false;
        }
    }

    public static void frameStart() {
        players = 0;
        worldNanos = 0L;
        if (AVAILABLE) {
            FrameEvent event = new FrameEvent();
            if (event.isEnabled()) {
                event.begin();
                frame = event;
            }
        }
    }

    public static void frameEnd() {
        FrameEvent event = frame;
        if (event != null) {
            frame = null;
            event.worldNanos = worldNanos;
            event.players = players;
            event.enabled = ArmyFps.enabled;
            event.commit();
        }
    }

    public static void worldStart() {
        worldStart = System.nanoTime();
    }

    public static void worldEnd() {
        worldNanos += System.nanoTime() - worldStart;
    }
}
