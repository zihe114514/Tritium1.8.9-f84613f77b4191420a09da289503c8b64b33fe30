package today.opai.api.events;

import today.opai.api.interfaces.render.WindowResolution;

/**
 * 对应原项目 today.opai.api.events.EventRender2D。
 * 由 Forge 的 RenderGameOverlayEvent 触发，携带当前窗口分辨率信息。
 */
public class EventRender2D {

    private final WindowResolution windowResolution;

    public EventRender2D(WindowResolution windowResolution) {
        this.windowResolution = windowResolution;
    }

    public WindowResolution getWindowResolution() {
        return windowResolution;
    }
}
