package sumicya.fcmself.health;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import sumicya.fcmself.core.Contract;

public class HookHealthReportTest {

    private static Map<String, HookHealthReport.ProcessReport> noResponses() {
        return new HashMap<>();
    }

    @Test
    public void reportsDisconnectedStateWithoutAnyResponse() {
        String text = HookHealthReport.render(false, false, noResponses());
        assertTrue(text.contains("框架服务：未连接"));
        assertTrue(text.contains("配置：未就绪"));
        assertTrue(text.contains("system_server：未响应"));
        assertTrue(text.contains("PowerKeeper：未响应"));
        assertTrue(text.contains("GMS：未响应"));
    }

    @Test
    public void nullResponseMapIsTreatedAsNoResponses() {
        assertTrue(HookHealthReport.render(true, true, null).contains("system_server：未响应"));
    }

    @Test
    public void readyProcessListsItsHookDetails() {
        Map<String, HookHealthReport.ProcessReport> responses = noResponses();
        responses.put("android", new HookHealthReport.ProcessReport(
                true,
                true,
                Collections.singletonList("BroadcastFix"),
                Arrays.asList(
                        "BroadcastFix" + Contract.DETAIL_SEPARATOR + "已安装",
                        "Config" + Contract.DETAIL_SEPARATOR + "已加载")));

        String text = HookHealthReport.render(true, true, responses);
        assertTrue(text.contains("框架服务：已连接"));
        assertTrue(text.contains("配置：正常"));
        assertTrue(text.contains("system_server：就绪"));
        assertTrue(text.contains("BroadcastFix：已安装"));
        assertTrue(text.contains("Config：已加载"));
    }

    @Test
    public void fallsBackToModuleNamesWhenNoDetailsWereReported() {
        Map<String, HookHealthReport.ProcessReport> responses = noResponses();
        responses.put(Contract.PACKAGE_POWERKEEPER, new HookHealthReport.ProcessReport(
                false, true, Arrays.asList("PowerkeeperFix", "AutoStartFix"), null));

        String text = HookHealthReport.render(true, true, responses);
        // bootComplete is still false, so the process is reported as initialising.
        assertTrue(text.contains("PowerKeeper：初始化中"));
        assertTrue(text.contains("PowerkeeperFix、AutoStartFix"));
    }

    @Test
    public void alwaysAppendsTheCoverageDisclaimer() {
        assertTrue(HookHealthReport.render(true, true, noResponses())
                .endsWith("已加载不代表所有 OEM Hook 均命中。"));
    }
}
