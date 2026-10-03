package io.specqr

/** Kotlin-native checks for ownership, bounded validation, exact segmentation, and QR algebra. */
object CoreSegmentTests {
    @JvmStatic fun main(args: Array<String>) { println("Kotlin core/segments: ${run()} assertions passed") }

    @JvmStatic fun run(): Int {
        var assertions = 0
        fun verify(condition: Boolean) { check(condition); assertions++ }
        fun rejects(code: String, action: () -> Unit) {
            try { action(); error("Expected $code") }
            catch (failure: SpecQrException) { verify(failure.code == code) }
        }
        fun immutable(action: () -> Unit) {
            try { action(); error("Mutable collection exposed") }
            catch (_: UnsupportedOperationException) { assertions++ }
        }

        val owned = byteArrayOf(0, 127, 128.toByte(), 255.toByte())
        val segment = Segment.bytes(owned)
        owned.fill(42)
        verify(segment.binary!!.contentEquals(byteArrayOf(0, 127, 128.toByte(), 255.toByte())))
        segment.binary!!.fill(3)
        segment.logicalBytes.fill(4)
        verify(segment.logicalBytes.contentEquals(byteArrayOf(0, 127, 128.toByte(), 255.toByte())))
        verify(segment.count == 4 && segment.byteCount == 4 && segment.characterCount == 0)
        val unicode = Segment.bytes("漢😀")
        verify(unicode.characterCount == 2 && unicode.byteCount == 7)
        verify(unicode.mode == "byte" && unicode.text == "漢😀" && unicode.binary == null)
        verify(Segment.kanji("漢字").byteCount == 4)
        verify(Segment.kanji("漢字").logicalBytes.contentEquals("漢字".toByteArray()))
        for (invalid in arrayOf(null, "auto", "eci", "fnc1", "fnc1-second", "structured-append", "unknown")) {
            rejects("INVALID_MODE") { Segment.fromText(invalid, "payload") }
        }
        for (invalid in listOf("\uD800", "\uDC00", "\uD800a", "a\uDC00", "\uD800\uD800")) {
            rejects("INVALID_INPUT") { Segment.bytes(invalid) }
            rejects("INVALID_INPUT") { Segments.create(invalid, 1, "auto", true, true) }
        }
        rejects("DATA_TOO_LONG") { Segment.bytes(ByteArray(Segments.MAX_PAYLOAD_UNITS + 1)) }
        rejects("DATA_TOO_LONG") { Segment.numeric("1".repeat(1024)).bits(1) }
        rejects("DATA_TOO_LONG") { Core.padDataBits(IntArray(73) { -1 }, 1, "H") }
        rejects("INVALID_INPUT") { Core.penaltyScore(arrayOf(booleanArrayOf(true), null)) }
        rejects("INVALID_INPUT") { Core.gfPow(1, -1) }
        verify(Core.gfPow(1, Int.MAX_VALUE) == 1)

        val supplied = mutableListOf(Segment.numeric("1"), Segment.numeric("2"))
        val normalized = Segments.normalize(supplied)
        supplied.clear()
        verify(normalized.size == 2)
        immutable { (normalized as MutableList).clear() }
        immutable { (Segments.normalize(emptyList()) as MutableList).clear() }
        immutable { (Segments.create("A123", 1, "auto", true, true) as MutableList).clear() }
        rejects("INVALID_GS1") { Segments.normalize(listOf(Segment.fnc1(), Segment.eci(26))) }
        rejects("INVALID_MODE") { Segments.normalize(listOf(Segment.bytes("a"), Segment.fnc1Second("12"))) }
        verify(Segment.fnc1Second("a").applicationIndicatorCodeword == 197)
        verify(Segment.eci(999_999).dataBitLength() == 24)

        // Prefix tracking must use the same exact objective as full path reconstruction.
        val tokens = listOf("1", "A", "a", "漢", "😀", "0", "%", "～", "é")
        for (version in listOf(1, 10, 27)) for (kanji in listOf(false, true)) {
            val tracker = Segments.OptimizationTracker(version, kanji)
            var prefix = ""
            for (token in tokens) {
                prefix += token
                val cost = tracker.append(token)
                val optimized = Segments.create(prefix, version, "auto", true, kanji)
                verify(cost == Segments.bitLength(optimized, version))
                verify(optimized.joinToString("") { it.text!! } == prefix)
            }
        }
        for (cp in listOf(-1, 0xD800, 0xDFFF, 0x110000)) {
            rejects("INVALID_INPUT") { Segments.OptimizationTracker(1, true).append(cp) }
        }

        verify(Core.reedSolomonDivisor(7).contentEquals(byteArrayOf(1, 127, 122, 154.toByte(), 164.toByte(), 11, 68, 117)))
        val data = ByteArray(40) { it.toByte() }
        for (degree in listOf(1, 7, 10, 18, 30, 255)) {
            val parity = Core.reedSolomonRemainder(data, degree)
            verify(Core.reedSolomonRemainder(data + parity, degree).all { it == 0.toByte() })
        }
        val divisorCopy = Core.reedSolomonDivisor(7)
        divisorCopy.fill(0)
        verify(Core.reedSolomonDivisor(7)[0] == 1.toByte())

        for (version in 1..40) for (level in listOf("L", "M", "Q", "H")) {
            val info = Tables.blockInfo(version, level)
            verify(info.dataCodewords + info.blocks * info.eccPerBlock == info.rawCodewords)
            val interleaved = Core.interleaveCodewords(ByteArray(info.dataCodewords), version, level)
            verify(interleaved.totalCodewords == info.rawCodewords)
            verify(interleaved.blocks.size == info.blocks)
        }
        val interleaved = Core.interleaveCodewords(ByteArray(19), 1, "L")
        interleaved.codewords.fill(1)
        interleaved.blocks[0].data.fill(2)
        interleaved.blocks[0].ecc.fill(3)
        verify(interleaved.codewords.all { it == 0.toByte() })
        immutable { (interleaved.blocks as MutableList).clear() }
        val matrix = Core.buildMatrix(interleaved.codewords, 1, "L")
        val mutableMatrix = matrix.matrix
        mutableMatrix[0].fill(false)
        verify(matrix.matrix[0][0])
        immutable { (matrix.maskPenalties as MutableList).clear() }
        verify(matrix.maskPenalties.minBy { it.penalty }.maskPattern == matrix.maskPattern)
        return assertions
    }
}
