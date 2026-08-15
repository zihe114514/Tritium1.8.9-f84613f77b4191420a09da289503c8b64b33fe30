package today.opai.api.features;

/**
 * 对应原项目 today.opai.api.features.ExtensionWidget。
 * 表示一个 HUD 组件，携带位置/尺寸，由 Forge RenderGameOverlayEvent 每帧按 renderPredicate 渲染。
 */
public abstract class ExtensionWidget {

    private final String name;
    private float x;
    private float y;
    private float width;
    private float height;

    public ExtensionWidget(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public float getWidth() {
        return width;
    }

    public float getHeight() {
        return height;
    }

    public void setX(float x) {
        this.x = x;
    }

    public void setY(float y) {
        this.y = y;
    }

    public void setWidth(float width) {
        this.width = width;
    }

    public void setHeight(float height) {
        this.height = height;
    }

    /** 每帧渲染回调。 */
    public abstract void render();

    /** 是否渲染（原项目对应 module.isEnabled()）。 */
    public boolean renderPredicate() {
        return true;
    }
}
