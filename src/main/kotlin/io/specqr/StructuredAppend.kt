package io.specqr

import java.io.ByteArrayOutputStream
import java.util.Collections

/** Deterministic 2..16-symbol Structured Append. Public indexes are one-based.
 * Parity is XOR of original UTF-8/raw bytes, not authentication. Text boundaries
 * are Unicode scalars; manual numeric, alphanumeric and Kanji are indivisible.
 */
object StructuredAppend {
    private val dataModes = setOf("numeric", "alphanumeric", "byte", "kanji")
    private fun <T> immutable(values: List<T>): List<T> {
        val copy = ArrayList(values)
        copy.forEach { java.util.Objects.requireNonNull(it) }
        return Collections.unmodifiableList(copy)
    }
    class Result(symbols: List<QrCode>?, val total: Int, val parity: Int, val inputLength: Int,
                 val byteLength: Int, diagnostics: Map<String, Any?>?) {
        val symbols: List<QrCode>
        val diagnostics: Map<String, Any?>
        init {
            if (symbols == null || symbols.size !in 2..16 || total != symbols.size) throw invalid("Structured Append result requires 2..16 matching symbols")
            if (parity !in 0..255 || inputLength !in 1..Segments.MAX_PAYLOAD_UNITS || byteLength < 1 || byteLength > 4L * Segments.MAX_PAYLOAD_UNITS) throw invalid("Invalid Structured Append result metadata")
            this.symbols = immutable(symbols); this.diagnostics = SpecQr.freeze(diagnostics)
        }
        fun symbols() = symbols; fun total() = total; fun parity() = parity; fun inputLength() = inputLength
        fun byteLength() = byteLength; fun diagnostics() = diagnostics
    
        override fun equals(other: Any?): Boolean = other is Result && symbols == other.symbols && total == other.total && parity == other.parity && inputLength == other.inputLength && byteLength == other.byteLength && diagnostics == other.diagnostics
        override fun hashCode(): Int = listOf<Any?>(symbols, total, parity, inputLength, byteLength, diagnostics).fold(0) { hash, value -> 31 * hash + (value?.hashCode() ?: 0) }
        override fun toString(): String = "Result[symbols=${symbols}, total=${total}, parity=${parity}, inputLength=${inputLength}, byteLength=${byteLength}, diagnostics=${diagnostics}]"
    }
    data class DiagnosticOptions(val splitUnits: String?, val symbolResults: String?) {
        fun splitUnits() = splitUnits; fun symbolResults() = symbolResults
        companion object {
            @JvmStatic fun defaults() = DiagnosticOptions("summary", "output")
            @JvmStatic fun full() = DiagnosticOptions("full", "diagnostics")
        }
    
        override fun toString(): String = "DiagnosticOptions[splitUnits=${splitUnits}, symbolResults=${symbolResults}]"
    }
    class Part(val index: Int, val total: Int, val parity: Int, data: Any?) {
        private val storedData: Any
        val data: Any get() = if (storedData is ByteArray) storedData.clone() else storedData
        init {
            validateMetadata(index, total, parity)
            if (data is String) Segment.validateText(data)
            if (data !is String && data !is ByteArray) throw invalid("Part data must be text or bytes")
            if (data is ByteArray && data.size > Segments.MAX_PAYLOAD_UNITS) throw tooLong("Part exceeds payload resource limit")
            storedData = if (data is ByteArray) data.clone() else data
        }
        fun index() = index; fun total() = total; fun parity() = parity; fun data() = data
    
        override fun equals(other: Any?): Boolean = other is Part && index == other.index && total == other.total && parity == other.parity && storedData == other.storedData
        override fun hashCode(): Int = listOf<Any?>(index, total, parity, storedData).fold(0) { hash, value -> 31 * hash + (value?.hashCode() ?: 0) }
        override fun toString(): String = "Part[index=${index}, total=${total}, parity=${parity}, data=${storedData}]"
    }
    data class PartInfo(val index: Int, val total: Int, val parity: Int, val dataType: String?, val byteLength: Int) {
        fun index() = index; fun total() = total; fun parity() = parity; fun dataType() = dataType; fun byteLength() = byteLength
    
