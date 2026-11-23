package com.sgggzg.qmzg.core;

/**
 * 全局 Intent Action 与 Extra 常量。
 *
 * <p>集中定义可以避免 UI 层、服务层、通知层各自散落字符串，
 * 也方便以后把监控功能拆成独立模块。</p>
 */
public final class Actions {

    private Actions() {
    }

    // ===== 进程内广播（LocalBroadcastManager） =====

    /** 新增一条日志。 */
    public static final String LOG = "com.sgggzg.qmzg.LOG";
    public static final String EXTRA_LOG = "log_msg";

    /** 有新的识别区域预览图。 */
    public static final String IMAGE = "com.sgggzg.qmzg.IMAGE";

    /** 新一轮 OCR 结果已产生。 */
    public static final String OCR_RESULT = "com.sgggzg.qmzg.OCR_RESULT";

    /** 服务已停止。 */
    public static final String SERVICE_STOPPED = "com.sgggzg.qmzg.SERVICE_STOPPED";

    /** 服务运行状态发生变化（开始/停止/报警）。 */
    public static final String SERVICE_STATE_CHANGED = "com.sgggzg.qmzg.SERVICE_STATE_CHANGED";

    // ===== 发给 MonitorService 的命令 =====

    public static final String STOP_MONITOR = "com.sgggzg.qmzg.STOP_MONITOR";
    public static final String STOP_ALARM = "com.sgggzg.qmzg.STOP_ALARM";
    public static final String TEST_ALARM = "com.sgggzg.qmzg.TEST_ALARM";
    public static final String TOGGLE_MASK = "com.sgggzg.qmzg.TOGGLE_MASK";
    /** 暂停/继续/停止报警（按当前状态解释）。 */
    public static final String PAUSE_RESUME = "com.sgggzg.qmzg.PAUSE_RESUME";
}
