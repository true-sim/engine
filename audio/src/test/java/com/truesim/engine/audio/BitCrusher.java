package com.truesim.engine.audio;

import com.truesim.engine.audio.dsp.DspBlock;
import com.truesim.engine.audio.dsp.DspNode;
import com.truesim.engine.audio.dsp.DspSetup;
import com.truesim.engine.audio.dsp.NodeParam;
import com.truesim.engine.audio.dsp.NodeType;
import com.truesim.engine.audio.dsp.Param;
import java.util.Arrays;

/** A plugin's node as §3.8 writes it: reduces bit depth and sample rate; registered by type, used by name. */
public final class BitCrusher implements DspNode {
    public static final NodeType<BitCrusher> TYPE = NodeType.of("bitCrusher", BitCrusher::new,
        NodeParam.of("bits", 8, 1, 16), NodeParam.of("downsample", 1, 1, 64));

    private Param bits;
    private Param downsample;
    private float[] held;
    private int[] counter;

    @Override
    public void prepare(DspSetup setup) {
        bits = setup.param("bits");
        downsample = setup.param("downsample");
        held = new float[setup.channels()];
        counter = new int[setup.channels()];
    }

    @Override
    public void process(DspBlock block) {
        for (int c = 0; c < block.channels(); c++) {
            float[] in = block.input(c);
            float[] out = block.output(c);
            for (int i = 0; i < block.frames(); i++) {
                float steps = (float) (1 << (int) bits.at(i));
                if (counter[c]-- <= 0) {
                    counter[c] = (int) downsample.at(i) - 1;
                    held[c] = Math.round(in[i] * steps) / steps;
                }
                out[i] = held[c];
            }
        }
    }

    @Override
    public void reset() {
        Arrays.fill(held, 0);
        Arrays.fill(counter, 0);
    }
}
