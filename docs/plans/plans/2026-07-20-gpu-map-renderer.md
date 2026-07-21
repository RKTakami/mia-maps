# GPU Map Renderer (Rust-native) Implementation Plan


**Goal:** Replace the 3D orbit map's CPU point-cloud rasterizer with a self-contained, greedy-meshed
GPU renderer driven by a new `map-native` Rust module — crisp, solid, gap-free at every zoom, at
Axiom-class throughput (static mesh: upload once, redraw with a matrix).

**Architecture:** Java samples the occupancy+colour grid (existing `VoxelCloud.sampleGrid`; new
`AbyssSpanStore.toGrid` for whole-Abyss) on the worker thread. Over JNI, Rust greedy-meshes it, uploads
an immutable VBO/IBO, and draws it through an MVP into an offscreen FBO whose colour attachment is a
**Java-owned** GL texture; Java blits that texture into the map GUI exactly as today. The CPU path stays
as the fallback.

**Tech Stack:** Rust (cargo 1.91, `jni 0.21`, `gl_generator`, `rust-embed`, GL 4.6 core), Java 21,
Fabric 1.21.11, LWJGL GL/GLFW, JUnit 5.

**Spec:** `docs/plans/specs/2026-07-20-gpu-map-renderer-design.md`

**Branch:** Work on `main`. NO branches/worktrees (project policy). Builds:
```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build            # Java + bundles the native (once the Gradle hook lands in Task 1)
(cd map-native && cargo build --release)   # native only
(cd map-native && cargo test)              # Rust unit tests
```

**Reference implementation to mirror:** the fork's proven native module at
`D:/Users/dev/VSCode-Projects/MIA_MAP_VOXY_FORK_project/voxy-native/` (crate layout, `build.rs`,
`gl.rs`, `initGL`→`getGlAddress` callback) and `.../src/main/java/me/cortex/voxy/client/core/jni/NativeEngine.java`
(temp-extract + `System.load` loader). Copy files where noted rather than hand-writing GL glue.

## File Structure

| File | Responsibility | New? | Tested? |
|---|---|---|---|
| `map-native/Cargo.toml`, `build.rs` | crate manifest; gl_generator 4.6 bindings | new | — |
| `map-native/src/gl.rs` | generated-bindings module (copied from voxy-native) | new | — |
| `map-native/src/lib.rs` | JNI entry points | new | — |
| `map-native/src/mesher.rs` | greedy meshing (pure) | new | yes (Rust) |
| `map-native/src/renderer.rs` | FBO + VBO/IBO + draw | new | no (GL) |
| `map-native/src/shader.rs` + `shaders/*.vsh/.fsh` | embedded shader (rust-embed) | new | no |
| `src/main/java/com/mia/aperture/map/MapNative.java` | loader, JNI decls, `getGlAddress`, `available()` | new | partial |
| `src/main/java/com/mia/aperture/map/MapMatrix.java` | `OrbitCamera` → MVP float[16] (pure) | new | yes |
| `src/main/java/com/mia/aperture/map/OrbitGpuRenderer.java` | render-thread upload+draw+texture id | new | no |
| `src/main/java/com/mia/aperture/map/AbyssSpanStore.java` | `toGrid(level)` → `VoxelCloud.Grid` | modify | maybe |
| `src/main/java/com/mia/aperture/map/OrbitScene.java` | route live+whole through GPU when available; CPU fallback | modify | no |
| `src/main/java/com/mia/aperture/map/MapSettings.java` / `MapConfig.java` | `gpuRender` toggle | modify | yes |
| `build.gradle` | `buildMapNative` + `copyMapNative` → `processResources` | modify | — |
| `.github/workflows/*` | native build matrix (win/mac/linux) | modify | — |

---

### Task 0: SPIKE — render an FBO into a Java-owned GL texture and blit it

De-risks the one fiddly integration point (§10 of the spec) BEFORE any Rust. Pure Java + LWJGL. No TDD
(exploratory); success = a solid colour appears in the map view.

**Files:**
- Temporary scratch in `OrbitScene` (or a throwaway `GpuSpike` class) — reverted/absorbed later.

- [ ] **Step 1: Get the raw GL texture id from a 1.21.11 DynamicTexture**

In a render-thread context (e.g. a temporary hook in `OrbitScene.render`), create a `DynamicTexture`
(as today) and extract the backing GL id. In 1.21.11 the id lives on the blaze3d `GlTexture` behind
`AbstractTexture.getTexture()`. Probe it:

