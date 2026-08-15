package today.opai.api.interfaces.render;

/**
 * 对应原项目 today.opai.api.interfaces.render.WindowResolution。
 * 由 Minecraft 的 ScaledResolution 包装实现。
 */
public interface WindowResolution {

    int getScaleFactor();

    int getWidth();

    int getHeight();
}
