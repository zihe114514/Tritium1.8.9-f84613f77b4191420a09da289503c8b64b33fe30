package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;

/**
 * Compact modal confirmation dialog used for destructive player actions.
 * It deliberately keeps the pending operation untouched until the user confirms.
 */
public final class ConfirmationOverlay extends NCMPanel {

    /** 说明最多显示的行数；再长的说明会在最后一行截断，卡片不会长到顶出播放器。 */
    private static final int MAX_MESSAGE_LINES = 8;
    /** 说明文字距卡片左右两边的留白。 */
    private static final double MESSAGE_INSET = 28.0;
    /** 说明区域的上沿，上面是图标与标题。 */
    private static final double MESSAGE_TOP = 50.0;
    /** 分隔线到卡片底部的高度，即按钮区。 */
    private static final double ACTIONS_HEIGHT = 43.0;

    private final String title;
    private final String message;
    private final String confirmText;
    private final Runnable onConfirm;
    private boolean closing;
    private boolean resolved;

    /** 按当前卡片宽度换行后的说明文字。 */
    private String[] messageLines = new String[0];
    /** 上一次换行用的宽度；宽度没变就不重复换行（换行要逐字累加字形宽度）。 */
    private double wrappedForWidth = -1.0;
    private double messageBlockHeight = 28.0;
    /** 上一次换行时字形是否已全部就绪；没就绪算出来的宽度是 0，必须下一帧重算。 */
    private boolean wrappedWithLoadedGlyphs;
    /** 标题是否已经降到小一号字体；只在需要变化时才换字体。 */
    private boolean titleCompact;

    public ConfirmationOverlay(String title, String message, String confirmText, Runnable onConfirm) {
        this.title = title == null ? "请确认操作" : title;
        this.message = message == null ? "此操作无法直接撤销。" : message;
        this.confirmText = confirmText == null ? "确认" : confirmText;
        this.onConfirm = onConfirm;
    }

    public boolean shouldClose() {
        return closing;
    }

    public void cancel() {
        resolve(false);
    }

    @Override
    public void onInit() {
        RectWidget mask = new RectWidget();
        addChild(mask);
        mask.setClickable(false);
        mask.setColor(0x000000).setAlpha(.48f);
        mask.setBeforeRenderCallback(() -> mask.setMargin(0));

        // Clicking outside dismisses the dialog and never reaches the panel beneath it.
        setOnClickCallback((x, y, button) -> {
            if (button == 0) {
                cancel();
                return true;
            }
            return false;
        });

        Panel dialog = new Panel();
        addChild(dialog);
        dialog.setOnClickCallback((x, y, button) -> true);
        dialog.setBeforeRenderCallback(() -> {
            // The old 174-unit card left a large, visually empty middle section at
            // normal GUI scale. Keep destructive confirmations compact and arrange
            // all information in one clear header / message / actions flow.
            double width = Math.max(248, Math.min(340, getWidth() - 32));
            // 先按这个宽度把说明换行，再由行数决定卡片高度：隐私授权类的说明有好几行，
            // 固定高度只能把它裁成一行，用户根本看不到自己同意了什么。
            wrapMessage(Math.max(1, width - MESSAGE_INSET * 2));
            double height = Math.max(124, Math.min(Math.max(124, getHeight() - 28),
                    MESSAGE_TOP + messageBlockHeight + 12 + ACTIONS_HEIGHT));
            dialog.setBounds(width, height);
            dialog.center();
        });

        RoundedRectWidget background = new RoundedRectWidget();
        dialog.addChild(background);
        background.setClickable(false);
        background.setRadius(11);
        background.setColor(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND));
        background.setBeforeRenderCallback(() -> background.setMargin(0));

        RoundedRectWidget warningBadge = new RoundedRectWidget();
        dialog.addChild(warningBadge);
        warningBadge.setClickable(false);
        warningBadge.setRadius(12);
        warningBadge.setColor(0xD87857);
        warningBadge.setBeforeRenderCallback(() -> warningBadge.setBounds(18, 16, 24, 24));

        // Fontello's warning glyph is bundled with the player and avoids the malformed
        // half-width exclamation fallback that was visible on early Java 8 runtimes.
        LabelWidget warningIcon = new LabelWidget(FontelloIcons.WARNING, FontManager.fontello18);
        dialog.addChild(warningIcon);
        warningIcon.setClickable(false);
        warningIcon.setBeforeRenderCallback(() -> {
            warningIcon.setColor(0xFFFFFF);
            warningIcon.setPosition(30 - warningIcon.getWidth() * .5, 28 - warningIcon.getHeight() * .5 + 1);
        });

        LabelWidget titleLabel = new LabelWidget(title, FontManager.pf18bold);
        dialog.addChild(titleLabel);
        titleLabel.setClickable(false);
        titleLabel.setBeforeRenderCallback(() -> {
            double maxWidth = Math.max(1, dialog.getWidth() - 70);
            // 长标题降一号字，而不是直接截断：确认框的标题就是这次操作本身，看不全等于没提示。
            boolean compact = FontManager.pf18bold != null
                    && FontManager.pf18bold.getStringWidthD(title) > maxWidth;
            if (compact != titleCompact) {
                titleCompact = compact;
                titleLabel.setFont(compact ? FontManager.pf14bold : FontManager.pf18bold);
            }
            titleLabel.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            titleLabel.setMaxWidth(maxWidth);
            titleLabel.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            titleLabel.setPosition(52, 28 - titleLabel.getHeight() * .5);
        });