```java
com.mojang.blaze3d.textures.GpuTexture gt = texture.getTexture();
// GlTexture (the GL backend impl) exposes the GL name; find it via its API or a field.
// Log the class + candidate accessors so we know the exact call:
System.out.println("[MIA spike] GpuTexture impl = " + gt.getClass().getName());
for (var f : gt.getClass().getDeclaredFields()) System.out.println("  field " + f.getType() + " " + f.getName());
for (var m : gt.getClass().getMethods()) if (m.getReturnType()==int.class && m.getParameterCount()==0)
    System.out.println("  int-getter " + m.getName());
```

Run once in-game, read the log, and identify the accessor (expected: a `glId()`/`id` int on
`com.mojang.blaze3d.opengl.GlTexture`). Reflect it if not public.

- [ ] **Step 2: FBO into that texture + clear**

On the render thread, with LWJGL GL (`org.lwjgl.opengl.GL30`, `GL45`):

```java
int fbo = GL30.glGenFramebuffers();
GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, glTexId, 0);
GL11.glViewport(0, 0, size, size);
GL11.glClearColor(0.2f, 0.6f, 1.0f, 1.0f);
GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);   // restore MC's framebuffer
GL30.glDeleteFramebuffers(fbo);
```

Then let the existing blit run. Save/restore any GL state you touch (framebuffer binding, viewport,
clear colour) so MC's GUI is unaffected.

- [ ] **Step 3: Verify + decide**

Run in-game, open the 3D view. Expected: the view area shows the **solid sky-blue clear colour** (proves
we rendered GL into the Java texture and blitted it). Record the exact GL-id accessor found.

- If it works → the Java-owned-texture design (spec §4.3) is confirmed. Capture the accessor snippet;
  Task 3/5 reuse it. **Revert the spike code** (or park behind a debug flag).
- If the GL id can't be obtained/attached cleanly → switch to **Rust-owns-the-texture** (spec §10
  fallback): Rust creates the texture+FBO and returns the id; Java wraps it for blit. Note this and
  adjust Tasks 3/5 accordingly before proceeding.

- [ ] **Step 4: Commit the finding (not the spike)**

```bash
git add docs/plans/plans/2026-07-20-gpu-map-renderer.md
git commit -m "docs(plan): record GL-texture-id spike result for the GPU map renderer

```
Append the found accessor + the go/no-go decision to this task in the plan file before committing.

---

### Task 1: `map-native` crate skeleton + JNI round-trip

Prove the crate builds, the DLL bundles into the jar, `MapNative` loads it, and a JNI call returns.

**Files:**
- Create: `map-native/Cargo.toml`, `map-native/build.rs`, `map-native/src/lib.rs`, `map-native/src/gl.rs`
- Create: `src/main/java/com/mia/aperture/map/MapNative.java`
- Modify: `build.gradle`

- [ ] **Step 1: Cargo.toml**

```toml
[package]
name = "map-native"
version = "0.0.1"
edition = "2021"

[lib]
name = "map_native"
crate-type = ["cdylib"]

[dependencies]
jni = "0.21.1"
rust-embed = "8.12.0"

[build-dependencies]
gl_generator = "0.14.0"
```

- [ ] **Step 2: build.rs (copy from voxy-native)**

Copy `D:/Users/dev/VSCode-Projects/MIA_MAP_VOXY_FORK_project/voxy-native/build.rs` verbatim into
`map-native/build.rs` (gl_generator, `Api::Gl, (4,6), Profile::Core, Fallbacks::All,
["GL_ARB_indirect_parameters"]` → `bindings.rs`).

- [ ] **Step 3: gl.rs (copy from voxy-native)**

Copy `.../voxy-native/src/gl.rs` verbatim into `map-native/src/gl.rs` (it `include!`s the generated
`bindings.rs`).

- [ ] **Step 4: lib.rs with init + version**

```rust
pub mod gl;

use jni::objects::JClass;
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_com_mia_aperture_map_MapNative_nInit<'local>(
    _env: JNIEnv<'local>, _class: JClass<'local>,
) -> jni::sys::jboolean {
    println!("[MIA map-native] initialized");
    jni::sys::JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_com_mia_aperture_map_MapNative_nVersion<'local>(
    _env: JNIEnv<'local>, _class: JClass<'local>,
) -> jni::sys::jint {
    1
}
```

