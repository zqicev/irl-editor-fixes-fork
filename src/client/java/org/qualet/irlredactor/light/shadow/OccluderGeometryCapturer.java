package org.qualet.irlredactor.light.shadow;

import net.minecraft.block.BlockState;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

/**
 * 1.21.8 stub.
 *
 * <p>On 1.21.9+ the real capture rasterizes entity/model and cutout-block
 * geometry into world-space triangles through the render command queue
 * ({@code net.minecraft.client.render.command.*}) and the render-state system
 * ({@code net.minecraft.client.render.state.*}). Neither package exists on
 * 1.21.8: it has the 1.21.5 RenderPipelines but not the 1.21.9 command queue,
 * so the upstream capturer cannot compile here.</p>
 *
 * <p>Until an immediate-mode capture is ported for the 1.21.5&ndash;1.21.8
 * rendering era, both entry points return no geometry. {@link ShadowRenderer}
 * treats an empty result as "nothing to add", so on 1.21.8: placed lights and
 * solid-block shadows work as usual; mob/player shadows and cutout-block
 * (e.g. leaves) shadows are disabled.</p>
 */
public final class OccluderGeometryCapturer
{
    private static final float[] EMPTY = new float[0];

    private OccluderGeometryCapturer()
    {}

    /** No entity-caster geometry on 1.21.8 (see class doc). */
    public static float[] captureEntityTris(Entity entity, float tickDelta)
    {
        return EMPTY;
    }

    /** No cutout-block geometry on 1.21.8 (see class doc). */
    public static float[] captureCutoutBlockTris(BlockRenderView world, BlockRenderManager brm,
                                                 BlockPos pos, BlockState state, Random random)
    {
        return EMPTY;
    }
}
