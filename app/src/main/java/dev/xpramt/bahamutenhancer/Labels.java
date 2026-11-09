package dev.xpramt.bahamutenhancer;

/** The one place for setting names and descriptions, used by the player panel. */
final class Labels {
    static final String HIGH_PRECISION_TITLE = "關閉長按兩倍速播放";
    static final String HIGH_PRECISION_SUMMARY = "上下滑手勢直接接管";
    static final String DOUBLE_TAP_TITLE = "雙擊播放／暫停";
    static final String DOUBLE_TAP_SUMMARY = "取代雙擊快進與快退";
    static final String SWEEP_TITLE = "滑動範圍";

    private Labels() { }

    static String sweepSummary(int percent) {
        return "螢幕高度 " + percent + "%";
    }
}
