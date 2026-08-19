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

    private final String title;
    private final String message;
    private final String confirmText;
    private final Runnable onConfirm;
    private boolean closing;
    private boolean resolved;

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
            double height = Math.max(124, Math.min(132, getHeight() - 28));
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
            titleLabel.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            titleLabel.setPosition(52, 19);
            titleLabel.setMaxWidth(dialog.getWidth() - 70);
            titleLabel.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
        });

        RoundedRectWidget messageSurface = new RoundedRectWidget();
        dialog.addChild(messageSurface);
        messageSurface.setClickable(false);
        messageSurface.setRadius(6);
        messageSurface.setBeforeRenderCallback(() -> {
            messageSurface.setBounds(18, 50, Math.max(1, dialog.getWidth() - 36), 28);
            messageSurface.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });

        LabelWidget messageLabel = new LabelWidget(message, FontManager.pf12);
        dialog.addChild(messageLabel);
        messageLabel.setClickable(false);
        messageLabel.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
        messageLabel.setBeforeRenderCallback(() -> {
            messageLabel.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            messageLabel.setPosition(28, 60);
            messageLabel.setMaxWidth(Math.max(1, dialog.getWidth() - 56));
        });

        RectWidget divider = new RectWidget();
        dialog.addChild(divider);
        divider.setClickable(false);
        divider.setBeforeRenderCallback(() -> {
            divider.setBounds(18, dialog.getHeight() - 43, Math.max(1, dialog.getWidth() - 36), .5);
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

    private void resolve(boolean confirmed) {
        if (resolved) return;
        resolved = true;
        closing = true;
        if (confirmed && onConfirm != null) {
            onConfirm.run();
        }
    }
}
