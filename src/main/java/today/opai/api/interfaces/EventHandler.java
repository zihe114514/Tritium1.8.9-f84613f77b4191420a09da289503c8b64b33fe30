package today.opai.api.interfaces;

import today.opai.api.events.EventRender2D;

/**
 * 对应原项目 today.opai.api.interfaces.EventHandler。
 * 方法均为 default 空实现：原项目里部分实现类（如 OpenNCMScreen）只覆写部分方法。
 */
public interface EventHandler {

    default void onLoop() {
    }

    default void onRender2D(EventRender2D event) {
    }
}
