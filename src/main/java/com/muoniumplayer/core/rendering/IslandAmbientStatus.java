package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.settings.HudConfig;

import java.util.Calendar;

/**
 * 常驻灵动岛的内容采样与状态判定。
 *
 * <p>拆成独立类有两个原因：一是把「读游戏状态」和「画灵动岛」分开，渲染层只消费不可变快照；
 * 二是这里的判定与格式化全是纯函数，可以离线探针验证，不必进游戏才能确认排队/常驻的交互。</p>
 *
 * <p>宽度模板是关键设计：FPS 与延迟每秒都在变，如果按真实字宽排版，灵动岛会一直微微伸缩。
 * 每个条目都按固定位数的模板预留宽度，数值居中绘制，于是常驻状态下宽度完全静止。</p>
 */
final class IslandAmbientStatus {

    /** 常驻条目类别。左侧小图标与单位都由类别决定。 */
    enum Kind {
        FPS,
        PING,
        CLOCK
    }

    /** 数值健康度。渲染层据此选色，判定本身与主题、GL 无关，便于离线校验。 */
    enum Health {
        NEUTRAL,
        GOOD,
        WARN,
        BAD
    }

    /** 采样间隔。常驻内容不需要每帧刷新，250ms 已经比人眼分辨得出的更新更快。 */
    static final long SAMPLE_INTERVAL_MILLIS = 250L;

    private static final Chip[] NO_CHIPS = new Chip[0];
    /** 复用同一个 Calendar：常驻渲染每 250ms 才取一次时间，且只在渲染线程上使用。 */
    private static final Calendar CALENDAR = Calendar.getInstance();

    private IslandAmbientStatus() {
    }

    static final class Chip {
        final Kind kind;
        /** 实际绘制的数值文本。 */
        final String value;
        /** 只参与宽度计算的等位数模板，保证数值跳动时布局不动。 */
        final String widthTemplate;
        /** 数值后面的灰色单位，没有单位时为空串。 */
        final String unit;
        final Health health;

        private Chip(Kind kind, String value, String widthTemplate, String unit, Health health) {
            this.kind = kind;
            this.value = value;
            this.widthTemplate = widthTemplate;
            this.unit = unit;
            this.health = health;
        }
    }

    /** 一次采样的不可变结果。渲染线程只读，不会在绘制中途变化。 */
    static final class Snapshot {
        final Chip[] chips;
        final long sampledAt;

        private Snapshot(Chip[] chips, long sampledAt) {
            this.chips = chips;
            this.sampledAt = sampledAt;
        }

        boolean isEmpty() {
            return chips.length == 0;
        }
    }

    static final Snapshot EMPTY = new Snapshot(NO_CHIPS, 0L);

    /**
     * 按配置组装常驻条目。三个开关全关时返回空快照，灵动岛会退回紧凑胶囊而不是画一条空白行。
     *
     * @param fps        当前帧率，负数视为未知
     * @param ping       当前延迟毫秒，未知时配合 {@code pingKnown=false}
     * @param pingKnown  是否拿到了服务器延迟（单机/未进服时拿不到）
     */
    static Snapshot describe(boolean showFps, boolean showPing, boolean showClock,
                             int fps, int ping, boolean pingKnown, long epochMillis) {
        int count = (showFps ? 1 : 0) + (showPing ? 1 : 0) + (showClock ? 1 : 0);
        if (count == 0) return EMPTY;
        Chip[] chips = new Chip[count];
        int index = 0;
        if (showFps) {
            int safeFps = Math.max(0, Math.min(9999, fps));
            // 模板必须容得下真实数值，否则居中绘制会溢出预留槽压到下一个条目上。
            // 破千帧确实会发生（低画质小窗口），所以只在这时才升到四位模板。
            chips[index++] = new Chip(Kind.FPS, fps < 0 ? "--" : Integer.toString(safeFps),
                    safeFps >= 1000 ? "8888" : "888", "FPS",
                    fps < 0 ? Health.NEUTRAL : fpsHealth(safeFps));
        }
        if (showPing) {
            int safePing = Math.max(0, Math.min(9999, ping));
            chips[index++] = new Chip(Kind.PING, pingKnown ? Integer.toString(safePing) : "--",
                    "8888", "ms", pingKnown ? pingHealth(safePing) : Health.NEUTRAL);
        }
        if (showClock) {
            chips[index] = new Chip(Kind.CLOCK, formatClock(epochMillis), "88:88", "", Health.NEUTRAL);
        }
        return new Snapshot(chips, epochMillis);
    }