        override fun toString(): String = "PartInfo[index=${index}, total=${total}, parity=${parity}, dataType=${dataType}, byteLength=${byteLength}]"
    }
    class MergeResult(data: Any?, val total: Int, val parity: Int, parts: List<PartInfo>?, diagnostics: Map<String, Any?>?) {
        private val storedData: Any
        val data: Any get() = if (storedData is ByteArray) storedData.clone() else storedData
        val parts: List<PartInfo>
        val diagnostics: Map<String, Any?>
        init {
            if (total !in 2..16 || parity !in 0..255) throw invalid("Invalid Structured Append merge metadata")
            if (parts == null || parts.size != total) throw invalid("Merge metadata parts must match total")
            if (data is String) Segment.validateText(data)
            if (data is ByteArray && data.size > Segments.MAX_PAYLOAD_UNITS) throw tooLong("Merged payload exceeds the 1000000-unit resource limit")
            if (data !is String && data !is ByteArray) throw invalid("Merged data must be text or bytes")
            storedData = if (data is ByteArray) data.clone() else data
            this.parts = immutable(parts); this.diagnostics = SpecQr.freeze(diagnostics)
        }
        fun data() = data; fun total() = total; fun parity() = parity; fun parts() = parts; fun diagnostics() = diagnostics
        fun text(): String? = storedData as? String
        fun bytes(): ByteArray? = (storedData as? ByteArray)?.clone()
    
