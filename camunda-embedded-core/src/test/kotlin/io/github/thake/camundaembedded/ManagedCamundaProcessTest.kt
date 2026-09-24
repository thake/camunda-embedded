package io.github.thake.camundaembedded

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ManagedCamundaProcessTest {

    @Test
    fun `should start and stop managed camunda process independently`() {
        ManagedCamundaProcess().start().use { server ->
            assertThat(server.actualGrpcPort).isGreaterThan(0)
            assertThat(server.actualRestPort).isGreaterThan(0)
            assertThat(server.actualMonitoringPort).isGreaterThan(0)
        }
    }
}
