package io.specqr

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale
import java.util.zip.Adler32
import java.util.zip.CRC32
import kotlin.math.pow

/** Portable SVG and deterministic RGBA PNG rendering; only java.base and Kotlin stdlib. */
object Render {
    const val RASTER_PIXEL_BUDGET = 4 * 1024 * 1024
    const val SVG_CHARACTER_BUDGET = 8 * 1024 * 1024
    const val DATA_URL_CHARACTER_BUDGET = 32 * 1024 * 1024

    class Pixels internal constructor(val width: Int, val height: Int, pixels: ByteArray) {
        private val storage = pixels.copyOf()
        val pixels: ByteArray get() = storage.copyOf()
        fun width(): Int = width
        fun height(): Int = height
        fun pixels(): ByteArray = pixels
    }

    @JvmStatic fun copyMatrix(matrix: Array<out BooleanArray?>?): Array<BooleanArray> {
        if (matrix == null || matrix.size !in 1..177) throw bad("Matrix dimension must be 1..177")
        return Array(matrix.size) { y ->
            val row = matrix[y]
            if (row == null || row.size != matrix.size) throw bad("Matrix must be square")
            row.copyOf()
        }
    }
    private fun bad(message: String) = SpecQrException("INVALID_INPUT", message)
    private fun required(options: Options?) = options ?: throw bad("Options must not be null")
    private fun dimension(matrix: Array<BooleanArray>, options: Options, raster: Boolean): Long {
        val modules = matrix.size + 2L * options.margin
        if (modules > 9007199254740991L / options.scale) throw bad("Render geometry exceeds resource budget")
        val dimension = modules * options.scale
        if (dimension > 9007199254740991L || raster && (dimension > 2048 || dimension * dimension > RASTER_PIXEL_BUDGET))
            throw bad("Render geometry exceeds resource budget")
        return dimension
    }
    @JvmStatic fun parseColor(color: String?): IntArray = parseColor(color, true)!!
    @JvmStatic fun parseColor(color: String?, strict: Boolean): IntArray? {
        val value = color?.trim()?.lowercase(Locale.ROOT) ?: throw SpecQrException("INVALID_COLOR", "Color must not be null")
        when (value) {
            "black" -> return intArrayOf(0, 0, 0, 255)
            "white" -> return intArrayOf(255, 255, 255, 255)
            "transparent" -> return intArrayOf(0, 0, 0, 0)
        }
        if (Regex("#[0-9a-f]{3,4}|#[0-9a-f]{6}(?:[0-9a-f]{2})?").matches(value)) {
            val hex = value.substring(1)
            return intArrayOf(0, 0, 0, 255).also { components ->
                for (i in 0 until if (hex.length <= 4) hex.length else hex.length / 2)
                    components[i] = if (hex.length <= 4) hex.substring(i, i + 1).toInt(16) * 17
                    else hex.substring(i * 2, i * 2 + 2).toInt(16)
            }
        }
        if (strict) throw SpecQrException("INVALID_COLOR", "Raster color must be hex, black, white or transparent")
        return null
    }
    @JvmStatic fun contrastRatio(a: IntArray?, b: IntArray?): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
    }
    private fun luminance(color: IntArray?): Double {
        if (color == null || color.size != 4) throw bad("Color must have four components")
        if (color.any { it !in 0..255 }) throw bad("Color component out of range")
        val weights = doubleArrayOf(.2126, .7152, .0722)
        return (0..2).sumOf { i ->
            val value = color[i] / 255.0
            weights[i] * if (value <= .03928) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
    }
    @JvmStatic @JvmOverloads fun toPixels(matrix: Array<out BooleanArray?>?, options: Options? = Options()): Pixels {
        val o = required(options)
        val m = copyMatrix(matrix)
        val n = dimension(m, o, true).toInt()
        val foreground = parseColor(o.foreground)
        val background = parseColor(o.background)
        val pixels = ByteArray(n * n * 4)
        var offset = 0
        for (y in 0 until n) {
            val my = y / o.scale - o.margin
            for (x in 0 until n) {
                val mx = x / o.scale - o.margin
                val color = if (my in m.indices && mx in m.indices && m[my][mx]) foreground else background
                for (component in color) pixels[offset++] = component.toByte()
            }
        }
        return Pixels(n, n, pixels)
    }
    private fun xml(value: String): String {
        var i = 0
        while (i < value.length) {
            val c = value.codePointAt(i)
            if (!(c == 9 || c == 10 || c == 13 || c in 32..0xd7ff || c in 0xe000..0xfffd || c in 0x10000..0x10ffff))
                throw SpecQrException("INVALID_COLOR", "SVG color contains invalid XML character")
            i += Character.charCount(c)
        }
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("'", "&#x27;").replace("<", "&lt;").replace(">", "&gt;")
    }
    @JvmStatic @JvmOverloads fun toSvg(matrix: Array<out BooleanArray?>?, options: Options? = Options()): String {
        val o = required(options)
        val m = copyMatrix(matrix)
        val n = dimension(m, o, false)
        val digits = n.toString().length
        val scaleDigits = o.scale.toString().length
        val dark = m.sumOf { row -> row.count { it } }
        val budget = 512L + 6L * (o.foreground.length + o.background.length) + 4L * digits + dark.toLong() * (7 + 2 * digits + 3 * scaleDigits)
        if (budget > SVG_CHARACTER_BUDGET) throw bad("SVG exceeds output budget")
        val foreground = xml(o.foreground)
        val background = xml(o.background)
        return buildString(budget.toInt()) {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(n)
            append("\" height=\"").append(n).append("\" viewBox=\"0 0 ").append(n).append(' ').append(n)
            append("\" role=\"img\"><rect width=\"100%\" height=\"100%\" fill=\"").append(background)
            append("\"/><path fill=\"").append(foreground).append("\" d=\"")
            for (y in m.indices) for (x in m.indices) if (m[y][x]) {
                append('M').append((x + o.margin.toLong()) * o.scale).append(',')
                append((y + o.margin.toLong()) * o.scale).append('h').append(o.scale)
                append('v').append(o.scale).append("h-").append(o.scale).append('z')
            }
            append("\"/></svg>")
        }
    }
    private fun bigEndian(out: ByteArrayOutputStream, value: Long) {
        for (i in 3 downTo 0) out.write((value ushr (i * 8)).toInt() and 255)
    }
    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val bytes = type.toByteArray(Charsets.US_ASCII)
        bigEndian(out, data.size.toLong())
        out.writeBytes(bytes)
        out.writeBytes(data)
        val checksum = CRC32().apply { update(bytes); update(data) }
        bigEndian(out, checksum.value)
    }
    @JvmStatic @JvmOverloads fun toPng(matrix: Array<out BooleanArray?>?, options: Options? = Options()): ByteArray {
        val pixels = toPixels(matrix, options)
        val stride = pixels.width * 4
        val raw = ByteArray((stride + 1) * pixels.height)
        val rgba = pixels.pixels
        for (y in 0 until pixels.height) rgba.copyInto(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride)
        val zlib = ByteArrayOutputStream(raw.size + raw.size / 65535 * 5 + 16)
        zlib.write(0x78)
        zlib.write(1)
        for (i in raw.indices step 65535) {
            val count = minOf(65535, raw.size - i)
            zlib.write(if (i + count == raw.size) 1 else 0)
            zlib.write(count and 255)
            zlib.write(count ushr 8)
            zlib.write((count xor 65535) and 255)
            zlib.write((count xor 65535) ushr 8)
            zlib.write(raw, i, count)
        }
        bigEndian(zlib, Adler32().apply { update(raw) }.value)
        val header = ByteArrayOutputStream(13)
        bigEndian(header, pixels.width.toLong())
        bigEndian(header, pixels.height.toLong())
        header.writeBytes(byteArrayOf(8, 6, 0, 0, 0))
        return ByteArrayOutputStream(zlib.size() + 57).apply {
            writeBytes(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            chunk(this, "IHDR", header.toByteArray())
            chunk(this, "IDAT", zlib.toByteArray())
            chunk(this, "IEND", byteArrayOf())
        }.toByteArray()
    }
    @JvmStatic @JvmOverloads fun toPngDataUrl(matrix: Array<out BooleanArray?>?, options: Options? = Options()): String =
        "data:image/png;base64," + Base64.getEncoder().encodeToString(toPng(matrix, options))
    @JvmStatic @JvmOverloads fun toSvgDataUrl(matrix: Array<out BooleanArray?>?, options: Options? = Options()): String {
        val bytes = toSvg(matrix, options).toByteArray(Charsets.UTF_8)
        if (bytes.size * 3L + 31 > DATA_URL_CHARACTER_BUDGET) throw bad("SVG data URL exceeds output budget")
        val hex = "0123456789ABCDEF"
        return buildString {
            append("data:image/svg+xml;charset=utf-8,")
            for (byte in bytes) {
                val value = byte.toInt() and 255
                if (value in 65..90 || value in 97..122 || value in 48..57 || value.toChar() in "~()*!.'-_") append(value.toChar())
                else append('%').append(hex[value ushr 4]).append(hex[value and 15])
            }
        }
    }
}
