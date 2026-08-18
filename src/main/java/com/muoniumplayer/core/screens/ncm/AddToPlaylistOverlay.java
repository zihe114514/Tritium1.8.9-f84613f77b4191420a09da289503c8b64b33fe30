package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.util.List;

/**
 * 加入歌单弹窗：展示请求中的状态，并根据网易云接口的 HTTP 状态与业务 code 给出真实结果反馈。
 */
public class AddToPlaylistOverlay extends NCMPanel {

    private static final int STATUS_PROCESSING_COLOR = 0xC4C9D4;
    private static final int STATUS_SUCCESS_COLOR = 0x53C68C;
    private static final int STATUS_ERROR_COLOR = 0xF1767D;

    public Music music;
    private boolean closing = false;
    private boolean submitting = false;
    private PlayList selectedPlaylist;
    private String statusMessage = "";
    private FeedbackState feedbackState = FeedbackState.IDLE;
    private long closeAfterMillis = -1L;

    private enum FeedbackState {
        IDLE,
        PROCESSING,
        SUCCESS,
        ALREADY_EXISTS,
        ERROR
    }

    public AddToPlaylistOverlay(Music music) {
        this.music = music;
    }

    public boolean shouldClose() {
        return closing;
    }

    @Override
    public void onInit() {
        this.setBeforeRenderCallback(() -> {
            if (!submitting && closeAfterMillis > 0L && System.currentTimeMillis() >= closeAfterMillis) {
                closing = true;
            }
        });

        // 半透明遮罩只负责弱化下方 UI。关闭逻辑由 overlay 根组件处理，避免吞掉歌单行点击。
        RectWidget mask = new RectWidget();
        this.addChild(mask);
        mask.setColor(0x000000).setAlpha(.45f);
        mask.setClickable(false);
        mask.setBeforeRenderCallback(() -> mask.setMargin(0));

        // 提交期间保留弹窗，防止用户误以为请求已结束，也避免离开后无法看到结果。
        this.setOnClickCallback((x, y, button) -> {
            if (button != 0) {
                return false;
            }
            if (submitting) {
                showStatus("正在加入「" + getPlaylistName(selectedPlaylist) + "」…", FeedbackState.PROCESSING);
                return true;
            }
            closing = true;
            return true;
        });

        Panel dialog = new Panel();
        this.addChild(dialog);
        dialog.setBeforeRenderCallback(() -> {
            double w = Math.min(360, this.getWidth() - 80);
            double h = Math.min(440, this.getHeight() - 80);
            dialog.setBounds(w, h);
            dialog.center();
        });

        RoundedRectWidget dialogBg = new RoundedRectWidget();
        dialog.addChild(dialogBg);
        dialogBg.setRadius(6);
        dialogBg.setColor(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND));
        dialogBg.setBeforeRenderCallback(() -> dialogBg.setMargin(0));

        LabelWidget lblTitle = new LabelWidget("加入歌单", FontManager.pf18bold);
        dialog.addChild(lblTitle);
        lblTitle.setClickable(false);
        lblTitle.setBeforeRenderCallback(() -> {
            lblTitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            lblTitle.setPosition(14, 12);
        });

        LabelWidget lblHint = new LabelWidget(
                () -> "将「" + (music == null ? "" : music.getName()) + "」加入歌单",
                FontManager.pf12
        );
        dialog.addChild(lblHint);
        lblHint.setClickable(false);
        lblHint.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
        lblHint.setBeforeRenderCallback(() -> {
            lblHint.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            lblHint.setMaxWidth(dialog.getWidth() - 28);
            lblHint.setPosition(14, lblTitle.getRelativeY() + lblTitle.getHeight() + 4);
        });

        // 状态栏始终保留一行空间，列表不会因为反馈文本出现/消失而跳动。
        LabelWidget lblStatus = new LabelWidget(() -> statusMessage, FontManager.pf12bold);
        dialog.addChild(lblStatus);
        lblStatus.setClickable(false);
        lblStatus.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
        lblStatus.setBeforeRenderCallback(() -> {
            lblStatus.setHidden(statusMessage.isEmpty());
            lblStatus.setColor(getStatusColor());
            lblStatus.setMaxWidth(dialog.getWidth() - 28);
            lblStatus.setPosition(14, lblHint.getRelativeY() + lblHint.getHeight() + 5);
        });

