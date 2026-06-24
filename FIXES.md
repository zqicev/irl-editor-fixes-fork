# Fork fixes (IRL-editor, 1.21.1)

This fork of [`irl-editor`](https://github.com/quaIett/irl-editor) (`port/1.21.1`) adds two
fixes. Each is a separate commit so they can be reviewed/cherry-picked independently.

## 1. Axiom compatibility — ImGui backend init survives a conflicting imgui-java

`ImGuiRuntime` initialised the imgui-java GL3/GLFW backends with a direct virtual call. When
another mod (e.g. **Axiom**) ships an **unrelocated** `imgui-java` that wins on the shared
classpath, `ImGuiImplGl3.init`'s return type differs (`boolean` in our bundled 1.89.0 vs `void`
in the older build) — a compiled `init()Z` then throws `NoSuchMethodError` against the loaded
`init()V`, and the editor overlay dies.

Fix: invoke the backend `init` methods **reflectively** (match on name + parameter types, ignore
the return type), so it links against whichever `imgui-java` actually won on the classpath.

## 2. Dynamic lights go dark far from world origin (camera-relative light pipeline)

Lights stopped illuminating far from `(0,0,0)` (reproduced at X/Z ≈ 100 000; fine near spawn).
Symptoms: editor + gizmo fine, light is in the registry and shadows bake, but no light. Not an
SP/MP issue — pure coordinate precision.

Cause: the light position went into the SSBO as an **absolute** `float` (`(float) l.x`), and the
`.irlights` GLSL reconstructed an **absolute** fragment position (`fragWorld = playerPos + cameraPosition`)
to compare against it. Far from origin that is a subtraction of two large floats whose precision /
world-space rebasing no longer line up → the light is effectively "nowhere" → dark. The in-world
gizmo stays correct because it is computed relative to the camera in `double`.

Fix: move the whole light comparison into **camera-relative** space.
- **CPU:** upload the light position as `lightWorld − cameraPos` (subtracted in `double`).
- **GLSL (all 5 packs):** stop adding `cameraPosition` — compare in the pack's native
  camera-relative `playerPos` space, which now matches the camera-relative light. The internal
  math (`light.pos − frag`, `frag − lp`, shadow projection rebuilds, volumetrics, cookie) is
  unchanged — it works on differences and is identical in the relative frame.

Note: shadow-map **baking** still runs in absolute coordinates (`lookAt(absoluteLightPos)` in
`ShadowRenderer`), so shadows may be slightly noisy very far from origin — the shader lookup is
already relative, so correctness holds; only the baked depth precision is affected. Re-baking each
light in its own frame is a separate, self-contained follow-up not included here.

### ⚠️ Requires the matching `irl-core` change

The CPU side touches `irl-core` (`LightRegistry.flush` gains a camera-origin overload;
`LightBuffer` SSBO contract note). Those two files are **not** in this repo (separate project),
so this fork **will not compile** against an unpatched `irl-core`. Apply the bundled patch first:

```bash
# from your irl-core checkout (sibling of this repo, per includeBuild("../irl-core"))
cd ../irl-core
git apply ../irl-editor-fixes-fork/irl-core-camera-relative.patch
# or: patch -p1 < ../irl-editor-fixes-fork/irl-core-camera-relative.patch
```

The patch keeps the old `flush()` (= absolute) for ABI compatibility and only adds the
camera-relative `flush(double, double, double)` overload that this fork calls.

## Build / verify
- Apply the `irl-core` patch (above), then `./gradlew build` in this repo.
- In-game: enable a patched shaderpack, teleport to X ≈ 100 000, place a point light → it lights.
- Near origin: unchanged.
