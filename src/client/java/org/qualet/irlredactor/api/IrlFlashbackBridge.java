package org.qualet.irlredactor.api;

import org.qualet.irlredactor.light.LightScene;
import org.qualet.irlredactor.light.PlacedLight;

import java.util.List;

/**
 * Stable, public facade for animating placed lights from an external mod — built
 * for the Flashback keyframe integration, but deliberately generic. It is the ONLY
 * supported entry point for outside code; everything in {@code org.qualet.irlredactor.light}
 * is internal and may change.
 *
 * <p><b>Reflection contract.</b> Flashback links to IRL via a soft dependency and
 * reaches this class purely by reflection (no compile-time coupling), so the surface
 * here uses only JDK types ({@code String}/{@code double[]}/primitives), every method
 * is {@code static} and never throws, and the signatures are frozen. Do not change the
 * method names, parameter types, or the {@link #VALUE_COUNT}-slot value layout without
 * updating the Flashback-side mirror.</p>
 *
 * <p><b>Threading.</b> All methods touch the live {@link LightScene}, which is owned by
 * the client main thread (client tick + world render). Flashback's keyframe apply and
 * its ImGui popups both run there, so no synchronization is needed.</p>
 *
 * <p><b>Value layout</b> ({@link #snapshot}/{@link #apply}, length {@link #VALUE_COUNT}):</p>
 * <pre>
 *   0:x  1:y  2:z          absolute world position
 *   3:r  4:g  5:b          linear colour 0..1
 *   6:intensity            0..20
 *   7:radius               point reach (blocks)
 *   8:range                spot reach (blocks)
 *   9:dirX 10:dirY 11:dirZ spot world-space direction (normalized by the driver)
 *   12:outerAngleDeg       spot outer cone full angle
 *   13:innerAngleDeg       spot inner cone full angle
 *   14:beamStrength        volumetric beam 0..5
 *   15:vlDensity           volumetric density
 *   16:anisotropy          Henyey-Greenstein g
 *   17:bulbSize            shadow softness
 * </pre>
 * The light's {@link PlacedLight.Type type} (point/spot) is NOT animated here — it is a
 * property set in the editor; the driver ignores the slots that don't apply to the type.
 */
public final class IrlFlashbackBridge
{
    /** Number of animatable slots in a {@link #snapshot}/{@link #apply} value array. */
    public static final int VALUE_COUNT = 18;

    private IrlFlashbackBridge()
    {
    }

    /** Always true: a successful class load means the bridge is usable. The caller
     *  still gates on {@code FabricLoader.isModLoaded("irl-redactor")} before reaching
     *  this class. */
    public static boolean isAvailable()
    {
        return true;
    }

    /**
     * Snapshot of every placed light, as a flat array {@code [uid0, name0, uid1, name1, ...]}.
     * Lets the caller present a target picker. Order matches {@link LightScene#all()}.
     */
    public static String[] listLights()
    {
        List<PlacedLight> lights = LightScene.all();
        String[] out = new String[lights.size() * 2];
        int o = 0;
        for (PlacedLight l : lights)
        {
            if (l == null)
            {
                continue;
            }
            out[o++] = l.uid;
            out[o++] = l.name == null ? "" : l.name;
        }
        if (o == out.length)
        {
            return out;
        }
        // Compact in the rare case a null light was skipped.
        String[] trimmed = new String[o];
        System.arraycopy(out, 0, trimmed, 0, o);
        return trimmed;
    }

    /**
     * Current values of the light with this uid, in the {@link #VALUE_COUNT}-slot layout,
     * or {@code null} if no such light exists. Used to seed a new keyframe from the light's
     * live state.
     */
    public static double[] snapshot(String uid)
    {
        PlacedLight l = find(uid);
        if (l == null)
        {
            return null;
        }
        double[] v = new double[VALUE_COUNT];
        v[0] = l.x;            v[1] = l.y;            v[2] = l.z;
        v[3] = l.r;            v[4] = l.g;            v[5] = l.b;
        v[6] = l.intensity;
        v[7] = l.radius;
        v[8] = l.range;
        v[9] = l.dirX;         v[10] = l.dirY;        v[11] = l.dirZ;
        v[12] = l.outerAngleDeg;
        v[13] = l.innerAngleDeg;
        v[14] = l.beamStrength;
        v[15] = l.vlDensity;
        v[16] = l.anisotropy;
        v[17] = l.bulbSize;
        return v;
    }

    /**
     * Write animated values into the light with this uid. No-op if the light was deleted
     * or the array is the wrong length, so a stale keyframe can never crash the caller.
     */
    public static void apply(String uid, double[] values)
    {
        if (values == null || values.length < VALUE_COUNT)
        {
            return;
        }
        PlacedLight l = find(uid);
        if (l == null)
        {
            return;
        }
        l.x = values[0];                 l.y = values[1];                 l.z = values[2];
        l.r = (float) values[3];         l.g = (float) values[4];         l.b = (float) values[5];
        l.intensity = (float) values[6];
        l.radius = (float) values[7];
        l.range = (float) values[8];
        l.dirX = (float) values[9];      l.dirY = (float) values[10];     l.dirZ = (float) values[11];
        l.outerAngleDeg = (float) values[12];
        l.innerAngleDeg = (float) values[13];
        l.beamStrength = (float) values[14];
        l.vlDensity = (float) values[15];
        l.anisotropy = (float) values[16];
        l.bulbSize = (float) values[17];
    }

    private static PlacedLight find(String uid)
    {
        if (uid == null)
        {
            return null;
        }
        List<PlacedLight> lights = LightScene.all();
        for (int i = 0, n = lights.size(); i < n; i++)
        {
            PlacedLight l = lights.get(i);
            if (l != null && uid.equals(l.uid))
            {
                return l;
            }
        }
        return null;
    }
}
