package io.github.rocketmqtx;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rocketmqtx.service.OrderMessageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rocketmq.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:actuator-endpoints"
})
class ActuatorEndpointsTest {
    private static final String LIVENESS = "/actuator/health/liveness";
    private static final String READINESS = "/actuator/health/readiness";

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ApplicationContext context;

    // Keep the order controller wired without starting a RocketMQ client or broker.
    @MockitoBean
    private OrderMessageService orderMessageService;

    @Test
    void readinessCanRefuseTrafficWhileLivenessStaysUp() {
        assertProbe(LIVENESS, HttpStatus.OK, "UP");
        assertProbe(READINESS, HttpStatus.OK, "UP");

        try {
            AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
            assertProbe(READINESS, HttpStatus.SERVICE_UNAVAILABLE, "OUT_OF_SERVICE");
            assertProbe(LIVENESS, HttpStatus.OK, "UP");
        } finally {
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        }
        assertProbe(READINESS, HttpStatus.OK, "UP");
    }

    @Test
    void brokenLivenessReturnsServiceUnavailable() {
        try {
            AvailabilityChangeEvent.publish(context, LivenessState.BROKEN);
            assertProbe(LIVENESS, HttpStatus.SERVICE_UNAVAILABLE, "DOWN");
        } finally {
            AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
        }
        assertProbe(LIVENESS, HttpStatus.OK, "UP");
    }

    @Test
    void exposesApplicationInformationAndJvmMetrics() {
        var info = http.getForEntity("/actuator/info", JsonNode.class);
        assertThat(info.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(info.getBody()).isNotNull();
        assertThat(info.getBody().path("app").path("name").asText()).isEqualTo("rocketmq-transaction-lab");

        var metrics = http.getForEntity("/actuator/metrics/jvm.memory.used", JsonNode.class);
        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metrics.getBody()).isNotNull();
        assertThat(metrics.getBody().path("name").asText()).isEqualTo("jvm.memory.used");
        assertThat(metrics.getBody().path("measurements").isEmpty()).isFalse();
    }

    @Test
    void exportsPrometheusMetricsAndKeepsEnvUnexposed() {
        var scrape = http.getForEntity("/actuator/prometheus", String.class);
        assertThat(scrape.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(scrape.getBody()).contains("# TYPE jvm_memory_used_bytes gauge");
        assertThat(http.getForEntity("/actuator/env", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private void assertProbe(String path, HttpStatus httpStatus, String healthStatus) {
        var response = http.getForEntity(path, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(httpStatus);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("status").asText()).isEqualTo(healthStatus);
    }
}
