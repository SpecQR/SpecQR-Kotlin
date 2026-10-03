package io.specqr

import java.util.Collections

/** Immutable QR symbol. Array-valued properties and methods return defensive copies. */
class QrCode internal constructor(
    matrix: Array<BooleanArray>,
    val version: Int,
    val maskPattern: Int,
    val errorCorrectionLevel: String,
    data: ByteArray,
    words: ByteArray,
    segments: List<Segment>,
    diagnostics: Map<String, Any?>,
    val options: Options
) {
    private val modules = Render.copyMatrix(matrix)
    private val data = data.copyOf()
    private val words = words.copyOf()
    val segments: List<Segment> = Collections.unmodifiableList(ArrayList(segments))
    val diagnostics: Map<String, Any?> = SpecQr.freeze(diagnostics)
    val size: Int get() = modules.size
    val matrix: Array<BooleanArray> get() = Render.copyMatrix(modules)
    val dataCodewords: ByteArray get() = data.copyOf()
    val codewords: ByteArray get() = words.copyOf()
    val errorCorrectionCodewords: ByteArray get() = words.copyOfRange(data.size, words.size)
    fun matrix(): Array<BooleanArray> = matrix
    fun version(): Int = version
    fun size(): Int = size
    fun maskPattern(): Int = maskPattern
    fun errorCorrectionLevel(): String = errorCorrectionLevel
    fun dataCodewords(): ByteArray = dataCodewords
    fun codewords(): ByteArray = codewords
    fun errorCorrectionCodewords(): ByteArray = errorCorrectionCodewords
    fun segments(): List<Segment> = segments
    fun diagnostics(): Map<String, Any?> = diagnostics
    fun options(): Options = options
    operator fun get(x: Int, y: Int): Boolean = module(x, y)
    fun module(x: Int, y: Int): Boolean {
        if (x !in 0 until size || y !in 0 until size) throw SpecQrException("INVALID_INPUT", "Module coordinates out of bounds")
        return modules[y][x]
    }
    @JvmOverloads fun toSvg(options: Options? = this.options): String = Render.toSvg(modules, options)
    @JvmOverloads fun toPng(options: Options? = this.options): ByteArray = Render.toPng(modules, options)
    @JvmOverloads fun toPixels(options: Options? = this.options): Render.Pixels = Render.toPixels(modules, options)
    @JvmOverloads fun toSvgDataUrl(options: Options? = this.options): String = Render.toSvgDataUrl(modules, options)
    @JvmOverloads fun toPngDataUrl(options: Options? = this.options): String = Render.toPngDataUrl(modules, options)
}
