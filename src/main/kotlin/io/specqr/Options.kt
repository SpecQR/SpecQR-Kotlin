package io.specqr

/** Immutable options. Named arguments and [copy] are the idiomatic Kotlin configuration API. */
data class Options @JvmOverloads constructor(
    val errorCorrectionLevel: String = "M",
    val mode: String = "auto",
    val version: Int? = null,
    val minVersion: Int = 1,
    val maxVersion: Int = 40,
    val maskPattern: Int? = null,
    val optimizeSegments: Boolean = true,
    val boostErrorCorrection: Boolean = false,
    val eci: Int? = null,
    val gs1: Boolean = false,
    val fnc1Second: String? = null,
    val structuredAppend: Segment? = null,
    val margin: Int = 4,
    val scale: Int = 8,
    val foreground: String = "#000000",
    val background: String = "#ffffff",
    val printDpi: Double? = null
) {
    init {
        Tables.validateLevel(errorCorrectionLevel)
        Tables.validateVersion(minVersion)
        Tables.validateVersion(maxVersion)
        if (minVersion > maxVersion) fail("INVALID_VERSION", "minVersion must not exceed maxVersion")
        version?.let { Tables.validateVersion(it) }
        if (maskPattern != null && maskPattern !in 0..7) fail("INVALID_INPUT", "maskPattern must be 0..7")
        if (mode !in setOf("auto", "numeric", "alphanumeric", "byte", "kanji")) fail("INVALID_MODE", "Unknown mode")
        if (eci != null && eci !in 0..999999) fail("INVALID_ECI", "ECI must be 0..999999")
        fnc1Second?.let { Segment.fnc1Second(it) }
        if (structuredAppend != null && structuredAppend.mode() != "structured-append") fail("INVALID_MODE", "structuredAppend requires a Structured Append segment")
        if (listOf(eci != null, gs1, fnc1Second != null, structuredAppend != null).count { it } > 1)
            fail("INVALID_MODE", "ECI, GS1, FNC1 second and Structured Append cannot be combined")
        if (margin < 0 || scale < 1) fail("INVALID_INPUT", "margin must be nonnegative and scale positive")
        if (foreground.length > Render.SVG_CHARACTER_BUDGET / 12 || background.length > Render.SVG_CHARACTER_BUDGET / 12)
            fail("INVALID_COLOR", "Colors must be bounded strings")
        if (printDpi != null && (!printDpi.isFinite() || printDpi <= 0 || !((177L + 2L * margin) * (scale / printDpi * 25.4)).isFinite()))
            fail("INVALID_INPUT", "printDpi must be finite and positive with finite print geometry")
    }

    fun errorCorrectionLevel(): String = errorCorrectionLevel
    fun mode(): String = mode
    fun version(): Int? = version
    fun minVersion(): Int = minVersion
    fun maxVersion(): Int = maxVersion
    fun maskPattern(): Int? = maskPattern
    fun optimizeSegments(): Boolean = optimizeSegments
    fun boostErrorCorrection(): Boolean = boostErrorCorrection
    fun eci(): Int? = eci
    fun gs1(): Boolean = gs1
    fun fnc1Second(): String? = fnc1Second
    fun structuredAppend(): Segment? = structuredAppend
    fun margin(): Int = margin
    fun scale(): Int = scale
    fun foreground(): String = foreground
    fun background(): String = background
    fun printDpi(): Double? = printDpi

    fun toBuilder(): Builder = Builder(this)

    companion object {
        @JvmStatic fun defaults(): Options = Options()
        @JvmStatic fun builder(): Builder = Builder()
        private fun fail(code: String, message: String): Nothing = throw SpecQrException(code, message)
    }

    /** Java-friendly builder; Kotlin callers can use the constructor or [Options.copy]. */
    class Builder internal constructor(o: Options = Options()) {
        private var errorCorrectionLevel: String? = o.errorCorrectionLevel
        private var mode: String? = o.mode
        private var version: Int? = o.version
        private var minVersion: Int = o.minVersion
        private var maxVersion: Int = o.maxVersion
        private var maskPattern: Int? = o.maskPattern
        private var optimizeSegments: Boolean = o.optimizeSegments
        private var boostErrorCorrection: Boolean = o.boostErrorCorrection
        private var eci: Int? = o.eci
        private var gs1: Boolean = o.gs1
        private var fnc1Second: String? = o.fnc1Second
        private var structuredAppend: Segment? = o.structuredAppend
        private var margin: Int = o.margin
        private var scale: Int = o.scale
        private var foreground: String? = o.foreground
        private var background: String? = o.background
        private var printDpi: Double? = o.printDpi

        fun errorCorrectionLevel(value: String?): Builder = apply { errorCorrectionLevel = value }
        fun mode(value: String?): Builder = apply { mode = value }
        fun version(value: Int?): Builder = apply { version = value }
        fun minVersion(value: Int): Builder = apply { minVersion = value }
        fun maxVersion(value: Int): Builder = apply { maxVersion = value }
        fun maskPattern(value: Int?): Builder = apply { maskPattern = value }
        fun optimizeSegments(value: Boolean): Builder = apply { optimizeSegments = value }
        fun boostErrorCorrection(value: Boolean): Builder = apply { boostErrorCorrection = value }
        fun eci(value: Int?): Builder = apply { eci = value }
        fun gs1(value: Boolean): Builder = apply { gs1 = value }
        fun fnc1Second(value: String?): Builder = apply { fnc1Second = value }
        fun structuredAppend(value: Segment?): Builder = apply { structuredAppend = value }
        fun margin(value: Int): Builder = apply { margin = value }
        fun scale(value: Int): Builder = apply { scale = value }
        fun foreground(value: String?): Builder = apply { foreground = value }
        fun background(value: String?): Builder = apply { background = value }
        fun printDpi(value: Double?): Builder = apply { printDpi = value }
        fun build(): Options = Options(
            errorCorrectionLevel = errorCorrectionLevel ?: throw SpecQrException("INVALID_INPUT", "errorCorrectionLevel must not be null"),
            mode = mode ?: throw SpecQrException("INVALID_MODE", "mode must not be null"),
            version = version,
            minVersion = minVersion,
            maxVersion = maxVersion,
            maskPattern = maskPattern,
            optimizeSegments = optimizeSegments,
            boostErrorCorrection = boostErrorCorrection,
            eci = eci,
            gs1 = gs1,
            fnc1Second = fnc1Second,
            structuredAppend = structuredAppend,
            margin = margin,
            scale = scale,
            foreground = foreground ?: throw SpecQrException("INVALID_COLOR", "foreground must not be null"),
            background = background ?: throw SpecQrException("INVALID_COLOR", "background must not be null"),
            printDpi = printDpi
        )
    }
}
