package org.qualet.irlredactor.light;

/**
 * Plain configuration for the lighting engine — the BBS-free replacement for
 * IRLite's {@code IrliteConfig} (which was backed by BBS settings {@code Value*}).
 * Fields are static and mutable so the editor can drive them later; the getters
 * keep the same names and defaults the engine code expects.
 */
public final class LightConfig
{
    /** Draw in-world wireframe gizmos for placed lights (default off). */
    public static boolean showGuides = false;
    /** Shadow resolution preset ordinal (0 LOW .. 3 ULTRA), default 1 (MEDIUM). */
    public static int shadowQuality = 1;
    /** When on, shadow maps are only re-baked when the scene changes (default on). */
    public static boolean shadowCache = true;
    /** When on, world blocks cast shadows by their real shape (default on). */
    public static boolean shadowBlocks = true;
    /** Block-shadow collection radius in blocks (default 24). */
    public static int shadowBlockRadius = 24;
    /** Max full static shadow bakes started per frame before the rest are
     *  deferred to a later frame (default 4). Spreads a mass invalidation (a
     *  block edit near a cluster of lamps) across frames instead of one spike;
     *  the deferred lamps keep their existing (slightly stale) map until baked.
     *  &lt;= 0 disables throttling (bake everything every frame). First bakes and
     *  tile-reassign bakes are never deferred (they would sample a blank or
     *  foreign map); dynamic overlays and static-&gt;live copies are never
     *  budgeted (they must run every frame). */
    public static int shadowBakeBudget = 4;

    private LightConfig()
    {}

    public static boolean showGuides()
    {
        return showGuides;
    }

    public static boolean shadowCache()
    {
        return shadowCache;
    }

    public static boolean shadowBlocks()
    {
        return shadowBlocks;
    }

    public static int shadowQuality()
    {
        return shadowQuality;
    }

    public static int shadowBlockRadius()
    {
        return shadowBlockRadius;
    }

    public static int shadowBakeBudget()
    {
        return shadowBakeBudget;
    }
}
