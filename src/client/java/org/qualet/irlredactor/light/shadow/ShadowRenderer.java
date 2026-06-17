package org.qualet.irlredactor.light.shadow;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BlockModelPart;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.Vector2f;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.List;

/**
 * Bakes spot/point shadow depth maps into the per-light atlas tiles / cube faces.
 *
 * <p><b>1.21.11 port — block occluders rasterized with a self-contained raw-GL
 * depth program.</b> The 1.20.4 baker drew occluders with MC's {@code VertexBuffer}
 * + {@code RenderSystem} matrices, both removed by the 1.21.5 render-pipeline
 * rework. Rather than chase MC's churning {@code GpuDevice}/{@code RenderPass}
 * model, the block-shadow bake now owns a tiny GLSL program ({@code gl_Position =
 * uViewProj * pos}, no colour output) plus a VAO and a per-light VBO cache, and
 * draws the blocks' {@link BlockShadowEntry#shape} AABBs as triangles straight
 * into the bound depth FBO. This is independent of MC's render pipeline, so it is
 * robust across MC versions.</p>
 *
 * <p>GL-state safety: every piece of global GL state the draw touches (program,
 * VAO, array buffer, depth test/func/mask, cull) is snapshotted with {@code glGet*}
 * and restored to its real prior value, and the FBO/viewport/scissor are restored
 * by {@link #endPass}. MC's {@code GlStateManager} caches the FBO binding but NOT
 * program/VAO/buffer; restoring the actual binding to its captured value keeps
 * MC's cache consistent (verified: the Stage-1 raw-GL FBO binds rendered clean
 * in-world with shaders on).</p>
 *
 * <p>ENTITY OCCLUDERS: {@link #renderCaster} draws each in-range entity's
 * interpolated bounding box through the same depth program (an approximate "blob"
 * shadow). 1.21.11 moved entity rendering to the deferred {@code
 * OrderedRenderCommandQueue} (no immediate FBO rasterization) + the 1.21.9
 * {@code EntityRenderState} rewrite, so a faithful model render is no longer
 * reachable here; the box occluder is BBS-compatible and version-robust. The boxes
 * of one pass are accumulated and flushed once in {@link #endPass}.</p>
 *
 * <p>CUTOUT OCCLUDERS: blocks classified {@code BlockRenderLayer.CUTOUT} by the
 * collector (leaves / glass panes / iron bars / doors / foliage) are baked from
 * their {@link BakedModel} quads through a second, textured depth program that
 * samples the block atlas and discards transparent texels — so light passes
 * through the holes (lacy shadows) instead of a solid block silhouette. See
 * {@link #renderBlocksDepthCutout}.</p>
 *
 * <p>Nothing is VISIBLE until the GLSL/{@code .irlights} patcher (Stage 3) makes a
 * shaderpack sample these depth maps.</p>
 *
 * <p>TODO(precision): vertices are absolute world coordinates, so the depth bake
 * loses float precision far from the world origin; switch to light-relative
 * coordinates (subtract the light position on the CPU, view = light-at-origin)
 * if banding shows up at distance.</p>
 */
public final class ShadowRenderer
{
    private static final float NEAR = 0.05f;

    private static boolean inPass = false;
    /** True once {@link #savePassState} has snapshotted the original GL state
     *  this bake; passes within one bake share the single snapshot. */
    private static boolean passStateSaved = false;

    private static int savedFbo;
    private static final int[] savedViewport = new int[4];
    private static boolean savedScissorEnabled;
    private static final int[] savedScissorBox = new int[4];
    private static boolean savedDepthMask;

    /** The current light's view/projection (world space), combined into
     *  {@link #viewProj} for the depth program's uViewProj uniform. */
    private static final Matrix4f currentView = new Matrix4f();
    private static final Matrix4f currentProj = new Matrix4f();
    private static final Matrix4f viewProj = new Matrix4f();

    /** Cached perspective projections. The spot projection depends only on
     *  (fov, far) and the point projection only on far, but a bake runs many
     *  passes (every spot tile, every point face) — recomputing perspective()'s
     *  trig per pass is wasted work when consecutive lamps share parameters.
     *  These hold the last-built matrix and the params it was built for; each
     *  begin*() copies the cached matrix into {@link #currentProj} (cheap; the
     *  shared currentProj is also written by the other light kind, so the cache
     *  cannot live in currentProj itself), rebuilding only when params change. */
    private static final Matrix4f spotProjCache = new Matrix4f();
    private static float lastSpotFov = Float.NaN;
    private static float lastSpotFar = Float.NaN;
    private static final Matrix4f pointProjCache = new Matrix4f();
    private static float lastPointFar = Float.NaN;

