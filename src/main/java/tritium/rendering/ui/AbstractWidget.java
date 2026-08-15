package tritium.rendering.ui;

import lombok.Getter;

import tritium.interfaces.SharedConstants;
import tritium.interfaces.SharedRenderingConstants;
import tritium.rendering.RGBA;
import tritium.rendering.Rect;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.settings.ClientSettings;
import tritium.utils.cursor.CursorUtils;

import java.awt.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 组件 base.
 * @author IzumiiKonata
 * Date: 2025/7/8 19:42
 */
public abstract class AbstractWidget<SELF extends AbstractWidget<SELF>> implements SharedRenderingConstants, SharedConstants {

    public interface OnClickCallback {
        boolean onClick(double relativeX, double relativeY, int mouseButton);
    }

    public interface OnKeyTypedCallback {
        boolean onKeyTyped(char character, int keyCode);
    }

    public interface OnDWheelCallback {
        boolean onDWheel(double mouseX, double mouseY, int dWheel);
    }

    public interface RenderCallback {
        void onRender();
    }

    /**
     * 父组件，可为空
     */
    @Getter
    AbstractWidget<?> parent = null;

    /**
     * 子组件列表
     */
    @Getter
    List<AbstractWidget<?>> children = new CopyOnWriteArrayList<>();

    public static class Bounds {
        /**
         * 这个组件相对于父组件的坐标的偏移.
         * 比如说, 有一个坐标为 (100, 100) 的矩形组件,
         * 然后有一个新的组件作为这个矩形组件的子组件,
         * 然后设置子组件 x, y 都为 2,
         * 那么子组件的 {@link AbstractWidget#getX()} 将返回 父组件的屏幕 X 坐标 100 加上子组件的偏移 X 坐标 2, 结果为 102. Y 轴同理.
         * 子组件的 {@link AbstractWidget#getRelativeX()} 将返回 2.
         * 如果该组件没有父组件, 则 x, y 字段代表的是屏幕坐标.
         */
        private double x, y;
        private double width, height;
    }

    /**
     * 组件 (偏移) 位置
     */
    @Getter
    private final Bounds bounds = new Bounds();

    /**
     * 渲染回调
     */
    private RenderCallback beforeRenderCallback = () -> {};

    /**
     * 组件是否可点击
     */
    @Getter
    private boolean clickable = true;

    /**
     * 鼠标是否在组件内
     */
    @Getter
    private boolean hovering = false;

    /**
     * 组件是否被隐藏
     */
    @Getter
    private boolean hidden = false;
    /**
     * 是否应在被指向时更改鼠标指针类型
     */
    @Getter
    private boolean shouldOverrideMouseCursor = false;

    /**
     * 颜色
     */
    private Color color = Color.BLACK;

    /**
     * 点击回调
     */
    protected OnClickCallback clickCallback = null;

    /**
     * 键盘输入回调
     */
    private OnKeyTypedCallback keyTypedCallback = null;

    private OnDWheelCallback dWheelCallback = null;

    private Runnable transformations = null, onTick = null;

    /**
     * 组件的半透明度
     */
    private float alpha = 1.0f;

    /**
     * 在此处编写组件的渲染代码.
     */
    public abstract void onRender(double mouseX, double mouseY);

    /**
     * 返回是否应该渲染指定的子组件.
     * @param child 子组件
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     * @return 返回 true 表示应该渲染该子组件, 返回 false 表示应该跳过该子组件.
     */
    protected boolean shouldRenderChildren(AbstractWidget<?> child, double mouseX, double mouseY) {
        return true;
    }

