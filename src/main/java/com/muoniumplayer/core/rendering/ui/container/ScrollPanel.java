package com.muoniumplayer.core.rendering.ui.container;

import lombok.Getter;
import org.lwjgl.input.Keyboard;
import com.muoniumplayer.core.rendering.Rect;
import com.muoniumplayer.core.rendering.ScissorClipManager;
import com.muoniumplayer.core.rendering.StencilClipManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;

import java.util.List;

/**
 * 一个从上至下排列的可以滚动的容器
 * @author IzumiiKonata
 * Date: 2025/7/8 21:45
 */
public class ScrollPanel extends AbstractWidget<ScrollPanel> {

    @Getter
    private double spacing = 0;
    @Getter
    private double scrollStrength = 12;
    public double actualScrollOffset = 0, targetScrollOffset = 0;

    @Getter
    private Alignment alignment = Alignment.VERTICAL;

    /**
     * 子组件的排列方式
     */
    public enum Alignment {
        /**
         * 仅垂直排列 (默认)
         */
        VERTICAL,
        /**
         * 仅水平排列
         */
        HORIZONTAL,
        /**
         * 垂直排列并水平填充
         */
        VERTICAL_WITH_HORIZONTAL_FILL
    }

    public ScrollPanel setAlignment(Alignment alignment) {

        if (alignment == null)
            throw new IllegalArgumentException("Alignment cannot be null!");

        this.alignment = alignment;
        return this;
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        // 播放器尺寸动画会逐帧改变滚动区域和每行可容纳的控件数量。
        // 先按新边界修正偏移，避免尺寸切换时使用上一帧的无效滚动位置而冲出面板。
        this.clampScrollOffset();
        this.actualScrollOffset = Interpolations.interpolate(this.actualScrollOffset, this.targetScrollOffset, 1f);
        this.actualScrollOffset = Math.max(0, Math.min(this.actualScrollOffset, this.getMaximumScrollOffset()));

        // 设置子组件的垂直位置
        this.alignChildren();
    }

    @Override
    protected void renderDebugLayout() {
        super.renderDebugLayout();
        Rect.draw(this.getX(), this.getY(), this.getWidth(), this.getHeight(), reAlpha(0x00F50EE8, this.getAlpha() * .05f));
    }

    public ScrollPanel setSpacing(double spacing) {
        this.spacing = spacing;
        return this;
    }

    /**
     * 设置每个滚轮刻度移动的距离。内容较长的面板可以提高该值，避免用户
     * 需要滚动大量次数才能到达末尾。
     */
    public ScrollPanel setScrollStrength(double scrollStrength) {
        this.scrollStrength = Math.max(1, scrollStrength);
        return this;
    }

    @Override
    public boolean onDWheel(double mouseX, double mouseY, int dWheel) {

        // 垂直滑动
        this.performScroll(dWheel);
        super.onDWheel(mouseX, mouseY, dWheel);

        return true;
    }

    private void performScroll(int dWheel) {

        if (dWheel != 0) {

            double strength = this.scrollStrength;

            if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT))
                strength *= 2;

