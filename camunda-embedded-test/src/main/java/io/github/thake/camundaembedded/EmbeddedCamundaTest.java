package io.github.thake.camundaembedded;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to enable an embedded Camunda 8.9 test environment for JUnit 5 test classes.
 *
 * Automatically manages the lifecycle of the standalone Camunda 8.9 server
 * in an isolated child JVM using in-memory H2 and RocksDB, and configures the
 * Camunda Process Test extension to inject a ready-to-use CamundaClient.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@ExtendWith(EmbeddedCamundaTestExtension.class)
public @interface EmbeddedCamundaTest {

    /**
     * Port for Zeebe gRPC gateway (0 = random available port, default).
     */
    int grpcPort() default 0;

    /**
     * Port for Spring Boot REST web server (0 = random available port, default).
     */
    int restPort() default 0;

    /**
     * Port for Actuator management/monitoring (0 = random available port, default).
     */
    int monitoringPort() default 0;
}
