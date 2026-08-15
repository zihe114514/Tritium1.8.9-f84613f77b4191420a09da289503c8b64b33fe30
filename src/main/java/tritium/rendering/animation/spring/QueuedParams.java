package tritium.rendering.animation.spring;

/**
 * @author IzumiiKonata
 * Date: 2025/12/11 23:35
 */
public class QueuedParams {
    SpringParams params;
    double time;

    public QueuedParams(SpringParams params, double time) {
        this.params = params;
        this.time = time;
    }
}
