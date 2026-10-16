package de.fiden.botsystem.bot;

import de.fiden.botsystem.BotSystemMod;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * Hooks BotSystem's film reset (FilmScene) into the BBS-Paper plugin, if it is installed:
 * film scene start/stop and each film command clip. Wired by class name so BotSystem neither
 * needs BBS-Paper to build nor to run.
 */
public final class BbsPaperHook implements Listener {
    private BbsPaperHook() {
    }

    public static void install(Plugin owner) {
        Plugin bbs = Bukkit.getPluginManager().getPlugin("BBS-Paper");
        if (bbs == null || !bbs.isEnabled()) {
            return;
        }

        try {
            ClassLoader loader = bbs.getClass().getClassLoader();
            Class<? extends Event> scene = Class.forName("de.fiden.bbspaper.api.FilmSceneEvent", true, loader).asSubclass(Event.class);
            Class<? extends Event> command = Class.forName("de.fiden.bbspaper.api.FilmCommandEvent", true, loader).asSubclass(Event.class);
            Method scenePhase = scene.getMethod("phase");
            Method commandPhase = command.getMethod("phase");
            BbsPaperHook listener = new BbsPaperHook();
            Bukkit.getPluginManager().registerEvent(scene, listener, EventPriority.NORMAL, (l, event) -> {
                if (scene.isInstance(event)) {
                    if ("START".equals(phase(scenePhase, event))) {
                        FilmScene.begin();
                    } else {
                        FilmScene.restore();
                    }
                }
            }, owner);
            Bukkit.getPluginManager().registerEvent(command, listener, EventPriority.NORMAL, (l, event) -> {
                if (command.isInstance(event)) {
                    if ("BEFORE".equals(phase(commandPhase, event))) {
                        FilmScene.beginCommand();
                    } else {
                        FilmScene.endCommand();
                    }
                }
            }, owner);
            BotSystemMod.LOGGER.info("BBS-Paper gefunden - Filme setzen Bots und Armeen nach dem Stopp zurueck.");
        } catch (ReflectiveOperationException | ClassCastException error) {
            BotSystemMod.LOGGER.warn("BBS-Paper-Anbindung fehlgeschlagen: {}", error.toString());
        }
    }

    private static String phase(Method getter, Event event) {
        try {
            return String.valueOf(getter.invoke(event));
        } catch (ReflectiveOperationException error) {
            return "";
        }
    }
}
