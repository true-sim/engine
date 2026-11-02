package com.truesim.engine.render.particles;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.effects.ModuleRegistry;
import com.truesim.scene.effects.ModuleType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The particle modules of a kernel (effects.md E24): every {@code [ParticleModule]} Slang struct the build collected
 * into its dispatch, each at its opcode, with the stages it has and its module type. The build generates them, with
 * the kernel: {@link BuiltInModules#MODULES} holds the built-in modules (particles/modules, engine-render's kernels),
 * and a game whose shaders declare modules of its own gets a class of {@code slang.particleModules} holding them and
 * the built-in ones, with kernel variants (base and rays) of its own. Effects naming its modules are built against
 * {@link #registry()}.
 *
 * <pre>{@code
 * // gust.slang, the game's: one struct, its fields its parameters, its functions its stages
 * [ParticleModule("game.gust")]
 * public struct Gust
 * {
 *     public float strength = 1;
 *     public void update(inout ParticleStep s, inout Particle p) { p.velocity.x += strength * s.h; }
 * };
 *
 * particles.modules(GameModules.MODULES);                   // generated
 * EffectData leaves = EffectData.builder().emitter(...).build(GameModules.MODULES.registry());
 * }</pre>
 */
public final class ParticleModules {

    /** Stages, as particle-modules/modules.properties lists them: bit i of a module's stages is stage i. */
    static final int STAGE_INIT = 1;
    static final int STAGE_FORCE = 2;
    static final int STAGE_AFTER = 4;
    static final int STAGE_COLLIDE = 8;
    static final int STAGE_UPDATE = 16;
    static final int STAGE_OVER = 32;

    private final String shader;
    private final String raysShader;
    private final List<String> names;
    private final int[] stages;
    private final Map<String, Integer> opcodes = new HashMap<>();
    private final List<ModuleType> types;
    private final ModuleRegistry registry;

    @AllowAllocation("made once, by the generated classes")
    private ParticleModules(String entry, String[] names, int[] stages, List<List<ModuleType>> types) {
        Objects.requireNonNull(entry, "entry");
        shader = entry.isEmpty() ? null : "shaders/" + entry + ".spv";
        raysShader = entry.isEmpty() ? null : "shaders/" + entry + "Rays.spv";
        this.names = List.of(names);
        this.stages = stages.clone();
        if (stages.length != names.length) {
            throw new IllegalArgumentException(names.length + " modules, " + stages.length + " stages");
        }
        for (int i = 0; i < names.length; i++) {
            opcodes.put(names[i], i);
        }
        List<ModuleType> all = new ArrayList<>();
        types.forEach(all::addAll);
        this.types = List.copyOf(all);
        // The built-in modules scene-effects knows without the engine must be as their Slang declares them
        List<ModuleType> added = new ArrayList<>();
        for (ModuleType type : this.types) {
            ModuleType mirror = ModuleRegistry.BUILT_IN.type(type.name());
            if (mirror == null) {
                added.add(type);
            } else if (!mirror.equals(type)) {
                throw new IllegalStateException("Module " + type.name() + " disagrees with scene-effects' built-in one:"
                    + " Slang " + type + ", scene " + mirror);
            }
        }
        registry = ModuleRegistry.BUILT_IN.with(added.toArray(ModuleType[]::new));
    }

    /**
     * The modules of a kernel, as its generated class lists them: its entry points ({@code particle-modules/<class>},
     * empty for engine-render's own kernels), each module's name and stages by opcode, and the module types of its
     * shaders and their libraries.
     */
    @AllowAllocation("made once, by the generated classes")
    public static ParticleModules of(String entry, String[] names, int[] stages, List<List<ModuleType>> types) {
        return new ParticleModules(entry, names, stages, types);
    }

    /** The kernel variant's shader, with ray collision or not; null for engine-render's own kernels. */
    String shader(boolean rays) {
        return rays ? raysShader : shader;
    }

    /** The module types effects may name: scene-effects' built-in ones and these shaders' own. */
    public ModuleRegistry registry() {
        return registry;
    }

    /** The module types the build generated, of these shaders and their libraries. */
    public List<ModuleType> types() {
        return types;
    }

    /** The modules' names, by opcode. */
    public List<String> names() {
        return names;
    }

    /** The opcode of the module called {@code name}, or -1 if the kernel has none. */
    public int opcode(String name) {
        Integer op = opcodes.get(name);
        return op == null ? -1 : op;
    }

    /** The stages of the module at {@code opcode}: STAGE_* bits. */
    int stages(int opcode) {
        return stages[opcode];
    }
}
