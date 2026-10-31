package com.truesim.engine.demo;

import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Keys;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Locale;

/** Reads the view's scene color back each frame and prints what it holds now and then: for checking the demo draws. */
final class Probe implements Technique {

    private long printed;

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.SCENE_COLOR);
    }

    @Override
    public void build(GraphBuilder g) {
        Image color = g.modify(Keys.SCENE_COLOR);
        int width = color.width();
        g.readback("probe", color.mip(0), (data, frame) -> print(data, frame, width));
        g.provide(Keys.SCENE_COLOR, color);
    }

    private void print(MemorySegment data, long frame, int width) {
        if (frame - printed < 120) {
            return;
        }
        printed = frame;
        long texels = data.byteSize() / 8;
        int distinct = 0;
        float first = Float.float16ToFloat(data.get(ValueLayout.JAVA_SHORT_UNALIGNED, 0));
        double sum = 0;
        for (long i = 0; i < texels; i += 97) {
            float r = Float.float16ToFloat(data.get(ValueLayout.JAVA_SHORT_UNALIGNED, i * 8));
            sum += r;
            distinct += Math.abs(r - first) > 1e-3 ? 1 : 0;
        }
        long middle = (texels / 2 + width / 2) * 8;
        System.out.printf(Locale.ROOT, "probe frame %d: red mean %.3f, %d of %d samples unlike the corner, middle "
            + "(%.3f, %.3f, %.3f)%n", frame, sum / (texels / 97.0), distinct, texels / 97,
            Float.float16ToFloat(data.get(ValueLayout.JAVA_SHORT_UNALIGNED, middle)),
            Float.float16ToFloat(data.get(ValueLayout.JAVA_SHORT_UNALIGNED, middle + 2)),
            Float.float16ToFloat(data.get(ValueLayout.JAVA_SHORT_UNALIGNED, middle + 4)));
    }
}
