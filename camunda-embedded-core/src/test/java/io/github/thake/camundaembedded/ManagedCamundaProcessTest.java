package io.github.thake.camundaembedded;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedCamundaProcessTest {

    @Test
    void shouldStartAndStopManagedCamundaProcessIndependently() {
        try (ManagedCamundaProcess server = new ManagedCamundaProcess().start()) {
            assertThat(server.getActualGrpcPort()).isGreaterThan(0);
            assertThat(server.getActualRestPort()).isGreaterThan(0);
            assertThat(server.getActualMonitoringPort()).isGreaterThan(0);
        }
    }
}
