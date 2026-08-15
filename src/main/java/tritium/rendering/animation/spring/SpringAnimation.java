package tritium.rendering.animation.spring;

import lombok.Getter;
import java.util.function.DoubleUnaryOperator;

/**
 * @author IzumiiKonata
 * Date: 2025/12/11 23:28
 */
public class SpringAnimation {

    @Getter
    private double currentPosition;
    private double targetPosition;
    private double currentTime = 0.0;

    private SpringParams params = new SpringParams();

    private QueuedParams queueParams = null;
    private QueuedPosition queuePosition = null;

    private DoubleUnaryOperator currentSolver;
    private DoubleUnaryOperator getV;
    private DoubleUnaryOperator getV2;

    public SpringAnimation(double current) {
        this.targetPosition = current;
        this.currentPosition = current;
        this.currentSolver = t -> this.targetPosition;
        this.getV = t -> 0.0;
        this.getV2 = t -> 0.0;
    }

    public SpringAnimation() {
        this(0.0);
    }

    public SpringAnimation(SpringParams params) {
        this();
        this.setParams(params);
    }

    private DoubleUnaryOperator makeVelocityFunc(DoubleUnaryOperator f) {
        return t -> {
            final double h = 1e-4;
            return (f.applyAsDouble(t + h) - f.applyAsDouble(t - h)) / (2 * h);
        };
    }

    private DoubleUnaryOperator solveSpring(double from, double velocity, double to, SpringParams p) {
        double delta = to - from;

        double mass = p.mass;
        double damping = p.damping;
        double stiffness = p.stiffness;
        boolean soft = p.soft;

        if (soft || damping >= 2.0 * Math.sqrt(stiffness * mass)) {
            double angular_freq = -Math.sqrt(stiffness / mass);
            double leftover = -angular_freq * delta - velocity;

            return t -> to - (delta + t * leftover) * Math.exp(t * angular_freq);
        }

        double damp_freq = Math.sqrt(4.0 * mass * stiffness - damping * damping);
        double leftover = (damping * delta - 2.0 * mass * velocity) / damp_freq;
        double dfm = (0.5 * damp_freq) / mass;
        double dm = -(0.5 * damping) / mass;

        return t -> to - (Math.cos(t * dfm) * delta + Math.sin(t * dfm) * leftover) * Math.exp(t * dm);
    }

    public void update(double delta) {
        this.currentTime += delta;
        this.currentPosition = this.currentSolver.applyAsDouble(this.currentTime);

        if (this.queueParams != null) {
            this.queueParams.time -= delta;
            if (this.queueParams.time <= 0) {
                this.setParams(queueParams.params);
                this.queueParams = null;
            }
        }

        if (this.queuePosition != null) {
            this.queuePosition.time -= delta;
            if (this.queuePosition.time <= 0) {
                this.setTargetPosition(queuePosition.position);
                this.queuePosition = null;
            }
        }

        if (this.isArrived()) {
            this.setPosition(this.targetPosition);
        }
    }

    public void setPosition(double position) {
        this.targetPosition = position;
        this.currentPosition = position;
        this.currentSolver = t -> this.targetPosition;
        this.getV = t -> 0.0;
        this.getV2 = t -> 0.0;
    }

    public void setTargetPosition(double position, double delay) {

        if (position == this.targetPosition && (this.queuePosition != null && this.queuePosition.time == delay))
            return;

        if (delay > 0) {
            this.queuePosition = new QueuedPosition(position, delay);
        } else {
            this.queuePosition = null;
            this.targetPosition = position;
            this.resetSolver();
        }
    }

    public void setTargetPosition(double position) {
        this.setTargetPosition(position, 0.0);
    }

    public void setParams(SpringParams newParams, double delay) {
        if (delay > 0) {
            this.queueParams = new QueuedParams(newParams, delay);
        } else {
            this.params = newParams;
            this.resetSolver();
        }
    }

    public void setParams(SpringParams newParams) {
        this.setParams(newParams, 0.0);
    }

    public boolean isArrived() {
        return Math.abs(this.targetPosition - this.currentPosition) < 0.01 && Math.abs(this.getV.applyAsDouble(this.currentTime)) < 0.01 && Math.abs(this.getV2.applyAsDouble(this.currentTime)) < 0.01 && this.queueParams == null && this.queuePosition == null;
    }

    private void resetSolver() {
        double v = this.getV.applyAsDouble(this.currentTime);
        this.currentTime = 0.0;

        this.currentSolver = this.solveSpring(this.currentPosition, v, this.targetPosition, this.params);

        this.getV = this.makeVelocityFunc(this.currentSolver);
        this.getV2 = this.makeVelocityFunc(this.getV);
    }
}