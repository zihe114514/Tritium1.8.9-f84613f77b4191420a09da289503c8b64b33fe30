package tritium.rendering.animation;

import tritium.rendering.rendersystem.RenderSystem;
import tritium.utils.math.Mth;

import java.awt.*;

/**
 * @author IzumiiKonata
 * @since 2023/12/30
 */
public class Interpolations {

    /**
     * nano delta frame time for timing animations
     */
    public static long lastNanoFrame = System.nanoTime();

    /**
     * interpolate animation
     *
     * @param startValue start
     * @param endValue   end
     * @param fraction   speed
     * @return animation value
     */
    public static double interpolate(double startValue, double endValue,
                                     double fraction) {
        boolean increasing = startValue < endValue;

        double result = Mth.lerp(RenderSystem.getFrameDeltaTime() * fraction * 0.5, startValue, endValue);

        if (increasing) {
            return Math.min(endValue, result);
        } else {
            return Math.max(endValue, result);
        }

    }

    public static float interpolate(float startValue, float endValue,
                                    float fraction) {
        boolean increasing = startValue < endValue;

        float result = (float) Mth.lerp(RenderSystem.getFrameDeltaTime() * fraction * 0.5f, startValue, endValue);

        if (increasing) {
            return Math.min(endValue, result);
        } else {
            return Math.max(endValue, result);
        }
    }

    public static double interpolate(double startValue, double endValue,
                                     double fraction, double delta) {
        boolean increasing = startValue < endValue;

        double result = Mth.lerp(delta * fraction * 0.5, startValue, endValue);

        if (increasing) {
            return Math.min(endValue, result);
        } else {
            return Math.max(endValue, result);
        }

    }

    public static float interpolate(float startValue, float endValue,
                                    float fraction, float delta) {
        boolean increasing = startValue < endValue;

        double result = startValue + (endValue - startValue) * interpolateCurve(fraction) * delta;

        if (increasing) {
            return (float) Math.min(endValue, result);
        } else {
            return (float) Math.max(endValue, result);
        }
    }

    /**
     * the curve of the interpolate animation
     *
     * @param t speed input
     * @return curve value
     */
    private static double interpolateCurve(double t) {
        double clampValue;

        if (t < 0.2) {
            // 3.125 * t²
            clampValue = 3.125 * StrictMath.pow(t, 2);
        } else if (t > 0.8f) {
            // -3.125 * t² + 6.25 * t - 2.125
            clampValue = -3.125 * StrictMath.pow(t, 2) + 6.25 * t - 2.125;
        } else {
            // 1.25 * (t - 0.1)
            clampValue = 1.25 * (t - 0.1);
        }

        return clamp(clampValue);
    }

    private static double clamp(double t) {
        return (t < 0.0d) ? 0.0d : Math.min(t, 1.0d);
    }

    /**
     * calculates the delta frame time
     */
    public static void calcFrameDelta() {

        double value = ((double) System.nanoTime() - (double) lastNanoFrame) / 10000000.0;

        if (value > 20) {
            value = 0.01;
        }

        RenderSystem.setFrameDeltaTime(value);
        lastNanoFrame = System.nanoTime();
    }

    public static Color getColorAnimationState(Color animation, Color finalState, double speed) {
        float add = (float) (RenderSystem.getFrameDeltaTime() * 0.1 * speed);
        float animationr = animation.getRed();
        float animationg = animation.getGreen();
        float animationb = animation.getBlue();
        float finalStater = finalState.getRed();
        float finalStateg = finalState.getGreen();
        float finalStateb = finalState.getBlue();
        float finalStatea = finalState.getAlpha();
        //r
        if (animationr < finalStater) {
            if (animationr + add < finalStater)
                animationr += add;
            else
                animationr = finalStater;
        } else {
            if (animationr - add > finalStater)
                animationr -= add;
            else
                animationr = finalStater;
        }
        //g
        if (animationg < finalStateg) {
            if (animationg + add < finalStateg)
                animationg += add;
            else
                animationg = finalStateg;
        } else {
            if (animationg - add > finalStateg)
                animationg -= add;
            else
                animationg = finalStateg;
        }
        //b
        if (animationb < finalStateb) {
            if (animationb + add < finalStateb)
                animationb += add;
            else
                animationb = finalStateb;
        } else {
            if (animationb - add > finalStateb)
                animationb -= add;
            else
                animationb = finalStateb;
        }
        animationr /= 255.0f;
        animationg /= 255.0f;
        animationb /= 255.0f;
        finalStatea /= 255.0f;
        if (animationr > 1.0f) animationr = 1.0f;
        if (animationg > 1.0f) animationg = 1.0f;
        if (animationb > 1.0f) animationb = 1.0f;
        if (finalStatea > 1.0f) finalStatea = 1.0f;
        return new Color(animationr, animationg, animationb, finalStatea);
    }

    public static double interpolateLinear(double now, double end, float interpolation) {
        double add = RenderSystem.getFrameDeltaTime() * 0.1 * interpolation;
        if (now < end) {
            if (now + add < end)
                now += add;
            else
                now = end;
        } else {
            if (now - add > end)
                now -= add;
            else
                now = end;
        }
        return now;
    }

    public static float interpolateLinear(float now, float end, float interpolation) {
        float add = (float) (RenderSystem.getFrameDeltaTime() * 0.1 * interpolation);
        if (now < end) {
            if (now + add < end)
                now += add;
            else
                now = end;
        } else {
            if (now - add > end)
                now -= add;
            else
                now = end;
        }
        return now;
    }

    public static double interpBezierApprox(double startValue, double endValue,
                                            double fraction) {

        if (startValue < endValue) {
            if (Math.abs(startValue + 0.000001) / Math.abs(endValue + 0.000001) > 0.99) {
                return endValue;
            }
        } else {
            if (Math.abs(endValue + 0.000001) / Math.abs(startValue + 0.000001) > 0.99) {
                return endValue;
            }
        }

        return interpolate(startValue, endValue, fraction);
    }
}
