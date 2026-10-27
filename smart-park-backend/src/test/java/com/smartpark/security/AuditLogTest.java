package com.smartpark.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartpark.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * TC-AUD-01 审计日志：登录 / 审批 / 放行拒绝这几类关键操作，必须产生一条带 traceId 的 INFO 审计日志。
 *
 * 实现方式：挂载一个内存 Appender 捕获全部日志事件，断言存在 INFO 级、且内容含本次响应 traceId 的事件。
 * 不绑定具体 logger 名，避免 backend 调整 logger 命名导致用例失效。
 *
 * 审计日志已落地（AuditLogger，格式 AUDIT | traceId= | operator= | action= | target= | result= | detail=），
 * 用例启用；无 Docker 环境时由 @Testcontainers(disabledWithoutDocker = true) 自动跳过。
 */
class AuditLogTest extends MySqlIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void loginMustEmitAuditLogWithTraceId() throws Exception {
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.smartpark");
        logger.addAppender(appender);
        try {
            String body = mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"admin\",\"password\":\"" + TEST_PASSWORD + "\"}"))
                    .andReturn().getResponse().getContentAsString();
            JsonNode node = mapper.readTree(body);
            String traceId = node.path("traceId").asText();

            assertThat(traceId).as("响应体应带 traceId").isNotBlank();
            assertThat(appender.events.stream()
                    .anyMatch(e -> e.getLevel() == Level.INFO
                            && e.getFormattedMessage() != null
                            && e.getFormattedMessage().contains(traceId)))
                    .as("登录必须产生一条含 traceId 的 INFO 审计日志")
                    .isTrue();
        } finally {
            logger.detachAppender(appender);
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
