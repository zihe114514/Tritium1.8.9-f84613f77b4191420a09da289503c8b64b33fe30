package com.muoniumplayer.core.screens.ncm.panels;

import org.lwjgl.input.Mouse;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.Rect;
import com.muoniumplayer.core.rendering.ScissorClipManager;
import com.muoniumplayer.core.rendering.StencilClipManager;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.animation.spring.SpringAnimation;
import com.muoniumplayer.core.rendering.animation.spring.SpringParams;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedImageWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.utils.Location;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放器底部的"下一首播放"抽屉。
 *
 * <p>列表内容是 {@link CloudMusic#getQueuedNextSongs()} 的每帧快照，顺序调整只走
 * {@link CloudMusic#moveQueuedNext(int, int)}：当前播放位置、队列长度、播放模式都不受影响，
 * 没用过这个功能的用户拿到的仍然是原来的行为。</p>
 *
 * <p>每一行的位置由本面板在 {@link #onRender(double, double)} 里统一计算（父组件的 onRender
 * 先于子组件执行），列表容器只负责滚动与裁剪，因此不存在"容器自动排列覆盖动画位置"的问题。</p>
 *
 * @author Codex
 */
public class PlayQueuePanel extends NCMPanel {

    private static final double ROW_HEIGHT = 26.0;
    private static final double ROW_GAP = 2.0;
    private static final double SLOT_HEIGHT = ROW_HEIGHT + ROW_GAP;
    private static final double HEADER_HEIGHT = 20.0;
    private static final double DRAWER_INSET = 8.0;
    private static final double DRAWER_PADDING = 6.0;
    /** 展开时逐行错峰出现的间隔，让整列像被铺开而不是整块闪现。 */
    private static final long ROW_STAGGER_MILLIS = 45L;
    private static final long ROW_FADE_MILLIS = 190L;

    private boolean open;
    /** 抽屉滑入/滑出的进度，0 为完全收起、1 为完全展开。 */
    private double openAnimation;

    private final RoundedRectWidget drawer = new RoundedRectWidget();
    private final QueueList list = new QueueList();
    private LabelWidget emptyHint;

    /** 界面上的行，顺序与用户队列一致。 */
    private final List<QueueRow> rows = new ArrayList<>();
    /** 已经离开队列、正在淡出的行；它们不参与排位，只是别让列表凭空少一行。 */
    private final List<QueueRow> fadingRows = new ArrayList<>();

    private QueueRow draggingRow;
    private double dragGrabOffset;

    /** 平滑线性进度，让起步与收尾都不生硬。 */
    private static double smooth(double value) {
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }

    public boolean isOpen() {
        return this.open;
    }

    /** 收起动画播完前仍然需要渲染，否则关闭会变成瞬间消失。 */
    public boolean isVisible() {
        return this.open || this.openAnimation > .004;
    }

    public void toggle() {
        this.setOpen(!this.open);
    }

    public void setOpen(boolean open) {
        if (this.open == open) return;
        this.open = open;

        if (!open) {
            this.cancelDrag();
            return;
        }

        this.list.scrollTarget = 0.0;
        this.list.scrollActual = 0.0;
        long now = System.currentTimeMillis();
        for (int slot = 0; slot < this.rows.size(); slot++) {
            QueueRow row = this.rows.get(slot);
            // 起始时间放在未来，靠后的行就会自己晚一点出现。
            row.appearedAt = now + slot * ROW_STAGGER_MILLIS;
            row.animation.setPosition(slot * SLOT_HEIGHT + 8.0);
        }
    }

    @Override
    public void onInit() {
        this.getChildren().clear();
        this.list.getChildren().clear();
        this.rows.clear();
        this.fadingRows.clear();
        this.draggingRow = null;
        this.list.scrollTarget = 0.0;
        this.list.scrollActual = 0.0;

        this.addChild(this.drawer);
        this.drawer
                .setShouldOverrideMouseCursor(false)
                .setBeforeRenderCallback(() -> this.drawer
                        .setRadius(9.0)
                        .setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND)));
        // 抽屉盖住了下面的歌曲列表，落在空白处的点击必须由它吃掉，不能穿透触发播放。
        this.drawer.setOnClickCallback((relativeX, relativeY, mouseButton) -> true);

        LabelWidget title = new LabelWidget(() -> this.rows.isEmpty()
                ? "下一首播放"
                : ("下一首播放 · " + this.rows.size() + " 首"), FontManager.pf12bold);
        this.drawer.addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> title
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setPosition(DRAWER_PADDING + 2.0,
                        DRAWER_PADDING + Math.max(0.0, (HEADER_HEIGHT - title.getHeight()) * .5)));

        LabelWidget hint = new LabelWidget("拖动可调整顺序", FontManager.pf10bold);
        this.drawer.addChild(hint);
        hint.setClickable(false);
        hint.setBeforeRenderCallback(() -> hint
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setPosition(title.getRelativeX() + title.getWidth() + 8.0,
                        DRAWER_PADDING + Math.max(0.0, (HEADER_HEIGHT - hint.getHeight()) * .5)));

        RoundedRectWidget close = new RoundedRectWidget();
        this.drawer.addChild(close);
        close.setShouldOverrideMouseCursor(true);
        close.setBeforeRenderCallback(() -> close
                .setBounds(16.0, 14.0)
                .setPosition(this.drawer.getWidth() - DRAWER_PADDING - 16.0,
                        DRAWER_PADDING + Math.max(0.0, (HEADER_HEIGHT - 14.0) * .5))
                .setRadius(3.0)
                .setColor(close.isHovering()
                        ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                        : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND)));
        close.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton == 0) this.setOpen(false);
            return true;
        });

        LabelWidget closeText = new LabelWidget("×", FontManager.pf12bold);
        close.addChild(closeText);
        closeText.setClickable(false);
        closeText.setBeforeRenderCallback(() -> closeText
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .center());

        this.drawer.addChild(this.list);

        this.emptyHint = new LabelWidget("队列为空 · 点击歌曲右侧的 » 加入", FontManager.pf10bold);
        this.drawer.addChild(this.emptyHint);
        this.emptyHint.setClickable(false);
        this.emptyHint.setBeforeRenderCallback(() -> this.emptyHint
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setPosition(this.list.getRelativeX() + Math.max(0.0, (this.list.getWidth() - this.emptyHint.getWidth()) * .5),
                        this.list.getRelativeY() + Math.max(0.0, (this.list.getHeight() - this.emptyHint.getHeight()) * .5)));
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        this.openAnimation = Interpolations.interpolate(this.openAnimation, this.open ? 1.0 : 0.0, .32);
        if (this.open && this.openAnimation > .996) this.openAnimation = 1.0;
        if (!this.open && this.openAnimation < .004) this.openAnimation = 0.0;

        if (!this.isVisible()) {
            this.drawer.setHidden(true);
            this.cancelDrag();
            return;
        }
        this.drawer.setHidden(false);

        this.syncRows();

        double progress = smooth(this.openAnimation);
        double panelWidth = this.getWidth();
        double panelHeight = this.getHeight();
        double drawerWidth = Math.max(150.0, panelWidth - DRAWER_INSET * 2.0);

        int rowCount = this.rows.size();
        double contentHeight = Math.max(0.0, rowCount * SLOT_HEIGHT - ROW_GAP);
        double maxListHeight = Math.max(SLOT_HEIGHT, panelHeight * .46);
        double listHeight = rowCount == 0
                ? 26.0
                : Math.max(SLOT_HEIGHT, Math.min(maxListHeight, contentHeight));
        double drawerHeight = DRAWER_PADDING * 2.0 + HEADER_HEIGHT + listHeight;
        // 收起时整块滑到内容区下沿之外，由内容区的裁剪吃掉，看起来就是抽屉缩回底部。
        double drawerBottom = panelHeight - 4.0 + (1.0 - progress) * (drawerHeight + 8.0);

        this.drawer
                .setBounds(drawerWidth, drawerHeight)
                .setPosition((panelWidth - drawerWidth) * .5, drawerBottom - drawerHeight)
                .setAlpha((float) (.99f * (.25 + .75 * progress)));

        this.list
                .setBounds(Math.max(0.0, drawerWidth - DRAWER_PADDING * 2.0), listHeight)
                .setPosition(DRAWER_PADDING, DRAWER_PADDING + HEADER_HEIGHT);
        this.list.contentHeight = contentHeight;
        this.emptyHint.setAlpha(rowCount == 0 ? 1.0f : 0.0f);

        this.updateDrag(mouseX, mouseY);

        double frameDelta = RenderSystem.getFrameDeltaTime();
        long now = System.currentTimeMillis();

        for (int slot = 0; slot < rowCount; slot++) {
            QueueRow row = this.rows.get(slot);
            row.slot = slot;

            if (row == this.draggingRow) {
                // 拖动中的行 1:1 跟着鼠标，松手后弹簧从当前位置接着走，不会跳。
                row.animation.setPosition(row.dragY);
            } else {
                row.animation.setTargetPosition(slot * SLOT_HEIGHT);
            }
            row.animation.update(frameDelta * .0125);
            row.lift = Interpolations.interpolate(row.lift, row == this.draggingRow ? 1.0 : 0.0, .38);
            this.applyRowLayout(row, now);
        }

        for (int index = this.fadingRows.size() - 1; index >= 0; index--) {
            QueueRow row = this.fadingRows.get(index);
            row.fade = Interpolations.interpolate(row.fade, 0.0, .55);
            row.lift = Interpolations.interpolate(row.lift, 0.0, .38);
            row.animation.update(frameDelta * .0125);
            this.applyRowLayout(row, now);

            if (row.fade <= .02) {
                this.list.getChildren().remove(row);
                this.fadingRows.remove(index);
            }
        }
    }

    /** 把一行搬到它这一帧该在的位置，并处理视口内外的可见性与命中框。 */
    private void applyRowLayout(QueueRow row, long now) {
        double y = row.animation.getCurrentPosition() - this.list.scrollActual;
        row.setBounds(this.list.getWidth(), ROW_HEIGHT).setPosition(0.0, y);

        boolean offscreen = y + ROW_HEIGHT < -.5 || y > this.list.getHeight() + .5;
        boolean fullyInside = y >= -.5 && y + ROW_HEIGHT <= this.list.getHeight() + .5;
        // 完全滚出视口的行必须真正隐藏：只被裁掉画面却留着命中框，会把抽屉外的点击吃掉。
        row.setHidden(offscreen);
        row.setClickable(fullyInside && !row.removing);

        double entrance = row.removing ? 1.0 : Math.max(0.0, Math.min(1.0,
                (now - row.appearedAt) / (double) ROW_FADE_MILLIS));
        row.setAlpha((float) (row.fade * smooth(entrance)));
    }

    /** 用队列快照对齐界面上的行，尽量复用同一个行对象，动画状态才不会被重建打断。 */
    private void syncRows() {
        List<Music> queued = CloudMusic.getQueuedNextSongs();

        // 拖动途中队列长度变了（比如这首歌播完了），继续按旧下标搬动就会错位，直接放手最安全。
        if (this.draggingRow != null && queued.size() != this.rows.size()) {
            this.cancelDrag();
        }

        if (this.matchesRows(queued)) return;

        List<QueueRow> reusable = new ArrayList<>(this.rows);
        List<QueueRow> next = new ArrayList<>(queued.size());
        long now = System.currentTimeMillis();

        for (int index = 0; index < queued.size(); index++) {
            Music song = queued.get(index);
            QueueRow row = takeMatching(reusable, song);

            if (row == null) {
                row = new QueueRow(song);
                row.appearedAt = now;
                // 新行从下方稍微滑上来，插入时有一个明确的"进来了"的动作。
                row.animation.setPosition(index * SLOT_HEIGHT + SLOT_HEIGHT * .55);
                this.list.addChild(row);
            }

            row.slot = index;
            next.add(row);
        }

        for (QueueRow leftover : reusable) {
            leftover.removing = true;
            leftover.setClickable(false);
            if (leftover == this.draggingRow) this.cancelDrag();
            if (!this.fadingRows.contains(leftover)) this.fadingRows.add(leftover);
        }

        this.rows.clear();
        this.rows.addAll(next);
    }

    private boolean matchesRows(List<Music> queued) {
        if (queued.size() != this.rows.size()) return false;
        for (int index = 0; index < queued.size(); index++) {
            QueueRow row = this.rows.get(index);
            if (row.removing) return false;
            Music song = queued.get(index);
            if (row.music != song && (row.music == null || !row.music.equals(song))) return false;
        }
        return true;
    }

    private static QueueRow takeMatching(List<QueueRow> pool, Music song) {
        for (int index = 0; index < pool.size(); index++) {
            if (pool.get(index).music == song) return pool.remove(index);
        }
        for (int index = 0; index < pool.size(); index++) {
            QueueRow row = pool.get(index);
            if (row.music != null && song != null && row.music.equals(song)) return pool.remove(index);
        }
        return null;
    }

    private void beginDrag(QueueRow row, double grabOffsetY) {
        if (row == null || row.removing || !this.rows.contains(row)) return;
        this.draggingRow = row;
        this.dragGrabOffset = grabOffsetY;
        row.dragY = row.animation.getCurrentPosition();
        // 子组件顺序就是渲染顺序：把被拖的行挪到最后，它才会浮在其它行之上。
        this.list.getChildren().remove(row);
        this.list.getChildren().add(row);
    }

    private void updateDrag(double mouseX, double mouseY) {
        QueueRow row = this.draggingRow;
        if (row == null) return;

        if (!Mouse.isButtonDown(0) || row.removing || !this.rows.contains(row)) {
            this.cancelDrag();
            return;
        }

        int rowCount = this.rows.size();
        double maxY = Math.max(0.0, (rowCount - 1) * SLOT_HEIGHT);
        double desired = mouseY - this.list.getY() + this.list.scrollActual - this.dragGrabOffset;
        row.dragY = Math.max(0.0, Math.min(maxY, desired));

        // 拖到列表上下边缘时自动滚动，长队列才能一路拖到头或拖到底。
        double edge = 10.0;
        if (mouseY < this.list.getY() + edge) {
            this.list.scrollTarget -= 2.5;
        } else if (mouseY > this.list.getY() + this.list.getHeight() - edge) {
            this.list.scrollTarget += 2.5;
        }

        int from = this.rows.indexOf(row);
        int target = (int) Math.round(row.dragY / SLOT_HEIGHT);
        target = Math.max(0, Math.min(rowCount - 1, target));

        if (target != from && CloudMusic.moveQueuedNext(from, target)) {
            this.rows.remove(from);
            this.rows.add(target, row);
        }
    }

    private void cancelDrag() {
        this.draggingRow = null;
    }

    /**
     * 抽屉范围内的点击由它自己处理，并且不再往下传。
     * @return 是否吃掉了这次点击
     */
    public boolean consumeClick(double mouseX, double mouseY, int mouseButton) {
        if (!this.open || this.drawer.isHidden()) return false;
        if (!this.isHovered(mouseX, mouseY, this.drawer.getX(), this.drawer.getY(),
                this.drawer.getWidth(), this.drawer.getHeight())) {
            return false;
        }
        this.onMouseClickReceived(mouseX, mouseY, mouseButton);
        return true;
    }

    /** 滚轮停在抽屉里时，下面的歌单不能跟着一起滚。 */
    public boolean capturesWheel(double mouseX, double mouseY) {
        if (!this.open || this.drawer.isHidden()) return false;
        return this.isHovered(mouseX, mouseY, this.drawer.getX(), this.drawer.getY(),
                this.drawer.getWidth(), this.drawer.getHeight());
    }

    private static boolean loadCover(Music music) {
        TextureManager textureManager = TextureManager.getInstance();
        Location coverLocation = music.getSmallCoverLocation();
        if (textureManager.getTexture(coverLocation) != null) return true;

        String url = music.getCoverUrl(64);
        if (url == null || url.trim().isEmpty()) {
            // GD 封面仍在异步预取：下一帧再试，不要对空串发起无效下载。
            return false;
        }
        Textures.downloadTextureAndLoadAsync(url, coverLocation);
        return true;
    }

    /**
     * 队列列表容器：只管滚动、裁剪和滚轮，不自动排列子组件——行的位置由
     * {@link PlayQueuePanel#onRender(double, double)} 决定。
     */
    private final class QueueList extends AbstractWidget<QueueList> {

        private double scrollTarget;
        private double scrollActual;
        private double contentHeight;

        @Override
        public void onRender(double mouseX, double mouseY) {
            double max = Math.max(0.0, this.contentHeight - this.getHeight());
            this.scrollTarget = Math.max(0.0, Math.min(this.scrollTarget, max));
            this.scrollActual = Interpolations.interpolate(this.scrollActual, this.scrollTarget, 1.0);
            this.scrollActual = Math.max(0.0, Math.min(this.scrollActual, max));
        }

        @Override
        public boolean onDWheel(double mouseX, double mouseY, int dWheel) {
            if (dWheel > 0) this.scrollTarget -= SLOT_HEIGHT;
            else if (dWheel < 0) this.scrollTarget += SLOT_HEIGHT;

            double max = Math.max(0.0, this.contentHeight - this.getHeight());
            this.scrollTarget = Math.max(0.0, Math.min(this.scrollTarget, max));
            return true;
        }

        @Override
        public boolean canBeScrolled() {
            return true;
        }

        @Override
        protected boolean shouldClickChildren(double mouseX, double mouseY) {
            return this.testHovered(mouseX, mouseY);
        }

        @Override
        protected void beforeRenderChildren(double mouseX, double mouseY) {
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
    }

    /** 队列里的一行：只负责自己的外观，位置与透明度由父面板每帧写入。 */
    private final class QueueRow extends RoundedRectWidget {

        private final Music music;
        private final SpringAnimation animation = new SpringAnimation(new SpringParams(.9, 15.0, 90.0, false));

        private int slot;
        /** 被拖起来的程度，0 为静止、1 为完全提起。 */
        private double lift;
        /** 拖动中这一帧应该在的内容坐标 Y。 */
        private double dragY;
        private double fade = 1.0;
        private boolean removing;
        private long appearedAt;
        private boolean coverLoaded;

        private QueueRow(Music music) {
            this.music = music;
            this.setShouldOverrideMouseCursor(true);

            this.setBeforeRenderCallback(() -> {
                if (!this.coverLoaded) this.coverLoaded = loadCover(this.music);

                boolean dragging = this == PlayQueuePanel.this.draggingRow;
                this.setRadius(4.0);
                this.setColor(dragging
                        ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                        : (this.isHovering()
                                ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                                : NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND)));
            });

            // 拖起来的一行略微放大浮起，放手后 lift 归零就自己恢复原样。
            this.setTransformations(() -> {
                if (this.lift <= .002) return;
                this.scaleAtPos(this.getX() + this.getWidth() * .5,
                        this.getY() + this.getHeight() * .5, 1.0 + .045 * this.lift);
            });

            this.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                if (mouseButton != 0) return true;
                PlayQueuePanel.this.beginDrag(this, relativeY);
                return true;
            });

            LabelWidget index = new LabelWidget(() -> String.valueOf(this.slot + 1), FontManager.pf10bold);
            this.addChild(index);
            index.setClickable(false);
            index.setBeforeRenderCallback(() -> index
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                    .setPosition(Math.max(2.0, 14.0 - index.getWidth()),
                            (ROW_HEIGHT - index.getHeight()) * .5));

            RoundedImageWidget cover = new RoundedImageWidget(this.music.getSmallCoverLocation(), 0, 0, 0, 0);
            this.addChild(cover);
            cover.setClickable(false);
            cover.fadeIn().setLinearFilter(true);
            cover.setBeforeRenderCallback(() -> cover
                    .setBounds(20.0, 20.0)
                    .setRadius(2.0)
                    .setPosition(18.0, (ROW_HEIGHT - 20.0) * .5));

            LabelWidget name = new LabelWidget(this.music.getName(), FontManager.pf12bold);
            this.addChild(name);
            name.setClickable(false);
            name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            name.setBeforeRenderCallback(() -> name
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                    .setMaxWidth(Math.max(10.0, this.getWidth() - 42.0 - 24.0))
                    .setPosition(42.0, 4.0));

            LabelWidget artist = new LabelWidget(this.music.getArtistsName(), FontManager.pf10bold);
            this.addChild(artist);
            artist.setClickable(false);
            artist.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            artist.setBeforeRenderCallback(() -> artist
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                    .setMaxWidth(Math.max(10.0, this.getWidth() - 42.0 - 24.0))
                    .setPosition(42.0, ROW_HEIGHT - artist.getHeight() - 4.0));

            DragHandleWidget handle = new DragHandleWidget();
            this.addChild(handle);
            handle.setClickable(false);
            handle.setBeforeRenderCallback(() -> handle
                    .setBounds(10.0, 7.0)
                    .setPosition(Math.max(0.0, this.getWidth() - 16.0), (ROW_HEIGHT - 7.0) * .5)
                    .setColor(NCMScreen.getColor(this == PlayQueuePanel.this.draggingRow
                            ? NCMScreen.ColorType.PRIMARY_TEXT
                            : NCMScreen.ColorType.SECONDARY_TEXT)));
        }
    }

    /** 右侧的拖动把手：三条短横线，纯几何绘制，不依赖任何图标字体。 */
    private static final class DragHandleWidget extends AbstractWidget<DragHandleWidget> {

        @Override
        public void onRender(double mouseX, double mouseY) {
            int color = this.getHexColor();
            for (int bar = 0; bar < 3; bar++) {
                this.roundedRect(this.getX(), this.getY() + bar * 3.0, this.getWidth(), 1.0, .5, color);
            }
        }
    }
}
