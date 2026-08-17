package tritium.screens.ncm;

import tritium.management.FontManager;
import tritium.rendering.ui.container.Panel;
import tritium.rendering.ui.widgets.LabelWidget;
import tritium.rendering.ui.widgets.RectWidget;
import tritium.rendering.ui.widgets.RoundedButtonWidget;
import tritium.rendering.ui.widgets.RoundedRectWidget;

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
            double width = Math.max(248, Math.min(356, getWidth() - 32));
            double height = Math.max(154, Math.min(174, getHeight() - 28));
            dialog.setBounds(width, height);
            dialog.center();
        });

        RoundedRectWidget background = new RoundedRectWidget();
        dialog.addChild(background);
        background.setClickable(false);
        background.setRadius(10);
        background.setColor(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND));
        background.setBeforeRenderCallback(() -> background.setMargin(0));

        RoundedRectWidget warningBadge = new RoundedRectWidget();
        dialog.addChild(warningBadge);
        warningBadge.setClickable(false);
        warningBadge.setRadius(10);
        warningBadge.setColor(0xD87857);
        warningBadge.setBeforeRenderCallback(() -> warningBadge.setBounds(20, 18, 20, 20));

        LabelWidget warningMark = new LabelWidget("!", FontManager.pf14bold);
        dialog.addChild(warningMark);
        warningMark.setClickable(false);
        warningMark.setBeforeRenderCallback(() -> {
            warningMark.setColor(0xFFFFFF);
            warningMark.setPosition(27, 22);
        });

        LabelWidget titleLabel = new LabelWidget(title, FontManager.pf18bold);
        dialog.addChild(titleLabel);
        titleLabel.setClickable(false);
        titleLabel.setBeforeRenderCallback(() -> {
            titleLabel.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            titleLabel.setPosition(50, 18);
            titleLabel.setMaxWidth(dialog.getWidth() - 70);
            titleLabel.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
        });

        LabelWidget messageLabel = new LabelWidget(message, FontManager.pf12);
        dialog.addChild(messageLabel);
        messageLabel.setClickable(false);
        messageLabel.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
        messageLabel.setBeforeRenderCallback(() -> {
            messageLabel.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            messageLabel.setPosition(20, 53);
            messageLabel.setMaxWidth(dialog.getWidth() - 40);
        });

        RoundedButtonWidget cancelButton = new RoundedButtonWidget("取消", FontManager.pf12bold);
        dialog.addChild(cancelButton);
        cancelButton.setRadius(6);
        cancelButton.setBeforeRenderCallback(() -> {
            double buttonWidth = Math.max(76, Math.min(104, (dialog.getWidth() - 56) * .38));
            cancelButton.setBounds(buttonWidth, 29);
            cancelButton.setPosition(dialog.getWidth() - buttonWidth * 2 - 28, dialog.getHeight() - 44);
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
            double buttonWidth = Math.max(96, Math.min(128, (dialog.getWidth() - 56) * .52));
            confirmButton.setBounds(buttonWidth, 29);
            confirmButton.setPosition(dialog.getWidth() - buttonWidth - 20, dialog.getHeight() - 44);
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
