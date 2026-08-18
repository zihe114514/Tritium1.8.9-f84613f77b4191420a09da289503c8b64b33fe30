package com.muoniumplayer.core.rendering.ui.container;

import com.muoniumplayer.core.rendering.Rect;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;

/**
 * @author IzumiiKonata
 * Date: 2025/7/8 21:44
 */
public class Panel extends AbstractWidget<Panel> {
    @Override
    public void onRender(double mouseX, double mouseY) {
        // 什么也不干, 这个只是一个隐形的容器, 用于将组件分组。
    }

    @Override
    protected void renderDebugLayout() {
        super.renderDebugLayout();
        Rect.draw(this.getX(), this.getY(), this.getWidth(), this.getHeight(), reAlpha(0x00F50EE8, this.getAlpha() * .05f));
    }
}
