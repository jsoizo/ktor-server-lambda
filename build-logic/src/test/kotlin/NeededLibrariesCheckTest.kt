import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NeededLibrariesCheckTest {
    @Test
    fun readsNeededLibrariesThroughTheDynamicSegment() {
        assertEquals(listOf("libc.so.6", "libm.so.6"), readNeededLibraries(elf(listOf("libc.so.6", "libm.so.6"))))
    }

    @Test
    fun staticBinaryWithoutDynamicSegmentNeedsNothing() {
        assertEquals(emptyList(), readNeededLibraries(elf(listOf("libc.so.6"), withDynamic = false)))
    }

    @Test
    fun failsInsteadOfPassingWhenTheStringTableCannotBeLocated() {
        assertFailsWith<IllegalStateException> { readNeededLibraries(elf(listOf("libc.so.6"), stringTableInLoad = false)) }
    }

    @Test
    fun rejectsFilesThatAreNotLittleEndianElf64() {
        assertFailsWith<IllegalArgumentException> { readNeededLibraries(ByteArray(128)) }
    }

    /** Builds the smallest ELF64 file the parser reads: header, PT_LOAD, optional PT_DYNAMIC, dynamic entries, strings. */
    private fun elf(needed: List<String>, withDynamic: Boolean = true, stringTableInLoad: Boolean = true): ByteArray {
        val base = 0x400000L
        val headerSize = 0x40
        val programHeaderSize = 0x38
        val programHeaders = if (withDynamic) 2 else 1
        val dynamicOffset = headerSize + programHeaders * programHeaderSize
        val dynamicEntries = needed.size + 2
        val stringsOffset = dynamicOffset + dynamicEntries * 16
        val strings = needed.fold(byteArrayOf(0)) { acc, name -> acc + name.toByteArray() + 0 }
        val size = stringsOffset + strings.size
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1))
        buffer.putLong(0x20, headerSize.toLong())
        buffer.putShort(0x36, programHeaderSize.toShort())
        buffer.putShort(0x38, programHeaders.toShort())
        // PT_LOAD mapping the whole file at `base`, or a range that misses the string table.
        buffer.putInt(headerSize, 1)
        buffer.putLong(headerSize + 0x08, 0)
        buffer.putLong(headerSize + 0x10, base)
        buffer.putLong(headerSize + 0x20, if (stringTableInLoad) size.toLong() else stringsOffset.toLong())
        if (withDynamic) {
            val at = headerSize + programHeaderSize
            buffer.putInt(at, 2)
            buffer.putLong(at + 0x08, dynamicOffset.toLong())
            buffer.putLong(at + 0x10, base + dynamicOffset)
            buffer.putLong(at + 0x20, (dynamicEntries * 16).toLong())
        }
        var entry = dynamicOffset
        var nameOffset = 1L
        for (name in needed) {
            buffer.putLong(entry, 1)
            buffer.putLong(entry + 8, nameOffset)
            nameOffset += name.length + 1
            entry += 16
        }
        buffer.putLong(entry, 5)
        buffer.putLong(entry + 8, base + stringsOffset)
        buffer.position(stringsOffset)
        buffer.put(strings)
        return buffer.array()
    }
}