    public static final int CASTER_ENTITY = 0;
    public static final int CASTER_MODEL_BLOCK = 1;
    public static final int CASTER_REPLAY = 2;

    private ShadowRenderer()
    {}

    /** Call once at the start of a bake, before any begin*()/endPass(). Arms a
     *  fresh snapshot of the GL state on the first pass of this bake. */
    public static void beginBake()
    {
        passStateSaved = false;
        // Drop any entity boxes a previous bake left unflushed (defensive; endPass
        // flushes + clears every pass) so they can never draw with stale matrices.
        if (casterBuf != null)
        {
            casterBuf.clear();
        }
    }

    /**
     * Begin a spot depth pass into the live ({@code toStatic} false) or static
     * ({@code toStatic} true) atlas tile. {@code clear} false keeps the tile's
     * current depth (used for the dynamic-caster overlay on top of a static base
     * restored by {@link SpotlightDepthAtlas#copyStaticToLive}).
     */
    public static void beginSpot(int tile,
                                 float lpx, float lpy, float lpz,
                                 float ldx, float ldy, float ldz,
                                 float range, float outerDeg,
                                 boolean toStatic, boolean clear)
    {
        savePassState();

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, SpotlightDepthAtlas.getFboId(toStatic));
        int px = SpotlightDepthAtlas.tilePixelX(tile);
        int py = SpotlightDepthAtlas.tilePixelY(tile);
        int ts = SpotlightDepthAtlas.TILE_SIZE;
        GL11.glViewport(px, py, ts, ts);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(px, py, ts, ts);
        if (clear)
        {
            GL11.glDepthMask(true);
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        }

        float fovDeg = Math.max(outerDeg, 1.0f);
        float far = Math.max(range, NEAR + 0.1f);
        if (fovDeg != lastSpotFov || far != lastSpotFar)
        {
            spotProjCache.identity().perspective((float) Math.toRadians(fovDeg), 1.0f, NEAR, far);
            lastSpotFov = fovDeg;
            lastSpotFar = far;
        }
        currentProj.set(spotProjCache);

