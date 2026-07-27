# Standalone WorldEngine — reading a LOD store without Voxy's renderer (macOS)

**Status:** design only, nothing implemented. Written 2026-07-26.

## Problem

On macOS, Voxy disables itself and MIA Maps consequently has no data source.

`VoxyClient.initVoxyClient` gates on:

```java
systemSupported = Capabilities.compute && Capabilities.indirectParameters && !hasBrokenDepthSampler;
```

- `compute` = `cap.glDispatchComputeIndirect != 0` — ARB_compute_shader, **GL 4.3**
- `indirectParameters` = `cap.glMultiDrawElementsIndirectCountARB != 0` — ARB_indirect_parameters, **GL 4.6**

Apple froze OpenGL at 4.1 and deprecated it in 2018. Verified three ways on the M4 Mac:
the runtime log (`OpenGL Version: 4.1 Metal - 90.5`, `DSA support not detected`,
`Voxy is unsupported on your system.`), the capability source above, and Apple's own SDK headers —
which declare `GL_VERSION_3_0` … `GL_VERSION_4_1` and stop, with both required entry points absent
while GL 4.0/4.1 control symbols are present. **This is not fixable in packaging.**

When unsupported, `VoxyCommon.setInstanceFactory(VoxyClientInstance::new)` is never called, so no
`VoxyClientInstance` and no `WorldEngine` exist.

## Key insight — the renderer blocker and the data blocker are separate

`me.cortex.voxy.common.world.WorldEngine` needs **no GL**:

```java
public WorldEngine(SectionStorage storage)
public WorldEngine(SectionStorage storage, @Nullable VoxyInstance instance)   // instance is NULLABLE
```

`SectionStorage` is plain Java over RocksDB/zstd. What actually blocks MIA Maps is **its own
coupling**: every engine access goes through the render system.

```
IGetVoxyRenderSystem.getNullable() -> rs.getEngine()
```

`rs` is null exactly when Voxy is unsupported. Replacing that single accessor with one that can fall
back to a self-constructed engine decouples the map from the renderer.

## Scope

| | On macOS after this change |
|---|---|
| **Read** a store | Yes — the point of the change |
| **Ingest** / update a store | **No, ever.** Ingest is driven by the gated client instance. |
| Aperture culling (`H`, Ctrl+scroll) | No — genuinely needs the Voxy renderer |

So the Mac reads a store **copied from the Windows box, frozen at copy time**. That is enough for
map rendering, routing, 3D-view and UI work; it is not live play.

## Design

### 1. `com.mia.aperture.map.MapEngineSource` — one choke point

```java
public final class MapEngineSource {
    private static volatile WorldEngine standalone;   // lazily opened, null when unavailable

    /** Live engine if Voxy is running, else the standalone read-only engine, else null. */
    public static WorldEngine get() {
        VoxyRenderSystem rs = IGetVoxyRenderSystem.getNullable();
        if (rs != null) return rs.getEngine();        // unchanged behaviour on Windows
        return standalone();                          // macOS / Voxy-unsupported path
    }
}
```

Returns `null` when no store path is configured or opening failed. **Every existing call site already
guards on a null render system**, so those guards become null-engine guards with no new failure mode.

`standalone()` opens once under a lock; the map worker and route service are daemon threads and will
race on first use.

### 2. Building the storage — mirror `VoxyClientInstance.createStorage`, minus the instance

The client does (`VoxyClientInstance:106-121`):

```java
var ctx = new ConfigBuildCtx();
ctx.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, basePath.toString());
ctx.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, identifier.getWorldId());
ctx.setProperty(ConfigBuildCtx.PLAYER_UUID, ...);
ctx.pushPath(ConfigBuildCtx.DEFAULT_STORAGE_PATH);
return this.config.sectionStorageConfig.build(ctx);
```

We instead push an **absolute** path, which per `docs/STORAGE-CONFIG.md` overrides the default — the
fork's documented way to point a backend at an existing store:

```
Serializer -> CompressionAdaptor{ZSTD level 1} -> BasicPathConfig{path=<abs>} -> RocksDB
```

then `new WorldEngine(storage, null)`.

**No `ReadonlyCachingLayer` is needed.** That layer exists to let world A write while reading B.
MIA Maps only ever reads, so the plain chain is simpler and avoids depending on the fork's
`flush()` fix (`993c595b`).

### 3. Configuration

`MapSettings.lodStorePath` — absolute path to a `.../storage/` directory. Empty (default) = feature
off, which is every existing Windows install. Only consulted when `rs == null`, so it cannot affect
the live path.

### 4. Call sites

Six move to `MapEngineSource.get()`:

- `MapCompositor:83`, `MapCompositor:157`
- `RouteService:167`, `RouteService:178`
- `AbyssModelBuilder:64`
- `OrbitScene:314`

**`InputHandler:52` stays as-is** — it wants `mia$getRenderDistanceTracker()`, which is Voxy
renderer state, not the engine. Aperture culling is correctly dead without Voxy.

### 5. Lifecycle

Close the standalone engine on disconnect/exit to release the RocksDB lock; reopen per world.
Leaking it holds a lock on the store directory across sessions.

## Hard prerequisite — the fork jar, rebuilt

**Neither the stock jar nor an old fork jar can do this on macOS.** Verified by unpacking both:

- Stock `voxy-mia-edition-2.5-normal-version.jar`: LWJGL lmdb/zstd for **linux + windows only**, and
  its bundled `rocksdbjni-10.2.1.jar` has **no `.jnilib`**.
- The fork before `d968fbae`: same gaps.

So the macOS native work in the fork (`d968fbae`) is the **enabling prerequisite** for this design,
not a cosmetic fix. macOS must run the **fork** jar rebuilt at or after that commit.

