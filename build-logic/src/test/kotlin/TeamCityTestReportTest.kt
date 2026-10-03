import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeamCityTestReportTest {
    // Trimmed from a real linuxArm64 test binary run with --ktest_logger=TEAMCITY.
    private val output = """
        ##teamcity[testSuiteStarted name='com.example.EngineTest' locationHint='ktest:suite://com.example.EngineTest']
        ##teamcity[testStarted name='serves' locationHint='ktest:test://com.example.EngineTest.serves']
        [INFO] (io.ktor.server.Application): Responding at http://0.0.0.0:39135
        ##teamcity[testFinished name='serves' duration='25']
        ##teamcity[testStarted name='fails' locationHint='ktest:test://com.example.EngineTest.fails']
        ##teamcity[testFailed name='fails' message='Expected <it|'s |[a|]|nb||c>, actual <other>.' details='kotlin.AssertionError: Expected <it|'s |[a|]|nb||c>, actual <other>.|n    at 0   test.kexe']
        ##teamcity[testFinished name='fails' duration='0']
        ##teamcity[testIgnored name='ignored']
        ##teamcity[testSuiteFinished name='com.example.EngineTest']
        ##teamcity[testSuiteStarted name='com.example.CodecTest' locationHint='ktest:suite://com.example.CodecTest']
        printed without a newline##teamcity[testStarted name='decodes' locationHint='ktest:test://com.example.CodecTest.decodes']
        ##teamcity[testFinished name='decodes' duration='1500']
        ##teamcity[testSuiteFinished name='com.example.CodecTest']
    """.trimIndent()

    @Test
    fun readsResultsAndOutputPerTest() {
        val (engine, codec) = parseTeamCityOutput(output.lineSequence())
        val (serves, fails, ignored) = engine.cases

        assertEquals(listOf("serves", "fails", "ignored"), engine.cases.map { it.name })
        assertNull(serves.failure)
        assertEquals(25, serves.durationMillis)
        assertEquals("[INFO] (io.ktor.server.Application): Responding at http://0.0.0.0:39135\n", serves.output.toString())
        assertEquals("Expected <it's [a]\nb|c>, actual <other>.", fails.failure?.message)
        assertTrue(ignored.skipped)
        assertEquals(listOf("decodes"), codec.cases.map { it.name })
        assertEquals("printed without a newline\n", codec.output.toString())
    }

    @Test
    fun countsATestTheBinaryDiedInAsFailed() {
        val crashed = "##teamcity[testSuiteStarted name='S']\n##teamcity[testStarted name='crashes']\nSegmentation fault"

        val case = parseTeamCityOutput(crashed.lineSequence()).single().cases.single()

        assertEquals("The test binary exited before the test finished", case.failure?.message)
        assertEquals("Segmentation fault\n", case.output.toString())
    }

    @Test
    fun writesJUnitXmlPerSuite() {
        val directory = Files.createTempDirectory("junit").toFile()
        parseTeamCityOutput(output.lineSequence()).writeJUnitXml(directory)

        val suite = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(directory.resolve("TEST-com.example.EngineTest.xml")).documentElement
        assertEquals(listOf("3", "1", "1"), listOf("tests", "failures", "skipped").map(suite::getAttribute))
        val failure = suite.getElementsByTagName("failure").item(0)
        assertTrue(failure.textContent.startsWith("kotlin.AssertionError: Expected <it's [a]\nb|c>, actual <other>.\n"))
        assertEquals(
            "1.500",
            DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(directory.resolve("TEST-com.example.CodecTest.xml")).documentElement.getAttribute("time"),
        )
    }
}