- [ ] **Step 5: MapNative loader (mirror NativeEngine)**

Create `src/main/java/com/mia/aperture/map/MapNative.java`. Mirror the fork's
`me.cortex.voxy.client.core.jni.NativeEngine` loader: pick the resource path by OS/arch
(`map_native.dll` / `libmap_native.so` / `libmap_native.dylib` under `/natives/`), extract to a temp
file, `System.load`, call `nInit()`. Wrap load in try/catch → set `available=false` on any failure.

```java
package com.mia.aperture.map;

import java.io.InputStream;
import java.nio.file.*;

public final class MapNative {
    private static boolean available = false;
    private MapNative() {}

    public static synchronized void ensureLoaded() {
        if (available) return;
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String lib = os.contains("win") ? "map_native.dll"
                       : os.contains("mac") ? "libmap_native.dylib" : "libmap_native.so";
            String res = "/natives/" + lib;
            try (InputStream in = MapNative.class.getResourceAsStream(res)) {
                if (in == null) throw new RuntimeException("native not found: " + res);
                Path tmp = Files.createTempFile("map_native", lib.substring(lib.lastIndexOf('.')));
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                System.load(tmp.toAbsolutePath().toString());
            }
            if (!nInit()) throw new RuntimeException("nInit returned false");
            available = true;
            System.out.println("[MIA Maps] map-native loaded, version " + nVersion());
        } catch (Throwable t) {
            available = false;
            System.err.println("[MIA Maps] map-native unavailable, using CPU fallback: " + t);
        }
    }

    public static boolean available() { return available; }

    private static native boolean nInit();
    private static native int nVersion();
}
```

- [ ] **Step 6: Gradle native build + bundle (mirror voxy build.gradle)**

Add to `build.gradle` (mirror `buildNativeEngine`/`copyNativeEngine`):

```groovy
task buildMapNative(type: Exec) {
    workingDir 'map-native'
    commandLine 'cargo', 'build', '--release'
}
task copyMapNative(type: Copy) {
    dependsOn buildMapNative
    from 'map-native/target/release'
    include '*.dll', '*.so', '*.dylib'
    into 'src/main/resources/natives'
}
processResources.dependsOn copyMapNative
```

- [ ] **Step 7: Load on client init**

Call `MapNative.ensureLoaded()` once from `MiaApertureModClient` init (render thread / client startup).

- [ ] **Step 8: Build + verify**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
```
Expected: BUILD SUCCESSFUL; `src/main/resources/natives/map_native.dll` produced and bundled. In-game
(or a smoke run) logs `map-native loaded, version 1`.

- [ ] **Step 9: Commit**

```bash
git add map-native src/main/java/com/mia/aperture/map/MapNative.java build.gradle
git commit -m "feat(gpu): map-native crate skeleton + JNI loader round-trip

```

---

### Task 2: Greedy mesher in Rust (TDD)

**Files:**
- Create/modify: `map-native/src/mesher.rs`, wire `pub mod mesher;` in `lib.rs`

- [ ] **Step 1: Write failing tests**

In `map-native/src/mesher.rs`:

```rust
// Interleaved output: per vertex [x,y,z (f32, cell units), nx,ny,nz (f32), rgba (u32)].
pub struct Mesh { pub vertices: Vec<f32>, pub colors: Vec<u32>, pub indices: Vec<u32> }

pub fn greedy_mesh(opaque: &[bool], argb: &[i32], gx: usize, gy: usize, gz: usize) -> Mesh { todo!() }

#[cfg(test)]
mod tests {
    use super::*;
    fn idx(x:usize,y:usize,z:usize,gx:usize,gz:usize)->usize { (y*gz+z)*gx+x }

    #[test]
    fn all_air_no_quads() {
        let m = greedy_mesh(&vec![false;6*6*6], &vec![0;6*6*6], 6,6,6);
        assert!(m.indices.is_empty());
    }

    #[test]
    fn solid_slab_has_only_exposed_faces() {
        // fill the whole 4^3 grid solid; every interior face is hidden, so only the 6 outer
        // sides produce geometry: 6 quads = 6*2 tris = 36 indices when fully merged.
        let n = 4*4*4;
        let m = greedy_mesh(&vec![true;n], &vec![0xFF808080u32 as i32;n], 4,4,4);
        assert_eq!(m.indices.len(), 36, "6 merged faces -> 12 tris");
    }

