package tritium.screens.ncm;

import top.fpsmaster.music.QrCode;
import top.fpsmaster.music.QrLoginState;
import tritium.management.FontManager;
import tritium.ncm.OptionsUtil;
import tritium.ncm.music.CadenceMusicService;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.MusicPlatform;
import tritium.ncm.music.QRCodeGenerator;
import tritium.rendering.TextureManager;
import tritium.rendering.texture.DynamicTexture;
import tritium.rendering.ui.container.Panel;
import tritium.rendering.ui.widgets.LabelWidget;
import tritium.rendering.ui.widgets.RectWidget;
import tritium.rendering.ui.widgets.RoundedButtonWidget;
import tritium.rendering.ui.widgets.RoundedImageWidget;
import tritium.rendering.ui.widgets.RoundedRectWidget;
import tritium.utils.Location;
import tritium.utils.other.multithreading.MultiThreadingUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网易云 / QQ 音乐账号管理模态框。一级页面展示账号，二级页面负责二维码登录和退出。
 * 网络请求与二维码解码在后台执行，纹理提交回 Minecraft 主线程。
 */
public class AccountManagerOverlay extends NCMPanel {

    private static final Location NETEASE_QR = Location.of("tritium/textures/account/netease_qr.png");
    private static final Location QQ_QR = Location.of("tritium/textures/account/qq_qr.png");

    private final AtomicLong pageGeneration = new AtomicLong();
    private volatile boolean closing;
    private volatile MusicPlatform detailPlatform;
    private volatile String statusText = "";
    private volatile int statusColor = 0xAEB5C4;
    private volatile boolean requestRunning;

    @Override
    public void onInit() {
        showOverview();
    }

    public boolean shouldClose() {
        return closing;
    }

    public void dispose() {
        closing = true;
        pageGeneration.incrementAndGet();
    }

    public void handleEscape() {
        if (detailPlatform != null) {
            showOverview();
        } else {
            dispose();
        }
    }

    private void showOverview() {
        pageGeneration.incrementAndGet();
        detailPlatform = null;
        requestRunning = false;
        getChildren().clear();
        Panel dialog = createDialog(392, 286);

        addTitle(dialog, "账号管理", "分别管理网易云音乐与 QQ 音乐登录状态");
        addAccountCard(dialog, MusicPlatform.NETEASE, 66);
        addAccountCard(dialog, MusicPlatform.QQ, 128);

        LabelWidget hint = new LabelWidget("账号仅保存在本地配置中 · 点击卡片进入二级菜单", FontManager.pf12);
        dialog.addChild(hint);
        hint.setClickable(false);
        hint.setBeforeRenderCallback(() -> {
            hint.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            hint.setPosition(16, dialog.getHeight() - 28);
            hint.setMaxWidth(dialog.getWidth() - 32);
        });
    }

