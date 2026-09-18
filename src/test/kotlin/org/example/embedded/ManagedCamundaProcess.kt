package org.example.embedded

import org.awaitility.Awaitility
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit

class ManagedCamundaProcess(
    private val distDir: File = findDistDir(),
    val grpcPort: Int = 26500,
    val restPort: Int = 8080,
    val monitoringPort: Int = 9600
) : AutoCloseable {

    private var process: Process? = null
    private var shutdownHook: Thread? = null

    companion object {
        fun findDistDir(): File {
            val distPath = System.getProperty("camunda.dist.path")
                ?: "build/camunda-dist"
            val file = File(distPath)
            if (File(file, "bin/camunda").exists()) {
                return file
            }
            val subdirs = file.listFiles { f -> f.isDirectory && File(f, "bin/camunda").exists() }
            if (!subdirs.isNullOrEmpty()) {
                return subdirs[0]
            }
            throw IllegalStateException("Could not find bin/camunda in $distPath. Please run './gradlew unpackCamunda' first.")
        }
    }

    fun start(): ManagedCamundaProcess {
        val binCamunda = File(distDir, "bin/camunda").absoluteFile
        val logFile = File("build/camunda-server.log").apply { parentFile.mkdirs() }
        val dataDir = File("build/tmp/zeebe-data").apply { mkdirs() }

        val pb = ProcessBuilder(binCamunda.absolutePath).apply {
            directory(distDir)
            redirectOutput(ProcessBuilder.Redirect.to(logFile))
            redirectError(ProcessBuilder.Redirect.to(logFile))
        }

        val env = pb.environment()
        env["CAMUNDA_SECURITY_AUTHENTICATION_UNPROTECTEDAPI"] = "true"
        env["CAMUNDA_SECURITY_AUTHORIZATIONS_ENABLED"] = "false"
        env["CAMUNDA_DATABASE_TYPE"] = "rdbms"
        env["CAMUNDA_DATA_SECONDARYSTORAGE_TYPE"] = "rdbms"
        env["CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_URL"] = "jdbc:h2:mem:camunda;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        env["CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_USERNAME"] = "sa"
        env["CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_PASSWORD"] = ""
        env["CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_FLUSHINTERVAL"] = "PT0S"
        env["ZEEBE_BROKER_DATA_DIRECTORY"] = dataDir.absolutePath
        env["SPRING_PROFILES_ACTIVE"] = "broker,rest"

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
        val healthUri = URI.create("http://localhost:$monitoringPort/actuator/health").toURL()
        Awaitility.await()
            .atMost(Duration.ofMinutes(2))
            .pollInterval(Duration.ofMillis(500))
            .until {
                if (!proc.isAlive) {
                    val exitCode = proc.exitValue()
                    throw IllegalStateException("Camunda server exited prematurely with code $exitCode. See $logFile for details.")
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
    }
}
