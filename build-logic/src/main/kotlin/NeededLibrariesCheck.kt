import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fails when an ELF executable needs a shared library outside [allowed].
 *
 * The Lambda `provided.al2023` image ships only a few libraries, and a missing one makes the function
 * fail during INIT. The ELF dynamic segment is parsed directly so the check runs on any build host;
 * macOS has no `readelf`.
 */
@CacheableTask
abstract class NeededLibrariesCheck : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val executable: RegularFileProperty

    /** Allowed names; a trailing `*` matches any suffix, as in `ld-linux-*`. */
    @get:Input
    abstract val allowed: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val file = executable.get().asFile
        val needed = try {
            readNeededLibraries(file.readBytes())
        } catch (e: RuntimeException) {
            throw GradleException("Cannot read the ELF dynamic section of ${file.name}: ${e.message}", e)
        }
        val patterns = allowed.get()
        val disallowed = needed.filterNot { name -> patterns.any { matches(name, it) } }
        report.get().asFile.writeText(needed.joinToString("\n", postfix = "\n"))
        if (disallowed.isNotEmpty()) {
            throw GradleException(
                "${executable.get().asFile.name} needs libraries missing from provided.al2023: $disallowed",
            )
        }
    }

    private fun matches(name: String, pattern: String): Boolean =
        if (pattern.endsWith("*")) name.startsWith(pattern.dropLast(1)) else name == pattern
}

private const val ELF_CLASS_64: Byte = 2
private const val ELF_DATA_LITTLE_ENDIAN: Byte = 1
private const val ELF_HEADER_SIZE = 0x40
private const val PROGRAM_TYPE_LOAD = 1
private const val PROGRAM_TYPE_DYNAMIC = 2
private const val DYNAMIC_TAG_NULL = 0L
private const val DYNAMIC_TAG_NEEDED = 1L
private const val DYNAMIC_TAG_STRTAB = 5L
private const val DYNAMIC_ENTRY_SIZE = 16
private val ELF_MAGIC = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())

/**
 * Reads the `DT_NEEDED` entries of a little-endian ELF64 file, which covers both Lambda architectures.
 *
 * Uses the program headers, as the dynamic loader does, so stripped section headers cannot hide a dependency.
 * A binary without `PT_DYNAMIC` is statically linked and needs nothing.
 */
internal fun readNeededLibraries(elf: ByteArray): List<String> {
    require(elf.size >= ELF_HEADER_SIZE && elf.copyOfRange(0, 4).contentEquals(ELF_MAGIC)) { "Not an ELF file" }
    require(elf[4] == ELF_CLASS_64 && elf[5] == ELF_DATA_LITTLE_ENDIAN) { "Only little-endian ELF64 is supported" }
    val buffer = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN)
    val programHeaders = buffer.getLong(0x20).toInt()
    val programHeaderSize = buffer.getShort(0x36).toInt() and 0xFFFF
    val programCount = buffer.getShort(0x38).toInt() and 0xFFFF
    val segments = (0 until programCount).map { index ->
        val at = programHeaders + index * programHeaderSize
        Segment(
            type = buffer.getInt(at),
            offset = buffer.getLong(at + 0x08),
            virtualAddress = buffer.getLong(at + 0x10),
            fileSize = buffer.getLong(at + 0x20),
        )
    }
    val dynamic = segments.firstOrNull { it.type == PROGRAM_TYPE_DYNAMIC } ?: return emptyList()
    val entries = (0 until dynamic.fileSize.toInt() step DYNAMIC_ENTRY_SIZE)
        .asSequence()
        .map { dynamic.offset.toInt() + it }
        .map { buffer.getLong(it) to buffer.getLong(it + 8) }
        .takeWhile { (tag, _) -> tag != DYNAMIC_TAG_NULL }
        .toList()
    val stringTableAddress = entries.firstOrNull { it.first == DYNAMIC_TAG_STRTAB }?.second
        ?: error("PT_DYNAMIC has no DT_STRTAB")
    // DT_STRTAB is a virtual address; the PT_LOAD segment that maps it gives its file offset.
    val load = segments.firstOrNull {
        it.type == PROGRAM_TYPE_LOAD && stringTableAddress in it.virtualAddress until it.virtualAddress + it.fileSize
    } ?: error("DT_STRTAB is outside every PT_LOAD segment")
    val stringTable = (stringTableAddress - load.virtualAddress + load.offset).toInt()
    return entries
        .filter { (tag, _) -> tag == DYNAMIC_TAG_NEEDED }
        .map { (_, offset) -> readCString(elf, stringTable + offset.toInt()) }
}

private class Segment(val type: Int, val offset: Long, val virtualAddress: Long, val fileSize: Long)

private fun readCString(bytes: ByteArray, offset: Int): String {
    var end = offset
    while (bytes[end] != 0.toByte()) end++
    return String(bytes, offset, end - offset, Charsets.US_ASCII)
}
