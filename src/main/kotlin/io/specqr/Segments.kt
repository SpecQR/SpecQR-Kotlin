package io.specqr

/** Deterministic segment construction, validation, and bounded bit materialization. */
object Segments {
    const val MAX_PAYLOAD_UNITS: Int = 1_000_000
    const val MAX_MANUAL_SEGMENTS: Int = 16_384
    const val MAX_SINGLE_SYMBOL_CHARACTERS: Int = 7_089
    const val MAX_SINGLE_SYMBOL_DATA_BITS: Int = 23_648
    private val dataModes = arrayOf("numeric", "alphanumeric", "kanji", "byte")

    /**
     * Exact minimum-bit automatic segmentation. Ties prefer fewer segments, then the stable
     * numeric/alphanumeric/Kanji/byte traversal order. Explicit Kanji ignores [allowKanji].
     */
    @JvmStatic fun create(text: String?, version: Int, mode: String?, optimize: Boolean, allowKanji: Boolean): List<Segment> {
        validateVersion(version)
        validateMode(mode)
        val scalars = Segment.validateText(text)
        val source = text!!
        if (mode != "auto") return buildList { add(Segment.fromText(mode!!, source)) }
        if (optimize) {
            if (scalars > MAX_SINGLE_SYMBOL_CHARACTERS) {
                throw Segment.error("DATA_TOO_LONG", "Text exceeds maximum single-symbol character capacity")
            }
            return optimize(source, scalars, version, allowKanji)
        }
        var numeric = source.isNotEmpty()
        var alphanumeric = source.isNotEmpty()
        var kanji = source.isNotEmpty() && allowKanji
        var offset = 0
        while (offset < source.length) {
            val cp = Segment.codePointAt(source, offset)
            numeric = numeric && Segment.isDigit(cp)
            alphanumeric = alphanumeric && Segment.alphanumericValue(cp) >= 0
            if (kanji) kanji = Kanji.canEncode(cp)
            offset += Segment.codePointWidth(cp)
        }
        val selected = when { numeric -> "numeric"; alphanumeric -> "alphanumeric"; kanji -> "kanji"; else -> "byte" }
        return buildList { add(Segment.fromText(selected, source)) }
    }

    /** Constructs an owned binary byte segment; only auto and byte modes accept binary input. */
    @JvmStatic fun create(data: ByteArray?, version: Int, mode: String?, optimize: Boolean, allowKanji: Boolean): List<Segment> {
        validateVersion(version)
        validateMode(mode)
        if (mode != "auto" && mode != "byte") throw Segment.error("INVALID_MODE", "Binary input can only be encoded in byte mode")
        return buildList { add(Segment.bytes(data)) }
    }

    /** Takes an immutable snapshot, preserving manual boundaries and repeated ECI transitions. */
    @JvmStatic fun normalize(segments: List<Segment?>?): List<Segment> {
        if (segments == null) throw Segment.error("INVALID_INPUT", "Manual segments must be a list")
        if (segments.size > MAX_MANUAL_SEGMENTS) throw Segment.error("DATA_TOO_LONG", "Manual segments exceed the 16384-segment resource limit")
        var units = 0L
        var fnc1 = 0
        var fnc1Second = 0
        var structuredAppend = 0
        var eci = 0
        return buildList(segments.size) {
            for (element in segments) {
                val segment = element ?: throw Segment.error("INVALID_INPUT", "Manual segments must not contain null")
                if (size >= MAX_MANUAL_SEGMENTS) throw Segment.error("DATA_TOO_LONG", "Manual segments exceed the 16384-segment resource limit")
                units += segment.payloadUnits()
                if (units > MAX_PAYLOAD_UNITS) throw Segment.error("DATA_TOO_LONG", "Manual payload exceeds the 1000000-unit resource limit")
                when (segment.mode) {
                    "fnc1" -> {
                        if (++fnc1 > 1) throw Segment.error("INVALID_GS1", "Manual segments can include at most one FNC1 segment")
                        if (isNotEmpty()) throw Segment.error("INVALID_GS1", "Manual FNC1 segment must be first")
                    }
                    "fnc1-second" -> {
                        if (++fnc1Second > 1) throw Segment.error("INVALID_MODE", "Manual segments can include at most one FNC1 second segment")
                        if (isNotEmpty()) throw Segment.error("INVALID_MODE", "Manual FNC1 second segment must be first")
                    }
                    "structured-append" -> {
                        if (++structuredAppend > 1) throw Segment.error("INVALID_MODE", "Manual segments can include at most one Structured Append segment")
                        if (isNotEmpty()) throw Segment.error("INVALID_MODE", "Manual Structured Append segment must be first")
                    }
                    "eci" -> eci++
                }
                add(segment)
            }
            val controlsPresent = (if (fnc1 > 0) 1 else 0) + (if (fnc1Second > 0) 1 else 0) +
                (if (structuredAppend > 0) 1 else 0) + (if (eci > 0) 1 else 0)
            if (controlsPresent > 1) {
                throw Segment.error(if (fnc1 > 0) "INVALID_GS1" else "INVALID_MODE",
                    "FNC1, FNC1 second, Structured Append, and ECI cannot be combined in this implementation")
            }
        }
    }

    /** Arithmetic unpadded length, even for a sequence too large for a single symbol. */
    @JvmStatic fun bitLength(segments: List<Segment?>?, version: Int): Long {
        validateVersion(version)
        var length = 0L
        for (segment in normalize(segments)) length += segment.totalBits(version)
        return length
    }

