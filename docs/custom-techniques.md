# Writing a custom technique

A game or a mod can ship a technique the engine does not have and have players choose it like the engine's own: the
engine registers its techniques through the same registry (`Techniques`) and the same call.

## The parts

- **A slot** (`TechniqueSlot`) is a place a technique plugs into, such as `render.ao`. Its one setting holds the chosen
  kind's id, or `off` where the slot may be off. The engine's slots are `AmbientOcclusion.SLOT`,
  `ScreenSpaceReflections.SLOT`, `ProbeVolumeLighting.SLOT`, `Transparency.SLOT`, `WorldLights.SLOT`,
  `RenderSettings.INTERPOLATION_SLOT`, `DisplayTransform.SLOT` and `Audio.SPATIALIZER`. A game may declare slots of its
  own with `TechniqueSlot.of`.
- **A kind** (`TechniqueKind`) is an id, a record of tunables with their defaults, and a factory. The factory is given
  what the slot's owner hands it (for the render slots, the owner itself: its view, its ray tracing scene) and a
  supplier of the kind's config as it is now, and makes what the slot runs (for the render slots, a frame graph
  `Technique` built in the owner's place).
- **The tunables** are a record implementing `TechniqueConfig`. Each component (`boolean`, `int`, `float`, `String` or
  an enum) is a setting named `<slot>.<kind>.<component>`, described and bounded by `@Tunable`.

## An ambient occlusion of your own

```java
public final class AcmeAo {

    /** Its tunables: render.ao.acme-ao.strength, 0 to 1. */
    public record Config(@Tunable(value = "how dark creases get", min = 0, max = 1) float strength)
        implements TechniqueConfig {
    }

    public static final TechniqueKind<Config, AmbientOcclusion, Technique> KIND = TechniqueKind.of(
        AmbientOcclusion.SLOT, "acme-ao", new Config(0.8f), "Acme's occlusion",
        (ao, config) -> new Pass(ao.view(), config));

    /** Built in AmbientOcclusion's place: modifies the scene's color, provides the specular occlusion. */
    record Pass(SceneView view, Supplier<Config> config) implements Technique {
        public List<Key<?>> provides() { return List.of(Keys.SPECULAR_OCCLUSION); }

        public void build(GraphBuilder g) {
            Image color = g.modify(Keys.SCENE_COLOR);
            ...
            g.pass("acme ao").write(color, Access.STORAGE_WRITE).record(ctx -> {
                float strength = config.get().strength();   // read as frames record: no rebuild, nothing allocated
                ...
            });
            g.provide(Keys.SPECULAR_OCCLUSION, specular);
        }
    }
}

engine.settings().techniques().register(AmbientOcclusion.SLOT, AcmeAo.KIND);   // at startup, before frames
```

The player then chooses it as any other: `render.ao=acme-ao` in the settings file, `--set render.ao=acme-ao` on the
command line, `AmbientOcclusion.SLOT.choose(settings, AcmeAo.KIND)` in code, or from an options screen listing
`techniques.variants(AmbientOcclusion.SLOT)`. Its tunables save and load with the player's settings
(`render.ao.acme-ao.strength=0.6`).

## What each slot's owner expects

| Slot | Factory given | Makes | Contract |
|---|---|---|---|
| `render.ao` | `AmbientOcclusion` | `Technique` | modifies `Keys.SCENE_COLOR`, provides `Keys.SPECULAR_OCCLUSION` (R8, 1: none) |
| `render.ssr` | `ScreenSpaceReflections` | `Technique` | modifies `Keys.SCENE_COLOR`, may read `Keys.SCENE_DEPTH` with `g.read` |
| `render.gi` | `ProbeVolumeLighting` | `Technique` | modifies `Keys.INDIRECT_LIGHT` |
| `render.transparency` | `ChosenTransparency` | `Technique` | modifies `Keys.SCENE_COLOR` and `Keys.SCENE_DEPTH` (`g.modify` both) |
| `audio.spatializer` | the output's `AudioFormat` | `Spatializer` | made on a background thread; used on the audio thread |
| `render.gi.lights`, `render.interpolation`, `display.toneCurve` | nothing | one of the engine's modes | the shaders' or the sync's: a kind picks one, with tunables of its components' names |

The slot's owner keeps its id, order and keys; the kind's technique is made once per owner and kind, built whenever
the graph builds, and swapped by the rebuild that a change of the slot's setting causes, as the engine's kinds are.
Values the technique reads when the graph builds (sizes, which passes) should be read with `g.setting(KIND.setting(
"name"))` so the graph rebuilds when they change; the rest come from the config supplier as frames record.

## When a kind goes away

A settings file naming a kind no one registered (the mod was removed) loads: the slot uses its default kind, the
problem is logged once and listed in `settings.techniques().problems()`, and the saved id is kept, so the kind is
chosen again once the mod is back. Register kinds before the first frame, so a saved choice of them is never reported.

`AmbientOcclusionTest.aGamesOwnKindIsChosenThroughTheSettingsAsTheEnginesAre` registers a kind of this sort and selects
it through the settings; `TechniquesTest` covers the registry without a GPU.
