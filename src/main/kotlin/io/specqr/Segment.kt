package io.specqr

/**
 * An immutable, validated QR data or control segment.
 *
 * Text is strictly validated Unicode and encoded as UTF-8. An ECI designator labels following
 * bytes; it does not transcode text. Logical text bytes remain UTF-8 even in Kanji mode, including
 * for Structured Append parity. Manual FNC1 alphanumeric '%' escaping is preserved verbatim.
 */
class Segment private constructor(
    val mode: String,
    val text: String?,
    private val logical: ByteArray,
    private val binarySource: Boolean,
    private val characters: Int,
    val assignment: Int? = null,
    val applicationIndicator: String? = null,
    val index: Int? = null,
    val total: Int? = null,
    val parity: Int? = null,
) {
    val binary: ByteArray? get() = if (binarySource) logical.copyOf() else null
    val logicalBytes: ByteArray get() = logical.copyOf()
    val count: Int get() = if (mode == "byte") logical.size else characters
    val characterCount: Int get() = characters
    val byteCount: Int get() = if (mode == "kanji") characters * 2 else logical.size
    val applicationIndicatorCodeword: Int?
        get() = applicationIndicator?.let {
            if (it.length == 2) (it[0] - '0') * 10 + (it[1] - '0') else it[0].code + 100
        }

    fun mode(): String = mode
    fun text(): String? = text
    fun binary(): ByteArray? = binary
    fun logicalBytes(): ByteArray = logicalBytes
    fun assignment(): Int? = assignment
    fun applicationIndicator(): String? = applicationIndicator
    fun index(): Int? = index
    fun total(): Int? = total
    fun parity(): Int? = parity
    fun count(): Int = count
    fun characterCount(): Int = characterCount
    fun byteCount(): Int = byteCount
    fun applicationIndicatorCodeword(): Int? = applicationIndicatorCodeword

    fun isControl(): Boolean = mode == "eci" || mode == "fnc1" || mode == "fnc1-second" || mode == "structured-append"

    /** Payload/designator length, excluding mode and count headers. */
    fun dataBitLength(): Int = when (mode) {
        "numeric" -> count / 3 * 10 + when (count % 3) { 0 -> 0; 1 -> 4; else -> 7 }
        "alphanumeric" -> count / 2 * 11 + count % 2 * 6
        "byte" -> count * 8
        "kanji" -> count * 13
        "eci" -> when { assignment!! < 128 -> 8; assignment < 16384 -> 16; else -> 24 }
        "fnc1" -> 0
        "fnc1-second" -> 8
        "structured-append" -> 16
        else -> throw AssertionError("Unrecognized validated segment mode")
    }

    /** Arithmetic unpadded length, also available for oversized (unencodable) payloads. */
    fun totalBits(version: Int): Long {
        Segments.validateVersion(version)
        return 4L + (if (isControl()) 0 else Tables.countBits(mode, version)) + dataBitLength()
    }

    /** Fresh header and payload bits, after checking count-field and materialization limits. */
    fun bits(version: Int): IntArray {
        val length = totalBits(version)
        validateMaterialization(version, length)
        return IntArray(length.toInt()).also { appendBits(it, 0, version) }
    }

    internal fun validateMaterialization(version: Int, length: Long) {
        if (!isControl() && count >= (1 shl Tables.countBits(mode, version))) {
            throw error("DATA_TOO_LONG", "Segment count exceeds the count field for version $version")
        }
        if (length > Segments.MAX_SINGLE_SYMBOL_DATA_BITS) {
            throw error("DATA_TOO_LONG", "Segment exceeds the maximum single-symbol bit capacity")
        }
    }

    internal fun appendBits(bits: IntArray, initialOffset: Int, version: Int): Int {
        val indicator = when (mode) {
            "numeric" -> 1; "alphanumeric" -> 2; "structured-append" -> 3; "byte" -> 4
            "fnc1" -> 5; "eci" -> 7; "kanji" -> 8; "fnc1-second" -> 9
            else -> throw AssertionError("Unrecognized validated segment mode")
        }
        var offset = append(bits, initialOffset, indicator, 4)
        if (!isControl()) offset = append(bits, offset, count, Tables.countBits(mode, version))
        when (mode) {
            "eci" -> {
                val value = assignment!!
                offset = when {
                    value < 128 -> append(bits, offset, value, 8)
                    value < 16384 -> append(bits, offset, 0x8000 or value, 16)
                    else -> append(bits, offset, 0xC00000 or value, 24)
                }
            }
            "fnc1-second" -> offset = append(bits, offset, applicationIndicatorCodeword!!, 8)
            "structured-append" -> {
                offset = append(bits, offset, index!! - 1, 4)
                offset = append(bits, offset, total!! - 1, 4)
                offset = append(bits, offset, parity!!, 8)
            }
            "numeric" -> {
                val source = text!!
                var start = 0
                while (start < source.length) {
                    val end = minOf(start + 3, source.length)
                    var value = 0
                    for (i in start until end) value = value * 10 + (source[i] - '0')
                    offset = append(bits, offset, value, when (end - start) { 3 -> 10; 2 -> 7; else -> 4 })
                    start = end
                }
            }
            "alphanumeric" -> {
                val source = text!!
                var i = 0
                while (i + 1 < source.length) {
                    offset = append(bits, offset, alphanumericValue(source[i].code) * 45 + alphanumericValue(source[i + 1].code), 11)
                    i += 2
                }
                if (i < source.length) offset = append(bits, offset, alphanumericValue(source[i].code), 6)
            }
            "byte" -> for (value in logical) offset = append(bits, offset, value.toInt() and 0xFF, 8)
            "kanji" -> for (character in text!!) offset = append(bits, offset, Kanji.value(character.code), 13)
            "fnc1" -> Unit
            else -> throw AssertionError("Unrecognized validated segment mode")
        }
        return offset
    }

    internal fun payloadUnits(): Int = if (binarySource) logical.size else characters

    override fun equals(other: Any?): Boolean = this === other || other is Segment &&
        mode == other.mode && text == other.text && binarySource == other.binarySource &&
        logical.contentEquals(other.logical) && assignment == other.assignment &&
        applicationIndicator == other.applicationIndicator && index == other.index &&
        total == other.total && parity == other.parity

    override fun hashCode(): Int {
        var hash = 1
        for (field in arrayOf<Any?>(mode, text, binarySource, assignment, applicationIndicator, index, total, parity)) {
            hash = 31 * hash + (field?.hashCode() ?: 0)
        }
        return 31 * hash + logical.contentHashCode()
    }

    override fun toString(): String = "Segment[mode=$mode, count=$count]"

    companion object {
        const val ALPHANUMERIC: String = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

        @JvmStatic fun numeric(text: String?): Segment = fromText("numeric", text)
        @JvmStatic fun alphanumeric(text: String?): Segment = fromText("alphanumeric", text)
        @JvmStatic fun bytes(text: String?): Segment = fromText("byte", text)
        @JvmStatic fun kanji(text: String?): Segment = fromText("kanji", text)

        @JvmStatic fun bytes(bytes: ByteArray?): Segment {
            if (bytes == null) throw error("INVALID_INPUT", "Byte segment requires binary data")
            if (bytes.size > Segments.MAX_PAYLOAD_UNITS) {
                throw error("DATA_TOO_LONG", "Payload exceeds the 1000000-unit resource limit")
            }
            return Segment("byte", null, bytes.copyOf(), true, 0)
        }

        @JvmStatic fun eci(assignment: Int): Segment {
            if (assignment !in 0..999_999) throw error("INVALID_ECI", "ECI assignment number must be from 0 to 999999")
            return Segment("eci", null, byteArrayOf(), false, 0, assignment = assignment)
        }

        @JvmStatic fun fnc1(): Segment = Segment("fnc1", null, byteArrayOf(), false, 0)

        @JvmStatic fun fnc1Second(applicationIndicator: String?): Segment {
            if (applicationIndicator == null || !(
                applicationIndicator.length == 2 && applicationIndicator.all { isDigit(it.code) } ||
                    applicationIndicator.length == 1 && isLatinLetter(applicationIndicator[0].code)
                )) {
                throw error("INVALID_MODE", "FNC1 second application indicator must be two ASCII digits or one Latin letter")
            }
            return Segment("fnc1-second", null, byteArrayOf(), false, 0, applicationIndicator = applicationIndicator)
        }

        @JvmStatic fun structuredAppend(index: Int, total: Int, parity: Int): Segment {
            if (index !in 1..16 || total !in 2..16 || index > total || parity !in 0..255) {
                throw error("INVALID_MODE", "Structured Append requires 1 <= index <= total, 2 <= total <= 16, and parity 0..255")
            }
            return Segment("structured-append", null, byteArrayOf(), false, 0, index = index, total = total, parity = parity)
        }

        @JvmStatic fun fromText(mode: String?, text: String?): Segment {
            if (mode != "numeric" && mode != "alphanumeric" && mode != "byte" && mode != "kanji") {
                throw error("INVALID_MODE", "Mode must be numeric, alphanumeric, byte, or kanji")
            }
            val characters = validateText(text)
            val source = text!!
            var offset = 0
            while (offset < source.length) {
                val cp = codePointAt(source, offset)
                when {
                    mode == "numeric" && !isDigit(cp) -> throw error("INVALID_MODE", "Numeric mode can only encode decimal digits 0-9")
                    mode == "alphanumeric" && alphanumericValue(cp) < 0 -> throw error("INVALID_MODE", "Alphanumeric mode can only encode: $ALPHANUMERIC")
                    mode == "kanji" && !Kanji.canEncode(cp) -> throw error("INVALID_MODE", "Kanji mode cannot encode U+${cp.toString(16).uppercase()}")
                }
                offset += codePointWidth(cp)
            }
            return Segment(mode!!, source, source.toByteArray(Charsets.UTF_8), false, characters)
        }

        @JvmStatic fun validateText(text: String?): Int {
            if (text == null) throw error("INVALID_INPUT", "QR text must be a string")
            if (text.length > 2L * Segments.MAX_PAYLOAD_UNITS) {
                throw error("DATA_TOO_LONG", "Payload exceeds the 1000000-unit resource limit")
            }
            var count = 0
            var offset = 0
            while (offset < text.length) {
                val character = text[offset++]
                if (character.isHighSurrogate()) {
                    if (offset >= text.length || !text[offset++].isLowSurrogate()) {
                        throw error("INVALID_INPUT", "QR text must contain correctly paired UTF-16 surrogates")
                    }
                } else if (character.isLowSurrogate()) {
                    throw error("INVALID_INPUT", "QR text must contain correctly paired UTF-16 surrogates")
                }
                if (++count > Segments.MAX_PAYLOAD_UNITS) throw error("DATA_TOO_LONG", "Payload exceeds the 1000000-unit resource limit")
            }
            return count
        }

        @JvmStatic fun alphanumericValue(codePoint: Int): Int = if (codePoint in 0..127) ALPHANUMERIC.indexOf(codePoint.toChar()) else -1
        @JvmStatic fun isDigit(codePoint: Int): Boolean = codePoint in '0'.code..'9'.code
        private fun isLatinLetter(codePoint: Int): Boolean = codePoint in 'A'.code..'Z'.code || codePoint in 'a'.code..'z'.code
        @JvmStatic fun error(code: String, message: String): SpecQrException = SpecQrException(code, message)

        internal fun codePointAt(text: String, offset: Int): Int {
            val first = text[offset]
            return if (first.isHighSurrogate()) {
                0x10000 + ((first.code - 0xD800) shl 10) + text[offset + 1].code - 0xDC00
            } else first.code
        }
        internal fun codePointWidth(codePoint: Int): Int = if (codePoint >= 0x10000) 2 else 1
        internal fun append(bits: IntArray, initialOffset: Int, value: Int, width: Int): Int {
            var offset = initialOffset
            for (shift in width - 1 downTo 0) bits[offset++] = (value ushr shift) and 1
            return offset
        }
    }
}