    /** Fresh unpadded bits; all allocation and count-field limits are checked first. */
    @JvmStatic fun bits(segments: List<Segment?>?, version: Int): IntArray {
        validateVersion(version)
        val normalized = normalize(segments)
        var length = 0L
        for (segment in normalized) {
            val segmentLength = segment.totalBits(version)
            segment.validateMaterialization(version, segmentLength)
            length += segmentLength
        }
        if (length > MAX_SINGLE_SYMBOL_DATA_BITS) throw Segment.error("DATA_TOO_LONG", "Segments exceed the maximum single-symbol bit capacity")
        val result = IntArray(length.toInt())
        var offset = 0
        for (segment in normalized) offset = segment.appendBits(result, offset, version)
        check(offset == result.size) { "Segment bit length disagrees with encoding" }
        return result
    }

    /** Constant-memory exact prefix cost tracker; each append accepts one Unicode scalar. */
    class OptimizationTracker(private val version: Int, private val allowKanji: Boolean) {
        private var states = initialStates()
        private var characters = 0
        init { validateVersion(version) }

        fun append(codePoint: Int): Long {
            if (codePoint !in 0..0x10FFFF || codePoint in 0xD800..0xDFFF) {
                throw Segment.error("INVALID_INPUT", "Append requires exactly one Unicode scalar")
            }
            if (characters >= MAX_PAYLOAD_UNITS) throw Segment.error("DATA_TOO_LONG", "Payload exceeds the 1000000-unit resource limit")
            states = advance(states, codePoint, version, allowKanji)
            characters++
            return best(states).value.cost.toLong()
        }

        fun append(character: String?): Long {
            if (Segment.validateText(character) != 1) throw Segment.error("INVALID_INPUT", "Append requires exactly one Unicode scalar")
            return append(Segment.codePointAt(character!!, 0))
        }
    }

    private data class State(val cost: Int, val segmentCount: Int, val mode: Int, val mod: Int, val previous: Int)
    private fun initialStates(): LinkedHashMap<Int, State> = linkedMapOf(-1 to State(0, 0, -1, 0, -1))

    private fun optimize(text: String, scalars: Int, version: Int, allowKanji: Boolean): List<Segment> {
        if (scalars == 0) return buildList { add(Segment.bytes("")) }
        val characters = IntArray(scalars)
        var offset = 0
        for (i in characters.indices) {
            val cp = Segment.codePointAt(text, offset)
            characters[i] = cp
            offset += Segment.codePointWidth(cp)
        }
        val layers = ArrayList<LinkedHashMap<Int, State>>(scalars + 1)
        layers.add(initialStates())
        for (cp in characters) layers.add(advance(layers.last(), cp, version, allowKanji))
        var key = best(layers[scalars]).key
        val assignments = IntArray(scalars)
        for (i in scalars downTo 1) {
            val state = layers[i].getValue(key)
            assignments[i - 1] = state.mode
            key = state.previous
        }
        return buildList {
            var startCharacter = 0
            var startOffset = 0
            offset = Segment.codePointWidth(characters[0])
            for (i in 1..scalars) {
                if (i == scalars || assignments[i] != assignments[startCharacter]) {
                    add(Segment.fromText(dataModes[assignments[startCharacter]], text.substring(startOffset, offset)))
                    startCharacter = i
                    startOffset = offset
                }
                if (i < scalars) offset += Segment.codePointWidth(characters[i])
            }
        }
    }

    private fun advance(states: LinkedHashMap<Int, State>, cp: Int, version: Int, allowKanji: Boolean): LinkedHashMap<Int, State> {
        val eligible = booleanArrayOf(Segment.isDigit(cp), Segment.alphanumericValue(cp) >= 0, allowKanji && Kanji.canEncode(cp), true)
        val byteBits = when { cp < 0x80 -> 8; cp < 0x800 -> 16; cp < 0x10000 -> 24; else -> 32 }
        val next = linkedMapOf<Int, State>()
        for ((key, state) in states) {
            for (mode in dataModes.indices) {
                if (!eligible[mode]) continue
                val same = state.mode == mode
                val mod = if (same) state.mod else 0
                val payload = when (mode) { 0 -> if (mod == 0) 4 else 3; 1 -> if (mod == 0) 6 else 5; 2 -> 13; else -> byteBits }
                val nextMod = when (mode) { 0 -> (mod + 1) % 3; 1 -> (mod + 1) % 2; else -> 0 }
                val nextKey = mode * 3 + nextMod
                val candidate = State(
                    state.cost + payload + if (same) 0 else 4 + Tables.countBits(dataModes[mode], version),
                    state.segmentCount + if (same) 0 else 1,
                    mode, nextMod, key,
                )
                val current = next[nextKey]
                // Replacement retains first insertion order, making equally optimal output stable.
                if (current == null || better(candidate, current)) next[nextKey] = candidate
            }
        }
        return next
    }

    private fun best(states: LinkedHashMap<Int, State>): Map.Entry<Int, State> {
        var result: Map.Entry<Int, State>? = null
        for (entry in states.entries) if (result == null || better(entry.value, result.value)) result = entry
        return result ?: throw AssertionError("No byte-mode optimization state")
    }
    private fun better(candidate: State, current: State): Boolean = candidate.cost < current.cost ||
        candidate.cost == current.cost && candidate.segmentCount < current.segmentCount

    @JvmStatic fun validateVersion(version: Int) {
        if (version !in 1..40) throw Segment.error("INVALID_VERSION", "Version must be from 1 to 40")
    }
    private fun validateMode(mode: String?) {
        if (mode != "auto" && mode != "numeric" && mode != "alphanumeric" && mode != "kanji" && mode != "byte") {
            throw Segment.error("INVALID_MODE", "Mode must be auto, numeric, alphanumeric, byte, or kanji")
        }
    }
}
