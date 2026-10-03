import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.zip.ZipFile
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream
import kotlin.test.fail

/** A fixture app running in an official Lambda base image, invoked through the Runtime Interface Emulator it ships. */
class LambdaFunction private constructor(
    private val container: GenericContainer<*>,
    private val workDir: Path? = null,
    private val baseImage: String? = null,
) {
    // A failed start is kept, so the other tests of the class fail at once instead of pulling and building again.
    private val endpoint: Result<URI> by lazy {
        runCatching {
            Runtime.getRuntime().addShutdownHook(Thread(::close))
            baseImage?.let(::pull)
            container.start()
            URI("http://${container.host}:${container.getMappedPort(RIE_PORT)}/2015-03-31/functions/function/invocations")
        }
    }

    /** Invokes the function and returns what the emulator answered: the function's result or its error report. */
    fun invoke(event: String): JsonObject = Json.parseToJsonElement(invokeRaw(event)).jsonObject

    /** Runs [block], adding the container log to any failure, where the cause usually shows. */
    @Suppress("TooGenericExceptionCaught")
    fun <T> withLogs(block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        val logs = runCatching { container.logs }.getOrDefault("(container not running)")
        throw AssertionError("${e.message}\n--- container log ---\n$logs", e)
    }

    // kotlin.test has no per-class teardown, so the container lives until the test JVM exits.
    private fun close() {
        container.stop()
        workDir?.toFile()?.deleteRecursively()
    }

    // The runtime may still be starting when the emulator already accepts connections, so retry for a while.
    private fun invokeRaw(event: String): String {
        val request = HttpRequest.newBuilder(endpoint.getOrThrow()).timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(event)).build()
        val deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos()
        var last: Exception? = null
        while (System.nanoTime() < deadline) {
            try {
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 200) return response.body()
                last = IllegalStateException("HTTP ${response.statusCode()}: ${response.body()}")
            } catch (e: ConnectException) {
                last = e
            }
            Thread.sleep(1_000)
        }
        fail("No answer from the emulator: $last")
    }

    companion object {
        private const val RIE_PORT = 8080
        private const val EXECUTABLE = 0b111_101_101
        private const val PROVIDED_AL2023 = "public.ecr.aws/lambda/provided:al2023"
        private const val JAVA_21 = "public.ecr.aws/lambda/java:21"
        private val fixtures: Path = Path(System.getProperty("fixtures.dir"))
        private val arch: String = System.getProperty("rie.arch")
        private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

        fun nativeBootstrap(): LambdaFunction {
            val target = if (arch == "arm64") "linuxArm64" else "linuxX64"
            val dir = unzip(fixtures.resolve("native/build/lambda/bootstrap-$target.zip"))
            return LambdaFunction(
                baseImage(PROVIDED_AL2023)
                    .withCopyFileToContainer(MountableFile.forHostPath(dir.resolve("bootstrap"), EXECUTABLE), "/var/runtime/bootstrap")
                    .withCommand("function.handler"),
                dir,
                PROVIDED_AL2023,
            )
        }

        fun jvmManaged(): LambdaFunction {
            val task = unzip(fixtures.resolve("jvm-managed/build/lambda/fixture-jvm-managed.zip"))
            return LambdaFunction(
                baseImage(JAVA_21)
                    .withCopyFileToContainer(MountableFile.forHostPath(task), "/var/task")
                    .withCommand("Handler"),
                task,
                JAVA_21,
            )
        }

        private fun baseImage(image: String): GenericContainer<*> = GenericContainer(DockerImageName.parse(image))
            .withCreateContainerCmdModifier { it.withPlatform("linux/$arch") }
            .lambda()

        // Pulls the requested architecture explicitly; a plain pull fetches the host's.
        private fun pull(image: String) {
            DockerClientFactory.instance().client().pullImageCmd(image).withPlatform("linux/$arch").start().awaitCompletion()
        }

        private fun GenericContainer<*>.lambda(): GenericContainer<*> =
            withExposedPorts(RIE_PORT).withEnv("AWS_LAMBDA_FUNCTION_TIMEOUT", "30")

        private fun unzip(zip: Path): Path {
            val target = Files.createTempDirectory("ktor-server-lambda-rie")
            ZipFile(zip.toFile()).use { file ->
                file.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                    val out = target.resolve(entry.name).also { it.parent.createDirectories() }
                    file.getInputStream(entry).use { input -> out.outputStream().use(input::copyTo) }
                }
            }
            return target
        }
    }
}
