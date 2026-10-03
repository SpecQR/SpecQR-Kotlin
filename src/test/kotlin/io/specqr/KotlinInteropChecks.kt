package io.specqr

import java.lang.reflect.Modifier

/** Kotlin caller contracts beyond the Java interoperability corpus. */
object KotlinInteropChecks {
    @JvmStatic fun run(): Int {
        var assertions = 0
        fun verify(condition: Boolean, label: String) {
            assertions++
            if (!condition) throw AssertionError(label)
        }
        fun rejects(code: String, action: () -> Unit) {
            assertions++
            try {
                action()
                throw AssertionError("Expected $code")
            } catch (error: SpecQrException) {
                if (error.code != code) throw AssertionError("Expected $code, received ${error.code}", error)
            }
        }

        val defaults = Options()
        val configured = defaults.copy(version = 4, maskPattern = 2, errorCorrectionLevel = "H")
        val (level, mode) = configured
        verify(level == "H" && mode == "auto", "Options destructuring is stable")
        verify(configured == Options(version = 4, maskPattern = 2, errorCorrectionLevel = "H"), "Named construction and copy agree")
        verify(configured.hashCode() == configured.copy().hashCode(), "Options value hash contract")
        verify(defaults.version == null && defaults.maskPattern == null, "Copy leaves original defaults unchanged")
        rejects("INVALID_VERSION") { configured.copy(version = 0) }
        rejects("INVALID_INPUT") { configured.copy(maskPattern = 8) }
        rejects("INVALID_MODE") { configured.copy(eci = 26, gs1 = true) }
        rejects("INVALID_ECI") { configured.copy(eci = 1_000_000) }

        val text = "Kotlin 日本語 😀"
        val symbol = SpecQr.generate(text = text, options = configured)
        val plan = SpecQr.estimate(text = text, options = configured)
        verify(symbol.version == 4 && symbol.maskPattern == 2, "Named facade arguments")
        verify(plan.ok && plan.version == symbol.version, "Nullable plan version and Kotlin properties")
        verify(plan.selectedVersion == plan.version && plan.dataBitLength == plan.requiredBits, "Planning aliases")
        verify(symbol.matrix.contentDeepEquals(SpecQr.generate(text, configured).matrix), "Overload determinism")
        verify(symbol.segments.map { it.mode } == symbol.segments.map { it.mode() }, "Property and Java accessor agreement")
        verify(symbol.toSvg().startsWith("<svg"), "Default rendering options")
        verify(symbol.toPngDataUrl().startsWith("data:image/png;base64,"), "PNG data URL overload")
        verify(symbol.toSvgDataUrl().startsWith("data:image/svg+xml;charset=utf-8,"), "SVG data URL overload")
        verify(symbol.toPixels(options = configured.copy(scale = 1)).width == symbol.size + 8, "Named render options")

        val bytes = byteArrayOf(0, 1, 127, -128, -1)
        val binary = SpecQr.generate(data = bytes, options = configured.copy(mode = "byte"))
        val expected = binary.codewords
        bytes.fill(23)
        verify(binary.codewords.contentEquals(expected), "Caller bytes cannot change a generated symbol")
        val first = binary.dataCodewords
        first.fill(0)
        verify(!binary.dataCodewords.contentEquals(first), "Data codeword property owns its storage")
        val segment = Segment.bytes(byteArrayOf(-1, 0, 1))
        verify(segment.binary!!.contentEquals(byteArrayOf(-1, 0, 1)), "Nullable binary property")
        verify(segment.text == null && segment.byteCount == 3 && segment.characterCount == 0, "Binary segment properties")
        val logical = segment.logicalBytes
        logical.fill(0)
        verify(segment.logicalBytes.contentEquals(byteArrayOf(-1, 0, 1)), "Logical byte property defensive copy")
        val manual = listOf(Segment.eci(26), Segment.bytes("é😀"))
        verify(SpecQr.generateSegments(segments = manual).segments.first().assignment == 26, "Named manual segments and control properties")

        for (version in 1..40) for (ecc in listOf("L", "M", "Q", "H")) {
            val capacity = SpecQr.getCapacity(version = version, level = ecc, mode = "byte")
            verify(capacity.maxBytes == capacity.maximum(), "Nullable byte capacity alias")
            verify(capacity.maxCharacters == null, "Byte capacity does not claim character capacity")
            verify(capacity.version == version && capacity.errorCorrectionLevel == ecc, "Capacity property identity")
            verify(capacity.capacityBits == capacity.dataCodewords * 8, "Kotlin capacity arithmetic")
        }
        val overflow = SpecQr.estimate(data = ByteArray(200), options = Options(version = 1, errorCorrectionLevel = "H"))
        verify(!overflow.ok && overflow.overflowBits > 0 && overflow.version == 1, "Fixed overflow is a plan rather than exception")
        val absent = SpecQr.estimate(data = ByteArray(200), options = Options(minVersion = 1, maxVersion = 1, errorCorrectionLevel = "H"))
        verify(!absent.ok && absent.version == null, "Unfittable automatic plan has no selected version")

        rejects("INVALID_INPUT") { SpecQr.generate(text = null) }
        rejects("INVALID_INPUT") { SpecQr.generate(data = null) }
        rejects("INVALID_INPUT") { SpecQr.generate(text = "x", options = null) }
        rejects("INVALID_INPUT") { SpecQr.estimate(text = null) }
        rejects("INVALID_INPUT") { SpecQr.estimate(data = null) }
        rejects("INVALID_INPUT") { SpecQr.generateSegments(segments = null) }
        rejects("INVALID_INPUT") { SpecQr.analyzeSegments(segments = null) }
        rejects("INVALID_INPUT") { Render.toPixels(matrix = null) }
        rejects("INVALID_INPUT") { Render.toSvg(matrix = null) }
        rejects("INVALID_INPUT") { Render.toPng(matrix = null) }
        rejects("INVALID_INPUT") { symbol.toPng(options = null) }
        rejects("INVALID_INPUT") { symbol[-1, 0] }
        rejects("INVALID_INPUT") { symbol[symbol.size, 0] }
        rejects("INVALID_INPUT") { SpecQr.generate(text = "\ud800") }
        rejects("INVALID_INPUT") { SpecQr.generate(text = "\udc00") }

        for (type in listOf(SpecQr::class.java, Core::class.java, Tables::class.java, Options::class.java,
            Segment::class.java, Segments::class.java, QrCode::class.java, Plan::class.java,
            Capacity::class.java, Render::class.java, Gs1::class.java, StructuredAppend::class.java)) {
            verify(type.getAnnotation(Metadata::class.java) != null, "${type.simpleName} is compiled Kotlin")
        }
        val generate = SpecQr::class.java.getMethod("generate", String::class.java, Options::class.java)
        verify(Modifier.isStatic(generate.modifiers), "Java facade overload is truly static")
        val invoke = generate.invoke(null, "Java reflection → Kotlin", Options()) as QrCode
        verify(invoke.size >= 21, "Java reflection executes Kotlin facade")
        verify(SpecQr::class.java.getMethod("generate", ByteArray::class.java).returnType == QrCode::class.java,
            "One-argument Java binary overload is available")
        return assertions
    }
}
