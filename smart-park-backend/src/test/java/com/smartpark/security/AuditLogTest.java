package com.smartpark.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartpark.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * TC-AUD-01 审计日志：关键操作必须产生一条带 traceId 的 INFO 审计日志。
 *
 * 实现方式：挂载内存 Appender 到 **ROOT logger**，再断言捕获到的事件里存在
 * loggerName == "AUDIT"、级别 INFO、内容含本次响应 traceId 且 action=LOGIN 的那一条。
 *
 * ⚠️ 两个必须注意的点（踩过才知道）：
 *   1. 必须挂 ROOT：AuditLogger 用的 logger 名是 **"AUDIT"**（devops 按此名做日志分流与 180 天留存），
 *      它不在 com.smartpark 这条 logger 树上。挂 com.smartpark 一条也收不到 → 用例假失败。
 *   2. 必须校验 loggerName：只挂 ROOT 的话，请求日志等其它 INFO 事件也会带 traceId，
 *      断言"存在含 traceId 的 INFO"会假通过（审计没落也能过），所以要把 loggerName=审计 作为硬条件。
 *
 * 审计已落地（AuditLogger：AUDIT | traceId= | operator= | action= | target= | result= | detail=），用例启用。
 * 当前自动化覆盖 LOGIN；ACCESS_SIMULATE / APPROVE / STOCK_* / DELETE_* 等 action 的审计
 * 由 UAT 阶段的日志检索（D 载体）覆盖。
 * 无 Docker 环境时由 @Testcontainers(disabledWithoutDocker = true) 自动跳过（跳过 ≠ 通过）。
 */
class AuditLogTest extends MySqlIntegrationTest {

    private static final String AUDIT_LOGGER_NAME = "AUDIT";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void loginMustEmitAuditLogWithTraceId() throws Exception {
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        var root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(appender);
        try {
            String body = mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"admin\",\"password\":\"" + TEST_PASSWORD + "\"}"))
                    .andReturn().getResponse().getContentAsString();
            JsonNode node = mapper.readTree(body);
            String traceId = node.path("traceId").asText();

            assertThat(traceId).as("响应体应带 traceId").isNotBlank();

            // 硬条件：loggerName=AUDIT（审计专用）+ INFO + 含本次 traceId + 动作是 LOGIN
            boolean emitted = appender.events.stream().anyMatch(e ->
                    e.getLevel() == Level.INFO
                            && AUDIT_LOGGER_NAME.equals(e.getLoggerName())
                            && e.getFormattedMessage() != null
                            && e.getFormattedMessage().contains(traceId)
                            && e.getFormattedMessage().contains("action=LOGIN"));
            assertThat(emitted)
                    .as("登录必须产生一条 AUDIT logger 的 INFO 审计日志（含 traceId 且 action=LOGIN）")
                    .isTrue();
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
    }

    private static class CapturingAppender extends AppenderBase<ILoggingEvent> {
        private final List<ILoggingEvent> events = new ArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            events.add(event);
        }
    }
}
