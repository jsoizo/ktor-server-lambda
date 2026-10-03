import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Runs a Kotlin/Native test binary inside a Linux container and writes JUnit XML results.
 *
 * Kotlin's own test tasks run Linux binaries only on a Linux host. Running them in the image the functions run in
 * works on macOS too, and catches libraries missing from that image.
 */
abstract class NativeContainerTest @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val executable: RegularFileProperty

    @get:Input
    abstract val image: Property<String>

    /** Docker platform matching the binary, such as `linux/arm64`. */
    @get:Input
    abstract val platform: Property<String>

    @get:Input
    @get:Optional
    @get:Option(option = "tests", description = "Runs only the tests matching these patterns, separated by commas.")
    abstract val filter: Property<String>

    @get:OutputDirectory
    abstract val results: DirectoryProperty

    @TaskAction
    fun run() {
        val binary = executable.get().asFile
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        val result = runContainer(binary, output, errors)
        // Kept apart so that stderr written from another thread cannot split a service message line.
        val text = output.toString(Charsets.UTF_8)
        val errorText = errors.toString(Charsets.UTF_8)
        val directory = results.get().asFile
        directory.deleteRecursively()
        directory.mkdirs()
        val log = File(directory, "output.txt").apply { writeText(text + errorText) }
        if (result.exitValue in DOCKER_FAILURES && !text.contains("##teamcity[")) {
            throw GradleException("docker run failed with ${result.exitValue}; is Docker running?\n$errorText")
        }
        val suites = parseTeamCityOutput(text.lineSequence())
        suites.writeJUnitXml(directory)

        val tests = suites.sumOf { it.cases.size }
        val failed = suites.flatMap { suite -> suite.cases.filter { it.failure != null }.map { suite to it } }
        when {
            failed.isNotEmpty() -> throw GradleException(
                failed.joinToString("\n", "${failed.size} of $tests tests failed. Output: $log\n") { (suite, case) ->
                    "  ${suite.name}.${case.name}: ${case.failure?.message}"
                },
            )
            result.exitValue != 0 || tests == 0 -> throw GradleException(
                "The test binary exited with ${result.exitValue} after $tests tests. Output: $log\n" +
                    (text + errorText).lines().takeLast(OUTPUT_TAIL_LINES).joinToString("\n"),
            )
        }
        logger.lifecycle("$tests tests passed in ${image.get()}")
    }

    private fun runContainer(binary: File, output: ByteArrayOutputStream, errors: ByteArrayOutputStream): ExecResult = try {
        exec.exec {
            commandLine(
                // --init forwards signals: the test binary as PID 1 would ignore SIGTERM and outlive a cancelled build.
                "docker", "run", "--rm", "--init", "--platform", platform.get(),
                "--volume", "${binary.parentFile.absolutePath}:/test:ro",
                "--entrypoint", "/test/${binary.name}",
                image.get(),
                "--ktest_logger=TEAMCITY",
            )
            filter.orNull?.let { args("--ktest_gradle_filter=$it") }
            standardOutput = output
            errorOutput = errors
            isIgnoreExitValue = true
        }
    } catch (e: GradleException) {
        throw GradleException("Running the Kotlin/Native tests needs Docker: ${e.message}", e)
    }

    private companion object {
        const val OUTPUT_TAIL_LINES = 20

        // Exit codes of docker itself: it failed, or could not start the binary.
        val DOCKER_FAILURES = 125..127
    }
}
