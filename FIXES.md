# Fork fixes — IRL-editor (all version branches)

A fork of [`irl-editor`](https://github.com/quaIett/irl-editor) that adds two fixes on **every
version branch** — branches mirror upstream:

| Branch | Minecraft |
|---|---|
| `main` | 1.20.4 |
| `port/1.20.1` | 1.20.1 |
| `port/1.21.1` | 1.21.1 |
| `port/1.21.4` | 1.21.4 |
| `port/1.21.11` | 1.21.11 |

The fixes are **version-independent and identical on every branch**; each is a separate commit so
they can be reviewed/cherry-picked independently. **Check out the branch that matches your
Minecraft version** (see [Building](#building) below).

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

## Building

This mod is built as a Gradle **composite build**: its `settings.gradle` has
`includeBuild("../irl-core")`, so `irl-core` must sit **next to this repo as a sibling
directory named exactly `irl-core`**, and it must have the camera-relative patch applied
(see above) — otherwise the build fails to compile (`LightRegistry.flush(double,double,double)`
won't exist). Required layout:

```
<workspace>/
├── irl-editor-fixes-fork/   ← this repo (the folder name doesn't matter)
└── irl-core/                ← quaIett/irl-core, MUST be named "irl-core", patch applied
```

Steps:

```bash
# 1. clone both side by side
git clone https://github.com/zqicev/irl-editor-fixes-fork.git
git clone https://github.com/quaIett/irl-core.git

# 2. check out the branch for YOUR Minecraft version
#    main = 1.20.4 · port/1.20.1 · port/1.21.1 · port/1.21.4 · port/1.21.11
cd irl-editor-fixes-fork
git checkout port/1.21.4      # ← change to your version (default branch = main = 1.20.4)
cd ..

# 3. apply the camera-relative patch to irl-core (the SAME patch for every version)
cd irl-core
git apply ../irl-editor-fixes-fork/irl-core-camera-relative.patch
#   (if it doesn't apply cleanly: `git apply --3way …`, or `patch -p1 < …`)

# 4. build the mod
cd ../irl-editor-fixes-fork
./gradlew build      # Windows: gradlew.bat build
```

- JDK 21 (Gradle/Loom provisions the right toolchain per Minecraft version automatically).
- `irl-core` is version-independent (one jar for every MC version), so the **same** patched
  `irl-core` builds **every** branch of this fork — only check out the branch for your MC version.
- Output: `build/libs/irl-redactor-1.0-obt.jar` (bundles `irl-core` via Jar-in-Jar).

## Verify
- In-game: enable a patched shaderpack, teleport to X ≈ 100 000, place a point light → it lights.
- Near origin (X ≈ 0): unchanged.