    /**
     * 渲染这个组件以及它的子组件.
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     * @param dWheel 滚轮
     */
    public void renderWidget(double mouseX, double mouseY, int dWheel) {

        if (this.isHidden())
            return;

        boolean shouldResetMatrixState = this.transformations != null;

        if (shouldResetMatrixState) {
            api.getGLStateManager().pushMatrix();
            this.transformations.run();
        }

        this.beforeRenderCallback.onRender();

        this.onRender(mouseX, mouseY);

        boolean debug = ClientSettings.SHOW_WIDGET_BOUNDARY;

        boolean childHovering = false;

        // 渲染所有子组件
        for (AbstractWidget<?> child : this.getChildren()) {

            if (child.isHidden())
                continue;

            if (!this.shouldRenderChildren(child, mouseX, mouseY))
                continue;

            child.renderWidget(mouseX, mouseY, dWheel);

            if (debug) {
                child.renderDebugLayout();
            }

            if (!childHovering && child.isClickable() && child.testHovered(mouseX, mouseY)) {
                childHovering = true;
            }
        }

        if (debug) {
            this.renderDebugLayout();
        }

        if (shouldResetMatrixState)
            api.getGLStateManager().popMatrix();

        // 更新组件悬停状态
        // 如果所有的子组件都没有被选中, 且这个组件被选中, 才设置这个组件的 hovering 为 true.
        this.hovering = !childHovering && this.testHovered(mouseX, mouseY);

        if (this.hovering && this.isShouldOverrideMouseCursor()) {
            CursorUtils.setOverride(this.getHoveringCursorType());
        }

        if (dWheel != 0)
            this.onDWheelReceived(mouseX, mouseY, dWheel);
    }

    public SELF setShouldOverrideMouseCursor(boolean shouldOverrideMouseCursor) {
        this.shouldOverrideMouseCursor = shouldOverrideMouseCursor;
        return (SELF) this;
    }

    public long getHoveringCursorType() {
        return CursorUtils.HAND;
    }

    public SELF setOnTick(Runnable onTick) {
        this.onTick = onTick;
        return (SELF) this;
    }

    public void onTick() {

    }

    public void onTickReceived() {

        if (this.onTick != null) {
            this.onTick.run();
        }

        this.onTick();

        this.getChildren().forEach(AbstractWidget::onTickReceived);

    }

    /**
     * 添加子组件
     * @param children 子组件
     */
    public void addChild(AbstractWidget<?>... children) {
        for (AbstractWidget<?> child : children) {
            this.children.add(child);
            child.setParent(this);
        }
    }

    /**
     * 添加子组件
     * @param children 子组件
     */
    public void addChild(List<AbstractWidget<?>> children) {
        this.children.addAll(children);
        children.forEach(child -> child.setParent(this));
    }

    /**
     * 渲染 Debug 布局
     */
    protected void renderDebugLayout() {
        // show layout
        RenderSystem.drawOutLine(this.getX(), this.getY(), this.getWidth(), this.getHeight(), 0.5, reAlpha(0x00FF0000, this.getAlpha()));

        double lineLength = Math.min(8, Math.min(this.getWidth() * .25, this.getHeight() * .25));
        double lineSize = 1;
        int lineColor = reAlpha(0x000090FF, this.getAlpha());
        // left top
        Rect.draw(this.getX(), this.getY(), lineLength, lineSize, lineColor);
        Rect.draw(this.getX(), this.getY(), lineSize, lineLength, lineColor);

        // right top
        Rect.draw(this.getX() + this.getWidth() - lineLength, this.getY(), lineLength, lineSize, lineColor);
        Rect.draw(this.getX() + this.getWidth() - lineSize, this.getY(), lineSize, lineLength, lineColor);

        // left bottom
        Rect.draw(this.getX(), this.getY() + this.getHeight() - lineLength, lineSize, lineLength, lineColor);
        Rect.draw(this.getX(), this.getY() + this.getHeight() - lineSize, lineLength, lineSize, lineColor);

        // right bottom
        Rect.draw(this.getX() + this.getWidth() - lineLength, this.getY() + this.getHeight() - lineSize, lineLength, lineSize, lineColor);
        Rect.draw(this.getX() + this.getWidth() - lineSize, this.getY() + this.getHeight() - lineLength, lineSize, lineLength, lineColor);
    }

    /**
     * 设置这个组件相较于父组件的留边, 会直接修改组件的 x, y 坐标以及长宽.
     * @param margin 留边大小
     */
    public SELF setMargin(double margin) {
        return this.setMargin(margin, margin, margin, margin);
    }

    /**
     * 设置这个组件相较于父组件的留边, 会直接修改组件的 x, y 坐标以及长宽.
     * @param left 左边的留边大小
     * @param top 上面的留边大小
     * @param right 右边的留边大小
     * @param bottom 下面的留边大小
     */
    public SELF setMargin(double left, double top, double right, double bottom) {
        this.getBounds().x = left;
        this.getBounds().y = top;
        this.getBounds().width = this.getParentWidth() - left - right;
        this.getBounds().height = this.getParentHeight() - top - bottom;
        return (SELF) this;
    }

