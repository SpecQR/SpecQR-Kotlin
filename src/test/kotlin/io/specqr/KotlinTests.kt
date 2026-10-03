package io.specqr

/** Native Kotlin API, defensive ownership, Java interop bridge, and java.base-only runtime checks. */
object KotlinTests {
    @JvmStatic fun main(args: Array<String>) {
        var checks = 0
        fun verify(value: Boolean) { check(value); checks++ }
        val options = Options(errorCorrectionLevel = "Q", minVersion = 1, maxVersion = 10, scale = 3)
        val changed = options.copy(version = 2, maskPattern = 5)
        verify(options.version == null && changed.version == 2)
        verify(changed.copy(version = null, maskPattern = null) == options)
        for (level in listOf("L", "M", "Q", "H")) for (mask in 0..7) {
            val qr = SpecQr.generate("012345 ABC xyz 日本語 😀", options.copy(errorCorrectionLevel = level, maskPattern = mask))
            verify(qr.maskPattern == mask && qr.errorCorrectionLevel == level)
            verify(qr.size == qr.matrix.size)
            verify(qr[0, 0] == qr.module(0, 0))
            val matrix = qr.matrix
            matrix[0][0] = !matrix[0][0]
            verify(matrix[0][0] != qr[0, 0])
            val words = qr.codewords
            words[0] = (words[0].toInt() xor 255).toByte()
            verify(!words.contentEquals(qr.codewords))
            val rgba = qr.toPixels().pixels
            val first = rgba[0]
            rgba[0] = (first.toInt() xor 255).toByte()
            verify(qr.toPixels().pixels[0] == first)
            val estimate = SpecQr.estimate("012345 ABC xyz 日本語 😀", options.copy(errorCorrectionLevel = level, maskPattern = mask))
            verify(estimate.ok && estimate.capacityVersion == qr.version)
            verify(estimate.requiredBits == qr.diagnostics["dataBitLength"])
        }
        val bytes = byteArrayOf(0, 127, 128.toByte(), 255.toByte())
        val segment = Segment.bytes(bytes)
        bytes.fill(42)
        verify(segment.binary()!!.contentEquals(byteArrayOf(0, 127, 128.toByte(), 255.toByte())))
        val manual = mutableListOf(Segment.numeric("1234"), Segment.alphanumeric("AB%"), Segment.bytes("日本😀"))
        val qr = SpecQr.generateSegments(manual)
        manual.clear()
        verify(qr.segments.size == 3)
        val snapshot = qr.segments
        try { (snapshot as MutableList<Segment>).clear(); error("Mutable segments exposed") } catch (_: UnsupportedOperationException) { checks++ }
        try { (qr.diagnostics as MutableMap<String, Any?>).clear(); error("Mutable diagnostics exposed") } catch (_: UnsupportedOperationException) { checks++ }
        val fields = mutableMapOf<String, Any?>("nested" to mutableListOf(mutableMapOf("key" to 1)))
        val frozen = SpecQr.freeze(fields)
        fields.clear()
        verify((frozen["nested"] as List<*>).size == 1)
        try { ((frozen["nested"] as List<*>)[0] as MutableMap<*, *>).clear(); error("Mutable nested diagnostics") } catch (_: UnsupportedOperationException) { checks++ }
        val gs = Gs1.toElementString(listOf(Gs1.Element("01", "09501101530003"), Gs1.Element("10", "L%OT")))
        verify(SpecQr.generate(gs, Options(gs1 = true)).segments[1].mode() == "byte")
        val original = "日本語😀".repeat(24)
        val append = StructuredAppend.generate(original, Options(version = 3))
        verify(append.total() > 1)
        val parts = append.symbols().mapIndexed { index, symbol ->
            StructuredAppend.Part(index + 1, append.total(), append.parity(), symbol.segments().filterNot { it.isControl() }.joinToString("") { it.text()!! })
        }
        verify(StructuredAppend.merge(parts.reversed()).text() == original)
        verify(Core::class.java.getAnnotation(Metadata::class.java) != null)
        verify(SpecQr::class.java.getAnnotation(Metadata::class.java) != null)
        checks += KotlinInteropChecks.run()
        checks += CoreSegmentTests.run()
        println("Kotlin API: PASS ($checks assertions; java.base and kotlin-stdlib only)")
    }
}
