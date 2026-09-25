package io.github.thake.camundaembedded;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.EvaluateDecisionResponse;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.client.api.worker.JobWorker;
import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.TestDeployment;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EmbeddedCamundaTest
@TestDeployment(resources = {
        "order-process.bpmn",
        "timer-process.bpmn",
        "user-task-process.bpmn",
        "failing-process.bpmn",
        "discount-decision.dmn"
})
class EmbeddedCamundaProcessTest {

    private CamundaClient client;
    private CamundaProcessTestContext testContext;

    @Test
    void shouldSupportDependencyInjectionAndParameterInjection(CamundaClient paramClient, CamundaProcessTestContext paramContext) {
        // Verify field injection
        assertThat(client).isNotNull();
        assertThat(testContext).isNotNull();
        assertThat(client.getConfiguration().getGrpcAddress().getPort()).isGreaterThan(0);
        assertThat(client.getConfiguration().getRestAddress().getPort()).isGreaterThan(0);

        // Verify parameter injection
        assertThat(paramClient).isNotNull();
        assertThat(paramContext).isNotNull();
    }

    @Test
    void shouldSupportProcessExecutionAndStandardAssertions() {
        // Process is automatically deployed via @TestDeployment
        ProcessInstanceEvent instance = client.newCreateInstanceCommand()
                .bpmnProcessId("order-process")
                .latestVersion()
                .variables(Map.of("orderId", "ORD-12345", "amount", 99.50))
                .send()
                .join();

        CamundaAssert.assertThat(instance).isActive();
        CamundaAssert.assertThat(instance).hasNoActiveIncidents();

        // Complete job via JobWorker
        try (JobWorker worker = client.newWorker()
                .jobType("process-payment")
                .handler((jobClient, job) -> {
                    jobClient.newCompleteCommand(job.getKey())
                            .variables(Map.of("paymentStatus", "SUCCESS"))
                            .send()
                            .join();
                })
                .open()) {

            CamundaAssert.assertThat(instance).isCompleted();
            CamundaAssert.assertThat(instance).hasCompletedElements("task_process_payment");
            CamundaAssert.assertThat(instance).hasVariable("paymentStatus", "SUCCESS");
        }
    }

    @Test
    void shouldSupportControlledClockAndTimeTravel() {
        ProcessInstanceEvent instance = client.newCreateInstanceCommand()
                .bpmnProcessId("timer-process")
                .latestVersion()
                .send()
                .join();

        // Process should be waiting at the timer intermediate catch event
        CamundaAssert.assertThat(instance).isActive();
        CamundaAssert.assertThat(instance).hasActiveElements("timer_event");

        // Advance simulated time by 2 hours (timer is PT1H)
        testContext.increaseTime(Duration.ofHours(2));

        // Process should have resumed and completed
        CamundaAssert.assertThat(instance).hasCompletedElements("timer_event");
        CamundaAssert.assertThat(instance).isCompleted();
    }

    @Test
    void shouldSupportJobMockingAndDirectCompletionViaContext() {
        ProcessInstanceEvent instance = client.newCreateInstanceCommand()
                .bpmnProcessId("order-process")
                .latestVersion()
                .variables(Map.of("orderId", "ORD-DIRECT"))
                .send()
                .join();

        CamundaAssert.assertThat(instance).isActive();

        // Complete job directly through testContext without manually starting a worker
        testContext.completeJob("process-payment", Map.of("paymentStatus", "AUTO_COMPLETED"));

        CamundaAssert.assertThat(instance).isCompleted();
        CamundaAssert.assertThat(instance).hasVariable("paymentStatus", "AUTO_COMPLETED");
    }

    @Test
    void shouldSupportUserTasksAndCompletion() {
        ProcessInstanceEvent instance = client.newCreateInstanceCommand()
                .bpmnProcessId("user-task-process")
                .latestVersion()
                .variables(Map.of("requestId", "REQ-99"))
                .send()
                .join();

        CamundaAssert.assertThat(instance).isActive();
        CamundaAssert.assertThat(instance).hasActiveElements("task_review");

        // Complete user task directly via testContext
        testContext.completeUserTask("task_review", Map.of("approved", true));

        CamundaAssert.assertThat(instance).isCompleted();
        CamundaAssert.assertThat(instance).hasVariable("approved", true);
    }

    @Test
    void shouldSupportIncidentDetection() {
        ProcessInstanceEvent instance = client.newCreateInstanceCommand()
                .bpmnProcessId("failing-process")
                .latestVersion()
                .send()
                .join();

        CamundaAssert.assertThat(instance).isActive();

        // Fail job with 0 retries to create an incident
        try (JobWorker ignored = client.newWorker()
                .jobType("failing-worker-type")
                .handler((jobClient, job) -> {
                    jobClient.newFailCommand(job.getKey())
                            .retries(0)
                            .errorMessage("Permanent failure triggered")
                            .send()
                            .join();
                })
                .open()) {

            CamundaAssert.assertThat(instance).hasActiveIncidents();
        }
    }

    @Test
    void shouldSupportProcessTermination() {
        ProcessInstanceEvent instance = client.newCreateInstanceCommand()
                .bpmnProcessId("timer-process")
                .latestVersion()
                .send()
                .join();

        CamundaAssert.assertThat(instance).isActive();

        // Cancel the running process instance
        client.newCancelInstanceCommand(instance.getProcessInstanceKey())
                .send()
                .join();

        CamundaAssert.assertThat(instance).isTerminated();
    }

    @Test
    void shouldSupportDmnDecisionEvaluation() {
        EvaluateDecisionResponse response = client.newEvaluateDecisionCommand()
                .decisionId("decision_discount")
                .variables(Map.of("customerType", "VIP"))
                .send()
                .join();

        assertThat(response).isNotNull();
        assertThat(response.getDecisionOutput()).isEqualTo("20");

        EvaluateDecisionResponse regularResponse = client.newEvaluateDecisionCommand()
                .decisionId("decision_discount")
                .variables(Map.of("customerType", "REGULAR"))
                .send()
                .join();

        assertThat(regularResponse).isNotNull();
        assertThat(regularResponse.getDecisionOutput()).isEqualTo("5");
    }

    @Test
    void shouldSupportUpdatingVariables() {
        ProcessInstanceEvent instance = client.newCreateInstanceCommand()
                .bpmnProcessId("timer-process")
                .latestVersion()
                .variables(Map.of("initialVar", "initialValue"))
                .send()
                .join();

        CamundaAssert.assertThat(instance).hasVariable("initialVar", "initialValue");

        client.newSetVariablesCommand(instance.getProcessInstanceKey())
                .variables(Map.of("updatedVar", "newValue"))
                .send()
                .join();

        CamundaAssert.assertThat(instance).hasVariable("updatedVar", "newValue");
    }
}