    /**
     * 扩大这个组件的边界
     * @param expand 边界大小
     */
    public SELF expand(double expand) {
        this.getBounds().x -= expand;
        this.getBounds().y -= expand;
        this.getBounds().width += expand * 2;
        this.getBounds().height += expand * 2;
        return (SELF) this;
    }

    /**
     * 测试该组件有没有被鼠标指向
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     * @return 该组件有没有被鼠标指向
     */
    protected boolean testHovered(double mouseX, double mouseY) {
        return this.isHovered(mouseX, mouseY, this.getX(), this.getY(), this.getWidth(), this.getHeight());
    }

    /**
     * 测试该组件有没有被鼠标指向
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     * @param expand 扩张范围
     * @return 该组件有没有被鼠标指向
     */
    protected boolean testHovered(double mouseX, double mouseY, double expand) {
        return this.isHovered(mouseX, mouseY, this.getX() - expand, this.getY() - expand, this.getWidth() + expand * 2, this.getHeight() + expand * 2);
    }

    /**
     * 实用方法, 检测鼠标有没有在一个矩形范围内.
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     * @param x 矩形 X 坐标
     * @param y 矩形 Y 坐标
     * @param width 矩形宽度
     * @param height 举行长度
     * @return 是否在范围内
     */
    public boolean isHovered(double mouseX, double mouseY, double x, double y, double width, double height) {

        // bounds check
        if (width < 0) {
            width = -width;
            x -= width;
        }

        if (height < 0) {
            height = -height;
            y -= height;
        }

        return mouseX >= x && mouseY >= y && mouseX <= x + width && mouseY <= y + height;
    }

    protected boolean iterateChildrenMouseClick(List<AbstractWidget<?>> children, double mouseX, double mouseY, int mouseButton) {
        for (AbstractWidget<?> child : children) {

            if (child.isHidden()) {
                continue;
            }

            if (!child.shouldClickChildren(mouseX, mouseY))
                continue;

            if (!child.getChildren().isEmpty()) {
                if (this.iterateChildrenMouseClick(child.getChildren(), mouseX, mouseY, mouseButton)) {
                    return true;
                }
            }

            if (child.isHovering() && child.isClickable() && child.onMouseClicked(mouseX - child.getX(), mouseY - child.getY(), mouseButton)) {
                return true;
            }
        }

        return false;
    }

    protected boolean iterateChildrenDWheel(List<AbstractWidget<?>> children, double mouseX, double mouseY, int dWheel) {
        for (AbstractWidget<?> child : children) {

            if (child.isHidden()) {
                continue;
            }

            if (!child.shouldClickChildren(mouseX, mouseY))
                continue;

            if (!child.getChildren().isEmpty()) {
                if (this.iterateChildrenDWheel(child.getChildren(), mouseX, mouseY, dWheel)) {
                    return true;
                }
            }

            if ((child.isHovering() || child.canBeScrolled()) && child.isClickable() && child.onDWheel(mouseX - child.getX(), mouseY - child.getY(), dWheel)) {
                return true;
            }
        }

        return false;
    }

    public boolean canBeScrolled() {
        return false;
    }

    protected boolean shouldClickChildren(double mouseX, double mouseY) {
        return true;
    }

    public void onMouseClickReceived(double mouseX, double mouseY, int mouseButton) {

        if (!this.shouldClickChildren(mouseX, mouseY))
            return;

        // 先检测子组件
        // 如果子组件都没有响应点击事件, 则测试这个组件
        if (!this.iterateChildrenMouseClick(this.getChildren(), mouseX, mouseY, mouseButton)) {
            if (!this.isHidden() && this.isHovering()) {
                // 不需要在乎返回值.
                this.onMouseClicked(mouseX - this.getX(), mouseY - this.getY(), mouseButton);
            }
        }

    }

