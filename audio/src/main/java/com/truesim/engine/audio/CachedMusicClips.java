package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.AssetState;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.MusicClips;
import java.util.HashSet;
import java.util.Set;

/**
 * Music's clips through the asset cache: a clip is ready once loaded (a streamed one's ring primes when it starts),
 * priming acquires it (held while the music lives), and its length is known once loaded.
 */
@AllowAllocation("set up once; priming acquires")
public final class CachedMusicClips implements MusicClips {

    private final AssetCache cache;
    private final Set<AssetRef<AudioClip>> acquired = new HashSet<>();

    /** Music's clips loaded through {@code cache}. */
    public CachedMusicClips(AssetCache cache) {
        this.cache = cache;
    }

    @Override
    public boolean ready(AssetRef<AudioClip> clip) {
        return cache.state(clip) == AssetState.LOADED;
    }

    @Override
    public void prime(AssetRef<AudioClip> clip) {
        if (acquired.add(clip)) {
            cache.acquire(clip);
        }
    }

    @Override
    public double seconds(AssetRef<AudioClip> clip) {
        return ready(clip) ? cache.get(clip).seconds() : Double.NaN;
    }

    /** Releases every clip it acquired. */
    public void release() {
        for (AssetRef<AudioClip> clip : acquired) {
            cache.release(clip);
        }
        acquired.clear();
    }
}
