package io.github.thake.camundaembedded;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedCamundaProcessTest {

    @Test
    void shouldHaveSensibleDefaultConfig() {
        ManagedCamundaProcess.Config config = ManagedCamundaProcess.Config.defaults();
        assertThat(config.grpcPort()).isEqualTo(0);
        assertThat(config.restPort()).isEqualTo(0);
        assertThat(config.monitoringPort()).isEqualTo(0);
        assertThat(config.maxHeap()).isEqualTo("1024m");
        assertThat(config.clockControlled()).isTrue();
        assertThat(config.startupTimeout()).isEqualTo(Duration.ofMinutes(2));
        assertThat(config.properties()).isEmpty();
    }

    @Test
    void shouldAllowCustomConfigurationViaBuilder() {
        ManagedCamundaProcess.Config config = ManagedCamundaProcess.Config.builder()
                .grpcPort(26500)
                .restPort(8080)
                .monitoringPort(9600)
                .maxHeap("2048m")
                .clockControlled(false)
                .startupTimeout(Duration.ofMinutes(3))
                .property("logging.level.io.camunda", "DEBUG")
                .build();

        assertThat(config.grpcPort()).isEqualTo(26500);
        assertThat(config.restPort()).isEqualTo(8080);
        assertThat(config.monitoringPort()).isEqualTo(9600);
        assertThat(config.maxHeap()).isEqualTo("2048m");
        assertThat(config.clockControlled()).isFalse();
        assertThat(config.startupTimeout()).isEqualTo(Duration.ofMinutes(3));
        assertThat(config.properties()).containsEntry("logging.level.io.camunda", "DEBUG");
    }

    @Test
    void shouldStartAndStopManagedCamundaProcessIndependently() {
        ManagedCamundaProcess.Config config = ManagedCamundaProcess.Config.builder()
                .startupTimeout(Duration.ofMinutes(2))
                .build();

        try (ManagedCamundaProcess server = new ManagedCamundaProcess(config).start()) {
            assertThat(server.getActualGrpcPort()).isGreaterThan(0);
            assertThat(server.getActualRestPort()).isGreaterThan(0);
            assertThat(server.getActualMonitoringPort()).isGreaterThan(0);
            assertThat(server.getConfig()).isEqualTo(config);
        }
    }
}