Conveniently, the fork's known renderer hang (terrain-load `NodeManager: request-in-flight`, INTEROP
`19dd840d`) is **irrelevant on macOS** — Voxy disables its renderer there anyway, so only the storage
layer is exercised.

## Caveats to verify before trusting output

- **RocksDB opens read-write even for read-only use** and may compact on open. Point at a **copy**,
  never the live PC store and never a network share of it.
- **Compression must match** how the store was written (default ZSTD level 1) or sections won't decode.
- **World identifier must line up** — copy the whole `<world-hash>/storage/` directory.
- **Mapper comes from the store** (`SectionStorage implements IMappingStorage`), so block colours
  should align for the same MC version — validate in game, per the fork's own note.
- **Unverified:** whether `NativeEngine.loadNativeLibrary()` (called before the support check)
  succeeds on macOS with the fork jar. It did not fatally throw with the stock jar, but the fork
  ships a Rust `libvoxy_native.dylib` that stock lacks. Check first.
- **Data is frozen** at copy time. Nothing on the Mac will ever update it.

## MIA Maps' own rendering is NOT blocked on macOS — only the data is

Worth stating plainly, because the Voxy blocker makes it easy to assume otherwise: **the map never
renders through Voxy**, it only reads Voxy's data and draws itself. Auditing every GL entry point in
`map-native/src` (`gl.rs`, `renderer.rs`, `mesher.rs`, `shader.rs`):

- shaders are **`#version 330 core`** (GL 3.3)
- calls are VAO/VBO/EBO/FBO/renderbuffer/`DrawElements`/uniform — all GL 3.3-era
- **no** compute dispatch, DSA, `MultiDraw*`, indirect draw, SSBO or buffer-storage calls

Nothing exceeds Apple's 4.1.

| Path | Needs | Apple GL 4.1 |
|---|---|---|
| 2D map (minimap + fullscreen) | CPU raster → `DynamicTexture` | Works |
| 3D orbit, CPU path | same | Works |
| 3D orbit, GPU path (`map-native`) | GL 3.3 / GLSL 330 | Works |
| Voxy terrain renderer | GL 4.3 + 4.6 | Blocked — **and unused by the map** |

`map-native` already loads on the Mac (`map-native loaded, version 1`, 0.1.12-beta, 2026-07-26).

**Caveat:** this is static analysis of the call sites plus a successful native load, **not** a
rendered frame. `initGLOnce()` resolves GL symbols on the render thread and has never run on this
Mac, because there has been no data to draw. Treat "the GPU path works on macOS" as well-founded but
unproven until a frame renders.

## BETTER OPTION (investigate before building the above): ungate storage+ingest in the fork

The design above gives the Mac a **frozen copy** of a store. A small fork change may give it a
**live, self-updating** one instead — strictly better, and it removes the copy step entirely.

**GL 4.3 is not obtainable on macOS** (Apple capped OpenGL at 4.1 in 2018; GLFW on macOS creates
contexts only through NSGL/CGL and does not implement the EGL context API, so Zink/Mesa-over-MoltenVK
cannot be substituted under LWJGL without deep surgery; ANGLE targets GL ES, not desktop GL). **But
we do not need it** — GL 4.3 would only revive Voxy's *terrain renderer*, and the sole thing that
buys us is **ingest**. And ingest does not use the renderer.

**Voxy already separates the two concerns:**

| | Where | Needs GL |
|---|---|---|
| **Instance** — storage + ingest | `commonImpl/VoxyInstance` (constructs `VoxelIngestService:38`) | **No** — the class has *no* client/render/GL imports |
| **RenderSystem** — terrain drawing | created in `client/mixin/minecraft/MixinLevelRenderer:86` on world load | Yes |

They are only coupled by `VoxyClient.initVoxyClient` bundling `setInstanceFactory(...)` into the same
`if (systemSupported)` block as the GL call `SharedIndexBuffer.INSTANCE.id()`.

**Ingest is driven by vanilla Minecraft, not by Voxy's renderer** — `VoxelIngestService.tryAutoIngestChunk`
is called from `MixinClientChunkCache:45` and `MixinClientLevel:85` (plus Sodium's
`MixinRenderSectionManager`, and Sodium works fine on macOS).

### The change — TWO edits, and the second is mandatory

1. **`VoxyClient.initVoxyClient`** — register the instance factory outside the `systemSupported`
   gate (or under a storage-only mode), keeping `SharedIndexBuffer.INSTANCE.id()` gated.
2. **⚠ `MixinLevelRenderer` — add an explicit support guard.** It currently skips renderer creation
   only because `instance == null` (line 76). **Ungating the instance removes the very condition
   that protects it**, so it would begin constructing `VoxyRenderSystem` on a GL-4.1 machine, and
   the `catch (RuntimeException)` there **rethrows** unless an Iris shaderpack is active. Without
   this guard the change turns a clean "unsupported" into a crash.

Then MIA Maps takes its engine from the **instance** rather than the render system, and gets **live
data**. `MapEngineSource` from the design above is still the right shape — only its fallback source
changes.

### Unverified — check before relying on this

- `VoxyClientInstance.shutdown()` calls `RenderResourceReuse.clearResources()` (GL cleanup). May
  need guarding when no GL resources were ever created.
- Whether anything else on the instance path touches GL indirectly.
- Whether Voxy's config/UI/commands assume a live render system.
- **None of this has been run.** It is a code-reading result, not a tested one.

## Open question

Rendering is therefore not the obstacle — **the data path is the whole of it.** If this is built, the
2D map, 3D view and routing should all draw on the Mac. What it still does not buy is live testing,
since ingest can never run there and the store stays frozen at copy time.
