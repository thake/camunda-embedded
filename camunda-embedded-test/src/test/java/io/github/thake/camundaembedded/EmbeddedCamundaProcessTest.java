package io.github.thake.camundaembedded;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.DeploymentEvent;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.client.api.worker.JobWorker;
import io.camunda.process.test.api.CamundaAssert;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EmbeddedCamundaTest
class EmbeddedCamundaProcessTest {

    private CamundaClient client;

    @Test
    void shouldRunAndTestProcessUsingEmbeddedCamundaInstanceAndCptWithoutDocker() {
        // Verify dynamic random ports (default 0) were allocated
        assertThat(client.getConfiguration().getGrpcAddress().getPort()).isGreaterThan(0);
        assertThat(client.getConfiguration().getRestAddress().getPort()).isGreaterThan(0);

        // Deploy process
        DeploymentEvent deployment = client.newDeployResourceCommand()
                .addResourceFromClasspath("order-process.bpmn")
                .send()
                .join();
        assertThat(deployment).isNotNull();

        // Start process instance
        ProcessInstanceEvent instance = client.newCreateInstanceCommand()
                .bpmnProcessId("order-process")
                .latestVersion()
                .variables(Map.of("orderId", "ORD-12345", "amount", 99.50))
                .send()
                .join();

        // Assert process is active
        CamundaAssert.assertThat(instance).isActive();

        // Handle the service task job
        JobWorker worker = client.newWorker()
                .jobType("process-payment")
                .handler((jobClient, job) -> {
                    jobClient.newCompleteCommand(job.getKey())
                            .variables(Map.of("paymentStatus", "SUCCESS"))
                            .send()
                            .join();
                })
                .open();

        // Assert process completes and verify state
        CamundaAssert.assertThat(instance).isCompleted();
        CamundaAssert.assertThat(instance).hasCompletedElements("task_process_payment");
        CamundaAssert.assertThat(instance).hasVariable("paymentStatus", "SUCCESS");

        worker.close();
    }
}
