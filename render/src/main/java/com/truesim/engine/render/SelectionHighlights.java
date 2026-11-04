package com.truesim.engine.render;

import com.truesim.annotations.AllowAllocation;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/**
 * Which instances of a scene are highlighted, as the {@link SelectionOutline} reads them: two bits per instance id,
 * {@link #HOVERED} and {@link #SELECTED}, sixteen instances a word. Filled on the CPU each frame something changes (by
 * engine-scene's {@code SceneHighlights} from the scene's {@code Selected} and {@code Hovered} components); a new
 * {@link #version()} uploads it. Marking allocates nothing once grown; one thread.
 *
 * <pre>{@code
 * SelectionHighlights highlights = new SelectionHighlights();
 * highlights.begin();
 * highlights.mark(sync.slotOf(node), SelectionHighlights.SELECTED);
 * highlights.end();
 * }</pre>
 */
public final class SelectionHighlights {

    /** A pointer is over it. */
    public static final int HOVERED = 1;
    /** It is selected. */
    public static final int SELECTED = 2;

    private int[] words = new int[64];
    private MemorySegment segment = MemorySegment.ofArray(words);
    private int[] marked = new int[64]; // the words marked since begin, to clear
    private boolean[] dirty = new boolean[64];
    private int markedCount;
    private int highest = -1; // the highest word marked, for the upload
    private long version;
    private boolean changed;

    /** Starts marking anew: every instance unmarked. */
    public void begin() {
        for (int i = 0; i < markedCount; i++) {
            words[marked[i]] = 0;
            dirty[marked[i]] = false;
        }
        changed = markedCount > 0;
        markedCount = 0;
        highest = -1;
    }

    /** Marks instance {@code instance} (an id of the GPU scene; negative ids are ignored) with {@code flags}. */
    public void mark(int instance, int flags) {
        if (instance < 0 || flags == 0) {
            return;
        }
        int word = instance >>> 4;
        if (word >= words.length) {
            grow(word + 1);
        }
        if (!dirty[word]) {
            dirty[word] = true;
            marked[markedCount++] = word;
        }
        words[word] |= (flags & 3) << ((instance & 15) * 2);
        highest = Math.max(highest, word);
        changed = true;
    }

    /** Ends marking: a new version if anything changed. */
    public void end() {
        if (changed) {
            version++;
            changed = false;
        }
    }

    @AllowAllocation("growing with the scene's instances")
    private void grow(int length) {
        int n = Math.max(length, words.length * 2);
        words = Arrays.copyOf(words, n);
        segment = MemorySegment.ofArray(words);
        marked = Arrays.copyOf(marked, n);
        dirty = Arrays.copyOf(dirty, n);
    }

    /** The flags of instance {@code instance}. */
    public int flags(int instance) {
        int word = instance >>> 4;
        return instance < 0 || word >= words.length ? 0 : words[word] >>> ((instance & 15) * 2) & 3;
    }

    /** Whether any instance is marked. */
    public boolean any() {
        return markedCount > 0;
    }

    /** A count that grows whenever the marks changed. */
    public long version() {
        return version;
    }

    /** How many words hold marks: up to the highest marked. */
    public int words() {
        return highest + 1;
    }

    /** The words, as the shader reads them: valid up to {@link #words()}. */
    MemorySegment segment() {
        return segment;
    }
}