            if (dWheel > 0)
                this.targetScrollOffset -= strength;
            else
                this.targetScrollOffset += strength;
        }

        this.clampScrollOffset();
    }

    private void clampScrollOffset() {
        this.targetScrollOffset = Math.max(0, Math.min(this.targetScrollOffset, this.getMaximumScrollOffset()));
    }

    private double getMaximumScrollOffset() {
        double contentLength;
        switch (this.alignment) {
            case HORIZONTAL:
                contentLength = this.getChildrenWidthSum();
                return Math.max(0, contentLength - this.getWidth());
            case VERTICAL_WITH_HORIZONTAL_FILL:
                contentLength = this.getChildrenHeightSumHorizontalFill();
                return Math.max(0, contentLength - this.getHeight());
            case VERTICAL:
            default:
                contentLength = this.getChildrenHeightSum();
                return Math.max(0, contentLength - this.getHeight());
        }
    }

    public void scrollToEnd() {
        switch (this.alignment) {
            case VERTICAL: {
                double childrenHeightSum = this.getChildrenHeightSum();
                if (childrenHeightSum > this.getHeight())
                    this.targetScrollOffset = childrenHeightSum - this.getHeight();
                else
                    this.targetScrollOffset = 0;
                break;
            }
            case HORIZONTAL: {
                double childrenWidthSum = this.getChildrenWidthSum();
                if (childrenWidthSum > this.getWidth())
                    this.targetScrollOffset = childrenWidthSum - this.getWidth();
                else
                    this.targetScrollOffset = 0;
                break;
            }
            case VERTICAL_WITH_HORIZONTAL_FILL: {
                double childrenHeightSum = this.getChildrenHeightSumHorizontalFill();
                if (childrenHeightSum > this.getHeight())
                    this.targetScrollOffset = childrenHeightSum - this.getHeight();
                else
                    this.targetScrollOffset = 0;
                break;
            }
        }
    }

    public void scrollToEndImmediately() {
        switch (this.alignment) {
            case VERTICAL: {
                double childrenHeightSum = this.getChildrenHeightSum();
                if (childrenHeightSum > this.getHeight())
                    this.actualScrollOffset = childrenHeightSum - this.getHeight();
                else
                    this.actualScrollOffset = 0;
                break;
            }
            case HORIZONTAL: {
                double childrenWidthSum = this.getChildrenWidthSum();
                if (childrenWidthSum > this.getWidth())
                    this.actualScrollOffset = childrenWidthSum - this.getWidth();
                else
                    this.actualScrollOffset = 0;
                break;
            }
            case VERTICAL_WITH_HORIZONTAL_FILL: {
                double childrenHeightSum = this.getChildrenHeightSumHorizontalFill();
                if (childrenHeightSum > this.getHeight())
                    this.actualScrollOffset = childrenHeightSum - this.getHeight();
                else
                    this.actualScrollOffset = 0;
                break;
            }
        }
    }

    public boolean isScrolledToEnd() {
        if (this.alignment == Alignment.VERTICAL) {
            double childrenHeightSum = this.getChildrenHeightSum();
            if (childrenHeightSum > this.getHeight())
                return this.targetScrollOffset >= childrenHeightSum - this.getHeight();
            else
                return this.targetScrollOffset == 0;
        } else if (this.alignment == Alignment.HORIZONTAL) {
            double childrenWidthSum = this.getChildrenWidthSum();
            if (childrenWidthSum > this.getWidth())
                return this.targetScrollOffset >= childrenWidthSum - this.getWidth();
            else
                return this.targetScrollOffset == 0;
        } else {
            double childrenHeightSum = this.getChildrenHeightSumHorizontalFill();
            if (childrenHeightSum > this.getHeight())
                return this.targetScrollOffset >= childrenHeightSum - this.getHeight();
            else
                return this.targetScrollOffset == 0;
        }
    }

    public void alignChildren() {
        double offsetX = 0;
        double offsetY = 0;
        double rowHeight = 0;

        if (this.alignment == Alignment.VERTICAL || this.alignment == Alignment.VERTICAL_WITH_HORIZONTAL_FILL)
            offsetY = -this.actualScrollOffset;
        else
            offsetX = -this.actualScrollOffset;

        switch (this.alignment) {
            case VERTICAL: {
                double childrenHeightSum = this.getChildrenHeightSum();

                if (childrenHeightSum < this.getHeight())
                    this.targetScrollOffset = 0;
                else if (this.targetScrollOffset > childrenHeightSum - this.getHeight()) {
                    this.targetScrollOffset = childrenHeightSum - this.getHeight();
                }
                break;
            }
            case HORIZONTAL: {
                double childrenWidthSum = this.getChildrenWidthSum();

                if (childrenWidthSum < this.getWidth())
                    this.targetScrollOffset = 0;
                else if (this.targetScrollOffset > childrenWidthSum - this.getWidth()) {
                    this.targetScrollOffset = childrenWidthSum - this.getWidth();
                }
                break;
            }
            case VERTICAL_WITH_HORIZONTAL_FILL: {
                double childrenHeightSum = this.getChildrenHeightSumHorizontalFill();

                if (childrenHeightSum < this.getHeight())
                    this.targetScrollOffset = 0;
                else if (this.targetScrollOffset > childrenHeightSum - this.getHeight()) {
                    this.targetScrollOffset = childrenHeightSum - this.getHeight();
                }
                break;
            }
        }

        for (AbstractWidget<?> child : this.getChildren()) {

            double width = child.getWidth();
            double height = child.getHeight();

            if (child.isHidden())
                continue;

            switch (this.alignment) {
                case VERTICAL: {
                    child.setPosition(child.getRelativeX(), offsetY);
                    offsetY += height + spacing;
                    break;
                }
                case HORIZONTAL: {
                    child.setPosition(offsetX, child.getRelativeY());
                    offsetX += width + spacing;
                    break;
                }
                case VERTICAL_WITH_HORIZONTAL_FILL: {
                    // Do not create an empty first row when a child is wider than a very
                    // small viewport. For mixed-size controls, advance by the tallest item
                    // in the completed row instead of whichever item happened to wrap.
                    if (offsetX > 0 && offsetX + width > this.getWidth()) {
                        offsetX = 0;
                        offsetY += rowHeight + spacing;
                        rowHeight = 0;
                    }

                    child.setPosition(offsetX, offsetY);
                    offsetX += width + spacing;
                    rowHeight = Math.max(rowHeight, height);
                    break;
                }
            }
        }
    }

    protected double getChildrenHeightSum() {
        double result = 0;

        List<AbstractWidget<?>> children = this.getChildren();

        for (AbstractWidget<?> child : children) {

            double width = child.getWidth();
            double height = child.getHeight();

            if (child.isHidden())
                continue;

            result += height + this.spacing;
        }

        if (result > 0)
            result -= this.spacing;

        return result;
    }

    protected double getChildrenWidthSum() {
        double result = 0;

        List<AbstractWidget<?>> children = this.getChildren();

        for (AbstractWidget<?> child : children) {

            double width = child.getWidth();
            double height = child.getHeight();

            if (child.isHidden())
                continue;

            result += width + this.spacing;
        }

        if (result > 0)
            result -= this.spacing;

        return result;
    }

    protected double getChildrenHeightSumHorizontalFill() {
        double result = 0;
        double offsetX = 0;
        double rowHeight = 0;

        List<AbstractWidget<?>> children = this.getChildren();

        for (AbstractWidget<?> child : children) {

            double width = child.getWidth();
            double height = child.getHeight();

            if (child.isHidden())
                continue;

            if (offsetX > 0 && offsetX + width > this.getWidth()) {
                result += rowHeight + spacing;
                offsetX = 0;
                rowHeight = 0;
            }

            offsetX += width + spacing;
            rowHeight = Math.max(rowHeight, height);
        }

        if (rowHeight > 0) {
            result += rowHeight;
        }

        return result;
    }

    @Override
    protected void beforeRenderChildren(double mouseX, double mouseY) {
        // The stencil provides normal widget clipping, while the projected scissor is a
        // hard screen-space boundary. Rounded cover rendering may open nested stencil
        // levels, but it can no longer escape above the header or below the controls bar.
        ScissorClipManager.begin(this.getX(), this.getY(), this.getWidth(), this.getHeight());
        StencilClipManager.beginClip(() ->
                Rect.draw(this.getX(), this.getY(), this.getWidth(), this.getHeight(), -1));
    }

    @Override
    protected void afterRenderChildren(double mouseX, double mouseY) {
        try {
            StencilClipManager.endClip();
        } finally {
            ScissorClipManager.end();
        }
    }

    @Override
    protected boolean shouldRenderChildren(AbstractWidget<?> child, double mouseX, double mouseY) {

        switch (this.getAlignment()) {
            case VERTICAL:
            case VERTICAL_WITH_HORIZONTAL_FILL:
                if (child.getRelativeY() + child.getHeight() < 0)
                    return false;

                if (child.getRelativeY() > this.getHeight())
                    return false;
                break;
            case HORIZONTAL:
                if (child.getRelativeX() + child.getWidth() < 0)
                    return false;

                if (child.getRelativeX() > this.getWidth())
                    return false;
                break;
        }

        return true;
    }

    @Override
    protected boolean shouldClickChildren(double mouseX, double mouseY) {
//        System.out.println(this.testHovered(mouseX, mouseY));
        return this.testHovered(mouseX, mouseY);
    }

    @Override
    public boolean canBeScrolled() {
        return true;
    }
}