        Vector3f up = pickStableUp(ldy);
        currentView.identity().lookAt(
            lpx, lpy, lpz,
            lpx + ldx, lpy + ldy, lpz + ldz,
            up.x, up.y, up.z
        );
    }

    /**
     * Begin a point-cube face depth pass into the live or static array (see
     * {@link #beginSpot} for the {@code toStatic}/{@code clear} semantics).
     */
    public static void beginPointFace(int slot, int face,
                                      float lpx, float lpy, float lpz,
                                      float radius,
                                      boolean toStatic, boolean clear)
    {
        savePassState();

        PointShadowArray.bindFaceForRender(slot, face, toStatic);
        int fs = PointShadowArray.FACE_SIZE;
        GL11.glViewport(0, 0, fs, fs);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(0, 0, fs, fs);
        if (clear)
        {
            GL11.glDepthMask(true);
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        }

        float far = Math.max(radius, NEAR + 0.1f);
        if (far != lastPointFar)
        {
            pointProjCache.identity().perspective((float) Math.toRadians(90.0), 1.0f, NEAR, far);
            lastPointFar = far;
        }
        currentProj.set(pointProjCache);

        float dx, dy, dz, ux, uy, uz;
        switch (face)
        {
            case 0:  dx =  1; dy =  0; dz =  0; ux = 0; uy = -1; uz =  0; break; // +X
            case 1:  dx = -1; dy =  0; dz =  0; ux = 0; uy = -1; uz =  0; break; // -X
            case 2:  dx =  0; dy =  1; dz =  0; ux = 0; uy =  0; uz =  1; break; // +Y
            case 3:  dx =  0; dy = -1; dz =  0; ux = 0; uy =  0; uz = -1; break; // -Y
            case 4:  dx =  0; dy =  0; dz =  1; ux = 0; uy = -1; uz =  0; break; // +Z
            default: dx =  0; dy =  0; dz = -1; ux = 0; uy = -1; uz =  0; break; // -Z
        }

        currentView.identity().lookAt(
            lpx, lpy, lpz,
            lpx + dx, lpy + dy, lpz + dz,
            ux, uy, uz
        );
    }

    /** Tiny inflation (blocks) on the entity box so a flat/thin entity (e.g. an
     *  item) still writes a non-degenerate depth silhouette. */
    private static final float ENTITY_BOX_INFLATE = 0.05f;
    /** Max entity boxes accumulated per pass (one box = 36 verts). In-range
     *  dynamic casters per light/face are few; extra are dropped (over-cap is a
     *  missing shadow, never corruption). */
    private static final int CASTER_MAX_BOXES = 64;
    /** Accumulated entity-box vertices for the current pass; uploaded + drawn once
     *  in {@link #endPass} so many entities cost a single draw + state snapshot. */
    private static FloatBuffer casterBuf;
    private static int casterScratchVbo = 0;

    /**
     * Accumulate one occluder for the current depth pass. Entities are drawn as
     * their interpolated bounding box (a blob occluder) through the depth program
     * — see the class note on why a faithful model render is not reachable on
     * 1.21.11. Block occluders go through {@link #renderBlocksDepth}; model-block
     * casters are not produced on this port, so only entities are handled here.
     * The boxes are batched and flushed in {@link #endPass}.
     */
    public static void renderCaster(Object caster, int casterType, float tickDelta)
    {
        if (caster == null || !inPass || glBroken)
        {
            return;
        }
        if (!(caster instanceof Entity))
        {
            return; // model-block / replay casters not produced on the 1.21.11 port
        }
        if (!glInit)
        {
            initGl();
        }
        if (glBroken || program == 0)
        {
            return;
        }

        Entity e = (Entity) caster;
        // entity.getBoundingBox() is at the current tick position; shift it by the
        // render-interpolation delta so the shadow tracks the smoothed pose.
        double ix = MathHelper.lerp(tickDelta, e.lastRenderX, e.getX()) - e.getX();
        double iy = MathHelper.lerp(tickDelta, e.lastRenderY, e.getY()) - e.getY();
        double iz = MathHelper.lerp(tickDelta, e.lastRenderZ, e.getZ()) - e.getZ();
        Box box = e.getBoundingBox();
        float m = ENTITY_BOX_INFLATE;

        if (casterBuf == null)
        {
            casterBuf = MemoryUtil.memAllocFloat(CASTER_MAX_BOXES * 36 * 3);
        }
        if (casterBuf.remaining() < 36 * 3)
        {
            return; // pass is at the box cap
        }
        emitBox(casterBuf,
            (float) (box.minX + ix) - m, (float) (box.minY + iy) - m, (float) (box.minZ + iz) - m,
            (float) (box.maxX + ix) + m, (float) (box.maxY + iy) + m, (float) (box.maxZ + iz) + m);
    }

    /** Draw the entity boxes accumulated this pass (if any) with the depth
     *  program, then reset the buffer. Called by {@link #endPass} before the GL
     *  state is restored. Mirrors {@link #drawOpaqueBlocks}'s state discipline:
     *  snapshot every global bit the draw mutates and restore it exactly. */
    private static void flushCasterBoxes()
    {
        if (casterBuf == null || casterBuf.position() == 0 || glBroken || program == 0)
        {
            return;
        }
        int verts = casterBuf.position() / 3;
        casterBuf.flip();

        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevArrayBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        boolean prevDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        int prevDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean prevDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean prevCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);

        try
        {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_CULL_FACE);

            GL20.glUseProgram(program);
            currentProj.mul(currentView, viewProj);
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush())
            {
                FloatBuffer fb = stack.mallocFloat(16);
                viewProj.get(fb);
                GL20.glUniformMatrix4fv(uViewProjLoc, false, fb);
            }

            if (casterScratchVbo == 0)
            {
                casterScratchVbo = GL15.glGenBuffers();
            }
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, casterScratchVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, casterBuf, GL15.GL_STREAM_DRAW);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);

            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, verts);
        }
        catch (Throwable t)
        {
            if (!blockErrorLogged)
            {
                blockErrorLogged = true;
                System.err.println("[irl-redactor] entity shadow bake failed: " + t);
                t.printStackTrace();
            }
        }
        finally
        {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArrayBuf);
            GL30.glBindVertexArray(prevVao);
            GL20.glUseProgram(prevProgram);
            GL11.glDepthMask(prevDepthMask);
            GL11.glDepthFunc(prevDepthFunc);
            if (prevDepthTest) { GL11.glEnable(GL11.GL_DEPTH_TEST); } else { GL11.glDisable(GL11.GL_DEPTH_TEST); }
            if (prevCull) { GL11.glEnable(GL11.GL_CULL_FACE); } else { GL11.glDisable(GL11.GL_CULL_FACE); }
            casterBuf.clear();
        }
    }

    // --- Block-shadow depth bake (raw-GL) -------------------------------------

    /** Shared depth program + its uniform/state, lazily created on the first bake. */
    private static int program = 0;
    private static int uViewProjLoc = -1;
    private static int vao = 0;
    private static boolean glInit = false;
    private static boolean glBroken = false;

    /** Per-light cached VBO of triangle vertices (POSITION). Rebuilt only when the
     *  block list instance changes ({@link BlockShadowCache} returns a stable
     *  instance until a block in range changes), so static lamps just redraw. */
    private static final Long2ObjectOpenHashMap<List<BlockShadowEntry>> vboList = new Long2ObjectOpenHashMap<>();
    private static final Long2IntOpenHashMap vboId = new Long2IntOpenHashMap();
    private static final Long2IntOpenHashMap vboVertCount = new Long2IntOpenHashMap();

    // --- Cutout (textured, alpha-tested) block depth program + per-light cache ---
    // Cutout blocks (leaves / glass panes / iron bars / doors / foliage; shape ==
    // null in the entry) are baked from their BakedModel quads through a second
    // depth program that samples the block atlas and discards transparent texels,
    // so light passes through the holes. Vertices are interleaved POSITION(3) +
    // UV(2) (stride 20). Per-light VBO cached + rebuilt on a list-instance change,
    // exactly like the opaque path; evicted alongside it in retainBlockVbos.
    private static int cutoutProgram = 0;
    private static int cutoutViewProjLoc = -1;
    private static int cutoutAtlasLoc = -1;
    private static int cutoutVao = 0;
    private static final Long2ObjectOpenHashMap<List<BlockShadowEntry>> cutoutList = new Long2ObjectOpenHashMap<>();
    private static final Long2IntOpenHashMap cutoutVboId = new Long2IntOpenHashMap();
    private static final Long2IntOpenHashMap cutoutVertCount = new Long2IntOpenHashMap();
    private static final Random cutoutRandom = Random.create();
    private static boolean cutoutErrorLogged = false;

    private static boolean blockErrorLogged = false;

    /**
     * Render a light's block occluders into the currently-bound depth FBO, between
     * a begin*()/endPass() bracket. Opaque-shape blocks are drawn as their
     * {@link BlockShadowEntry#shape} AABB triangles; cutout blocks (shape == null)
     * are drawn from their textured {@link BakedQuad} geometry with alpha discard
     * (see {@link #renderBlocksDepthCutout}). Both use the per-light view/proj.
     */
    public static void renderBlocksDepth(long id, List<BlockShadowEntry> blocks)
    {
        if (blocks == null || blocks.isEmpty() || !inPass || glBroken)
        {
            return;
        }
        if (!glInit)
        {
            initGl();
        }
        if (glBroken)
        {
            return;
        }
        drawOpaqueBlocks(id, blocks);
        renderBlocksDepthCutout(id, blocks);
    }

    /** Draw the opaque-shape (non-cutout) blocks of a light as triangle AABBs. */
    private static void drawOpaqueBlocks(long id, List<BlockShadowEntry> blocks)
    {
        if (program == 0)
        {
            return;
        }

        // (Re)build this light's VBO only when its block list instance changed.
        // Keyed on the list instance alone (not vbo != 0): buildVbo stores the
        // instance even when there is no shaped geometry (a cutout-only lamp), so
        // gating on vbo would re-tessellate that empty lamp every frame.
        int verts;
        if (vboList.get(id) != blocks)
        {
            verts = buildVbo(id, blocks);
        }
        else
        {
            verts = vboVertCount.get(id);
        }
        if (verts <= 0)
        {
            return;
        }
        int vbo = vboId.get(id);

        // Snapshot every global GL state the draw mutates, then restore it — MC's
        // GlStateManager doesn't cache program/VAO/buffer, and the FBO/viewport are
        // restored by endPass, so this keeps the surrounding renderer consistent.
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevArrayBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        boolean prevDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        int prevDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean prevDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean prevCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);

        try
        {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            // Cull OFF: both faces of every box triangle write depth and the depth
            // test keeps the nearest (light-facing) surface — tight, correct block
            // silhouettes regardless of winding.
            GL11.glDisable(GL11.GL_CULL_FACE);

            GL20.glUseProgram(program);
            currentProj.mul(currentView, viewProj);
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush())
            {
                FloatBuffer fb = stack.mallocFloat(16);
                viewProj.get(fb);
                GL20.glUniformMatrix4fv(uViewProjLoc, false, fb);
            }

            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);

            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, verts);
        }
        catch (Throwable t)
        {
            if (!blockErrorLogged)
            {
                blockErrorLogged = true;
                System.err.println("[irl-redactor] block shadow bake failed: " + t);
                t.printStackTrace();
            }
        }
        finally
        {
            // Restore exactly what we changed (actual values -> matches MC's cache).
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArrayBuf);
            GL30.glBindVertexArray(prevVao);
            GL20.glUseProgram(prevProgram);
            GL11.glDepthMask(prevDepthMask);
            GL11.glDepthFunc(prevDepthFunc);
            if (prevDepthTest) { GL11.glEnable(GL11.GL_DEPTH_TEST); } else { GL11.glDisable(GL11.GL_DEPTH_TEST); }
            if (prevCull) { GL11.glEnable(GL11.GL_CULL_FACE); } else { GL11.glDisable(GL11.GL_CULL_FACE); }
        }
    }

    /** Build (or rebuild) the per-light triangle VBO from the block shape AABBs.
     *  Returns the vertex count (0 if there is no shaped geometry). */
    private static int buildVbo(long id, List<BlockShadowEntry> blocks)
    {
        // Count boxes first (cheap AABB iteration) to size one allocation.
        int[] boxes = {0};
        for (int i = 0, n = blocks.size(); i < n; i++)
        {
            BlockShadowEntry e = blocks.get(i);
            if (e != null && e.shape != null)
            {
                e.shape.forEachBox((a, b, c, d, f, g) -> boxes[0]++);
            }
        }
        int boxCount = boxes[0];
        if (boxCount == 0)
        {
            // No shaped geometry (e.g. only the old cutout entries): drop any VBO.
            releaseBlockVbo(id);
            vboList.put(id, blocks);
            vboVertCount.put(id, 0);
            return 0;
        }

        int vertCount = boxCount * 36; // 6 faces * 2 tris * 3 verts
        FloatBuffer fb = MemoryUtil.memAllocFloat(vertCount * 3);
        try
        {
            for (int i = 0, n = blocks.size(); i < n; i++)
            {
                BlockShadowEntry e = blocks.get(i);
                if (e == null || e.shape == null)
                {
                    continue;
                }
                final float ox = e.pos.getX(), oy = e.pos.getY(), oz = e.pos.getZ();
                e.shape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) ->
                    emitBox(fb,
                        ox + (float) minX, oy + (float) minY, oz + (float) minZ,
                        ox + (float) maxX, oy + (float) maxY, oz + (float) maxZ));
            }
            fb.flip();

            int vbo = vboId.get(id);
            if (vbo == 0)
            {
                vbo = GL15.glGenBuffers();
                vboId.put(id, vbo);
            }
            int prevArrayBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, fb, GL15.GL_STATIC_DRAW);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArrayBuf);

            vboList.put(id, blocks);
            vboVertCount.put(id, vertCount);
            return vertCount;
        }
        finally
        {
            MemoryUtil.memFree(fb);
        }
    }

    /** Append one axis-aligned box as 12 triangles (36 verts, POSITION only).
     *  Winding is irrelevant — culling is disabled during the bake. */
    private static void emitBox(FloatBuffer b,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2)
    {
        // -X
        tri(b, x1,y1,z1, x1,y1,z2, x1,y2,z2);  tri(b, x1,y1,z1, x1,y2,z2, x1,y2,z1);
        // +X
        tri(b, x2,y1,z1, x2,y2,z1, x2,y2,z2);  tri(b, x2,y1,z1, x2,y2,z2, x2,y1,z2);
        // -Y
        tri(b, x1,y1,z1, x2,y1,z1, x2,y1,z2);  tri(b, x1,y1,z1, x2,y1,z2, x1,y1,z2);
        // +Y
        tri(b, x1,y2,z1, x1,y2,z2, x2,y2,z2);  tri(b, x1,y2,z1, x2,y2,z2, x2,y2,z1);
        // -Z
        tri(b, x1,y1,z1, x1,y2,z1, x2,y2,z1);  tri(b, x1,y1,z1, x2,y2,z1, x2,y1,z1);
        // +Z
        tri(b, x1,y1,z2, x2,y1,z2, x2,y2,z2);  tri(b, x1,y1,z2, x2,y2,z2, x1,y2,z2);
    }

    private static void tri(FloatBuffer b,
                            float ax, float ay, float az,
                            float bx, float by, float bz,
                            float cx, float cy, float cz)
    {
        b.put(ax).put(ay).put(az);
        b.put(bx).put(by).put(bz);
        b.put(cx).put(cy).put(cz);
    }

    // --- Cutout block depth bake (textured, alpha-tested) ---------------------

    private static final Direction[] DIRECTIONS = Direction.values();

    /**
     * Draw a light's cutout blocks from their textured {@link BakedQuad} geometry,
     * sampling the block atlas and discarding transparent texels so light passes
     * through (lacy leaf / grate / glass-pane shadows). Per-light VBO cached like
     * the opaque path. Called from {@link #renderBlocksDepth} within a pass.
     */
    private static void renderBlocksDepthCutout(long id, List<BlockShadowEntry> blocks)
    {
        if (cutoutProgram == 0)
        {
            return;
        }

        // Keyed on the list instance alone (see drawOpaqueBlocks): buildCutoutVbo
        // stores the instance even with zero quads, so this won't re-tessellate an
        // empty lamp every frame.
        int verts;
        if (cutoutList.get(id) != blocks)
        {
            verts = buildCutoutVbo(id, blocks);
        }
        else
        {
            verts = cutoutVertCount.get(id);
        }
        if (verts <= 0)
        {
            return;
        }
        int vbo = cutoutVboId.get(id);

        int atlas = atlasGlId();
        if (atlas == 0)
        {
            return; // atlas texture not ready this frame
        }

        // Snapshot every global GL bit the draw mutates (program/VAO/buffer, depth,
        // cull, blend, active texture unit + its 2D binding) and restore it, like
        // the opaque path — keeps the surrounding renderer's GL state consistent.
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevArrayBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        boolean prevDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        int prevDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean prevDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean prevCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean prevBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        int prevActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        try
        {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_BLEND);

            GL20.glUseProgram(cutoutProgram);
            currentProj.mul(currentView, viewProj);
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush())
            {
                FloatBuffer fb = stack.mallocFloat(16);
                viewProj.get(fb);
                GL20.glUniformMatrix4fv(cutoutViewProjLoc, false, fb);
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas);
            GL20.glUniform1i(cutoutAtlasLoc, 0);

            GL30.glBindVertexArray(cutoutVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL20.glEnableVertexAttribArray(0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 20, 0L);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 20, 12L);

            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, verts);
        }
        catch (Throwable t)
        {
            if (!cutoutErrorLogged)
            {
                cutoutErrorLogged = true;
                System.err.println("[irl-redactor] cutout block shadow bake failed: " + t);
                t.printStackTrace();
            }
        }
        finally
        {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex0);
            GL13.glActiveTexture(prevActiveTex);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArrayBuf);
            GL30.glBindVertexArray(prevVao);
            GL20.glUseProgram(prevProgram);
            GL11.glDepthMask(prevDepthMask);
            GL11.glDepthFunc(prevDepthFunc);
            if (prevDepthTest) { GL11.glEnable(GL11.GL_DEPTH_TEST); } else { GL11.glDisable(GL11.GL_DEPTH_TEST); }
            if (prevCull) { GL11.glEnable(GL11.GL_CULL_FACE); } else { GL11.glDisable(GL11.GL_CULL_FACE); }
            if (prevBlend) { GL11.glEnable(GL11.GL_BLEND); } else { GL11.glDisable(GL11.GL_BLEND); }
        }
    }

    /** Build (or rebuild) a light's cutout VBO (interleaved POSITION(3)+UV(2),
     *  stride 20) from its cutout entries' BakedModel quads. Returns the vertex
     *  count (0 if there is no cutout geometry). Quads are emitted with absolute
     *  world coordinates (model-local position + block origin), matching the
     *  opaque path; neighbour face-culling is skipped (over-draw is safe for a
     *  silhouette and avoids needing the world-aware tessellator). */
    private static int buildCutoutVbo(long id, List<BlockShadowEntry> blocks)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc != null ? mc.world : null;
        BlockRenderManager brm = mc != null ? mc.getBlockRenderManager() : null;
        if (world == null || brm == null)
        {
            releaseCutoutVbo(id);
            cutoutList.put(id, blocks);
            cutoutVertCount.put(id, 0);
            return 0;
        }

        FloatArrayList data = new FloatArrayList();
        for (int i = 0, n = blocks.size(); i < n; i++)
        {
            BlockShadowEntry e = blocks.get(i);
            if (e == null || !e.cutout)
            {
                continue;
            }
            BlockPos p = e.pos;
            try
            {
                BlockState state = world.getBlockState(p);
                if (state.isAir())
                {
                    continue;
                }
                BlockStateModel model = brm.getModel(state);
                if (model == null)
                {
                    continue;
                }
                cutoutRandom.setSeed(state.getRenderingSeed(p));
                List<BlockModelPart> parts = model.getParts(cutoutRandom);
                float ox = p.getX(), oy = p.getY(), oz = p.getZ();
                for (int pi = 0, pn = parts.size(); pi < pn; pi++)
                {
                    BlockModelPart part = parts.get(pi);
                    if (part == null)
                    {
                        continue;
                    }
                    emitCutoutQuads(data, part.getQuads(null), ox, oy, oz);
                    for (Direction d : DIRECTIONS)
                    {
                        emitCutoutQuads(data, part.getQuads(d), ox, oy, oz);
                    }
                }
            }
            catch (Throwable t)
            {
                // skip a single broken block, keep tessellating the rest
            }
        }

        int floats = data.size();
        if (floats == 0)
        {
            releaseCutoutVbo(id);
            cutoutList.put(id, blocks);
            cutoutVertCount.put(id, 0);
            return 0;
        }
        int verts = floats / 5;

        FloatBuffer fb = MemoryUtil.memAllocFloat(floats);
        try
        {
            fb.put(data.elements(), 0, floats);
            fb.flip();

            int vbo = cutoutVboId.get(id);
            if (vbo == 0)
            {
                vbo = GL15.glGenBuffers();
                cutoutVboId.put(id, vbo);
            }
            int prevArrayBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, fb, GL15.GL_STATIC_DRAW);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArrayBuf);

            cutoutList.put(id, blocks);
            cutoutVertCount.put(id, verts);
            return verts;
        }
        finally
        {
            MemoryUtil.memFree(fb);
        }
    }

    /** Append a quad list as triangles (POSITION+UV) to the cutout vertex data. */
    private static void emitCutoutQuads(FloatArrayList out, List<BakedQuad> quads, float ox, float oy, float oz)
    {
        if (quads == null)
        {
            return;
        }
        for (int i = 0, n = quads.size(); i < n; i++)
        {
            BakedQuad q = quads.get(i);
            if (q == null)
            {
                continue;
            }
            // quad corners 0,1,2,3 -> two triangles (0,1,2) + (0,2,3)
            appendCutoutVert(out, q, 0, ox, oy, oz);
            appendCutoutVert(out, q, 1, ox, oy, oz);
            appendCutoutVert(out, q, 2, ox, oy, oz);
            appendCutoutVert(out, q, 0, ox, oy, oz);
            appendCutoutVert(out, q, 2, ox, oy, oz);
            appendCutoutVert(out, q, 3, ox, oy, oz);
        }
    }

    private static void appendCutoutVert(FloatArrayList out, BakedQuad q, int i, float ox, float oy, float oz)
    {
        Vector3fc pos = q.getPosition(i);
        long uv = q.getTexcoords(i);
        out.add(pos.x() + ox);
        out.add(pos.y() + oy);
        out.add(pos.z() + oz);
        out.add(Vector2f.getX(uv));
        out.add(Vector2f.getY(uv));
    }

    /** Raw GL id of the block atlas texture, or 0 if it is not allocated yet. */
    private static int atlasGlId()
    {
        try
        {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null)
            {
                return 0;
            }
            AbstractTexture tex = mc.getTextureManager().getTexture(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
            if (tex == null)
            {
                return 0;
            }
            com.mojang.blaze3d.textures.GpuTexture gt = tex.getGlTexture();
            if (gt instanceof GlTexture)
            {
                return ((GlTexture) gt).getGlId();
            }
            return 0;
        }
        catch (Throwable t)
        {
            return 0;
        }
    }

    /** Free one lamp's cached cutout VBO. */
    private static void releaseCutoutVbo(long id)
    {
        int vbo = cutoutVboId.remove(id);
        if (vbo != 0)
        {
            GL15.glDeleteBuffers(vbo);
        }
        cutoutList.remove(id);
        cutoutVertCount.remove(id);
    }

    /** Lazily compile the depth program + create the shared VAO. */
    private static void initGl()
    {
        glInit = true;
        try
        {
            int vs = compile(GL20.GL_VERTEX_SHADER,
                "#version 150\n" +
                "in vec3 aPos;\n" +
                "uniform mat4 uViewProj;\n" +
                "void main() { gl_Position = uViewProj * vec4(aPos, 1.0); }\n");
            int fs = compile(GL20.GL_FRAGMENT_SHADER,
                "#version 150\n" +
                "void main() {}\n");

            int prog = GL20.glCreateProgram();
            GL20.glAttachShader(prog, vs);
            GL20.glAttachShader(prog, fs);
            GL20.glBindAttribLocation(prog, 0, "aPos");
            GL20.glLinkProgram(prog);
            if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
            {
                throw new IllegalStateException("link: " + GL20.glGetProgramInfoLog(prog));
            }
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);

            program = prog;
            uViewProjLoc = GL20.glGetUniformLocation(prog, "uViewProj");
            vao = GL30.glGenVertexArrays();

            // Cutout depth program: positions + atlas UVs, discards transparent
            // texels so light passes through the holes in the texture.
            int cvs = compile(GL20.GL_VERTEX_SHADER,
                "#version 150\n" +
                "in vec3 aPos;\n" +
                "in vec2 aUV;\n" +
                "uniform mat4 uViewProj;\n" +
                "out vec2 vUV;\n" +
                "void main() { vUV = aUV; gl_Position = uViewProj * vec4(aPos, 1.0); }\n");
            int cfs = compile(GL20.GL_FRAGMENT_SHADER,
                "#version 150\n" +
                "in vec2 vUV;\n" +
                "uniform sampler2D uAtlas;\n" +
                "void main() { if (texture(uAtlas, vUV).a < 0.5) discard; }\n");

            int cprog = GL20.glCreateProgram();
            GL20.glAttachShader(cprog, cvs);
            GL20.glAttachShader(cprog, cfs);
            GL20.glBindAttribLocation(cprog, 0, "aPos");
            GL20.glBindAttribLocation(cprog, 1, "aUV");
            GL20.glLinkProgram(cprog);
            if (GL20.glGetProgrami(cprog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
            {
                throw new IllegalStateException("cutout link: " + GL20.glGetProgramInfoLog(cprog));
            }
            GL20.glDeleteShader(cvs);
            GL20.glDeleteShader(cfs);

            cutoutProgram = cprog;
            cutoutViewProjLoc = GL20.glGetUniformLocation(cprog, "uViewProj");
            cutoutAtlasLoc = GL20.glGetUniformLocation(cprog, "uAtlas");
            cutoutVao = GL30.glGenVertexArrays();
        }
        catch (Throwable t)
        {
            glBroken = true;
            System.err.println("[irl-redactor] shadow depth program init failed: " + t);
            t.printStackTrace();
        }
    }

    private static int compile(int type, String src)
    {
        int sh = GL20.glCreateShader(type);
        GL20.glShaderSource(sh, src);
        GL20.glCompileShader(sh);
        if (GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE)
        {
            throw new IllegalStateException("compile: " + GL20.glGetShaderInfoLog(sh));
        }
        return sh;
    }

    /** Free one lamp's cached block VBO. */
    public static void releaseBlockVbo(long id)
    {
        int vbo = vboId.remove(id);
        if (vbo != 0)
        {
            GL15.glDeleteBuffers(vbo);
        }
        vboList.remove(id);
        vboVertCount.remove(id);
    }

    /** Free block VBOs (opaque + cutout) for lamps not in {@code liveIds} (gone,
     *  or feature off -> empty set drains all). Run once per bake after the light
     *  loops. A lamp with only cutout blocks isn't in the opaque {@link #vboList},
     *  so the cutout cache is swept separately. */
    public static void retainBlockVbos(LongSet liveIds)
    {
        if (!vboId.isEmpty())
        {
            ObjectIterator<Long2ObjectMap.Entry<List<BlockShadowEntry>>> it = vboList.long2ObjectEntrySet().iterator();
            while (it.hasNext())
            {
                long key = it.next().getLongKey();
                if (!liveIds.contains(key))
                {
                    int vbo = vboId.remove(key);
                    if (vbo != 0)
                    {
                        GL15.glDeleteBuffers(vbo);
                    }
                    vboVertCount.remove(key);
                    it.remove();
                }
            }
        }

        if (!cutoutVboId.isEmpty())
        {
            ObjectIterator<Long2ObjectMap.Entry<List<BlockShadowEntry>>> it = cutoutList.long2ObjectEntrySet().iterator();
            while (it.hasNext())
            {
                long key = it.next().getLongKey();
                if (!liveIds.contains(key))
                {
                    int vbo = cutoutVboId.remove(key);
                    if (vbo != 0)
                    {
                        GL15.glDeleteBuffers(vbo);
                    }
                    cutoutVertCount.remove(key);
                    it.remove();
                }
            }
        }
    }

    public static void endPass()
    {
        if (!inPass)
        {
            return;
        }

        // Draw the entity boxes accumulated in this pass before restoring state
        // (they share the pass's currentView/currentProj, still set here).
        flushCasterBoxes();

        GL11.glDepthMask(savedDepthMask);

        if (savedScissorEnabled)
        {
            GL11.glScissor(savedScissorBox[0], savedScissorBox[1], savedScissorBox[2], savedScissorBox[3]);
        }
        else
        {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        GL11.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo);

        inPass = false;
    }

    /**
     * Snapshot the GL state every endPass restores, once per bake (the glGet* are
     * CPU<->GPU sync points; up to ~112 passes per bake would otherwise stall on
     * each). {@link #beginBake} re-arms it.
     */
    private static void savePassState()
    {
        inPass = true;
        if (passStateSaved)
        {
            return;
        }

        savedFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport);
        savedScissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (savedScissorEnabled)
        {
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, savedScissorBox);
        }
        savedDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);

        passStateSaved = true;
    }

    /** Shared up-vectors for the spot lookAt. lookAt only reads the components,
     *  so a single instance of each is safe (and avoids a per-pass allocation). */
    private static final Vector3f UP_Y = new Vector3f(0f, 1f, 0f);
    private static final Vector3f UP_Z = new Vector3f(0f, 0f, 1f);

    private static Vector3f pickStableUp(float dy)
    {
        return Math.abs(dy) > 0.99f ? UP_Z : UP_Y;
    }
}
