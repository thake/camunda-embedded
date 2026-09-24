package io.github.thake.camundaembedded

import org.junit.jupiter.api.extension.ExtendWith

/**
 * Annotation to enable an embedded Camunda 8.9 test environment for JUnit 5 test classes.
 *
 * Automatically manages the lifecycle of the standalone Camunda 8.9 server
 * in an isolated child JVM using in-memory H2 and RocksDB, and configures the
 * Camunda Process Test extension to inject a ready-to-use CamundaClient.
 *
 * @property grpcPort Port for Zeebe gRPC gateway (0 = random available port, default).
 * @property restPort Port for Spring Boot REST web server (0 = random available port, default).
 * @property monitoringPort Port for Actuator management/monitoring (0 = random available port, default).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(EmbeddedCamundaTestExtension::class)
annotation class EmbeddedCamundaTest(
    val grpcPort: Int = 0,
    val restPort: Int = 0,
    val monitoringPort: Int = 0
)