    public void onDWheelReceived(double mouseX, double mouseY, int dWheel) {
        if (!this.shouldClickChildren(mouseX, mouseY))
            return;

        if (!this.iterateChildrenDWheel(this.getChildren(), mouseX, mouseY, dWheel)) {
            if (!this.isHidden() && (this.isHovering() || this.canBeScrolled())) {
                // 不需要在乎返回值.
                this.onDWheel(mouseX - this.getX(), mouseY - this.getY(), dWheel);
            }
        }
    }

    /**
     * 当组件被点击时, 此方法被调用.
     * @param relativeX 被点击的在组件内的坐标 X
     * @param relativeY 被点击的在组件内的坐标 Y
     * @param mouseButton 鼠标按钮, 0 为鼠标左键, 1 为鼠标右键
     * @return 是否接收点击事件
     */
    public boolean onMouseClicked(double relativeX, double relativeY, int mouseButton) {
        return this.clickCallback != null && this.isClickable() && this.clickCallback.onClick(relativeX, relativeY, mouseButton);
    }

    protected boolean iterateChildrenKeyType(List<AbstractWidget<?>> children, char typedChar, int keyCode) {
        for (AbstractWidget<?> child : children) {

            if (child.isHidden()) {
                continue;
            }

            if (!child.getChildren().isEmpty()) {
                if (this.iterateChildrenKeyType(child.getChildren(), typedChar, keyCode)) {
                    return true;
                }
            }

            if (child.onKeyTyped(typedChar, keyCode))
                return true;
        }

        return false;
    }

    public boolean onKeyTypedReceived(char typedChar, int keyCode) {
        if (this.isHidden())
            return false;

        // 先检测子组件
        // 如果子组件都没有响应点击事件, 则测试这个组件
        boolean responded = this.iterateChildrenKeyType(this.getChildren(), typedChar, keyCode);
        if (!responded) {
            if (!this.isHidden()) {
                return this.onKeyTyped(typedChar, keyCode);
            }

            return false;
        } else {
            return true;
        }
    }

    /**
     * 当接收到键盘输入时, 此方法被调用.
     * @param character 字符
     * @param keyCode 键码
     */
    public boolean onKeyTyped(char character, int keyCode) {
        return this.keyTypedCallback != null && this.keyTypedCallback.onKeyTyped(character, keyCode);
    }

    public boolean onDWheel(double mouseX, double mouseY, int dWheel) {
        return this.dWheelCallback != null && this.dWheelCallback.onDWheel(mouseX, mouseY, dWheel);
    }

    public SELF setBeforeRenderCallback(RenderCallback beforeRenderCallback) {
        this.beforeRenderCallback = beforeRenderCallback;
        return (SELF) this;
    }

    public Color getColor() {
        return new Color(this.color.getRed(), this.color.getGreen(), this.color.getBlue(), (int) (this.getAlpha() * 255));
    }

    public SELF setColor(int color) {
        this.color = new Color(color);
        return (SELF) this;
    }

    public SELF setColor(Color color) {
        this.color = color;
        return (SELF) this;
    }

    public int getHexColor() {
        int red = this.color.getRed();
        int green = this.color.getGreen();
        int blue = this.color.getBlue();
        return RGBA.color(red, green, blue, (int) (this.getAlpha() * 255));
    }

    public SELF setTransformations(Runnable transformations) {
        this.transformations = transformations;
        return (SELF) this;
    }

    public double getParentX() {
        if (this.getParent() != null)
            return this.getParent().getX();

        return 0;
    }

    public double getParentY() {
        if (this.getParent() != null)
            return this.getParent().getY();

        return 0;
    }

    public double getParentWidth() {

        if (this.getParent() != null)
            return this.getParent().getWidth();

        return RenderSystem.getWidth();
    }

    public double getParentHeight() {

        if (this.getParent() != null)
            return this.getParent().getHeight();

        return RenderSystem.getHeight();
    }

    public SELF setHidden(boolean hidden) {
        this.hidden = hidden;
        return (SELF) this;
    }

    /**
     * 获取用于渲染的 alpha.
     */
    public float getAlpha() {
        // 与父组件的 alpha 相乘
        if (this.getParent() != null)
            return this.getParent().getAlpha() * this.alpha;

        return this.alpha;
    }

    /**
     * 仅获取这个组件的 alpha.
     */
    public float getWidgetAlpha() {
        return this.alpha;
    }

