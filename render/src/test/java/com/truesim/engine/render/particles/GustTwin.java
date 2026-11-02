package com.truesim.engine.render.particles;

import com.truesim.scene.effects.ModuleTwin;
import com.truesim.scene.effects.TwinParticle;

/**
 * The Java twin of test/particlegust.slang's {@code game.gust} (wired by its {@code [ModuleTwin]}): the same push
 * along +X by its strength, for CPU emitters.
 */
public final class GustTwin implements ModuleTwin {

    @Override
    public void run(TwinParticle p) {
        float a = p.value(0); // strength
        p.position(p.x() + 0.5f * a * p.h() * p.h(), p.y(), p.z());
        p.velocity(p.vx() + a * p.h(), p.vy(), p.vz());
    }
}
