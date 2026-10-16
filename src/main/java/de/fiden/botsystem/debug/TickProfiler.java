package de.fiden.botsystem.debug;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.Consumer;

public final class TickProfiler {
    private static volatile boolean running;

    public static boolean isRunning() {
        return running;
    }

    public static void start(Thread target, int seconds, Consumer<String> report) {
        if (running) {
            report.accept("Profiler laeuft schon.");
        } else {
            running = true;
            Thread sampler = new Thread(
                () -> {
                    Map<String, Integer> self = new HashMap<>();
                    Map<String, Integer> inclusive = new HashMap<>();
                    Map<String, Integer> ours = new HashMap<>();
                    Map<String, Integer> callers = new HashMap<>();
                    int samples = 0;
                    int busy = 0;
                    long end = System.currentTimeMillis() + seconds * 1000L;

                    while (System.currentTimeMillis() < end) {
                        StackTraceElement[] stack = target.getStackTrace();
                        if (stack.length > 0) {
                            samples++;
                            self.merge(frame(stack[0]), 1, Integer::sum);
                            Set<String> seen = new HashSet<>();

                            for (StackTraceElement element : stack) {
                                String name = frame(element);
                                if (seen.add(name)) {
                                    inclusive.merge(name, 1, Integer::sum);
                                }
                            }

                            if (!stack[0].getMethodName().equals("park")) {
                                busy++;
                                // Our own innermost method: what of the plugin is costing time.
                                for (StackTraceElement element : stack) {
                                    if (element.getClassName().startsWith("de.fiden.")) {
                                        ours.merge(frame(element), 1, Integer::sum);
                                        break;
                                    }
                                }

                                // Who calls the usual expensive engine calls (3 frames above them).
                                for (int i = 0; i < stack.length; i++) {
                                    String method = stack[i].getMethodName();
                                    if (WATCH.contains(method)) {
                                        StringBuilder chain = new StringBuilder(frame(stack[i]));
                                        int shown = 0;
                                        for (int j = i + 1; j < stack.length && shown < 3; j++) {
                                            String up = frame(stack[j]);
                                            if (!up.startsWith("Level.") && !up.startsWith("EntityLookup.") && !up.startsWith("EntityGetter.")) {
                                                chain.append(" < ").append(up);
                                                shown++;
                                            }
                                        }
                                        callers.merge(chain.toString(), 1, Integer::sum);
                                        break;
                                    }
                                }
                            }
                        }

                        try {
                            Thread.sleep(5L);
                        } catch (InterruptedException ignored) {
                            break;
                        }
                    }

                    running = false;
                    int total = Math.max(1, samples);
                    List<String> lines = new ArrayList<>();
                    lines.add("Profil ueber " + seconds + " s, " + samples + " Stichproben. Eigenzeit (wo der Thread gerade war):");
                    top(self, total, 12).forEach(lines::add);
                    lines.add("Inklusive (Methode irgendwo im Stack, ohne Rahmenwerk):");
                    top(inclusive, total, 22)
                        .stream()
                        .filter(
                            line -> !line.contains("MinecraftServer.")
                                && !line.contains("Thread.run")
                                && !line.contains("ServerWorld.tick(")
                                && !line.contains("runServer")
                                && !line.contains("tickWorlds")
                                && !line.contains("EntityList.")
                                && !line.contains("Util.")
                        )
                        .forEach(lines::add);
                    lines.add(String.format(Locale.ROOT, "Beschaeftigt: %.1f%% der Zeit. Eigener Code (innerste BotSystem-Methode):", 100.0 * busy / total));
                    top(ours, total, 14).forEach(lines::add);
                    lines.add("Teure Aufrufe und wer sie macht:");
                    top(callers, total, 14).forEach(lines::add);
                    lines.forEach(report);
                },
                "BotSystem-Profiler"
            );
            sampler.setDaemon(true);
            sampler.start();
        }
    }

    private static final Set<String> WATCH = Set.of(
        "getEntities", "getEntitiesOfClass", "hasLineOfSight", "clip", "findPath", "getBlockState", "getNearestEntity", "getCollisions", "noCollision", "move", "send", "broadcast"
    );

    private static String frame(StackTraceElement element) {
        String cls = element.getClassName();
        int dot = cls.lastIndexOf(46);
        return (dot < 0 ? cls : cls.substring(dot + 1)) + "." + element.getMethodName() + "()";
    }

    private static List<String> top(Map<String, Integer> counts, int total, int limit) {
        List<Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> lines = new ArrayList<>();

        for (int i = 0; i < Math.min(limit, entries.size()); i++) {
            Entry<String, Integer> entry = entries.get(i);
            lines.add(String.format(Locale.ROOT, "  %5.1f%%  %s", 100.0 * entry.getValue().intValue() / total, entry.getKey()));
        }

        return lines;
    }
}
