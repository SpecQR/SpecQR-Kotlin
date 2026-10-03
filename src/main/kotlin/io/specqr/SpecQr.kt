package io.specqr

import java.util.Collections

/** From-scratch QR Code Model 2 encoder and planning facade. */
object SpecQr {
    const val VERSION = "0.1.0-rc.1"

    @JvmStatic fun map(vararg pairs: Any?): MutableMap<String, Any?> {
        require(pairs.size % 2 == 0)
        return LinkedHashMap<String, Any?>().apply {
            for (i in pairs.indices step 2) put(pairs[i] as String, pairs[i + 1])
        }
    }
    @JvmStatic fun freeze(value: Map<String, Any?>?): Map<String, Any?> {
        if (value == null) throw SpecQrException("INVALID_INPUT", "Diagnostics must not be null")
        var reservedNodes = 1
        var visitedNodes = 0
        fun reserve(children: Int) {
            if (children > 1_000_000 - reservedNodes) throw SpecQrException("INVALID_INPUT", "Diagnostics item budget exceeded")
            reservedNodes += children
        }
        fun visit(value: Any?, depth: Int): Any? {
            if (depth > 64 || ++visitedNodes > 1_000_000) throw SpecQrException("INVALID_INPUT", "Diagnostics nesting or item budget exceeded")
            fun list(values: List<*>): List<Any?> {
                reserve(values.size)
                val result = ArrayList<Any?>(minOf(values.size, 1024))
                for (item in values) result.add(visit(item, depth + 1))
                return Collections.unmodifiableList(result)
            }
            return when (value) {
                is Map<*, *> -> {
                    reserve(value.size)
                    val result = LinkedHashMap<String, Any?>()
                    for ((key, item) in value) {
                        if (key !is String) throw SpecQrException("INVALID_INPUT", "Diagnostic keys must be strings")
                        result[key] = visit(item, depth + 1)
                    }
                    Collections.unmodifiableMap(result)
                }
                is List<*> -> list(value)
                is Array<*> -> list(value.asList())
                is BooleanArray -> list(value.asList())
                is ByteArray -> list(value.asList())
                is ShortArray -> list(value.asList())
                is IntArray -> list(value.asList())
                is LongArray -> list(value.asList())
                is FloatArray -> list(value.asList())
                is DoubleArray -> list(value.asList())
                is CharArray -> list(value.asList())
                null, is String, is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double -> value
                else -> if (value.javaClass == java.math.BigInteger::class.java || value.javaClass == java.math.BigDecimal::class.java) value
                        else throw SpecQrException("INVALID_INPUT", "Unsupported mutable diagnostics value")
            }
        }
        @Suppress("UNCHECKED_CAST")
        return visit(value, 0) as Map<String, Any?>
    }
    private fun required(options: Options?): Options = options ?: throw SpecQrException("INVALID_INPUT", "Options must not be null")
    @JvmStatic @JvmOverloads fun generate(text: String?, options: Options? = Options()): QrCode = build(estimate(text, options), required(options))
    @JvmStatic @JvmOverloads fun generate(data: ByteArray?, options: Options? = Options()): QrCode = build(estimate(data, options), required(options))
    @JvmStatic @JvmOverloads fun generateSegments(segments: List<Segment>?, options: Options? = Options()): QrCode = build(analyzeSegments(segments, options), required(options))
    @JvmStatic @JvmOverloads fun estimate(text: String?, options: Options? = Options()): Plan {
        val o = required(options)
        if (text == null) throw SpecQrException("INVALID_INPUT", "Text must not be null")
        if (text.length > 2_000_000) throw SpecQrException("DATA_TOO_LONG", "Input exceeds resource budget")
        var mode = o.mode
        val gs1 = if (o.gs1) {
            val parsed = Gs1.parseElementString(text)
            map("enabled", true, "elementCount", parsed.elements().size, "ais", parsed.elements().map { it.ai() }, "hasSeparators", '\u001d' in text)
        } else null
        if ((o.gs1 || o.fnc1Second != null) && '%' in text) {
            if (mode == "alphanumeric") throw SpecQrException("INVALID_MODE", "Literal percent in high-level FNC1 requires byte mode; use manual escaped segments for low-level data")
            if (mode == "auto") mode = "byte"
        }
        val cache = mutableMapOf<Int, List<Segment>>()
        return select({ version -> cache.getOrPut(group(version)) {
            controls(Segments.create(text, version, mode, o.optimizeSegments && text.codePointCount(0, text.length) <= 7089, o.eci == null), o)
        } }, o, gs1)
    }
    @JvmStatic @JvmOverloads fun estimate(data: ByteArray?, options: Options? = Options()): Plan {
        val o = required(options)
        if (data == null) throw SpecQrException("INVALID_INPUT", "Bytes must not be null")
        if (data.size > 1_000_000) throw SpecQrException("DATA_TOO_LONG", "Input exceeds resource budget")
        if (o.gs1) throw SpecQrException("INVALID_GS1", "High-level GS1 input requires an element string")
        val snapshot = data.copyOf()
        val cache = mutableMapOf<Int, List<Segment>>()
        return select({ version -> cache.getOrPut(group(version)) {
            controls(Segments.create(snapshot, version, o.mode, o.optimizeSegments, o.eci == null), o)
        } }, o, null)
    }
    @JvmStatic @JvmOverloads fun analyzeSegments(segments: List<Segment>?, options: Options? = Options()): Plan {
        val o = required(options)
        val data = controls(Segments.normalize(segments), o)
        return select({ data }, o, null)
    }
    private fun group(version: Int): Int = if (version <= 9) 0 else if (version <= 26) 1 else 2
    private fun controls(segments: List<Segment>, options: Options): List<Segment> {
        val control = when {
            options.eci != null -> Segment.eci(options.eci)
            options.gs1 -> Segment.fnc1()
            options.fnc1Second != null -> Segment.fnc1Second(options.fnc1Second)
            options.structuredAppend != null -> options.structuredAppend
            else -> null
        }
        return Segments.normalize(if (control == null) segments else listOf(control) + segments)
    }
    private fun select(factory: (Int) -> List<Segment>, options: Options, gs1: Map<String, Any?>?): Plan {
        val first = options.version ?: options.minVersion
        val last = options.version ?: options.maxVersion
        var version = last
        var segments = emptyList<Segment>()
        var bits = 0L
        var level = options.errorCorrectionLevel
        var ok = false
        for (candidate in first..last) {
            version = candidate
            segments = factory(candidate)
            bits = Segments.bitLength(segments, candidate)
            ok = bits <= Tables.dataCodewords(candidate, level) * 8L
            if (ok) {
                if (options.boostErrorCorrection) for (i in "LMQH".indexOf(level) + 1..3) {
                    val stronger = "LMQH"[i].toString()
                    if (bits <= Tables.dataCodewords(candidate, stronger) * 8L) level = stronger
                }
                break
            }
        }
        val capacity = Tables.dataCodewords(version, level) * 8
        val diagnostics = diagnostics(segments, version, level, bits, options, true, ok)
        if (gs1 != null) diagnostics["gs1Validation"] = gs1
        return Plan(ok, if (ok || options.version != null) version else null, version, level,
            options.errorCorrectionLevel, level != options.errorCorrectionLevel, bits, capacity,
            capacity - bits, segments, diagnostics)
    }
    private fun build(plan: Plan, options: Options): QrCode {
        if (!plan.ok) throw SpecQrException("DATA_TOO_LONG", "Input requires ${plan.requiredBits} bits; version ${plan.capacityVersion}-${plan.errorCorrectionLevel} holds ${plan.capacityBits}")
        val version = plan.capacityVersion
        val data = Core.padDataBits(Segments.bits(plan.segments, version), version, plan.errorCorrectionLevel)
        val interleaved = Core.interleaveCodewords(data, version, plan.errorCorrectionLevel)
        val built = Core.buildMatrix(interleaved.codewords(), version, plan.errorCorrectionLevel, options.maskPattern)
        val diagnostics = diagnostics(plan.segments, version, plan.errorCorrectionLevel, plan.requiredBits, options, false, true)
        diagnostics["gs1Validation"] = plan.diagnostics["gs1Validation"]
        diagnostics.putAll(map("maskPattern", built.maskPattern(), "maskPenalty", built.penalty(),
            "maskPenalties", built.maskPenalties().map { map("maskPattern", it.maskPattern(), "penalty", it.penalty()) },
            "maskSelectionReason", if (options.maskPattern != null) "Explicit mask requested." else "Lowest penalty; first mask wins ties.",
            "dataCodewords", data.size, "errorCorrectionCodewords", interleaved.codewords().size - data.size,
            "totalCodewords", interleaved.codewords().size))
        return QrCode(built.matrix(), version, built.maskPattern(), plan.errorCorrectionLevel,
            data, interleaved.codewords(), plan.segments, diagnostics, options)
    }
    @JvmStatic @JvmOverloads fun getCapacity(version: Int, level: String? = "M", mode: String? = null, controls: Long = 0): Capacity {
        Tables.validateVersion(version)
        Tables.validateLevel(level)
        if (controls < 0 || controls > 9007199254740991L) throw SpecQrException("INVALID_INPUT", "controlBits must be 0..2^53-1")
        val data = Tables.dataCodewords(version, level)
        var width: Int? = null
        var maximum: Int? = null
        var payload: Long? = null
        if (mode != null) {
            if (mode !in setOf("numeric", "alphanumeric", "byte", "kanji")) throw SpecQrException("INVALID_MODE", "Unknown capacity mode")
            width = Tables.countBits(mode, version)
            payload = maxOf(0, 8L * data - controls - 4 - width)
            val count = when (mode) {
                "numeric" -> payload / 10 * 3 + if (payload % 10 >= 7) 2 else if (payload % 10 >= 4) 1 else 0
                "alphanumeric" -> payload / 11 * 2 + if (payload % 11 >= 6) 1 else 0
                "byte" -> payload / 8
                else -> payload / 13
            }
            maximum = minOf(count, ((1 shl width) - 1).toLong()).toInt()
        }
        return Capacity(version, level!!, Tables.size(version), data, Tables.rawCodewords(version), data * 8,
            mode, width, if (mode == null) null else 4, controls, payload,
            if (mode == "byte") null else maximum, if (mode == "byte") maximum else null)
    }
    private fun diagnostics(segments: List<Segment>, version: Int, level: String, bits: Long, options: Options, planning: Boolean, ok: Boolean): MutableMap<String, Any?> {
        val capacity = Tables.dataCodewords(version, level) * 8
        val controls = segments.filter { it.isControl() }
        val modes = segments.filterNot { it.isControl() }.map { it.mode() }.distinct()
        val mode = if (modes.size == 1) modes[0] else if (modes.isEmpty()) "byte" else "mixed"
        val warnings = mutableListOf<Map<String, Any?>>()
        fun warning(code: String, severity: String, message: String, details: Map<String, Any?> = emptyMap()) {
            warnings.add(map("code", code, "severity", severity, "message", message, "details", details))
        }
        if (options.margin < 4) warning("QUIET_ZONE_TOO_SMALL", "warning", "QR readers expect at least four quiet-zone modules.", map("margin", options.margin))
        val foreground = Render.parseColor(options.foreground, false)
        val background = Render.parseColor(options.background, false)
        val ratio = if (foreground == null || background == null) null else Render.contrastRatio(foreground, background)
        when {
            ratio == null -> warning("COLOR_CONTRAST_UNKNOWN", "info", "These SVG colors cannot be checked for contrast.")
            ratio < 4.5 -> warning("COLOR_CONTRAST_LOW", "warning", "Color contrast is below the recommended minimum.", map("ratio", ratio))
            ratio < 7 -> warning("COLOR_CONTRAST_MODERATE", "info", "Stronger contrast is recommended.", map("ratio", ratio))
        }
        if (foreground != null && background != null && (foreground[3] < 255 || background[3] < 255)) warning("COLOR_ALPHA_USED", "warning", "Transparency can reduce scan reliability.")
        if (capacity - bits >= 0 && capacity - bits < capacity * .05) warning("CAPACITY_NEAR_LIMIT", "info", "Selected version is close to full.")
        val mm = options.printDpi?.let { options.scale / it * 25.4 }
        if (mm != null && mm < .25) warning("PRINT_MODULE_TOO_SMALL", "warning", "Print modules are smaller than 0.25 mm.", map("moduleSizeMm", mm))
        val blocking = warnings.filter { it["severity"] == "warning" }.map { it["code"] }
        if (blocking.isNotEmpty()) warning("SCAN_RISK", "warning", "One or more settings may reduce scan reliability.", map("blockingWarnings", blocking))
        val append = controls.firstOrNull { it.mode() == "structured-append" }
        val second = controls.firstOrNull { it.mode() == "fnc1-second" }
        val eci = controls.firstOrNull { it.mode() == "eci" }
        val gs1 = controls.any { it.mode() == "fnc1" }
        return map(
            "phase", if (planning) "planning" else "generation", "renderPlanned", false, "maskEvaluated", !planning, "codewordsBuilt", !planning,
            "version", if (ok || options.version != null) version else null, "size", if (ok || options.version != null) Tables.size(version) else null,
            "errorCorrectionLevel", level, "requestedErrorCorrectionLevel", options.errorCorrectionLevel,
            "boostedErrorCorrection", level != options.errorCorrectionLevel,
            "versionSelection", if (options.version != null) "fixed" else if (ok) "auto-minimum" else "auto-range",
            "versionSelectionReason", if (options.version != null) "Explicit version requested." else if (ok) "Smallest fitting version in the requested range." else "No fitting version in the requested range.",
            "mode", mode, "controlSegments", controls.map { map("mode", it.mode(), "bitLength", it.totalBits(version)) },
            "eciAssignmentNumber", eci?.assignment(), "fnc1", if (gs1) "first-position" else if (second != null) "second-position" else null,
            "gs1", gs1, "gs1Validation", map("enabled", gs1, "elementCount", if (gs1) null else 0, "ais", emptyList<String>(), "hasSeparators", false),
            "fnc1Second", map("enabled", second != null, "applicationIndicator", second?.applicationIndicator(), "applicationIndicatorCodeword", second?.applicationIndicatorCodeword()),
            "structuredAppend", map("enabled", append != null, "index", append?.index(), "total", append?.total(), "parity", append?.parity(),
                "sequenceIndex", append?.index()?.minus(1), "sequenceTotal", append?.total()?.minus(1),
                "sequenceIndicator", append?.let { ((it.index()!! - 1) shl 4) or (it.total()!! - 1) }),
            "segments", segments.map { map("mode", it.mode(), "characterCount", it.characterCount(), "byteCount", it.byteCount(), "bitLength", it.totalBits(version)) },
            "dataBitLength", bits, "capacityBits", capacity, "remainingBits", capacity - bits, "capacityUtilization", bits.toDouble() / capacity,
            "inputBytes", segments.sumOf { it.logicalBytes().size.toLong() },
            "quietZone", map("modules", options.margin, "recommendedModules", 4, "isSufficient", options.margin >= 4),
            "colors", map("ratio", ratio, "isInspectable", ratio != null, "foregroundAlpha", foreground?.get(3), "backgroundAlpha", background?.get(3),
                "isStrong", ratio != null && ratio >= 7, "isSufficient", ratio != null && ratio >= 4.5 && foreground!![3] == 255 && background!![3] == 255),
            "print", map("dpi", options.printDpi, "modulePixels", options.scale, "moduleSizeMm", mm,
                "symbolSizeMm", mm?.let { (Tables.size(version) + options.margin * 2L) * it },
                "recommendedMinimumModuleSizeMm", .25, "isModuleSizeSufficient", mm?.let { it >= .25 }), "warnings", warnings)
    }
}
