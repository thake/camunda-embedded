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

    private final int grpcPort;
    private final int restPort;
    private final int monitoringPort;

    private CamundaProcessTestExtension cpt;

    @CamundaProcessTest
    private static final class CamundaAnnotatedDummy {
    }

    public EmbeddedCamundaTestExtension() {
        this(DEFAULT_PORT, DEFAULT_PORT, DEFAULT_PORT);
    }

    public EmbeddedCamundaTestExtension(int grpcPort, int restPort, int monitoringPort) {
        this.grpcPort = grpcPort;
        this.restPort = restPort;
        this.monitoringPort = monitoringPort;
    }

    public static synchronized ManagedCamundaProcess startServer(int grpcPort, int restPort, int monitoringPort) {
        if (server == null) {
            server = new ManagedCamundaProcess(grpcPort, restPort, monitoringPort).start();
        }
        refCount++;
        return server;
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

        int targetGrpcPort = (annotation != null && annotation.grpcPort() != 0) ? annotation.grpcPort() : this.grpcPort;
        int targetRestPort = (annotation != null && annotation.restPort() != 0) ? annotation.restPort() : this.restPort;
        int targetMonitoringPort = (annotation != null && annotation.monitoringPort() != 0) ? annotation.monitoringPort() : this.monitoringPort;

        ManagedCamundaProcess runningServer = startServer(targetGrpcPort, targetRestPort, targetMonitoringPort);

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

    public int getGrpcPort() {
        return grpcPort;
    }

    public int getRestPort() {
        return restPort;
    }

    public int getMonitoringPort() {
        return monitoringPort;
    }
}