    private void addAccountCard(Panel dialog, MusicPlatform platform, double y) {
        RoundedButtonWidget card = new RoundedButtonWidget(
                () -> platform.getDisplayName() + "  ·  " + CadenceMusicService.getAccountName(platform) + "    ›",
                FontManager.pf14bold);
        dialog.addChild(card);
        card.setBounds(16, y, 360, 48);
        card.setRadius(7);
        card.setOnClickCallback((x, yy, button) -> {
            if (button != 0) return false;
            showDetail(platform);
            return true;
        });
        card.setBeforeRenderCallback(() -> {
            card.setBounds(Math.max(1, dialog.getWidth() - 32), 48);
            card.setPosition(16, y);
            card.setColor(card.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            card.setTextColor(CadenceMusicService.isLoggedIn(platform)
                    ? platform.getBrandColor()
                    : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
    }

    private void showDetail(MusicPlatform platform) {
        long token = pageGeneration.incrementAndGet();
        detailPlatform = platform;
        requestRunning = false;
        statusText = CadenceMusicService.isLoggedIn(platform) ? "当前账号已登录" : "正在获取登录二维码…";
        statusColor = CadenceMusicService.isLoggedIn(platform) ? 0x53C68C : 0xAEB5C4;
        getChildren().clear();
        Panel dialog = createDialog(420, 390);

        RoundedButtonWidget back = new RoundedButtonWidget("‹ 返回", FontManager.pf12bold);
        dialog.addChild(back);
        back.setBounds(14, 12, 64, 22);
        back.setRadius(5);
        back.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        back.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        back.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            showOverview();
            return true;
        });

        LabelWidget title = new LabelWidget(platform.getDisplayName() + "账号", FontManager.pf18bold);
        dialog.addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> {
            title.setColor(platform.getBrandColor());
            title.setPosition(90, 15);
        });

        RoundedRectWidget qrPlate = new RoundedRectWidget();
        dialog.addChild(qrPlate);
        qrPlate.setClickable(false);
        qrPlate.setRadius(8);
        qrPlate.setColor(0xF5F7FA);
        qrPlate.setBeforeRenderCallback(() -> {
            double size = Math.max(80, Math.min(136, dialog.getHeight() - 150));
            qrPlate.setBounds(size, size);
            qrPlate.setPosition(dialog.getWidth() * .5 - size * .5, 48);
            qrPlate.setHidden(CadenceMusicService.isLoggedIn(platform));
        });

        RoundedImageWidget qrImage = new RoundedImageWidget(() -> getQrLocation(platform), 0, 0, 120, 120);
        dialog.addChild(qrImage);
        qrImage.setClickable(false);
        qrImage.setRadius(4);
        qrImage.setLinearFilter(true);
        qrImage.setBeforeRenderCallback(() -> {
            double plateSize = Math.max(80, Math.min(136, dialog.getHeight() - 150));
            double imageSize = Math.max(64, plateSize - 16);
            qrImage.setBounds(imageSize, imageSize);
            qrImage.setPosition(dialog.getWidth() * .5 - imageSize * .5, 56);
            qrImage.setHidden(CadenceMusicService.isLoggedIn(platform));
        });

        LabelWidget account = new LabelWidget(
                () -> CadenceMusicService.isLoggedIn(platform)
                        ? "已登录：" + CadenceMusicService.getAccountName(platform)
                        : "请使用" + platform.getDisplayName() + "客户端扫码并确认",
                FontManager.pf14bold);
        dialog.addChild(account);
        account.setClickable(false);
        account.setBeforeRenderCallback(() -> {
            account.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            account.setMaxWidth(dialog.getWidth() - 36);
            double plateSize = Math.max(80, Math.min(136, dialog.getHeight() - 150));
            double accountY = CadenceMusicService.isLoggedIn(platform) ? 92 : 48 + plateSize + 12;
            account.setPosition(dialog.getWidth() * .5 - account.getWidth() * .5, accountY);
        });

        LabelWidget status = new LabelWidget(() -> statusText, FontManager.pf12bold);
        dialog.addChild(status);
        status.setClickable(false);
        status.setBeforeRenderCallback(() -> {
            status.setColor(statusColor);
            status.setMaxWidth(dialog.getWidth() - 36);
            double plateSize = Math.max(80, Math.min(136, dialog.getHeight() - 150));
            double accountY = CadenceMusicService.isLoggedIn(platform) ? 92 : 48 + plateSize + 12;
            status.setPosition(dialog.getWidth() * .5 - status.getWidth() * .5, accountY + 25);
        });

        RoundedButtonWidget primary = new RoundedButtonWidget(
                () -> CadenceMusicService.isLoggedIn(platform) ? "退出登录" : (requestRunning ? "等待扫码…" : "刷新二维码"),
                FontManager.pf12bold);
        dialog.addChild(primary);
        primary.setBounds(16, 286, 388, 30);
        primary.setRadius(7);
        primary.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            if (CadenceMusicService.isLoggedIn(platform)) {
                logout(platform);
            } else if (!requestRunning) {
                startQrLogin(platform, pageGeneration.get());
            }
            return true;
        });
        primary.setBeforeRenderCallback(() -> {
            primary.setBounds(Math.max(1, dialog.getWidth() - 32), 30);
            primary.setPosition(16, dialog.getHeight() - 48);
            primary.setColor(CadenceMusicService.isLoggedIn(platform)
                    ? 0xA94A52
                    : (primary.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT_HOVER) : platform.getBrandColor()));
            primary.setTextColor(0xFFFFFF);
        });

        if (!CadenceMusicService.isLoggedIn(platform)) {
            startQrLogin(platform, token);
        }
    }

    private Panel createDialog(double preferredWidth, double preferredHeight) {
        RectWidget mask = new RectWidget();
        addChild(mask);
        mask.setColor(0x000000).setAlpha(.48f);
        mask.setClickable(false);
        mask.setBeforeRenderCallback(() -> mask.setMargin(0));

        setOnClickCallback((x, y, button) -> {
            if (button == 0) dispose();
            return button == 0;
        });

        Panel dialog = new Panel();
        addChild(dialog);
        dialog.setOnClickCallback((x, y, button) -> true);
        dialog.setBeforeRenderCallback(() -> {
            double w = Math.max(1, Math.min(preferredWidth, getWidth() - 24));
            double h = Math.max(1, Math.min(preferredHeight, getHeight() - 24));
            dialog.setBounds(w, h);
            dialog.center();
        });

        RoundedRectWidget bg = new RoundedRectWidget();
        dialog.addChild(bg);
        bg.setClickable(false);
        bg.setRadius(10);
        bg.setColor(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND));
        bg.setBeforeRenderCallback(() -> bg.setMargin(0));
        return dialog;
    }

    private void addTitle(Panel dialog, String titleText, String subtitleText) {
        LabelWidget title = new LabelWidget(titleText, FontManager.pf18bold);
        dialog.addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setPosition(16, 14);
        });

        LabelWidget subtitle = new LabelWidget(subtitleText, FontManager.pf12);
        dialog.addChild(subtitle);
        subtitle.setClickable(false);
        subtitle.setBeforeRenderCallback(() -> {
            subtitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            subtitle.setPosition(16, 39);
        });
    }

    private void startQrLogin(MusicPlatform platform, long token) {
        if (requestRunning || closing || detailPlatform != platform || token != pageGeneration.get()) return;
        requestRunning = true;
        statusText = "正在获取登录二维码…";
        statusColor = 0xAEB5C4;

        MultiThreadingUtil.runAsync(() -> {
            try {
                QrCode qr = CadenceMusicService.createQrCode(platform);
                if (!isCurrent(platform, token)) return;
                loadQrTexture(platform, qr.getQrContent(), token);
                statusText = "等待扫码";

                while (isCurrent(platform, token)) {
                    QrLoginState state = CadenceMusicService.checkQrCode(platform, qr);
                    if (!isCurrent(platform, token)) return;
                    if (state == QrLoginState.SCANNED) {
                        statusText = "已扫码，请在手机上确认";
                        statusColor = platform.getBrandColor();
                    } else if (state == QrLoginState.CONFIRMED) {
                        statusText = "登录成功";
                        statusColor = 0x53C68C;
                        requestRunning = false;
                        if (platform == MusicPlatform.NETEASE) {
                            CloudMusic.loadNCM(OptionsUtil.getCookie());
                        } else {
                            CadenceMusicService.refreshQQAccountData();
                        }
                        MultiThreadingUtil.runOnMainThread(() -> {
                            if (isCurrent(platform, token)) NCMScreen.getInstance().markDirty();
                        });
                        return;
                    } else if (state == QrLoginState.EXPIRED) {
                        statusText = "二维码已过期，点击刷新";
                        statusColor = 0xF1767D;
                        requestRunning = false;
                        return;
                    } else if (state == QrLoginState.ERROR) {
                        statusText = "登录状态检查失败，点击重试";
                        statusColor = 0xF1767D;
                        requestRunning = false;
                        return;
                    }
                    Thread.sleep(2500L);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Throwable throwable) {
                if (isCurrent(platform, token)) {
                    statusText = "二维码获取失败：" + safeMessage(throwable);
                    statusColor = 0xF1767D;
                    requestRunning = false;
                }
            }
        });
    }

    private void logout(MusicPlatform platform) {
        long token = pageGeneration.incrementAndGet();
        requestRunning = true;
        statusText = "正在退出登录…";
        statusColor = 0xAEB5C4;
        MultiThreadingUtil.runAsync(() -> {
            try {
                CadenceMusicService.logout(platform);
                statusText = "已退出登录";
                statusColor = 0x53C68C;
            } catch (Throwable throwable) {
                statusText = "退出失败：" + safeMessage(throwable);
                statusColor = 0xF1767D;
            } finally {
                requestRunning = false;
                MultiThreadingUtil.runOnMainThread(() -> {
                    NCMScreen.getInstance().markDirty();
                    if (!closing && detailPlatform == platform && token == pageGeneration.get()) {
                        showDetail(platform);
                    }
                });
            }
        });
    }

    private void loadQrTexture(MusicPlatform platform, String content, long token) throws Exception {
        BufferedImage image;
        if (platform == MusicPlatform.QQ) {
            String encoded = content == null ? "" : content;
            int comma = encoded.indexOf(',');
            if (comma >= 0) encoded = encoded.substring(comma + 1);
            image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
        } else {
            image = QRCodeGenerator.generateQRCode(content, 128, 128);
        }
        if (image == null || !isCurrent(platform, token)) throw new IllegalStateException("二维码图片为空");
        final BufferedImage finalImage = image;
        MultiThreadingUtil.runOnMainThread(() -> {
            if (!isCurrent(platform, token)) return;
            TextureManager manager = TextureManager.getInstance();
            Location location = getQrLocation(platform);
            if (manager.getTexture(location) != null) manager.deleteTexture(location);
            manager.loadTexture(location, new DynamicTexture(finalImage));
        });
    }

    private boolean isCurrent(MusicPlatform platform, long token) {
        return !closing && detailPlatform == platform && pageGeneration.get() == token;
    }

    private Location getQrLocation(MusicPlatform platform) {
        return platform == MusicPlatform.QQ ? QQ_QR : NETEASE_QR;
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        return message == null || message.trim().isEmpty() ? "未知错误" : message;
    }
}


