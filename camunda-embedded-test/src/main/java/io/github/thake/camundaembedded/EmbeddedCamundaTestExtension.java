package io.github.thake.camundaembedded;

import io.camunda.process.test.api.CamundaProcessTest;
import io.camunda.process.test.api.CamundaProcessTestExtension;
import io.camunda.process.test.api.CamundaProcessTestRuntimeMode;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.Optional;

public class EmbeddedCamundaTestExtension implements
        BeforeAllCallback,
        AfterAllCallback,
        BeforeEachCallback,
        AfterEachCallback {

    public static final int DEFAULT_PORT = 0;

    private static ManagedCamundaProcess server;
    private static int refCount = 0;

    private final ManagedCamundaProcess.Config config;

    private CamundaProcessTestExtension cpt;

    @CamundaProcessTest
    private static final class CamundaAnnotatedDummy {
    }

    public EmbeddedCamundaTestExtension() {
        this(ManagedCamundaProcess.Config.defaults());
    }

    public EmbeddedCamundaTestExtension(ManagedCamundaProcess.Config config) {
        this.config = (config != null) ? config : ManagedCamundaProcess.Config.defaults();
    }

    public EmbeddedCamundaTestExtension(int grpcPort, int restPort, int monitoringPort) {
        this(ManagedCamundaProcess.Config.builder()
                .grpcPort(grpcPort)
                .restPort(restPort)
                .monitoringPort(monitoringPort)
                .build());
    }

    public static synchronized ManagedCamundaProcess startServer(ManagedCamundaProcess.Config config) {
        if (server == null) {
            server = new ManagedCamundaProcess(config).start();
        }
        refCount++;
        return server;
    }

    public static synchronized ManagedCamundaProcess startServer(int grpcPort, int restPort, int monitoringPort) {
        return startServer(ManagedCamundaProcess.Config.builder()
                .grpcPort(grpcPort)
                .restPort(restPort)
                .monitoringPort(monitoringPort)
                .build());
    }

    public static synchronized void stopServer() {
        refCount--;
        if (refCount <= 0 && server != null) {
            server.close();
            server = null;
            refCount = 0;
        }
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        EmbeddedCamundaTest annotation = AnnotationSupport.findAnnotation(context.getElement(), EmbeddedCamundaTest.class)
                .orElse(null);

        ManagedCamundaProcess.Config.Builder configBuilder = ManagedCamundaProcess.Config.builder()
                .grpcPort(this.config.grpcPort())
                .restPort(this.config.restPort())
                .monitoringPort(this.config.monitoringPort())
                .maxHeap(this.config.maxHeap())
                .clockControlled(this.config.clockControlled())
                .startupTimeout(this.config.startupTimeout())
                .properties(this.config.properties());

        if (annotation != null) {
            if (annotation.grpcPort() != 0) {
                configBuilder.grpcPort(annotation.grpcPort());
            }
            if (annotation.restPort() != 0) {
                configBuilder.restPort(annotation.restPort());
            }
            if (annotation.monitoringPort() != 0) {
                configBuilder.monitoringPort(annotation.monitoringPort());
            }
        }

        ManagedCamundaProcess runningServer = startServer(configBuilder.build());

        CamundaProcessTestExtension extension = new CamundaProcessTestExtension()
                .withRuntimeMode(CamundaProcessTestRuntimeMode.REMOTE)
                .withCamundaClientBuilderOverrides(builder -> {
                    builder.grpcAddress(URI.create("http://localhost:" + runningServer.getActualGrpcPort()))
                            .restAddress(URI.create("http://localhost:" + runningServer.getActualRestPort()));
                })
                .withRemoteCamundaMonitoringApiAddress(URI.create("http://localhost:" + runningServer.getActualMonitoringPort()));
        this.cpt = extension;

        ExtensionContext wrappedContext = (ExtensionContext) Proxy.newProxyInstance(
                ExtensionContext.class.getClassLoader(),
                new Class<?>[]{ExtensionContext.class},
                (proxy, method, args) -> {
                    if ("getTestClass".equals(method.getName()) && (args == null || args.length == 0)) {
                        return Optional.of(CamundaAnnotatedDummy.class);
                    }
                    if ("getRequiredTestClass".equals(method.getName()) && (args == null || args.length == 0)) {
                        return context.getRequiredTestClass();
                    }
                    try {
                        return method.invoke(context, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
        );
        extension.beforeAll(wrappedContext);
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        if (cpt != null) {
            cpt.beforeEach(context);
        }
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        if (cpt != null) {
            cpt.afterEach(context);
        }
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        try {
            if (cpt != null) {
                cpt.afterAll(context);
            }
        } finally {
            cpt = null;
            stopServer();
        }
    }

    public ManagedCamundaProcess.Config getConfig() {
        return config;
    }

    public int getGrpcPort() {
        return config.grpcPort();
    }

    public int getRestPort() {
        return config.restPort();
    }

    public int getMonitoringPort() {
        return config.monitoringPort();
    }
}
