package org.example.embedded

import org.awaitility.Awaitility
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit

class ManagedCamundaProcess(
    val grpcPort: Int = 0,
    val restPort: Int = 0,
    val monitoringPort: Int = 0
) : AutoCloseable {

    var actualGrpcPort: Int = grpcPort
        private set
    var actualRestPort: Int = restPort
        private set
    var actualMonitoringPort: Int = monitoringPort
        private set

    private var process: Process? = null
    private var shutdownHook: Thread? = null
    private var argFile: File? = null

    companion object {
        fun findAvailableTcpPorts(count: Int): List<Int> {
            val sockets = mutableListOf<ServerSocket>()
            try {
                for (i in 0 until count) {
                    val socket = ServerSocket(0).apply { reuseAddress = true }
                    sockets.add(socket)
                }
                return sockets.map { it.localPort }
            } finally {
                sockets.forEach { runCatching { it.close() } }
            }
        }
    }

    fun start(): ManagedCamundaProcess {
        // Smart Classpath Discovery:
        // 1. Explicitly configured property via build tool (e.g. -Dcamunda.server.classpath=...)
        // 2. Fallback to current JVM classpath (System.getProperty("java.class.path"))
        val classpath = System.getProperty("camunda.server.classpath")
            ?: System.getProperty("java.class.path")
            ?: error("Unable to determine classpath for Camunda server.")

        val javaHome = System.getProperty("java.home")
        val javaBin = File(javaHome, "bin/java").absolutePath
        val baseDir = System.getProperty("camunda.server.basedir")
            ?: if (File("target").exists()) "target" else "build"
        val dataDir = File(baseDir, "tmp/zeebe-data").apply { mkdirs() }
        val logFile = File(baseDir, "camunda-server.log").apply { parentFile?.mkdirs() }

        // Allocate ephemeral ports for any port set to 0, including internal broker ports
        val availablePorts = findAvailableTcpPorts(5)
        var portIdx = 0

        actualGrpcPort = if (grpcPort == 0) availablePorts[portIdx++] else grpcPort
        actualRestPort = if (restPort == 0) availablePorts[portIdx++] else restPort
        actualMonitoringPort = if (monitoringPort == 0) availablePorts[portIdx++] else monitoringPort
        val commandApiPort = availablePorts[portIdx++]
        val internalApiPort = availablePorts[portIdx++]

        // Java argument file to prevent command line length limits across OSes
        val tempArgFile = File.createTempFile("camunda-cp-", ".args").apply {
            deleteOnExit()
            writeText("-cp\n$classpath\n")
        }
        argFile = tempArgFile

        val cmd = listOf(
            javaBin,
            "-Xmx1024m",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "@${tempArgFile.absolutePath}",
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
            "--camunda.data.primary-storage.directory=${dataDir.absolutePath}",
            "--camunda.api.grpc.port=$actualGrpcPort",
            "--server.port=$actualRestPort",
            "--management.server.port=$actualMonitoringPort",
            "--camunda.cluster.network.command-api.port=$commandApiPort",
            "--camunda.cluster.network.internal-api.port=$internalApiPort",
            "--camunda.system.clock-controlled=true",
            "--management.endpoints.web.exposure.include=health,cluster,clock"
        )

        val pb = ProcessBuilder(cmd).apply {
            redirectOutput(ProcessBuilder.Redirect.to(logFile))
            redirectError(ProcessBuilder.Redirect.to(logFile))
        }

        val proc = pb.start()
        process = proc

        shutdownHook = Thread {
            try {
                proc.destroy()
                proc.waitFor(5, TimeUnit.SECONDS)
            } catch (_: Exception) {
                proc.destroyForcibly()
            }
        }.also { Runtime.getRuntime().addShutdownHook(it) }

        // Wait until server reports UP on actuator health
        val healthUri = URI.create("http://localhost:$actualMonitoringPort/actuator/health").toURL()
        Awaitility.await()
            .atMost(Duration.ofMinutes(2))
            .pollInterval(Duration.ofMillis(500))
            .until {
                if (!proc.isAlive) {
                    val exitCode = proc.exitValue()
                    val errorLog = if (logFile.exists()) logFile.readText() else "No logs"
                    throw IllegalStateException("Camunda server process exited unexpectedly with code $exitCode:\n$errorLog")
                }
                try {
                    val conn = healthUri.openConnection() as HttpURLConnection
                    conn.connectTimeout = 1000
                    conn.readTimeout = 1000
                    conn.responseCode in 200..299
                } catch (_: Exception) {
                    false
                }
            }

        return this
    }

    override fun close() {
        shutdownHook?.let {
            try {
                Runtime.getRuntime().removeShutdownHook(it)
            } catch (_: IllegalStateException) {
                // JVM already shutting down
            }
        }
        process?.let { proc ->
            if (proc.isAlive) {
                proc.destroy()
                if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                }
            }
        }
        process = null
        argFile?.delete()
        argFile = null
    }
}
