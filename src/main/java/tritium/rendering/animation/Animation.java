package tritium.rendering.animation;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;

@Getter
@Setter
public class Animation {

    private Easing easing;
    private long duration;
    private long nanos;
    private long startTime;

    private double startValue;
    private double destinationValue;
    private double value;
    private boolean finished;

    public Animation(final Easing easing, final Duration duration) {
        this.easing = easing;
        this.startTime = System.nanoTime();
        this.duration = duration.toNanos();
    }

    public Animation(final Easing easing, final Duration duration, final double startValue) {
        this.easing = easing;
        this.startTime = System.nanoTime();
        this.duration = duration.toNanos();
        this.startValue = startValue;
        this.value = startValue;
    }

    public void setDuration(Duration duration) {
        this.duration = duration.toNanos();
    }

    public void setProgress(final double value) {
        final double result = this.easing.getFunction().apply(value);
        if (this.value > destinationValue) {
            this.value = this.startValue - (this.startValue - destinationValue) * result;
        } else {
            this.value = this.startValue + (destinationValue - this.startValue) * result;
        }
    }

    /**
     * Updates the animation by using the easing function and time
     *
     * @param destinationValue the value that the animation is going to reach
     */
    public double run(final double destinationValue) {
        this.nanos = System.nanoTime();
        if (this.destinationValue != destinationValue) {
            this.destinationValue = destinationValue;
            this.reset();
        } else {
            this.finished = this.nanos - this.duration > this.startTime;
            if (this.finished) {
                this.value = destinationValue;
                return this.value;
            }
        }

        final double result = this.easing.getFunction().apply(this.getProgress());
        if (this.value > destinationValue) {
            this.value = this.startValue - (this.startValue - destinationValue) * result;
        } else {
            this.value = this.startValue + (destinationValue - this.startValue) * result;
        }

        return this.value;
    }

    /**
     * Returns the progress of the animation
     *
     * @return value between 0 and 1
     */
    public double getProgress() {
        return Math.min(1, (double) (System.nanoTime() - this.startTime) / (double) this.duration);
    }

    public boolean isFinished() {
        return this.getProgress() == 1;
    }

    public void setValue(final double value) {
        this.destinationValue = this.value = value;
    }

    /**
     * Resets the animation to the start value
     */
    public void reset() {
        this.startTime = System.nanoTime();
        this.startValue = value;
        this.finished = false;
    }

}
