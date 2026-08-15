package today.opai.api.interfaces.render;

/**
 * 对应原项目 today.opai.api.interfaces.render.Font（Vanilla 字体门面）。
 * 由 Minecraft 的 FontRenderer 包装实现。
 */
public interface Font {

    int getWidth(String text);

    int getHeight();

    void drawString(String text, double x, double y, int color);
}