    #[test]
    fn single_cell_is_a_closed_cube() {
        let mut o = vec![false;3*3*3]; o[idx(1,1,1,3,3)] = true;
        let mut c = vec![0i32;3*3*3]; c[idx(1,1,1,3,3)] = 0xFF3366CCu32 as i32;
        let m = greedy_mesh(&o,&c,3,3,3);
        assert_eq!(m.indices.len(), 36, "6 faces of one cube");
        assert_eq!(m.vertices.len()/6, m.colors.len(), "one colour per vertex (6 floats/vertex)");
    }
}
```

- [ ] **Step 2: Run, verify fail**

```bash
cd map-native && cargo test
```
Expected: FAIL (`todo!()`).

- [ ] **Step 3: Implement greedy_mesh**

Implement the standard greedy meshing sweep: for each of the 6 face directions, iterate slices along
that axis; a cell contributes a face when it is solid and its neighbour across the face is air (or
out of bounds); merge maximal rectangles of equal-`argb`, equal-exposure cells within the slice into
one quad; emit 4 vertices (position in cell units, the face normal, the cell colour) + 6 indices.
Deduplicate is unnecessary (quads are independent). Colour stored per vertex (parallel `colors` vec, or
packed into the interleaved buffer — keep the test's "6 floats/vertex + parallel colors" shape).

- [ ] **Step 4: Run, verify pass**

```bash
cd map-native && cargo test
```
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add map-native/src/mesher.rs map-native/src/lib.rs
git commit -m "feat(gpu): greedy voxel mesher in Rust (TDD)

```

---

### Task 3: GL init + FBO clear into the Java texture (Rust)

Move Task 0's proof into Rust: load GL, create a context handle, clear the Java-owned texture via an FBO.

**Files:**
- Modify: `map-native/src/lib.rs`; Create: `map-native/src/renderer.rs`
- Modify: `src/main/java/com/mia/aperture/map/MapNative.java`

- [ ] **Step 1: initGL via getGlAddress callback (mirror voxy-native)**

Add to `lib.rs` a `Java_com_mia_aperture_map_MapNative_nInitGL` that calls `gl::load_with(|sym| ...)`
resolving through a Java static `getGlAddress(String):long` (copy the closure from
`voxy-native/src/lib.rs:22-42`). In `MapNative.java` add:

```java
public static native void nInitGL();
// Called back FROM native to resolve GL symbols against MC's context.
public static long getGlAddress(String symbol) {
    return org.lwjgl.glfw.GLFW.glfwGetProcAddress(symbol);
}
```
Call `nInitGL()` once on the render thread right after `ensureLoaded()` succeeds (guard so it runs once).

- [ ] **Step 2: renderer.rs — context + clear**

```rust
use crate::gl;

pub struct Ctx { fbo: u32 }

pub fn create() -> Box<Ctx> {
    let mut fbo = 0u32;
    unsafe { gl::GenFramebuffers(1, &mut fbo); }
    Box::new(Ctx { fbo })
}

pub fn clear_into(ctx: &Ctx, tex_id: u32, w: i32, h: i32, r: f32, g: f32, b: f32) {
    unsafe {
        let mut prev_fbo = 0i32;
        gl::GetIntegerv(gl::DRAW_FRAMEBUFFER_BINDING, &mut prev_fbo);
        gl::BindFramebuffer(gl::FRAMEBUFFER, ctx.fbo);
        gl::FramebufferTexture2D(gl::FRAMEBUFFER, gl::COLOR_ATTACHMENT0, gl::TEXTURE_2D, tex_id, 0);
        gl::Viewport(0, 0, w, h);
        gl::ClearColor(r, g, b, 1.0);
        gl::Clear(gl::COLOR_BUFFER_BIT);
        gl::BindFramebuffer(gl::FRAMEBUFFER, prev_fbo as u32);
    }
}
```

Expose JNI `nCreateContext()->jlong` (Box::into_raw), `nDestroyContext(jlong)`, and
`nClear(jlong handle, jint texId, jint w, jint h)` in `lib.rs`.

- [ ] **Step 3: Java calls it from the spike site**

In the same render-thread spot Task 0 used, replace the Java GL with `MapNative.nClear(handle, glTexId,
size, size)`. Build (`./gradlew build`), run in-game: the 3D view shows the clear colour — now driven by
**Rust**. This validates initGL + FBO + Java-texture attachment through JNI.

