package tritium.utils.math;

import lombok.experimental.UtilityClass;

/**
 * @author IzumiiKonata
 * Date: 2025/11/16 12:28
 */
@UtilityClass
public class Mth {

    public static int floor(float v) {
        int i = (int)v;
        return v < (float)i ? i - 1 : i;
    }

    public static int floor(double v) {
        int i = (int)v;
        return v < (double)i ? i - 1 : i;
    }

    public static float frac(float num) {
        return num - (float)Mth.floor(num);
    }

    public static double frac(double num) {
        return num - (double)Mth.lfloor(num);
    }

    public static long lfloor(double v) {
        long i = (long)v;
        return v < (double)i ? i - 1L : i;
    }

    public static int lerpInt(float alpha1, int p0, int p1) {
        return p0 + Mth.floor(alpha1 * (float)(p1 - p0));
    }

    public static double limit(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static float lerp(final float f, final float g, final float h) {
        return g + f * (h - g);
    }

    public static double lerp(final double d, final double e, final double f) {
        return e + d * (f - e);
    }

    public static float fastInvSqrt(float f) {
        final float g = 0.5f * f;
        int i = Float.floatToIntBits(f);
        i = 1597463007 - (i >> 1);
        f = Float.intBitsToFloat(i);
        f *= 1.5f - g * f * f;
        return f;
    }

    public static float fastInvCubeRoot(final float f) {
        int i = Float.floatToIntBits(f);
        i = 1419967116 - i / 3;
        float g = Float.intBitsToFloat(i);
        g = 0.6666667f * g + 0.33333334f * g * g * f;
        g = 0.6666667f * g + 0.33333334f * g * g * f;
        return g;
    }
}
