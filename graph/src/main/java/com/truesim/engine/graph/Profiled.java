package com.truesim.engine.graph;

import com.truesim.engine.app.profile.Profiler;
import com.truesim.engine.app.profile.Zone;

/** The frame graph's profiler zones. */
final class Profiled {

    /** Building (when changed) and recording the frame, on the calling thread. */
    static final Zone RECORD = Profiler.zone("render.record");
    /** Submitting the frame's batches. */
    static final Zone SUBMIT = Profiler.zone("render.submit");
    /** Recording a chunk of passes (on a job thread when recording in parallel). */
    static final Zone CHUNK = Profiler.zone("render.chunk");

    private Profiled() {
    }
}
