package tritium.rendering.animation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class MultipleEndpointAnimation {

    Animation anim;

    List<Double> endPoints = new ArrayList<>();
    List<Duration> durations = new ArrayList<>();

    public int curEndpoint = -1;
    public double startValue;

    public MultipleEndpointAnimation(final Easing easing, Duration duration, double startValue) {
        anim = new Animation(easing, duration);
        anim.setValue(startValue);

        this.startValue = startValue;
    }

    public MultipleEndpointAnimation addEndpoint(double to, Duration duration) {
        endPoints.add(to);
        durations.add(duration);
        return this;
    }

    public void reset() {
        curEndpoint = 0;

        anim.setValue(startValue);
        anim.setStartValue(startValue);
    }

    public double run(boolean reversed) {
        if (!reversed) {
            double nextPoint;
            if (curEndpoint < endPoints.size() - 1) {
                nextPoint = endPoints.get(curEndpoint + 1);
                anim.setDuration(durations.get(curEndpoint + 1));
            } else if (curEndpoint == -1) {
                nextPoint = 0;
                anim.setDuration(durations.get(0));
            } else {
                nextPoint = endPoints.get(endPoints.size() - 1);
                anim.setDuration(durations.get(durations.size() - 1));
            }

            anim.run(nextPoint);

            if (anim.isFinished() && curEndpoint < endPoints.size() - 1) {
                curEndpoint++;
            }
        } else {
            if (curEndpoint > -1) {
                double nextPoint;

                if (curEndpoint == 0) {
                    nextPoint = endPoints.get(0);
                    anim.setDuration(durations.get(0));
                } else {
                    nextPoint = endPoints.get(curEndpoint - 1);
                    anim.setDuration(durations.get(curEndpoint - 1));
                }

                anim.run(nextPoint);

                if (anim.isFinished() && curEndpoint > 0) {
                    curEndpoint--;
                }
            }
        }

        return anim.getValue();
    }

    public boolean isFinished(boolean reversed) {

        if (!reversed) {

            return curEndpoint == endPoints.size() - 1 && anim.isFinished();

        } else {

            return curEndpoint == 0 && anim.isFinished();
        }

    }

    public double getValue() {
        return this.anim.getValue();
    }

}
