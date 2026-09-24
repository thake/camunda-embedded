package io.github.thake.camundaembedded

import io.camunda.client.CamundaClient
import io.camunda.process.test.api.CamundaAssert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@EmbeddedCamundaTest
class EmbeddedCamundaProcessTest {

    private lateinit var client: CamundaClient

    @Test
    fun `should run and test process using embedded camunda instance and CPT without docker`() {
        // Verify dynamic random ports (default 0) were allocated
        assertThat(client.configuration.grpcAddress.port).isGreaterThan(0)
        assertThat(client.configuration.restAddress.port).isGreaterThan(0)

        // Deploy process
        val deployment = client.newDeployResourceCommand()
            .addResourceFromClasspath("order-process.bpmn")
            .send()
            .join()

        // Start process instance
        val instance = client.newCreateInstanceCommand()
            .bpmnProcessId("order-process")
            .latestVersion()
            .variables(mapOf("orderId" to "ORD-12345", "amount" to 99.50))
            .send()
            .join()

        // Assert process is active
        CamundaAssert.assertThat(instance).isActive()

        // Handle the service task job
        val worker = client.newWorker()
            .jobType("process-payment")
            .handler { jobClient, job ->
                jobClient.newCompleteCommand(job.key)
                    .variables(mapOf("paymentStatus" to "SUCCESS"))
                    .send()
                    .join()
            }
            .open()

        // Assert process completes and verify state
        CamundaAssert.assertThat(instance).isCompleted()
        CamundaAssert.assertThat(instance).hasCompletedElements("task_process_payment")
        CamundaAssert.assertThat(instance).hasVariable("paymentStatus", "SUCCESS")

        worker.close()
    }
}