    /** 读取真实游戏状态。任何取值失败都退化成「未知」，绝不让常驻显示把渲染打断。 */
    static Snapshot capture(long now) {
        boolean showFps = HudConfig.dynamicIslandAmbientFps;
        boolean showPing = HudConfig.dynamicIslandAmbientPing;
        boolean showClock = HudConfig.dynamicIslandAmbientClock;
        if (!showFps && !showPing && !showClock) return EMPTY;
        int fps = -1;
        int ping = 0;
        boolean pingKnown = false;
        if (showFps) {
            try {
                fps = net.minecraft.client.Minecraft.getDebugFPS();
            } catch (Throwable ignored) {
                fps = -1;
            }
        }
        if (showPing) {
            try {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
                if (mc != null && mc.thePlayer != null && mc.getNetHandler() != null) {
                    net.minecraft.client.network.NetworkPlayerInfo info =
                            mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
                    if (info != null) {
                        ping = info.getResponseTime();
                        pingKnown = true;
                    }
                }
            } catch (Throwable ignored) {
                pingKnown = false;
            }
        }
        return describe(showFps, showPing, showClock, fps, ping, pingKnown, now);
    }

    private static Health fpsHealth(int fps) {
        if (fps >= 90) return Health.GOOD;
        if (fps >= 45) return Health.NEUTRAL;
        if (fps >= 25) return Health.WARN;
        return Health.BAD;
    }

    private static Health pingHealth(int ping) {
        if (ping <= 60) return Health.GOOD;
        if (ping <= 120) return Health.NEUTRAL;
        if (ping <= 250) return Health.WARN;
        return Health.BAD;
    }

    private static String formatClock(long epochMillis) {
        CALENDAR.setTimeInMillis(epochMillis);
        int hour = CALENDAR.get(Calendar.HOUR_OF_DAY);
        int minute = CALENDAR.get(Calendar.MINUTE);
        return twoDigits(hour) + ":" + twoDigits(minute);
    }

    private static String twoDigits(int value) {
        int safe = Math.max(0, Math.min(99, value));
        return safe < 10 ? "0" + safe : Integer.toString(safe);
    }

    /**
     * 灵动岛是否应该可见。常驻只是多了一条「没有任何事件也留在屏幕上」的理由，
     * 下载/完成停留/通知这三条既有理由完全不变，所以排队机制的时序不受影响。
     */
    static boolean shouldShow(boolean enabled, boolean alwaysOn, boolean activeDownload,
                              boolean holdingCompletion, boolean activeNotice) {
        return enabled && (alwaysOn || activeDownload || holdingCompletion || activeNotice);
    }

    /** 常驻内容只在「确实无事可报」时出现，永远不会抢下载或通知的展示位。 */
    static boolean isAmbient(boolean enabled, boolean alwaysOn, boolean activeDownload,
                             boolean holdingCompletion, boolean activeNotice) {
        return enabled && alwaysOn && !activeDownload && !holdingCompletion && !activeNotice;
    }

    /**
     * 常驻且已经稳定停在屏幕上时，必须抑制入场动画：否则每来一条通知都会重播一次
     * 缩放入场，看起来像灵动岛在原地抽动。阈值取 .55 是为了让「刚打开常驻」那一次入场照常播完。
     */
    static boolean suppressEntry(boolean alwaysOn, float visibility) {
        return alwaysOn && visibility > .55f;
    }

    /** 常驻但三项内容全关时收成紧凑胶囊，而不是展开一条空白卡片。 */
    static double expansionTarget(boolean shouldShow, boolean ambient, boolean hasChips) {
        if (!shouldShow) return 0.0;
        return ambient && !hasChips ? 0.0 : 1.0;
    }
}