- [ ] **Step 4: Commit**

```bash
git add map-native src/main/java/com/mia/aperture/map/MapNative.java
git commit -m "feat(gpu): Rust initGL + FBO clear into the Java-owned map texture

```

---

### Task 4: MapMatrix — OrbitCamera → MVP (Java, TDD)

**Files:**
- Create: `src/main/java/com/mia/aperture/map/MapMatrix.java`
- Test: `src/test/java/com/mia/aperture/map/MapMatrixTest.java`

- [ ] **Step 1: Failing test**

```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapMatrixTest {
    @Test
    void pointOnCameraAxisProjectsToCentre() {
        // Camera looking down -Z at origin from z=+10; a point at origin projects to NDC ~ (0,0).
        float[] mvp = MapMatrix.orbit(0, 0, 0, /*yaw*/0, /*pitch*/0, /*dist*/10,
                /*fovDeg*/70, /*aspect*/1, /*near*/0.1f, /*far*/1000f);
        float[] p = MapMatrix.mul(mvp, 0, 0, 0, 1);   // clip space
        assertEquals(0.0, p[0] / p[3], 1e-4);
        assertEquals(0.0, p[1] / p[3], 1e-4);
        assertTrue(p[3] > 0, "point in front of camera");
    }

    @Test
    void columnMajorSixteenFloats() {
        assertEquals(16, MapMatrix.orbit(0,0,0,30,20,50,70,1.6f,0.1f,2000f).length);
    }
}
```

- [ ] **Step 2: Run, verify fail** — `./gradlew test --tests '*MapMatrixTest*'` → FAIL.

- [ ] **Step 3: Implement** `MapMatrix.orbit(...)` (perspective × view from yaw/pitch/distance around
focus) returning **column-major** `float[16]`, plus a `mul(float[16], x,y,z,w)->float[4]` helper. Match
`OrbitCamera`'s basis/FOV so overlays stay aligned (cross-check against `BeaconGeometry.project`
conventions — same handedness, same focal).

- [ ] **Step 4: Run, verify pass** — `./gradlew test --tests '*MapMatrixTest*'` → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/MapMatrix.java src/test/java/com/mia/aperture/map/MapMatrixTest.java
git commit -m "feat(gpu): OrbitCamera->MVP matrix (pure, tested)

```

---

### Task 5: uploadGrid + render (mesh draw) — the real renderer

**Files:**
- Modify: `map-native/src/lib.rs`, `map-native/src/renderer.rs`; Create: `map-native/src/shader.rs`,
  `map-native/shaders/map.vsh`, `map-native/shaders/map.fsh`
- Create: `src/main/java/com/mia/aperture/map/OrbitGpuRenderer.java`

- [ ] **Step 1: Shader (embedded via rust-embed)**

`shaders/map.vsh`: transform `position` by `uMVP`; pass normal + colour. `shaders/map.fsh`: `N·L`
directional (`LX,LY,LZ`) + ambient (match `OrbitScene` constants), output `colour * shade`. `shader.rs`
compiles them (copy the compile/link helpers from `voxy-native/src/shader.rs`).

- [ ] **Step 2: renderer.rs — upload + draw**

Extend `Ctx` with `vao, vbo, ibo, program, index_count`. Add:
- `upload(ctx, mesh)`: `glBufferStorage` the interleaved vertices + indices (immutable), set up the VAO
  attributes (pos vec3, normal vec3, colour as `GL_UNSIGNED_BYTE ×4 normalized`), store `index_count`.
- `render(ctx, mvp, tex_id, w, h)`: bind FBO→`tex_id` (+ a depth renderbuffer sized `w×h`, recreated on
  size change), viewport, clear colour+depth, enable depth test + back-face cull, use program, set
  `uMVP`, bind VAO, `glDrawElements(GL_TRIANGLES, index_count, GL_UNSIGNED_INT, 0)`, restore FBO/state.

- [ ] **Step 3: JNI in lib.rs**

```
nUploadGrid(handle, boolean[] opaque, int[] argb, int gx,gy,gz, int cell, int ox,oy,oz)
    -> call mesher::greedy_mesh, scale positions by cell (+origin), renderer::upload
