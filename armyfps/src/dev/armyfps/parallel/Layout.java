package dev.armyfps.parallel;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.util.IdentityHashMap;

/** Where each element sits in one vertex of a format (-1 = not in it), as BufferBuilder lays it out. */
final class Layout {
    private static final IdentityHashMap<VertexFormat, Layout> CACHE = new IdentityHashMap<>();

    final VertexFormat format;
    final int size;
    final int position;
    final int color;
    final int uv0;
    final int uv1;
    final int uv2;
    final int normal;

    private Layout(VertexFormat format) {
        this.format = format;
        this.size = format.getVertexSize();
        int[] offsets = format.getOffsetsByElement();
        this.position = offset(offsets, VertexFormatElement.POSITION);
        this.color = offset(offsets, VertexFormatElement.COLOR);
        this.uv0 = offset(offsets, VertexFormatElement.UV0);
        this.uv1 = offset(offsets, VertexFormatElement.UV1);
        this.uv2 = offset(offsets, VertexFormatElement.UV2);
        this.normal = offset(offsets, VertexFormatElement.NORMAL);
    }

    /** Render thread only (the cache is not shared with workers; they get finished Layouts). */
    static Layout of(VertexFormat format) {
        return CACHE.computeIfAbsent(format, Layout::new);
    }

    private static int offset(int[] offsets, VertexFormatElement element) {
        int id = element.id();
        return id >= 0 && id < offsets.length ? offsets[id] : -1;
    }
}
