package io.github.thake.camundaembedded

import io.camunda.process.test.api.CamundaProcessTest
import io.camunda.process.test.api.CamundaProcessTestExtension
import io.camunda.process.test.api.CamundaProcessTestRuntimeMode
import org.junit.jupiter.api.extension.*
import org.junit.platform.commons.support.AnnotationSupport
import java.net.URI
import java.util.Optional

@CamundaProcessTest
private class CamundaAnnotatedDummy

class EmbeddedCamundaTestExtension(
    val grpcPort: Int = DEFAULT_PORT,
    val restPort: Int = DEFAULT_PORT,
    val monitoringPort: Int = DEFAULT_PORT
) : BeforeAllCallback,
    AfterAllCallback,
    BeforeEachCallback,
    AfterEachCallback {

    companion object {
        const val DEFAULT_PORT = 0

        private var server: ManagedCamundaProcess? = null
        private var refCount = 0

        @Synchronized
        fun startServer(
            grpcPort: Int = DEFAULT_PORT,
            restPort: Int = DEFAULT_PORT,
            monitoringPort: Int = DEFAULT_PORT
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

    private var cpt: CamundaProcessTestExtension? = null

    override fun beforeAll(context: ExtensionContext) {
        val annotation = AnnotationSupport.findAnnotation(context.element, EmbeddedCamundaTest::class.java).orElse(null)

        val targetGrpcPort = annotation?.grpcPort ?: grpcPort
        val targetRestPort = annotation?.restPort ?: restPort
        val targetMonitoringPort = annotation?.monitoringPort ?: monitoringPort

        val runningServer = startServer(targetGrpcPort, targetRestPort, targetMonitoringPort)

        val extension = CamundaProcessTestExtension()
            .withRuntimeMode(CamundaProcessTestRuntimeMode.REMOTE)
            .withCamundaClientBuilderOverrides { builder ->
                builder.grpcAddress(URI.create("http://localhost:${runningServer.actualGrpcPort}"))
                    .restAddress(URI.create("http://localhost:${runningServer.actualRestPort}"))
            }
            .withRemoteCamundaMonitoringApiAddress(URI.create("http://localhost:${runningServer.actualMonitoringPort}"))
        cpt = extension

        val wrappedContext = object : ExtensionContext by context {
            override fun getTestClass(): Optional<Class<*>> {
                return Optional.of(CamundaAnnotatedDummy::class.java)
            }
            override fun getRequiredTestClass(): Class<*> {
                return context.requiredTestClass
            }
        }
        extension.beforeAll(wrappedContext)
    }

    override fun beforeEach(context: ExtensionContext) {
        cpt?.beforeEach(context)
    }

    override fun afterEach(context: ExtensionContext) {
        cpt?.afterEach(context)
    }

    override fun afterAll(context: ExtensionContext) {
        try {
            cpt?.afterAll(context)
        } finally {
            cpt = null
            stopServer()
        }
    }
}
