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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ManagedCamundaProcess implements AutoCloseable {

    private static final String MAIN_CLASS = "io.camunda.application.StandaloneCamunda";
    private static final String SPRING_PROFILES = "broker,rest";
    private static final String DATABASE_TYPE = "rdbms";
    private static final String DATABASE_URL = "jdbc:h2:mem:camunda;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
    private static final String DATABASE_USER = "sa";
    private static final String DATABASE_PASSWORD = "";
    private static final String HEALTH_ENDPOINT = "/actuator/health";
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final List<String> JVM_ARGS = List.of(
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED"
    );

    private final Config config;

    private int actualGrpcPort;
    private int actualRestPort;
    private int actualMonitoringPort;

    private Process process;
    private Thread shutdownHook;
    private File argFile;

    public ManagedCamundaProcess() {
        this(Config.defaults());
    }

    public ManagedCamundaProcess(int grpcPort, int restPort, int monitoringPort) {
        this(Config.builder()
                .grpcPort(grpcPort)
                .restPort(restPort)
                .monitoringPort(monitoringPort)
                .build());
    }

    public ManagedCamundaProcess(Config config) {
        this.config = (config != null) ? config : Config.defaults();
        this.actualGrpcPort = this.config.grpcPort();
        this.actualRestPort = this.config.restPort();
        this.actualMonitoringPort = this.config.monitoringPort();
    }

    /**
     * Configuration record containing relevant Camunda start parameters for {@link ManagedCamundaProcess}.
     */
    public record Config(
            int grpcPort,
            int restPort,
            int monitoringPort,
            String maxHeap,
            boolean clockControlled,
            Duration startupTimeout,
            Map<String, String> properties
    ) {

        public Config {
            if (grpcPort < 0 || restPort < 0 || monitoringPort < 0) {
                throw new IllegalArgumentException("Ports cannot be negative");
            }
            if (maxHeap == null || maxHeap.isBlank()) {
                maxHeap = "1024m";
            }
            if (startupTimeout == null) {
                startupTimeout = Duration.ofMinutes(2);
            }
            properties = (properties != null) ? Map.copyOf(properties) : Map.of();
        }

        public Config() {
            this(0, 0, 0, "1024m", true, Duration.ofMinutes(2), Map.of());
        }

        public Config(int grpcPort, int restPort, int monitoringPort) {
            this(grpcPort, restPort, monitoringPort, "1024m", true, Duration.ofMinutes(2), Map.of());
        }

        public static Config defaults() {
            return new Config();
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private int grpcPort = 0;
            private int restPort = 0;
            private int monitoringPort = 0;
            private String maxHeap = "1024m";
            private boolean clockControlled = true;
            private Duration startupTimeout = Duration.ofMinutes(2);
            private Map<String, String> properties = new LinkedHashMap<>();

            public Builder grpcPort(int grpcPort) {
                this.grpcPort = grpcPort;
                return this;
            }

            public Builder restPort(int restPort) {
                this.restPort = restPort;
                return this;
            }

            public Builder monitoringPort(int monitoringPort) {
                this.monitoringPort = monitoringPort;
                return this;
            }

            public Builder maxHeap(String maxHeap) {
                this.maxHeap = maxHeap;
                return this;
            }

            public Builder clockControlled(boolean clockControlled) {
                this.clockControlled = clockControlled;
                return this;
            }

            public Builder startupTimeout(Duration startupTimeout) {
                this.startupTimeout = startupTimeout;
                return this;
            }

            public Builder properties(Map<String, String> properties) {
                this.properties = (properties != null) ? new LinkedHashMap<>(properties) : new LinkedHashMap<>();
                return this;
            }

            public Builder property(String key, String value) {
                if (this.properties == null) {
                    this.properties = new LinkedHashMap<>();
                }
                this.properties.put(key, value);
                return this;
            }

            public Config build() {
                return new Config(grpcPort, restPort, monitoringPort, maxHeap, clockControlled, startupTimeout, properties);
            }
        }
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

        String propBaseDir = System.getProperty("camunda.server.basedir");
        Path baseDir = (propBaseDir != null && !propBaseDir.isBlank())
                ? Path.of(propBaseDir)
                : (new File("target").exists() ? Path.of("target") : Path.of("build"));
        Path dataDir = baseDir.resolve("tmp/zeebe-data");
        Path logFile = baseDir.resolve("camunda-server.log");

        try {
            Files.createDirectories(dataDir);
            if (logFile.getParent() != null) {
                Files.createDirectories(logFile.getParent());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare storage or log directory", e);
        }

        // Allocate ephemeral ports for any port set to 0, plus 2 internal cluster ports
        int neededEphemeralPorts = 2; // for internal commandApiPort and internalApiPort
        if (config.grpcPort() == 0) neededEphemeralPorts++;
        if (config.restPort() == 0) neededEphemeralPorts++;
        if (config.monitoringPort() == 0) neededEphemeralPorts++;

        List<Integer> availablePorts = findAvailableTcpPorts(neededEphemeralPorts);
        int portIdx = 0;

        actualGrpcPort = (config.grpcPort() == 0) ? availablePorts.get(portIdx++) : config.grpcPort();
        actualRestPort = (config.restPort() == 0) ? availablePorts.get(portIdx++) : config.restPort();
        actualMonitoringPort = (config.monitoringPort() == 0) ? availablePorts.get(portIdx++) : config.monitoringPort();
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

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        String heapArg = config.maxHeap().startsWith("-Xmx") ? config.maxHeap() : "-Xmx" + config.maxHeap();
        cmd.add(heapArg);
        cmd.addAll(JVM_ARGS);
        cmd.add("@" + tempArgFile.getAbsolutePath());
        cmd.add(MAIN_CLASS);
        cmd.add("--spring.profiles.active=" + SPRING_PROFILES);
        cmd.add("--camunda.security.authentication.unprotected-api=true");
        cmd.add("--camunda.security.authorizations.enabled=false");
        cmd.add("--camunda.database.type=" + DATABASE_TYPE);
        cmd.add("--camunda.data.secondary-storage.type=" + DATABASE_TYPE);
        cmd.add("--camunda.data.secondary-storage.rdbms.url=" + DATABASE_URL);
        cmd.add("--camunda.data.secondary-storage.rdbms.username=" + DATABASE_USER);
        cmd.add("--camunda.data.secondary-storage.rdbms.password=" + DATABASE_PASSWORD);
        cmd.add("--camunda.data.secondary-storage.rdbms.flush-interval=PT0S");
        cmd.add("--camunda.data.primary-storage.directory=" + dataDir.toAbsolutePath());
        cmd.add("--camunda.api.grpc.port=" + actualGrpcPort);
        cmd.add("--server.port=" + actualRestPort);
        cmd.add("--management.server.port=" + actualMonitoringPort);
        cmd.add("--camunda.cluster.network.command-api.port=" + commandApiPort);
        cmd.add("--camunda.cluster.network.internal-api.port=" + internalApiPort);
        cmd.add("--camunda.system.clock-controlled=" + config.clockControlled());
        cmd.add("--management.endpoints.web.exposure.include=health,cluster,clock");

        // Custom Camunda / Spring properties
        for (Map.Entry<String, String> entry : config.properties().entrySet()) {
            cmd.add("--" + entry.getKey() + "=" + entry.getValue());
        }

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()))
                .redirectError(ProcessBuilder.Redirect.to(logFile.toFile()));

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
            URL healthUrl = URI.create("http://localhost:" + actualMonitoringPort + HEALTH_ENDPOINT).toURL();
            Awaitility.await()
                    .atMost(config.startupTimeout())
                    .pollInterval(POLL_INTERVAL)
                    .until(() -> {
                        if (!proc.isAlive()) {
                            int exitCode = proc.exitValue();
                            String errorLog = Files.exists(logFile) ? Files.readString(logFile) : "No logs";
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

    public Config getConfig() {
        return config;
    }

    public Config config() {
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