nRender(handle, float[] mvp16, int texId, int w, int h) -> renderer::render
```
(Read the Java arrays via `env.get_..._array_region`. Positions: multiply cell-units by `cell` and add
origin*cell in Rust, matching `sampleGrid`/`OrbitMesher`.)

- [ ] **Step 4: OrbitGpuRenderer (Java)**

Render-thread renderer: owns the target `DynamicTexture` (as `OrbitScene.TEXTURE` does) + its GL id
(Task 0 accessor) + a native context handle. API:
- `void submitGrid(VoxelCloud.Grid g)` — remembers the latest grid + a dirty flag (called from the worker
  handoff).
- `Identifier render(OrbitCamera cam, double zoom, int size)` — on the render thread: if dirty, `nUploadGrid`;
  build MVP via `MapMatrix.orbit`; `nRender(handle, mvp, glTexId, size, size)`; return `TEXTURE`.

- [ ] **Step 5: Build + smoke**

Temporarily route `OrbitScene`'s live path to call `OrbitGpuRenderer` with a `sampleGrid` result (behind
a debug flag). `./gradlew build`, run in-game: the live 3D view shows **solid, crisp, greedy-meshed
terrain**. Fix winding/normal/colour packing as needed (this is the visual-verify step).

- [ ] **Step 6: Commit**

```bash
git add map-native src/main/java/com/mia/aperture/map/OrbitGpuRenderer.java
git commit -m "feat(gpu): greedy-meshed GPU draw into the map texture (uploadGrid + render)

```

---

### Task 6: OrbitScene rewire (live path) + gpuRender toggle + fallback

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/OrbitScene.java`
- Modify: `src/main/java/com/mia/aperture/map/MapSettings.java`, `MapConfig.java`
- Test: `src/test/java/com/mia/aperture/map/MapSettingsTest.java`

- [ ] **Step 1: Failing test for the setting**

Add to `MapSettingsTest`:
```java
@Test
void gpuRenderDefaultsOn() {
    assertTrue(new MapSettings().gpuRender);
    assertTrue(MapConfig.fromJson("{}").gpuRender);
}
```

- [ ] **Step 2: Run, verify fail** — `./gradlew test --tests '*MapSettingsTest*'` → FAIL.

- [ ] **Step 3: Add the field**

In `MapSettings.java`, near `smooth3d`:
```java
// Route the 3D view through the native GPU renderer when the native module is available.
public boolean gpuRender = true;
```

- [ ] **Step 4: Rewire OrbitScene**

Gate the live path: when `MapNative.available() && MiaApertureModClient.mapSettings.gpuRender`, route the
frame through `OrbitGpuRenderer` (submit the `sampleGrid` grid on the worker; upload+draw on the render
thread in `render(...)`), and SKIP the CPU raster (`buildFrame`/`rasterizeInto`) for that path. Otherwise
keep the existing CPU path (LOD-gated cubes + `smooth3d`). The GPU renderer's texture replaces the CPU
`buf`→texture handoff for the live path; keep the CPU `depthBuf`/projection for overlays OR route overlay
projection through `MapMatrix` (keep overlays correct — verify in Task 9).

- [ ] **Step 5: Run tests + build** — `./gradlew build` → PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mia/aperture/map/OrbitScene.java src/main/java/com/mia/aperture/map/MapSettings.java src/test/java/com/mia/aperture/map/MapSettingsTest.java
git commit -m "feat(gpu): route live 3D view through the GPU renderer (gpuRender toggle, CPU fallback)

```

---

### Task 7: Whole-Abyss GPU path — `AbyssSpanStore.toGrid`

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/AbyssSpanStore.java`
- Test: `src/test/java/com/mia/aperture/map/AbyssSpanStoreTest.java` (if a test harness exists there)
- Modify: `src/main/java/com/mia/aperture/map/OrbitScene.java`

- [ ] **Step 1: `toGrid(level)`**

Add `public VoxelCloud.Grid toGrid(Snapshot snap, int level)` that materializes the chosen mip into a
dense `opaque[]`+`argb[]` over the column's bounded extent at that level's `cellSize`, with origin in
cells (reuse `forEachSurface`/the mip arrays; fill solid cells + colour). Choose `level` as
`buildWholeCloud` does (finest mip whose cell grid stays within a size budget, e.g. ≤ ~4M cells).

- [ ] **Step 2: Test dims/origin (if harness available)**

Assert `toGrid` returns dims and origin consistent with `cellSize(level)` and the snapshot bounds; a
known solid span yields solid cells at the expected indices.