        RectWidget divider = new RectWidget();
        dialog.addChild(divider);
        divider.setClickable(false);
        divider.setBeforeRenderCallback(() -> {
            double dividerY = lblHint.getRelativeY() + lblHint.getHeight() + 23;
            divider.setBounds(12, dividerY, dialog.getWidth() - 24, 1);
            divider.setColor(RenderSystem.reAlpha(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT), .1f));
        });

        ScrollPanel list = new ScrollPanel();
        dialog.addChild(list);
        list.setSpacing(2);
        list.setBeforeRenderCallback(() -> {
            double listY = divider.getRelativeY() + divider.getHeight() + 7;
            list.setBounds(12, listY, dialog.getWidth() - 24, dialog.getHeight() - listY - 12);
        });

        List<PlayList> playLists = CloudMusic.playLists;
        if (playLists == null || playLists.isEmpty()) {
            LabelWidget empty = new LabelWidget("暂无可用歌单", FontManager.pf14bold);
            list.addChild(empty);
            empty.setClickable(false);
            empty.setBeforeRenderCallback(() -> {
                empty.setBounds(list.getWidth(), 28);
                empty.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                empty.setPosition(10, 7);
            });
            return;
        }

        for (PlayList pl : playLists) {
            list.addChild(new PlaylistRow(pl));
        }
    }

    private void submitToPlaylist(PlayList playlist) {
        if (submitting || music == null || playlist == null) {
            return;
        }

        selectedPlaylist = playlist;
        final String playlistName = getPlaylistName(playlist);
        // 本弹窗只处理网易云歌曲与网易云目标歌单，避免把跨平台稳定哈希误提交给网易云接口。
        if (!music.isNetease() || playlist.getPlatform() != MusicPlatform.NETEASE) {
            submitting = false;
            closeAfterMillis = -1L;
            showStatus("仅支持把网易云歌曲加入网易云歌单", FeedbackState.ERROR);
            DownloadDynamicIsland.showPlaylistTrackAddFailure(playlistName, "仅支持网易云歌曲和网易云歌单");
            closing = true;
            return;
        }

        final long musicId = music.getId();
        if (musicId <= 0L) {
            submitting = false;
            closeAfterMillis = -1L;
            showStatus("歌曲 ID 无效，无法加入歌单", FeedbackState.ERROR);
            DownloadDynamicIsland.showPlaylistTrackAddFailure(playlistName, "歌曲 ID 无效");
            closing = true;
            return;
        }

        submitting = true;
        closeAfterMillis = -1L;
        showStatus("正在加入「" + playlistName + "」…", FeedbackState.PROCESSING);
        DownloadDynamicIsland.showPlaylistTrackAddInProgress(playlistName);

        MultiThreadingUtil.runAsync(() -> {
            CloudMusicApi.PlaylistTrackOperationResult result;
            try {
                result = playlist.addToListWithResult(musicId);
            } catch (Throwable ignored) {
                result = new CloudMusicApi.PlaylistTrackOperationResult(
                        false, false, 502, -1, "请求发生异常，请稍后重试"
                );
            }

            final CloudMusicApi.PlaylistTrackOperationResult finalResult = result;
            MultiThreadingUtil.runOnMainThread(() -> handleSubmitResult(playlist, finalResult));
        });

        // 选择目标歌单即返回播放器页面；网络请求和结果校验仍在后台继续执行。
        closing = true;
    }

    private void handleSubmitResult(PlayList playlist, CloudMusicApi.PlaylistTrackOperationResult result) {
        // 弹窗会在点击后立即关闭，不能把 closing 视为请求过期；仅忽略真正不匹配的请求。
        if (playlist != selectedPlaylist) {
            return;
        }

        submitting = false;
        closeAfterMillis = -1L;
        String playlistName = getPlaylistName(playlist);
        if (result.isAlreadyExists() && result.isVerified()) {
            showStatus("已确认该歌曲已在「" + playlistName + "」中", FeedbackState.ALREADY_EXISTS);
            DownloadDynamicIsland.showPlaylistTrackAlreadyExists(playlistName);
        } else if (result.isSuccess() && result.isVerified()) {
            showStatus("网易云已确认加入「" + playlistName + "」", FeedbackState.SUCCESS);
            DownloadDynamicIsland.showPlaylistTrackAddSuccess(playlistName);
        } else {
            String message = result.getMessage();
            if (message == null || message.trim().isEmpty()) {
                message = "服务端未确认加入成功，请重试";
            }
            showStatus("加入失败：" + message, FeedbackState.ERROR);
            DownloadDynamicIsland.showPlaylistTrackAddFailure(playlistName, message);
        }
    }

    private void showStatus(String message, FeedbackState state) {
        statusMessage = message == null ? "" : message;
        feedbackState = state == null ? FeedbackState.IDLE : state;
    }

    private int getStatusColor() {
        if (feedbackState == FeedbackState.SUCCESS || feedbackState == FeedbackState.ALREADY_EXISTS) {
            return STATUS_SUCCESS_COLOR;
        }
        if (feedbackState == FeedbackState.ERROR) {
            return STATUS_ERROR_COLOR;
        }
        return STATUS_PROCESSING_COLOR;
    }

    private String getPlaylistName(PlayList playlist) {
        return playlist == null || playlist.getName() == null ? "此歌单" : playlist.getName();
    }

    private class PlaylistRow extends Panel {

        private final PlayList playlist;

        PlaylistRow(PlayList playlist) {
            this.playlist = playlist;
            this.setShouldOverrideMouseCursor(true);

            RoundedRectWidget bg = new RoundedRectWidget();
            this.addChild(bg);
            bg.setClickable(false);
            bg.setRadius(3);
            bg.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            bg.setBeforeRenderCallback(() -> bg.setMargin(0));

            RoundedRectWidget hover = new RoundedRectWidget();
            this.addChild(hover);
            hover.setAlpha(0f);
            hover.setClickable(false);
            hover.setRadius(3);
            hover.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER));
            hover.setBeforeRenderCallback(() -> hover.setMargin(0));

            LabelWidget lbl = new LabelWidget(playlist.getName(), FontManager.pf14bold);
            this.addChild(lbl);
            lbl.setClickable(false);
            lbl.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            lbl.setBeforeRenderCallback(() -> {
                lbl.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                lbl.setMaxWidth(this.getWidth() - 104);
                lbl.setPosition(12, this.getHeight() * .5 - lbl.getHeight() * .5);
            });

            LabelWidget action = new LabelWidget(this::getActionText, FontManager.pf12bold);
            this.addChild(action);
            action.setClickable(false);
            action.setBeforeRenderCallback(() -> {
                String text = action.getLabel();
                action.setHidden(text.isEmpty());
                action.setColor(getActionColor());
                action.setPosition(this.getWidth() - action.getFont().getStringWidthD(text) - 11,
                        this.getHeight() * .5 - action.getHeight() * .5);
            });

            this.setBeforeRenderCallback(() -> {
                this.setBounds(this.getParentWidth(), 28);
                boolean active = this.isHovering() && !submitting && closeAfterMillis <= 0L;
                hover.setAlpha(Interpolations.interpolate(hover.getWidgetAlpha(), active ? 1f : 0f, .3f));
                hover.setHidden(hover.getWidgetAlpha() <= .05f);
            });

            this.setOnClickCallback((x, y, button) -> {
                if (button != 0 || submitting || closeAfterMillis > 0L) {
                    return button == 0;
                }
                submitToPlaylist(playlist);
                return true;
            });
        }

        private String getActionText() {
            if (playlist != selectedPlaylist) {
                return "";
            }
            if (submitting) {
                return "校验中…";
            }
            if (feedbackState == FeedbackState.SUCCESS) {
                return "已确认";
            }
            if (feedbackState == FeedbackState.ALREADY_EXISTS) {
                return "已存在";
            }
            if (feedbackState == FeedbackState.ERROR) {
                return "点击重试";
            }
            return "";
        }

        private int getActionColor() {
            if (feedbackState == FeedbackState.ERROR) {
                return STATUS_ERROR_COLOR;
            }
            if (feedbackState == FeedbackState.SUCCESS || feedbackState == FeedbackState.ALREADY_EXISTS) {
                return STATUS_SUCCESS_COLOR;
            }
            return STATUS_PROCESSING_COLOR;
        }
    }
}
