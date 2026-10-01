import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
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
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Runs every sample in the official Lambda base images, behind the Runtime Interface Emulator they ship,
 * and invokes it the way Lambda would.
 */
class RieIntegrationTest {
    @Test
    fun nativeBootstrapOnProvidedAl2023() {
        val target = if (arch == "arm64") "linuxArm64" else "linuxX64"
        val bootstrap = unzip(samples.resolve("native-hello/build/lambda/bootstrap-$target.zip")).resolve("bootstrap")
        val container = lambdaContainer("public.ecr.aws/lambda/provided:al2023")
            .withCopyFileToContainer(MountableFile.forHostPath(bootstrap, EXECUTABLE), "/var/runtime/bootstrap")
            .withCommand("function.handler")
        verify(container, unsupportedEventError = "Runtime.UnsupportedEvent")
    }

    @Test
    fun jvmCustomRuntimeImage() {
        val image = ImageFromDockerfile("ktor-server-lambda-jvm-runtime", true)
            .withFileFromPath(".", samples.resolve("jvm-runtime"))
            .withBuildImageCmdModifier { it.withPlatform("linux/$arch") }
        // Same command as the image's ENTRYPOINT, wrapped by the emulator.
        val container = GenericContainer(image)
            .withCreateContainerCmdModifier { it.withPlatform("linux/$arch").withEntrypoint("/usr/local/bin/aws-lambda-rie") }
            .withCommand("java", "-cp", "/opt/app/lib/*", "MainKt")
            .lambda()
        verify(container, unsupportedEventError = "Runtime.UnsupportedEvent")
    }

    @Test
    fun jvmHandlerOnManagedJava21() {
        val task = unzip(samples.resolve("jvm-managed/build/lambda/jvm-managed.zip"))
        val container = lambdaContainer("public.ecr.aws/lambda/java:21")
            .withCopyFileToContainer(MountableFile.forHostPath(task), "/var/task")
            .withCommand("Handler")
        // The managed runtime reports the exception class as the error type.
        verify(container, unsupportedEventError = "UnsupportedEventException")
    }

    private fun verify(container: GenericContainer<*>, unsupportedEventError: String) {
        container.use {
            it.start()
            val endpoint = URI("http://${it.host}:${it.getMappedPort(RIE_PORT)}/2015-03-31/functions/function/invocations")
            try {
                val response = Json.parseToJsonElement(invoke(endpoint, V2_EVENT)).jsonObject
                assertEquals("200", response["statusCode"]?.jsonPrimitive?.content)
                assertContains(response["body"]!!.jsonPrimitive.content, "\"message\":\"hello\"")
                assertContains(invoke(endpoint, SQS_EVENT), unsupportedEventError)
            } catch (e: AssertionError) {
                throw AssertionError("${e.message}\n--- container log ---\n${it.logs}", e)
            }
        }
    }

    private companion object {
        const val RIE_PORT = 8080
        const val EXECUTABLE = 0b111_101_101
        val samples: Path = Path(System.getProperty("samples.dir"))
        val arch: String = System.getProperty("rie.arch")
        val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

        const val V2_EVENT = """
            {"version":"2.0","rawPath":"/","rawQueryString":"","headers":{"host":"x.lambda-url.us-east-1.on.aws"},
             "requestContext":{"domainName":"x.lambda-url.us-east-1.on.aws","stage":"${'$'}default",
             "http":{"method":"GET","sourceIp":"192.0.2.1"}},"isBase64Encoded":false}
        """
        const val SQS_EVENT = """{"Records":[{"eventSource":"aws:sqs","body":"x"}]}"""

        fun lambdaContainer(image: String): GenericContainer<*> {
            pull(image)
            return GenericContainer(DockerImageName.parse(image))
                .withCreateContainerCmdModifier { it.withPlatform("linux/$arch") }
                .lambda()
        }

        fun GenericContainer<*>.lambda(): GenericContainer<*> = withExposedPorts(RIE_PORT).withEnv("AWS_LAMBDA_FUNCTION_TIMEOUT", "30")

        // Pulls the requested architecture explicitly; a plain pull fetches the host's.
        fun pull(image: String) {
            DockerClientFactory.instance().client().pullImageCmd(image).withPlatform("linux/$arch").start().awaitCompletion()
        }

        // The runtime may still be starting when the emulator already accepts connections, so retry for a while.
        fun invoke(endpoint: URI, event: String): String {
            val request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(event)).build()
            val deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos()
            var last: Exception? = null
            while (System.nanoTime() < deadline) {
                try {
                    val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                    if (response.statusCode() == 200) return response.body()
                    last = IllegalStateException("HTTP ${response.statusCode()}: ${response.body()}")
                } catch (e: java.io.IOException) {
                    last = e
                }
                Thread.sleep(1_000)
            }
            fail("No answer from the emulator: $last")
        }

        fun unzip(zip: Path): Path {
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