- [ ] **Step 3: Wire whole-Abyss through the GPU renderer**

In `OrbitScene`, when `whole && MapNative.available() && gpuRender`, submit `toGrid(snap, level)` to
`OrbitGpuRenderer` instead of `buildWholeCloud`. Re-mesh only when the snapshot `seq` changes.
**Spike:** if the grid exceeds the single-VBO budget at the desired level, either bump to a coarser
level (simplest) or split into vertical sub-grids drawn via `glMultiDrawElementsIndirect` — record which
in the commit.

- [ ] **Step 4: Build + commit**

```bash
./gradlew build
git add src/main/java/com/mia/aperture/map/AbyssSpanStore.java src/main/java/com/mia/aperture/map/OrbitScene.java
git commit -m "feat(gpu): whole-Abyss via AbyssSpanStore.toGrid through the GPU renderer

```

---

### Task 8: Multiplatform native build + CI

**Files:**
- Modify: `.github/workflows/macos-build.yml` (+ a windows/linux native step or a dedicated workflow)
- Modify: `build.gradle` if per-target output paths need arch subfolders

- [ ] **Step 1: macOS universal native**

Extend the macOS workflow: install Rust, `rustup target add aarch64-apple-darwin x86_64-apple-darwin`,
build both, `lipo` into a universal `libmap_native.dylib`, place under `src/main/resources/natives/`
before the Java build.

- [ ] **Step 2: Windows + Linux natives**

Add cargo build steps (windows-latest → `map_native.dll`, ubuntu-latest → `libmap_native.so`) and bundle
them. Ensure the loader's OS/arch selection matches the bundled filenames.

- [ ] **Step 3: Verify the built jar contains all three natives**

```bash
unzip -l build/libs/mia-maps-*.jar | grep natives
```
Expected: `.dll`, `.so`, `.dylib` present.

- [ ] **Step 4: Commit**

```bash
git add .github build.gradle
git commit -m "build(gpu): multiplatform map-native build (win/mac/linux)

```

---

### Task 9: Build, install, in-game verify + measure (Axiom bar)

**Files:** none.

- [ ] **Step 1: Build + install**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
cp build/libs/mia-maps-0.1.8-beta.jar "C:/Users/dev.000/AppData/Roaming/ModrinthApp/profiles/Mine In Abyss Modpack/mods/"
```

- [ ] **Step 2: In-game checks**

1. **Crisp + solid + gap-free** at close, mid, wide, and **whole-Abyss** zoom — no cubes-cloud gaps, no
   mush, block ledges readable for a cliff descent.
2. **Overlays align** — waypoints, route, dig markers, mob markers land on the right terrain (MVP parity).
3. **Fallback** — toggle `gpuRender` off (or simulate native-missing): CPU path renders; no crash.
4. **Frame time** — 3D Stats on: steady orbit-frame GPU cost (target **sub-millisecond** map draw) and
   the one-off cost on a grid change (zoom/level/whole-snapshot). **This is the Axiom-bar number.**
5. **No GL errors / no leaks** across map open/close, zoom churn, and disconnect.

- [ ] **Step 3: Report findings** — per check + the frame-time numbers; note any winding/normal/colour or
overlay-parity fixes still needed.

---

## Notes for the implementer

- Work on `main`. NO branch/worktree. Commit per task; push only when the owner asks.
- **Task 0 is load-bearing** — do not start Rust rendering (Task 3/5) until the GL-texture-id handoff is
  proven (or the Rust-owns-texture fallback is chosen).
- Copy `build.rs`, `gl.rs`, and the shader/compile helpers from `voxy-native` rather than hand-writing GL
  glue — they are proven in the same MC/GL environment.
- All GL calls (`nInitGL`, `nUploadGrid`, `nRender`, FBO) run on the **render thread** only. Grid
  sampling/`toGrid` stay on the worker.
- Save/restore GL state you touch (FBO binding, viewport, clear colour, depth/cull enables) so MC's GUI
  is unaffected after the blit — the retired FBO path's lesson.
- Keep the CPU path intact for `gpuRender=false` / native-unavailable. No inline narrating comments;
  comments explain constraints/why.
- **Meshing off the render-thread critical path where possible**; per-frame steady state must be a single
  `nRender` with a new MVP (no re-mesh) — that is the Axiom-class target.
```
