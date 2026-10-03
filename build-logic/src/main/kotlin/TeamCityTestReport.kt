import java.io.File
import java.util.Locale
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

/** A test class as the Kotlin/Native test runner reports it. */
internal class TestSuiteResult(val name: String) {
    val cases = mutableListOf<TestCaseResult>()

    /** Output printed while no test of the suite was running. */
    val output = StringBuilder()

    val failures get() = cases.count { it.failure != null }
    val skipped get() = cases.count { it.skipped }
}

internal class TestCaseResult(val name: String) {
    var durationMillis = 0L
    var failure: TestFailure? = null
    var skipped = false
    val output = StringBuilder()
}

internal data class TestFailure(val message: String, val details: String)

/**
 * Reads the output of a Kotlin/Native test binary run with `--ktest_logger=TEAMCITY`.
 *
 * Lines that are not service messages are what the tests printed; they go to the test running at that moment.
 * A test that started but never finished counts as failed, since the binary stopped in the middle of it.
 */
internal fun parseTeamCityOutput(lines: Sequence<String>): List<TestSuiteResult> {
    val suites = mutableListOf<TestSuiteResult>()
    val open = ArrayDeque<TestSuiteResult>()
    var running: TestCaseResult? = null

    fun currentSuite(): TestSuiteResult = open.lastOrNull() ?: TestSuiteResult("").also { suites += it; open.addLast(it) }

    for (line in lines) {
        val start = line.indexOf(SERVICE_MESSAGE)
        val text = if (start < 0) line else line.substring(0, start)
        if (text.isNotEmpty() || start < 0) (running?.output ?: currentSuite().output).appendLine(text)
        if (start < 0) continue
        val (type, attributes) = parseServiceMessage(line.substring(start + SERVICE_MESSAGE.length)) ?: continue
        val name = attributes["name"].orEmpty()
        when (type) {
            "testSuiteStarted" -> TestSuiteResult(name).also { suites += it; open.addLast(it) }
            "testSuiteFinished" -> open.removeLastOrNull()
            "testStarted" -> running = TestCaseResult(name).also { currentSuite().cases += it }
            "testFailed" -> running?.failure = TestFailure(attributes["message"].orEmpty(), attributes["details"].orEmpty())
            "testFinished" -> {
                running?.durationMillis = attributes["duration"]?.toLongOrNull() ?: 0L
                running = null
            }
            "testIgnored" -> currentSuite().cases += TestCaseResult(name).apply { skipped = true }
        }
    }
    running?.let { it.failure = it.failure ?: TestFailure("The test binary exited before the test finished", "") }
    return suites.filter { it.cases.isNotEmpty() }
}

/** Writes one `TEST-<suite>.xml` per suite, in the JUnit format that CI tools and Gradle's own test tasks use. */
internal fun List<TestSuiteResult>.writeJUnitXml(directory: File) {
    directory.mkdirs()
    for (suite in this) {
        File(directory, "TEST-${suite.name.ifEmpty { "tests" }}.xml").bufferedWriter().use { out ->
            val xml = XMLOutputFactory.newInstance().createXMLStreamWriter(out)
            xml.writeStartDocument("UTF-8", "1.0")
            xml.writeStartElement("testsuite")
            xml.writeAttribute("name", suite.name)
            xml.writeAttribute("tests", suite.cases.size.toString())
            xml.writeAttribute("skipped", suite.skipped.toString())
            xml.writeAttribute("failures", suite.failures.toString())
            xml.writeAttribute("errors", "0")
            xml.writeAttribute("time", seconds(suite.cases.sumOf { it.durationMillis }))
            for (case in suite.cases) xml.writeTestCase(suite.name, case)
            xml.writeOutput(suite.output)
            xml.writeEndElement()
            xml.writeEndDocument()
            xml.close()
        }
    }
}

private fun XMLStreamWriter.writeTestCase(suite: String, case: TestCaseResult) {
    writeStartElement("testcase")
    writeAttribute("name", case.name)
    writeAttribute("classname", suite)
    writeAttribute("time", seconds(case.durationMillis))
    case.failure?.let {
        writeStartElement("failure")
        writeAttribute("message", xmlSafe(it.message))
        writeCharacters(xmlSafe(it.details))
        writeEndElement()
    }
    if (case.skipped) writeEmptyElement("skipped")
    writeOutput(case.output)
    writeEndElement()
}

private fun XMLStreamWriter.writeOutput(output: CharSequence) {
    if (output.isEmpty()) return
    writeStartElement("system-out")
    writeCharacters(xmlSafe(output.toString()))
    writeEndElement()
}

private const val SERVICE_MESSAGE = "##teamcity["
private const val MILLIS_PER_SECOND = 1000.0

private fun seconds(millis: Long) = String.format(Locale.ROOT, "%.3f", millis / MILLIS_PER_SECOND)

// Tests may print control characters, which XML 1.0 cannot carry even escaped.
private fun xmlSafe(text: String) = text.filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }

/** Parses `type name='value' ...]`, the part of a service message after `##teamcity[`. */
private fun parseServiceMessage(body: String): Pair<String, Map<String, String>>? {
    val type = body.takeWhile { it.isLetterOrDigit() }
    if (type.isEmpty()) return null
    val attributes = mutableMapOf<String, String>()
    var i = type.length
    while (i < body.length && body[i] != ']') {
        if (body[i] == ' ') {
            i++
            continue
        }
        val equals = body.indexOf("='", i)
        if (equals < 0) return null
        val key = body.substring(i, equals)
        val value = StringBuilder()
        i = equals + 2
        while (i < body.length && body[i] != '\'') {
            if (body[i] == '|' && i + 1 < body.length) {
                i++
                // Kotlin/Native escapes only | ' [ ] and line breaks.
                when (body[i]) {
                    'n' -> value.append('\n')
                    'r' -> value.append('\r')
                    else -> value.append(body[i])
                }
            } else {
                value.append(body[i])
            }
            i++
        }
        attributes[key] = value.toString()
        i++
    }
    return type to attributes
}
