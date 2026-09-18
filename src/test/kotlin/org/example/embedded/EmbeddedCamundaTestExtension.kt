package org.example.embedded

import io.camunda.process.test.api.CamundaProcessTest
import io.camunda.process.test.api.CamundaProcessTestExtension
import io.camunda.process.test.api.CamundaProcessTestRuntimeMode
import org.junit.jupiter.api.extension.*
import java.net.URI
import java.util.Optional

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(EmbeddedCamundaTestExtension::class)
annotation class EmbeddedCamundaTest

@CamundaProcessTest
private class CamundaAnnotatedDummy

class EmbeddedCamundaTestExtension(
    val grpcPort: Int = DEFAULT_GRPC_PORT,
    val restPort: Int = DEFAULT_REST_PORT,
    val monitoringPort: Int = DEFAULT_MONITORING_PORT
) : BeforeAllCallback,
    AfterAllCallback,
    BeforeEachCallback,
    AfterEachCallback {

    companion object {
        const val DEFAULT_GRPC_PORT = 26500
        const val DEFAULT_REST_PORT = 8080
        const val DEFAULT_MONITORING_PORT = 9600

        private var server: ManagedCamundaProcess? = null
        private var refCount = 0

        @Synchronized
        fun startServer(
            grpcPort: Int = DEFAULT_GRPC_PORT,
            restPort: Int = DEFAULT_REST_PORT,
            monitoringPort: Int = DEFAULT_MONITORING_PORT
        ): ManagedCamundaProcess {
            if (server == null) {
                server = ManagedCamundaProcess(
                    grpcPort = grpcPort,
                    restPort = restPort,
                    monitoringPort = monitoringPort
                ).start()
            }
            refCount++
            return server!!
        }

        @Synchronized
        fun stopServer() {
            refCount--
            if (refCount <= 0 && server != null) {
                server?.close()
                server = null
                refCount = 0
            }
        }
    }

    private val cpt: CamundaProcessTestExtension = CamundaProcessTestExtension()
        .withRuntimeMode(CamundaProcessTestRuntimeMode.REMOTE)
        .withCamundaClientBuilderOverrides { builder ->
            builder.grpcAddress(URI.create("http://localhost:$grpcPort"))
                .restAddress(URI.create("http://localhost:$restPort"))
        }
        .withRemoteCamundaMonitoringApiAddress(URI.create("http://localhost:$monitoringPort"))

    override fun beforeAll(context: ExtensionContext) {
        startServer(grpcPort, restPort, monitoringPort)
        val wrappedContext = object : ExtensionContext by context {
            override fun getTestClass(): Optional<Class<*>> {
                return Optional.of(CamundaAnnotatedDummy::class.java)
            }
            override fun getRequiredTestClass(): Class<*> {
                return context.requiredTestClass
            }
        }
        cpt.beforeAll(wrappedContext)
    }

    override fun beforeEach(context: ExtensionContext) {
        cpt.beforeEach(context)
    }

    override fun afterEach(context: ExtensionContext) {
        cpt.afterEach(context)
    }

    override fun afterAll(context: ExtensionContext) {
        try {
            cpt.afterAll(context)
        } finally {
            stopServer()
        }
    }
}