        override fun equals(other: Any?): Boolean = other is MergeResult && storedData == other.storedData && total == other.total && parity == other.parity && parts == other.parts && diagnostics == other.diagnostics
        override fun hashCode(): Int = listOf<Any?>(storedData, total, parity, parts, diagnostics).fold(0) { hash, value -> 31 * hash + (value?.hashCode() ?: 0) }
        override fun toString(): String = "MergeResult[data=${storedData}, total=${total}, parity=${parity}, parts=${parts}, diagnostics=${diagnostics}]"
    }
    private fun invalid(message: String) = SpecQrException("INVALID_INPUT", message)
    private fun mode(message: String) = SpecQrException("INVALID_MODE", message)
    private fun tooLong(message: String) = SpecQrException("DATA_TOO_LONG", message)
    private fun validateMetadata(index: Int, total: Int, parity: Int) {
        if (index !in 1..16) throw invalid("index must be an integer from 1 to 16")
        if (total !in 2..16) throw invalid("total must be an integer from 2 to 16")
        if (parity !in 0..255) throw invalid("parity must be an integer from 0 to 255")
        if (index > total) throw invalid("Structured Append index must not exceed total")
    }
    private fun xor(data: ByteArray): Int = data.fold(0) { value, b -> value xor (b.toInt() and 255) }
    /** Sparse UTF-8 index: two integers per 64 scalars. */
    private class TextIndex(value: String?) {
        val length = Segment.validateText(value)
        val text = value!!
        val charOffsets = IntArray(length / 64 + 1)
        val byteOffsets = IntArray(charOffsets.size)
        val byteLength: Int
        val parity: Int
        init {
            var bytes = 0; var checksum = 0; var scalar = 0; var offset = 0
            while (offset < text.length) {
                val cp = text.codePointAt(offset); offset += Character.charCount(cp); scalar++
                when {
                    cp < 0x80 -> { bytes++; checksum = checksum xor cp }
                    cp < 0x800 -> { bytes += 2; checksum = checksum xor (0xC0 or (cp ushr 6)) xor (0x80 or (cp and 63)) }
                    cp < 0x10000 -> { bytes += 3; checksum = checksum xor (0xE0 or (cp ushr 12)) xor (0x80 or ((cp ushr 6) and 63)) xor (0x80 or (cp and 63)) }
                    else -> { bytes += 4; checksum = checksum xor (0xF0 or (cp ushr 18)) xor (0x80 or ((cp ushr 12) and 63)) xor (0x80 or ((cp ushr 6) and 63)) xor (0x80 or (cp and 63)) }
                }
                if (scalar % 64 == 0) { charOffsets[scalar / 64] = offset; byteOffsets[scalar / 64] = bytes }
            }
            byteLength = bytes; parity = checksum
        }
        fun charOffset(scalar: Int): Int {
            val checkpoint = scalar / 64; var offset = charOffsets[checkpoint]
            for (i in checkpoint * 64 until scalar) offset += Character.charCount(text.codePointAt(offset))
            return offset
        }
        fun byteOffset(scalar: Int): Int {
            val checkpoint = scalar / 64; var offset = charOffsets[checkpoint]; var bytes = byteOffsets[checkpoint]
            for (i in checkpoint * 64 until scalar) {
                val cp = text.codePointAt(offset); offset += Character.charCount(cp)
                bytes += if (cp < 0x80) 1 else if (cp < 0x800) 2 else if (cp < 0x10000) 3 else 4
            }
            return bytes
        }
        fun byteLength(start: Int, length: Int) = byteOffset(start + length) - byteOffset(start)
        fun slice(start: Int, length: Int) = text.substring(charOffset(start), charOffset(start + length))
    }
    @JvmStatic fun calculateParity(input: String?): Int = TextIndex(input).parity
    @JvmStatic fun calculateParity(input: ByteArray?): Int {
        if (input == null) throw invalid("Input must be text or bytes")
        if (input.size > Segments.MAX_PAYLOAD_UNITS) throw tooLong("Payload exceeds the 1000000-unit resource limit")
        return xor(input)
    }
    private fun manual(segments: List<Segment?>?, unitLimit: Int): List<Segment> {
        if (segments.isNullOrEmpty()) throw invalid("Structured Append requires at least one non-empty data segment")
        if (segments.size > Segments.MAX_MANUAL_SEGMENTS) throw tooLong("Manual segments exceed the 16384-segment resource limit")
        if (segments.size > unitLimit) throw tooLong("Input segments cannot be split within the selected Structured Append capacity")
        val result = ArrayList<Segment>(segments.size); var units = 0L
        for (segment in segments) {
            if (segment == null) throw invalid("Manual segments must not contain null")
            if (segment.mode() == "fnc1") throw SpecQrException("INVALID_GS1", "Structured Append cannot be combined with manual FNC1 first position segments")
            if (segment.mode() !in dataModes) throw mode("Structured Append cannot be combined with manual ${segment.mode()} control segments")
            val count = if (segment.text() == null) segment.count() else segment.characterCount()
            if (count == 0) throw invalid("Structured Append segments must include non-empty data")
            units += count
            if (units > unitLimit) throw tooLong("Input segments cannot be split within the selected Structured Append capacity")
            result.add(segment)
        }
        return immutable(result)
    }
    @JvmStatic fun calculateSegmentsParity(segments: List<Segment?>?): Int {
        var result = 0
        for (segment in manual(segments, Segments.MAX_PAYLOAD_UNITS)) result = result xor (if (segment.text() == null) xor(segment.logicalBytes()) else calculateParity(segment.text()))
        return result
    }
    private fun options(options: Options?, maximum: Int, manual: Boolean): Options {
        if (options == null) throw invalid("Options must not be null")
        if (maximum !in 2..16) throw mode("maxSymbols must be an integer from 2 to 16")
        if (options.structuredAppend() != null) throw mode("Structured Append generation owns its header")
        if (options.eci() != null || options.fnc1Second() != null) throw mode("Structured Append cannot be combined with ECI or FNC1 second position")
        if (options.gs1()) throw SpecQrException("INVALID_GS1", "Structured Append cannot be combined with gs1")
        if (options.boostErrorCorrection()) throw mode("Structured Append does not support boostErrorCorrection")
        if (manual && (options.mode() != "auto" || !options.optimizeSegments())) throw mode("Manual Structured Append preserves caller segment modes")
        return options
    }
    private fun diagnosticOptions(options: DiagnosticOptions?): DiagnosticOptions {
        if (options == null) throw invalid("Diagnostic options must not be null")
        if (options.splitUnits != "summary" && options.splitUnits != "full") throw invalid("diagnostics.splitUnits must be summary or full")
        if (options.symbolResults != "output" && options.symbolResults != "diagnostics") throw invalid("diagnostics.symbolResults must be output or diagnostics")
        return options
    }
    private fun capacity(options: Options, version: Int) = Tables.dataCodewords(version, options.errorCorrectionLevel()) * 8
    private fun maximumVersion(options: Options) = options.version() ?: options.maxVersion()
    private fun unitBudget(options: Options, maximum: Int) = maximum * ((capacity(options, maximumVersion(options)) - 20) * 3 / 10)
    private fun numericBits(length: Int) = length / 3 * 10L + intArrayOf(0, 4, 7)[length % 3]
    private fun payloadBits(mode: String, length: Int, byteLength: Int): Long = when (mode) {
        "numeric" -> numericBits(length)
        "alphanumeric" -> length / 2 * 11L + length % 2 * 6
        "kanji" -> length * 13L
        else -> byteLength * 8L
    }
    private fun segmentBits(mode: String, length: Int, byteLength: Int, version: Int): Long {
        val width = Tables.countBits(mode, version); val count = if (mode == "byte") byteLength else length
        return if (count >= (1 shl width)) Long.MAX_VALUE / 1024 else 4 + width + payloadBits(mode, length, byteLength)
    }
    private data class Chunk(val data: Any, val offsets: Map<String, Any?>)
    private abstract class Source {
        var length = 0; var inputLength = 0; var byteLength = 0; var parity = 0
        abstract fun bits(start: Int, length: Int, options: Options, version: Int): Long
        abstract fun chunk(start: Int, length: Int): Chunk
        open fun largestPrefix(start: Int, maximum: Int, options: Options, version: Int): Int {
            var low = 1; var high = maximum; var best = 0
            while (low <= high) {
                val length = low + (high - low) / 2
                if (bits(start, length, options, version) <= capacity(options, version)) { best = length; low = length + 1 } else high = length - 1
            }
            return best
        }
    }
    private class InputSource(value: String?, data: ByteArray?, options: Options, maximum: Int): Source() {
        val text: String?; val binary: ByteArray?; val index: TextIndex?
        init {
            val budget = unitBudget(options, maximum)
            if (value == null && data == null) throw invalid("Input must be text or bytes")
            if (value != null && value.length > 2L * budget || data != null && data.size > budget) throw tooLong("Input cannot be split within the selected Structured Append capacity")
            if (value != null) {
                index = TextIndex(value); text = value; binary = null
                length = index.length; byteLength = index.byteLength; parity = index.parity
                if (length > budget) throw tooLong("Input cannot be split within the selected Structured Append capacity")
                if (options.mode() != "auto") Segments.create(value, maximumVersion(options), options.mode(), false, true)
            } else {
                if (options.mode() != "auto" && options.mode() != "byte") throw mode("Binary input can only be encoded in byte mode")
                index = null; text = null; binary = data!!.clone(); length = data.size; byteLength = data.size; parity = xor(data)
            }
            if (length == 0) throw invalid("Structured Append requires at least two non-empty symbols")
            inputLength = length
            val mode = if (binary != null) "byte" else options.mode(); val version = maximumVersion(options)
            val width = if (mode == "auto") dataModes.minOf { Tables.countBits(it, version) } else Tables.countBits(mode, version)
            val required = if (mode == "auto") numericBits(length) else payloadBits(mode, length, byteLength)
            if (required > maximum * maxOf(0, capacity(options, version) - 24L - width)) throw tooLong("Input cannot be split within the selected Structured Append capacity")
        }
        override fun bits(start: Int, length: Int, options: Options, version: Int): Long {
            val capacity = capacity(options, version)
            if (numericBits(length) > capacity - 20) return Long.MAX_VALUE / 1024
            if (binary != null) return 20 + segmentBits("byte", length, length, version)
            val bytes = index!!.byteLength(start, length)
            if (options.mode() != "auto") return 20 + segmentBits(options.mode(), length, bytes, version)
            if (options.optimizeSegments()) {
                val tracker = Segments.OptimizationTracker(version, true); var bits = 0L; var offset = index.charOffset(start)
                for (i in 0 until length) { val cp = text!!.codePointAt(offset); offset += Character.charCount(cp); bits = tracker.append(cp); if (bits + 20 > capacity) break }
                return bits + 20
            }
            return 20 + Segments.bitLength(Segments.create(index.slice(start, length), version, "auto", false, true), version)
        }
        override fun largestPrefix(start: Int, maximum: Int, options: Options, version: Int): Int {
            if (binary == null && options.mode() == "auto" && options.optimizeSegments()) {
                val tracker = Segments.OptimizationTracker(version, true); val capacity = capacity(options, version) - 20; var offset = index!!.charOffset(start)
                for (i in 0 until maximum) { val cp = text!!.codePointAt(offset); offset += Character.charCount(cp); if (tracker.append(cp) > capacity) return i }
                return maximum
            }
            return super.largestPrefix(start, maximum, options, version)
        }
        override fun chunk(start: Int, length: Int): Chunk = Chunk(
            if (binary == null) index!!.slice(start, length) else binary.copyOfRange(start, start + length),
            SpecQr.map("inputStart", start, "inputLength", length, "byteStart", if (binary == null) index!!.byteOffset(start) else start,
                "byteLength", if (binary == null) index!!.byteLength(start, length) else length))
    }
    private class Descriptor(val segment: Segment, val sourceIndex: Int, val splitStart: Int, val byteStart: Int) {
        val textIndex = segment.text()?.let(::TextIndex)
        val binary = if (textIndex == null) segment.logicalBytes() else null
        val byteLength = textIndex?.byteLength ?: binary!!.size
        val splitCount = if (segment.mode() == "byte") textIndex?.length ?: binary!!.size else 1
        fun localByteOffset(start: Int) = if (segment.mode() != "byte") 0 else textIndex?.byteOffset(start) ?: start
        fun localByteLength(start: Int, length: Int) = if (segment.mode() != "byte") byteLength else textIndex?.byteLength(start, length) ?: length
        fun parity() = textIndex?.parity ?: xor(binary!!)
        fun slice(start: Int, length: Int): Segment = if (segment.mode() != "byte") segment else if (textIndex == null) Segment.bytes(binary!!.copyOfRange(start, start + length)) else Segment.bytes(textIndex.slice(start, length))
    }
    private class SegmentSource(input: List<Segment?>?, options: Options, maximum: Int): Source() {
        val segments = manual(input, minOf(Segments.MAX_PAYLOAD_UNITS, unitBudget(options, maximum)))
        val descriptors = ArrayList<Descriptor>()
        init {
            inputLength = segments.size; val version = maximumVersion(options); var bits = 0L
            for ((i, segment) in segments.withIndex()) {
                val descriptor = Descriptor(segment, i, length, byteLength); descriptors.add(descriptor)
                length += descriptor.splitCount; byteLength += descriptor.byteLength; parity = parity xor descriptor.parity()
                bits += 4 + Tables.countBits(segment.mode(), version) + payloadBits(segment.mode(), segment.characterCount(), descriptor.byteLength)
            }
            if (bits > maximum * maxOf(0, capacity(options, version) - 20L)) throw tooLong("Input segments cannot be split within the selected Structured Append capacity")
        }
        override fun bits(start: Int, length: Int, options: Options, version: Int): Long {
            val end = start + length; var bits = 20L
            for (d in descriptors) {
                val finish = d.splitStart + d.splitCount
                if (finish <= start) continue
                if (d.splitStart >= end) break
                val localStart = maxOf(start, d.splitStart) - d.splitStart
                val localLength = minOf(end, finish) - (d.splitStart + localStart)
                bits += segmentBits(d.segment.mode(), if (d.segment.mode() == "byte") localLength else d.segment.characterCount(), d.localByteLength(localStart, localLength), version)
                if (bits > capacity(options, version)) break
            }
            return bits
        }
        override fun chunk(start: Int, length: Int): Chunk {
            val result = ArrayList<Segment>(); var firstIndex = -1; var lastIndex = -1; var byteStart = 0; var byteLength = 0; val end = start + length
            for (d in descriptors) {
                val finish = d.splitStart + d.splitCount
                if (finish <= start) continue
                if (d.splitStart >= end) break
                val localStart = maxOf(start, d.splitStart) - d.splitStart; val localLength = minOf(end, finish) - (d.splitStart + localStart)
                if (firstIndex < 0) { firstIndex = d.sourceIndex; byteStart = d.byteStart + d.localByteOffset(localStart) }
                lastIndex = d.sourceIndex + 1; byteLength += d.localByteLength(localStart, localLength); result.add(d.slice(localStart, localLength))
            }
            return Chunk(immutable(result), SpecQr.map("sourceSegmentStart", firstIndex, "sourceSegmentEnd", lastIndex, "splitUnitStart", start, "splitUnitLength", length, "byteStart", byteStart, "byteLength", byteLength))
        }
        fun fullDetail(): List<Map<String, Any?>> = buildList(length) {
            for (d in descriptors) for (unit in 0 until d.splitCount) add(SpecQr.map("sourceSegmentIndex", d.sourceIndex, "mode", d.segment.mode(), "unitStart", if (d.segment.mode() == "byte") unit else 0, "unitLength", if (d.segment.mode() == "byte") 1 else d.segment.characterCount(), "byteStart", d.byteStart + d.localByteOffset(unit), "byteLength", d.localByteLength(unit, 1)))
        }
    }
    private data class Range(val start: Int, val length: Int)
    private data class Selection(val version: Int, val ranges: List<Range>, val strategy: String)
    private fun select(source: Source, options: Options, maximum: Int): Selection {
        val first = options.version() ?: options.minVersion(); val last = maximumVersion(options); var sawTooLong = false
        for (version in first..last) {
            if (source.bits(0, source.length, options, version) <= capacity(options, version)) continue
            val ranges = ArrayList<Range>(); var start = 0
            while (start < source.length && ranges.size < maximum) {
                val limit = source.length - start - (if (ranges.isEmpty()) 1 else 0)
                val length = source.largestPrefix(start, limit, options, version)
                if (length == 0) break
                ranges.add(Range(start, length)); start += length
            }
            if (start == source.length && ranges.size >= 2) return Selection(version, immutable(ranges), if (options.version() == null) "auto-minimum" else "fixed")
            sawTooLong = true
        }
        if (sawTooLong) throw tooLong("Input cannot be split into $maximum or fewer Structured Append symbols in the selected version range")
        throw invalid("Input fits in one symbol in the selected version range; use generate(), generateSegments(), or a low-level Structured Append header")
    }
    @Suppress("UNCHECKED_CAST")
    private fun generate(source: Source, options: Options, maximum: Int, detail: DiagnosticOptions): Result {
        val selection = select(source, options, maximum); val version = selection.version; val total = selection.ranges.size
        val symbols = ArrayList<QrCode>(total); val diagnostics = ArrayList<Map<String, Any?>>(total)
        for ((i, range) in selection.ranges.withIndex()) {
            val chunk = source.chunk(range.start, range.length)
            val fixed = options.toBuilder().version(version).minVersion(version).maxVersion(version).structuredAppend(Segment.structuredAppend(i + 1, total, source.parity)).build()
            val result = when (val data = chunk.data) { is String -> SpecQr.generate(data, fixed); is ByteArray -> SpecQr.generate(data, fixed); else -> SpecQr.generateSegments(data as List<Segment>, fixed) }
            symbols.add(result); val bits = Segments.bitLength(result.segments(), version)
            val symbol = SpecQr.map("index", i + 1, "total", total, "parity", source.parity, "sequenceIndex", i, "sequenceTotal", total - 1, "sequenceIndicator", (i shl 4) or (total - 1))
            symbol.putAll(chunk.offsets)
            symbol.putAll(SpecQr.map("version", version, "errorCorrectionLevel", result.errorCorrectionLevel(), "dataBitLength", bits, "capacityBits", capacity(options, version), "remainingBits", capacity(options, version) - bits, "maskPattern", result.maskPattern()))
            diagnostics.add(symbol)
        }
        val warnings = ArrayList<Map<String, Any?>>()
        if (total == maximum) warnings.add(SpecQr.map("code", "STRUCTURED_APPEND_MAX_SYMBOLS_NEAR_LIMIT", "severity", "info", "message", "The generated Structured Append set uses the configured maximum number of symbols.", "details", SpecQr.map("total", total, "maxSymbols", maximum)))
        if (detail.symbolResults == "diagnostics") warnings.add(SpecQr.map("code", "STRUCTURED_APPEND_DECODER_SUPPORT_VARIES", "severity", "info", "message", "Decoder APIs vary in how they expose Structured Append set metadata.", "details", SpecQr.map("total", total)))
        val reason = if (selection.strategy == "fixed") "Version $version was requested explicitly." else "Version $version is the smallest version in ${options.minVersion()}..${options.maxVersion()} that can split the payload into $total Structured Append symbols at error correction ${options.errorCorrectionLevel()}."
        val summary = SpecQr.map("version", version, "errorCorrectionLevel", options.errorCorrectionLevel(), "versionSelection", selection.strategy, "versionSelectionReason", reason, "total", total, "parity", source.parity, "byteLength", source.byteLength, "inputLength", source.inputLength, "maxSymbols", maximum, "splitStrategy", if (source is SegmentSource) "segment-boundary-byte-chunk" else "greedy-largest-fitting", "symbols", diagnostics, "warnings", warnings)
        if (source is SegmentSource) {
            summary.putAll(SpecQr.map("segmentCount", source.segments.size, "splitUnitCount", source.length, "splitUnitsDetail", detail.splitUnits))
            if (detail.splitUnits == "full") summary["splitUnits"] = source.fullDetail()
        }
        return Result(symbols, total, source.parity, source.inputLength, source.byteLength, summary)
    }
    @JvmStatic fun generate(input: String?) = generate(input, Options.defaults())
    @JvmStatic fun generate(input: ByteArray?) = generate(input, Options.defaults())
    @JvmStatic fun generate(input: String?, options: Options?) = generate(input, options, 16)
    @JvmStatic fun generate(input: ByteArray?, options: Options?) = generate(input, options, 16)
    @JvmStatic fun generate(input: String?, options: Options?, maxSymbols: Int) = generate(input, options, maxSymbols, DiagnosticOptions.defaults())
    @JvmStatic fun generate(input: ByteArray?, options: Options?, maxSymbols: Int) = generate(input, options, maxSymbols, DiagnosticOptions.defaults())
    @JvmStatic fun generate(input: String?, options: Options?, maxSymbols: Int, detail: DiagnosticOptions?): Result {
        val opts = options(options, maxSymbols, false); val checked = diagnosticOptions(detail)
        return generate(InputSource(input, null, opts, maxSymbols), opts, maxSymbols, checked)
    }
    @JvmStatic fun generate(input: ByteArray?, options: Options?, maxSymbols: Int, detail: DiagnosticOptions?): Result {
        val opts = options(options, maxSymbols, false); val checked = diagnosticOptions(detail)
        return generate(InputSource(null, input, opts, maxSymbols), opts, maxSymbols, checked)
    }
    @JvmStatic fun generateSegments(segments: List<Segment?>?) = generateSegments(segments, Options.defaults())
    @JvmStatic fun generateSegments(segments: List<Segment?>?, options: Options?) = generateSegments(segments, options, 16)
    @JvmStatic fun generateSegments(segments: List<Segment?>?, options: Options?, maxSymbols: Int) = generateSegments(segments, options, maxSymbols, DiagnosticOptions.defaults())
    @JvmStatic fun generateSegments(segments: List<Segment?>?, options: Options?, maxSymbols: Int, detail: DiagnosticOptions?): Result {
        val opts = options(options, maxSymbols, true); val checked = diagnosticOptions(detail)
        return generate(SegmentSource(segments, opts, maxSymbols), opts, maxSymbols, checked)
    }
    @JvmStatic fun merge(parts: List<Part?>?): MergeResult {
        if (parts.isNullOrEmpty()) throw invalid("Structured Append parts must be a non-empty list")
        if (parts.size > 16) throw invalid("Structured Append cannot contain more than 16 parts")
        val ordered = arrayOfNulls<Part>(17); val descriptions = arrayOfNulls<PartInfo>(17)
        var total: Int? = null; var parity: Int? = null; var kind: String? = null; var bytes = 0; var actual = 0; var units = 0L
        for (part in parts) {
            if (part == null) throw invalid("Structured Append parts must not contain null")
            validateMetadata(part.index, part.total, part.parity)
            if (total != null && total != part.total) throw invalid("Structured Append total mismatch")
            if (parity != null && parity != part.parity) throw invalid("Structured Append parity mismatch")
            if (ordered[part.index] != null) throw invalid("Structured Append duplicate index ${part.index}")
            val partKind: String; val size: Int; val checksum: Int
            when (val data = part.data) {
                is String -> {
                    if (data.length > 2L * Segments.MAX_PAYLOAD_UNITS) throw tooLong("Merged payload exceeds the 1000000-unit resource limit")
                    val index = TextIndex(data); units += index.length; partKind = "string"; size = index.byteLength; checksum = index.parity
                }
                is ByteArray -> { units += data.size; partKind = "binary"; size = data.size; checksum = xor(data) }
                else -> throw invalid("Part data must be text or bytes")
            }
            if (units > Segments.MAX_PAYLOAD_UNITS) throw tooLong("Merged payload exceeds the 1000000-unit resource limit")
            if (kind != null && kind != partKind) throw invalid("Structured Append parts must not mix string and binary data")
            total = part.total; parity = part.parity; kind = partKind; bytes += size; actual = actual xor checksum
            ordered[part.index] = part; descriptions[part.index] = PartInfo(part.index, part.total, part.parity, kind, size)
        }
        val count = total!!; val expected = parity!!
        val missing = (1..count).filter { ordered[it] == null }
        if (missing.isNotEmpty()) throw invalid("Structured Append parts are missing indexes: ${missing.joinToString(", ")}")
        if (parts.size != count) throw invalid("Structured Append part count does not match total")
        if (actual != expected) throw invalid("Structured Append parity check failed: expected $expected, got $actual")
        val merged: Any = if (kind == "string") buildString(units.toInt()) { for (i in 1..count) append(ordered[i]!!.data as String) }
            else ByteArrayOutputStream(bytes).apply { for (i in 1..count) write(ordered[i]!!.data as ByteArray) }.toByteArray()
        val info = (1..count).map { descriptions[it]!! }
        return MergeResult(merged, count, expected, info, SpecQr.map("partCount", count, "total", count, "parity", expected, "dataType", kind, "byteLength", bytes, "missing", emptyList<Any>(), "duplicate", emptyList<Any>(), "parityCheck", SpecQr.map("expected", expected, "actual", actual, "matches", true)))
    }
}