    public SELF setAlpha(float alpha) {
        this.alpha = alpha;
        this.color = new Color(this.color.getRed(), this.color.getGreen(), this.color.getBlue(), (int) (alpha * 255));
        return (SELF) this;
    }

    public SELF setOnClickCallback(OnClickCallback callback) {
        this.clickCallback = callback;
        return (SELF) this;
    }

    public SELF setOnKeyTypedCallback(OnKeyTypedCallback callback) {
        this.keyTypedCallback = callback;
        return (SELF) this;
    }

    /**
     * @return 返回这个组件的屏幕 X 坐标.
     */
    public double getX() {

        // 如果父组件为空则直接返回 X 坐标
        if (this.getParent() == null)
            return this.getBounds().x;

        return this.getParent().getX() + this.getBounds().x;
    }

    /**
     * @return 返回这个组件的屏幕 Y 坐标.
     */
    public double getY() {

        // 如果父组件为空则直接返回 Y 坐标
        if (this.getParent() == null)
            return this.getBounds().y;

        return this.getParent().getY() + this.getBounds().y;
    }

    /**
     * @return 获取这个组件的偏移 X 坐标.
     */
    public double getRelativeX() {
        return this.getBounds().x;
    }

    /**
     * @return 获取这个组件的偏移 Y 坐标.
     */
    public double getRelativeY() {
        return this.getBounds().y;
    }

    /**
     * @return 获取这个组件的宽度.
     */
    public double getWidth() {
        return this.getBounds().width;
    }

    /**
     * @return 获取这个组件的高度.
     */
    public double getHeight() {
        return this.getBounds().height;
    }

    /**
     * 设置组件的宽度.
     * @param width 宽度
     */
    public SELF setWidth(double width) {
        this.getBounds().width = width;
        return (SELF) this;
    }

    /**
     * 设置组件的高度.
     * @param height 高度
     */
    public SELF setHeight(double height) {
        this.getBounds().height = height;
        return (SELF) this;
    }

    /**
     * 设置组件的偏移位置和大小.
     * @param x 相对于父组件的 X 偏移
     * @param y 相对于父组件的 Y 偏移
     * @param width 宽
     * @param height 长
     */
    public SELF setBounds(double x, double y, double width, double height) {
        this.getBounds().x = x;
        this.getBounds().y = y;
        this.getBounds().width = width;
        this.getBounds().height = height;
        return (SELF) this;
    }

    /**
     * 使组件居中.
     */
    public SELF center() {
        return this.setPosition(this.getParentWidth() * .5 - this.getWidth() * .5, this.getParentHeight() * .5 - this.getHeight() * .5);
    }

    /**
     * 使组件水平居中.
     */
    public SELF centerHorizontally() {
        return this.setPosition(this.getParentWidth() * .5 - this.getWidth() * .5, this.getRelativeY());
    }

    /**
     * 使组件垂直居中.
     */
    public SELF centerVertically() {
        return this.setPosition(this.getRelativeX(), this.getParentHeight() * .5 - this.getHeight() * .5);
    }

    /**
     * 设置子组件相对于父组件的坐标偏移
     * @param x X 坐标偏移
     * @param y Y 坐标偏移
     */
    public SELF setPosition(double x, double y) {
        this.getBounds().x = x;
        this.getBounds().y = y;
        return (SELF) this;
    }

    /**
     * 设置子组件渲染时的绝对坐标
     * @param x X 坐标
     * @param y Y 坐标
     */
    public SELF setPositionAbsolute(double x, double y) {
        this.getBounds().x = this.getBounds().y = 0;
        this.getBounds().x = x - this.getX();
        this.getBounds().y = y - this.getY();
        return (SELF) this;
    }

    /**
     * 设置组件的大小.
     */
    public SELF setBounds(double size) {
        return this.setBounds(size, size);
    }

    /**
     * 设置组件的大小.
     */
    public SELF setBounds(double width, double height) {
        this.getBounds().width = width;
        this.getBounds().height = height;
        return (SELF) this;
    }

    /**
     * 设置组件是否可点击.
     */
    public SELF setClickable(boolean clickable) {
        this.clickable = clickable;
        return (SELF) this;
    }

    /**
     * 设置组件的父组件.
     */
    public SELF setParent(AbstractWidget<?> parent) {
        this.parent = parent;
        return (SELF) this;
    }

}
