package io.github.thake.camundaembedded;

import org.awaitility.Awaitility;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ManagedCamundaProcess implements AutoCloseable {

    private final int grpcPort;
    private final int restPort;
    private final int monitoringPort;

    private int actualGrpcPort;
    private int actualRestPort;
    private int actualMonitoringPort;

    private Process process;
    private Thread shutdownHook;
    private File argFile;

    public ManagedCamundaProcess() {
        this(0, 0, 0);
    }

    public ManagedCamundaProcess(int grpcPort, int restPort, int monitoringPort) {
        this.grpcPort = grpcPort;
        this.restPort = restPort;
        this.monitoringPort = monitoringPort;
        this.actualGrpcPort = grpcPort;
        this.actualRestPort = restPort;
        this.actualMonitoringPort = monitoringPort;
    }

    public static List<Integer> findAvailableTcpPorts(int count) {
        List<ServerSocket> sockets = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                ServerSocket socket = new ServerSocket(0);
                socket.setReuseAddress(true);
                sockets.add(socket);
            }
            List<Integer> ports = new ArrayList<>();
            for (ServerSocket socket : sockets) {
                ports.add(socket.getLocalPort());
            }
            return ports;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to find available TCP ports", e);
        } finally {
            for (ServerSocket socket : sockets) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public ManagedCamundaProcess start() {
        // Smart Classpath Discovery:
        // 1. Explicitly configured property via build tool (e.g. -Dcamunda.server.classpath=...)
        // 2. Fallback to current JVM classpath (System.getProperty("java.class.path"))
        String classpath = System.getProperty("camunda.server.classpath");
        if (classpath == null || classpath.isBlank()) {
            classpath = System.getProperty("java.class.path");
        }
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalStateException("Unable to determine classpath for Camunda server.");
        }

        String javaHome = System.getProperty("java.home");
        String javaBin = Path.of(javaHome, "bin", "java").toAbsolutePath().toString();

        String baseDir = System.getProperty("camunda.server.basedir");
        if (baseDir == null || baseDir.isBlank()) {
            baseDir = new File("target").exists() ? "target" : "build";
        }
        File dataDir = new File(baseDir, "tmp/zeebe-data");
        dataDir.mkdirs();
        File logFile = new File(baseDir, "camunda-server.log");
        if (logFile.getParentFile() != null) {
            logFile.getParentFile().mkdirs();
        }

        // Allocate ephemeral ports for any port set to 0, including internal broker ports
        List<Integer> availablePorts = findAvailableTcpPorts(5);
        int portIdx = 0;

        actualGrpcPort = (grpcPort == 0) ? availablePorts.get(portIdx++) : grpcPort;
        actualRestPort = (restPort == 0) ? availablePorts.get(portIdx++) : restPort;
        actualMonitoringPort = (monitoringPort == 0) ? availablePorts.get(portIdx++) : monitoringPort;
        int commandApiPort = availablePorts.get(portIdx++);
        int internalApiPort = availablePorts.get(portIdx++);

        // Java argument file to prevent command line length limits across OSes
        File tempArgFile;
        try {
            tempArgFile = File.createTempFile("camunda-cp-", ".args");
            tempArgFile.deleteOnExit();
            Files.writeString(tempArgFile.toPath(), "-cp\n" + classpath + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create temporary arguments file", e);
        }
        this.argFile = tempArgFile;

        List<String> cmd = List.of(
                javaBin,
                "-Xmx1024m",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "@" + tempArgFile.getAbsolutePath(),
                "io.camunda.application.StandaloneCamunda",
                "--spring.profiles.active=broker,rest",
                "--camunda.security.authentication.unprotected-api=true",
                "--camunda.security.authorizations.enabled=false",
                "--camunda.database.type=rdbms",
                "--camunda.data.secondary-storage.type=rdbms",
                "--camunda.data.secondary-storage.rdbms.url=jdbc:h2:mem:camunda;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "--camunda.data.secondary-storage.rdbms.username=sa",
                "--camunda.data.secondary-storage.rdbms.password=",
                "--camunda.data.secondary-storage.rdbms.flush-interval=PT0S",
                "--camunda.data.primary-storage.directory=" + dataDir.getAbsolutePath(),
                "--camunda.api.grpc.port=" + actualGrpcPort,
                "--server.port=" + actualRestPort,
                "--management.server.port=" + actualMonitoringPort,
                "--camunda.cluster.network.command-api.port=" + commandApiPort,
                "--camunda.cluster.network.internal-api.port=" + internalApiPort,
                "--camunda.system.clock-controlled=true",
                "--management.endpoints.web.exposure.include=health,cluster,clock"
        );

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.to(logFile))
                .redirectError(ProcessBuilder.Redirect.to(logFile));

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start Camunda server process", e);
        }
        this.process = proc;

        this.shutdownHook = new Thread(() -> {
            try {
                proc.destroy();
                proc.waitFor(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                proc.destroyForcibly();
            }
        });
        Runtime.getRuntime().addShutdownHook(this.shutdownHook);

        // Wait until server reports UP on actuator health
        try {
            URL healthUrl = URI.create("http://localhost:" + actualMonitoringPort + "/actuator/health").toURL();
            Awaitility.await()
                    .atMost(Duration.ofMinutes(2))
                    .pollInterval(Duration.ofMillis(500))
                    .until(() -> {
                        if (!proc.isAlive()) {
                            int exitCode = proc.exitValue();
                            String errorLog = logFile.exists() ? Files.readString(logFile.toPath()) : "No logs";
                            throw new IllegalStateException("Camunda server process exited unexpectedly with code " + exitCode + ":\n" + errorLog);
                        }
                        try {
                            HttpURLConnection conn = (HttpURLConnection) healthUrl.openConnection();
                            conn.setConnectTimeout(1000);
                            conn.setReadTimeout(1000);
                            int responseCode = conn.getResponseCode();
                            return responseCode >= 200 && responseCode <= 299;
                        } catch (Exception e) {
                            return false;
                        }
                    });
        } catch (Exception e) {
            close();
            throw (e instanceof RuntimeException ? (RuntimeException) e : new IllegalStateException(e));
        }

        return this;
    }

    @Override
    public void close() {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM already shutting down
            }
            shutdownHook = null;
        }
        if (process != null) {
            if (process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(10, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
            process = null;
        }
        if (argFile != null) {
            argFile.delete();
            argFile = null;
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

    public int getActualGrpcPort() {
        return actualGrpcPort;
    }

    public int getActualRestPort() {
        return actualRestPort;
    }

    public int getActualMonitoringPort() {
        return actualMonitoringPort;
    }

    public Process getProcess() {
        return process;
    }
}
