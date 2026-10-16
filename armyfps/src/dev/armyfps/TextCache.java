package dev.armyfps;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.class_2561;
import net.minecraft.class_2583;
import net.minecraft.class_327;

/**
 * Name tags and other world text: TextRenderer lays a text out (bidi reordering, style, glyph
 * lookup) every frame, for every label. The laid-out result draws the same every time, so it is
 * kept and redrawn as long as the text, position, colour, shadow and background are equal.
 * Obfuscated (constantly changing) text is never kept. Cleared on resource reload.
 */
public final class TextCache {
    private record Key(class_327 renderer, class_2561 text, float x, float y, int color, boolean shadow, int background) {
    }

    private static final int MAX = 4096;
    private static final Map<Key, class_327.class_11465> CACHE = new LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, class_327.class_11465> eldest) {
            return this.size() > MAX;
        }
    };

    private TextCache() {
    }

    /** The prepared text, or null if this text must be laid out fresh (obfuscated). */
    public static class_327.class_11465 get(class_327 renderer, class_2561 text, float x, float y, int color, boolean shadow, int background) {
        Key key = new Key(renderer, text, x, y, color, shadow, background);
        class_327.class_11465 cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        if (obfuscated(text)) {
            return null;
        }

        // Exactly TextRenderer.draw's own preparation.
        class_327.class_11465 prepared = renderer.method_71795(text.method_30937(), x, y, color, shadow, false, background);
        CACHE.put(key, prepared);
        return prepared;
    }

    public static void clear() {
        CACHE.clear();
    }

    private static boolean obfuscated(class_2561 text) {
        return text.method_27658((style, string) -> style.method_10987() ? Optional.of(Boolean.TRUE) : Optional.empty(), class_2583.field_24360).isPresent();
    }
}
