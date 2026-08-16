package sumicya.fcmself.health;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import sumicya.fcmself.core.Contract;

/**
 * Renders the "Hook 状态" panel text from the answers the hooked processes broadcast back.
 *
 * <p>Framework free on purpose: the wording of this panel is the only diagnostic most users will
 * ever read, so it is covered by unit tests instead of being verified by hand on a device.
 */
public final class HookHealthReport {

    /** One hooked process' answer to a health request. */
    public static final class ProcessReport {
        public final boolean bootComplete;
        public final boolean configReady;
        public final List<String> modules;
        /** Entries are {@code name + DETAIL_SEPARATOR + state}. */
        public final List<String> details;

        public ProcessReport(
                boolean bootComplete,
                boolean configReady,
                List<String> modules,
                List<String> details) {
            this.bootComplete = bootComplete;
            this.configReady = configReady;
            this.modules = modules == null ? new ArrayList<>() : modules;
            this.details = details == null ? new ArrayList<>() : details;
        }
    }

    /** Processes shown in the panel, in display order: {process name, label}. */
    private static final String[][] WATCHED_PROCESSES = {
            {"android", "system_server"},
            {Contract.PACKAGE_POWERKEEPER, "PowerKeeper"},
            {Contract.PACKAGE_GMS, "GMS"}
    };

    private HookHealthReport() {
    }

    public static String render(
            boolean serviceConnected,
            boolean configReady,
            Map<String, ProcessReport> responses) {
        Map<String, ProcessReport> safe =
                responses == null ? Collections.emptyMap() : responses;
        StringBuilder status = new StringBuilder();
        status.append("框架服务：")
                .append(serviceConnected ? "已连接" : "未连接")
                .append('\n')
                .append("配置：")
                .append(configReady ? "正常" : "未就绪")
                .append("\n\n");
        for (String[] process : WATCHED_PROCESSES) {
            appendProcess(status, safe.get(process[0]), process[1]);
        }
        status.append("\n已加载不代表所有 OEM Hook 均命中。");
        return status.toString();
    }

    private static void appendProcess(StringBuilder status, ProcessReport report, String label) {
        status.append(label).append("：");
        if (report == null) {
            status.append("未响应");
        } else {
            status.append(report.bootComplete && report.configReady ? "就绪" : "初始化中");
            if (!report.details.isEmpty()) {
                for (String detail : report.details) {
                    int separator = detail.indexOf(Contract.DETAIL_SEPARATOR);
                    if (separator > 0) {
                        status.append("\n  ")
                                .append(detail, 0, separator)
                                .append("：")
                                .append(detail.substring(separator + 1));
                    }
                }
            } else if (!report.modules.isEmpty()) {
                status.append("\n  ").append(String.join("、", report.modules));
            }
        }
        status.append('\n');
    }
}