        RoundedRectWidget messageSurface = new RoundedRectWidget();
        dialog.addChild(messageSurface);
        messageSurface.setClickable(false);
        messageSurface.setRadius(6);
        messageSurface.setBeforeRenderCallback(() -> {
            messageSurface.setBounds(18, MESSAGE_TOP, Math.max(1, dialog.getWidth() - 36), messageBlockHeight);
            messageSurface.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });

        // 每行一个 Label：这个字体渲染器没有多行绘制，换行结果由 wrapMessage 每次布局时算好。
        // 用不到的行取空字符串，而不是 setHidden——被隐藏的组件不再执行自己的回调，就再也显示不回来。
        for (int index = 0; index < MAX_MESSAGE_LINES; index++) {
            final int lineIndex = index;
            LabelWidget messageLabel = new LabelWidget(
                    () -> lineIndex < messageLines.length ? messageLines[lineIndex] : "", FontManager.pf12);
            dialog.addChild(messageLabel);
            messageLabel.setClickable(false);
            messageLabel.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            messageLabel.setBeforeRenderCallback(() -> {
                messageLabel.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                messageLabel.setMaxWidth(Math.max(1, dialog.getWidth() - MESSAGE_INSET * 2));
                messageLabel.setPosition(MESSAGE_INSET, MESSAGE_TOP + 6 + lineIndex * lineHeight());
            });
        }

        RectWidget divider = new RectWidget();
        dialog.addChild(divider);
        divider.setClickable(false);
        divider.setBeforeRenderCallback(() -> {
            divider.setBounds(18, dialog.getHeight() - ACTIONS_HEIGHT, Math.max(1, dialog.getWidth() - 36), .5);
            divider.setColor(NCMScreen.getColor(NCMScreen.ColorType.BORDER));
        });

        RoundedButtonWidget cancelButton = new RoundedButtonWidget("取消", FontManager.pf12bold);
        dialog.addChild(cancelButton);
        cancelButton.setRadius(6);
        cancelButton.setBeforeRenderCallback(() -> {
            double totalWidth = Math.max(1, dialog.getWidth() - 36);
            double gap = 8;
            double confirmWidth = Math.max(96, Math.min(126, totalWidth * .54));
            double cancelWidth = Math.max(1, totalWidth - gap - confirmWidth);
            cancelButton.setBounds(cancelWidth, 26);
            cancelButton.setPosition(18, dialog.getHeight() - 34);
            cancelButton.setColor(cancelButton.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            cancelButton.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        cancelButton.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            cancel();
            return true;
        });

        RoundedButtonWidget confirmButton = new RoundedButtonWidget(confirmText, FontManager.pf12bold);
        dialog.addChild(confirmButton);
        confirmButton.setRadius(6);
        confirmButton.setBeforeRenderCallback(() -> {
            double totalWidth = Math.max(1, dialog.getWidth() - 36);
            double gap = 8;
            double confirmWidth = Math.max(96, Math.min(126, totalWidth * .54));
            double cancelWidth = Math.max(1, totalWidth - gap - confirmWidth);
            confirmButton.setBounds(confirmWidth, 26);
            confirmButton.setPosition(18 + cancelWidth + gap, dialog.getHeight() - 34);
            confirmButton.setColor(confirmButton.isHovering() ? 0xEC665D : 0xD94F4D);
            confirmButton.setTextColor(0xFFFFFF);
        });
        confirmButton.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            resolve(true);
            return true;
        });
    }

    private static double lineHeight() {
        return (FontManager.pf12 == null ? 9.0 : FontManager.pf12.getFontHeight()) + 3.0;
    }

    /**
     * 把说明按给定的正文宽度换行，并算出说明区域需要的高度。
     * 宽度没变时直接复用上一次的结果。
     */
    private void wrapMessage(double contentWidth) {
        // 字形是按需上传的，没上传的字宽度为 0——那样换行会把整段算成一行。项目里其它需要测量
        // 文字的地方（LyricLine、HomePanel 的歌单标题）也是这个套路：字形没齐之前每帧重算，
        // 照常绘制会把字形逐步上传，齐了之后再按宽度缓存结果。
        boolean glyphsReady = FontManager.pf12 != null && FontManager.pf12.areGlyphsLoaded(message);
        if (!wrappedWithLoadedGlyphs || Math.abs(contentWidth - wrappedForWidth) >= .5) {
            wrappedForWidth = contentWidth;
            wrappedWithLoadedGlyphs = glyphsReady;
            String[] lines;
            try {
                lines = FontManager.pf12.fitWidth(message, contentWidth);
            } catch (Throwable ignored) {
                // 换行只是排版，任何异常都不该让一个需要用户确认的弹窗打不开。
                lines = new String[]{message};
            }
            if (lines == null || lines.length == 0) lines = new String[]{message};
            if (lines.length > MAX_MESSAGE_LINES) {
                String[] limited = new String[MAX_MESSAGE_LINES];
                System.arraycopy(lines, 0, limited, 0, MAX_MESSAGE_LINES);
                limited[MAX_MESSAGE_LINES - 1] = FontManager.pf12.trim(
                        limited[MAX_MESSAGE_LINES - 1], Math.max(1, contentWidth - 8)) + "…";
                lines = limited;
            }
            messageLines = lines;
        }
        messageBlockHeight = Math.max(28.0, messageLines.length * lineHeight() + 12.0);
    }

    private void resolve(boolean confirmed) {
        if (resolved) return;
        resolved = true;
        closing = true;
        if (confirmed && onConfirm != null) {
            onConfirm.run();
        }
    }
}
