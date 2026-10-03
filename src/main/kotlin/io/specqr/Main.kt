package io.specqr

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/** Command-line entry point. Run with the thin SpecQR JAR and Kotlin stdlib on the class path. */
object Main {
    @JvmStatic fun main(args: Array<String>) { val code = run(args); if (code != 0) exitProcess(code) }
    @JvmStatic fun run(args: Array<String>): Int {
        try {
            val builder = Options.builder()
            var text: String? = null
            var binary: String? = null
            var segments: String? = null
            var output: String? = null
            var format = "svg"
            var diagnostics = false
            var estimate = false
            var i = 0
            fun value(arg: String): String { i++; if (i >= args.size) throw bad("Missing value for $arg"); return args[i] }
            while (i < args.size) {
                when (val arg = args[i]) {
                    "--help", "-h" -> {
                        println("""SpecQR Kotlin ${SpecQr.VERSION}
Usage: java -cp SPECQR_JAR:KOTLIN_STDLIB io.specqr.Main [options] TEXT
Sources: TEXT | --binary FILE | --segments FILE
Options: --format svg|png|matrix|svg-data-url|png-data-url --output FILE
         --ecc L|M|Q|H --version N --min-version N --max-version N --mask N
         --mode auto|numeric|alphanumeric|byte|kanji --eci N --gs1
         --fnc1-second XX --no-optimize --boost --estimate --diagnostics
         --scale N --margin N --foreground COLOR --background COLOR --dpi N
Use -- before text beginning with a dash. Windows class paths use ; instead of :.""")
                        return 0
                    }
                    "--binary" -> binary = value(arg)
                    "--segments" -> segments = value(arg)
                    "--output", "-o" -> output = value(arg)
                    "--format" -> format = value(arg)
                    "--ecc" -> builder.errorCorrectionLevel(value(arg))
                    "--version" -> builder.version(integer(value(arg)))
                    "--min-version" -> builder.minVersion(integer(value(arg)))
                    "--max-version" -> builder.maxVersion(integer(value(arg)))
                    "--mask" -> builder.maskPattern(integer(value(arg)))
                    "--mode" -> builder.mode(value(arg))
                    "--eci" -> builder.eci(integer(value(arg)))
                    "--gs1" -> builder.gs1(true)
                    "--fnc1-second" -> builder.fnc1Second(value(arg))
                    "--no-optimize" -> builder.optimizeSegments(false)
                    "--boost" -> builder.boostErrorCorrection(true)
                    "--scale" -> builder.scale(integer(value(arg)))
                    "--margin" -> builder.margin(integer(value(arg)))
                    "--foreground" -> builder.foreground(value(arg))
                    "--background" -> builder.background(value(arg))
                    "--dpi" -> builder.printDpi(value(arg).toDouble())
                    "--diagnostics" -> diagnostics = true
                    "--estimate" -> estimate = true
                    "--" -> {
                        if (i + 2 != args.size || text != null) throw bad("Expected one text argument after --")
                        text = args[++i]
                    }
                    else -> {
                        if (arg.startsWith('-')) throw bad("Unknown option: $arg")
                        if (text != null) throw bad("Only one text argument is accepted")
                        text = arg
                    }
                }
                i++
            }
            if (listOf(text, binary, segments).count { it != null } != 1) throw bad("Provide exactly one text, --binary or --segments source")
            if (format !in setOf("svg", "png", "matrix", "svg-data-url", "png-data-url")) throw SpecQrException("INVALID_OUTPUT", "Unknown output format")
            val options = builder.build()
            val bytes = binary?.let { readBounded(it, 1_000_000) }
            val manual = segments?.let { parseSegments(Json.parse(decode(readBounded(it, 4_000_000)))) }
            if (estimate) {
                val plan = when { manual != null -> SpecQr.analyzeSegments(manual, options); bytes != null -> SpecQr.estimate(bytes, options); else -> SpecQr.estimate(text, options) }
                write(output, Json.write(SpecQr.map("ok", plan.ok, "version", plan.version, "capacityVersion", plan.capacityVersion,
                    "requiredBits", plan.requiredBits, "capacityBits", plan.capacityBits, "overflowBits", plan.overflowBits, "diagnostics", plan.diagnostics)).toByteArray(Charsets.UTF_8), true)
                return 0
            }
            val qr = when { manual != null -> SpecQr.generateSegments(manual, options); bytes != null -> SpecQr.generate(bytes, options); else -> SpecQr.generate(text, options) }
            val result = when (format) {
                "png" -> qr.toPng()
                "svg" -> qr.toSvg().toByteArray(Charsets.UTF_8)
                "matrix" -> Json.write(qr.matrix).toByteArray(Charsets.UTF_8)
                "svg-data-url" -> qr.toSvgDataUrl().toByteArray(Charsets.UTF_8)
                "png-data-url" -> qr.toPngDataUrl().toByteArray(Charsets.UTF_8)
                else -> error("Unreachable format")
            }
            write(output, result, format != "png")
            if (diagnostics) System.err.println(Json.write(qr.diagnostics))
            return 0
        } catch (error: IllegalArgumentException) {
            System.err.println("specqr: " + (if (error is SpecQrException) "${error.code}: " else "") + error.message)
            return 2
        } catch (error: IOException) {
            System.err.println("specqr: ${error.message}")
            return 2
        }
    }
    private fun decode(bytes: ByteArray): String = try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: CharacterCodingException) { throw bad("JSON file must be valid UTF-8") }
    private fun readBounded(path: String, max: Int): ByteArray = Files.newInputStream(Path.of(path)).use {
        val bytes = it.readNBytes(max + 1)
        if (bytes.size > max) throw bad("Input file exceeds resource budget")
        bytes
    }
    private fun write(path: String?, bytes: ByteArray, newline: Boolean) {
        if (path == null) { System.out.write(bytes); if (newline) System.out.write('\n'.code); System.out.flush() }
        else Files.write(Path.of(path), bytes)
    }
    private fun integer(value: String): Int = value.toIntOrNull() ?: throw bad("Expected a 32-bit integer: $value")
    private fun bad(message: String) = SpecQrException("INVALID_INPUT", message)
    @JvmStatic fun number(value: Any?): Int {
        if (value !is Number || value is Double || value is Float || value.toLong() !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) throw bad("Expected integer")
        return value.toInt()
    }
    @JvmStatic fun string(value: Any?): String = value as? String ?: throw bad("Expected string")
    @JvmStatic fun parseSegments(value: Any?): List<Segment> {
        if (value !is List<*> || value.size > 16384) throw bad("Segments must be a bounded JSON array")
        val segments = ArrayList<Segment>()
        for (item in value) {
            if (item !is Map<*, *> || item["mode"] !is String) throw bad("Segment must be an object with a mode")
            val mode = item["mode"] as String
            val data = mode in setOf("numeric", "alphanumeric", "byte", "kanji")
            val allowed = when (mode) {
                "numeric", "alphanumeric", "byte", "kanji" -> setOf("mode", "text", "bytes")
                "eci" -> setOf("mode", "assignmentNumber")
                "fnc1" -> setOf("mode")
                "fnc1-second" -> setOf("mode", "applicationIndicator")
                "structured-append" -> setOf("mode", "index", "total", "parity")
                else -> throw bad("Unknown segment mode")
            }
            if (item.keys.any { it !in allowed }) throw bad("Unknown segment field")
            if (data && item.containsKey("text") == item.containsKey("bytes")) throw bad("Data segment requires exactly one text or bytes field")
            val segment = when (mode) {
                "numeric" -> Segment.numeric(string(item["text"]))
                "alphanumeric" -> Segment.alphanumeric(string(item["text"]))
                "kanji" -> Segment.kanji(string(item["text"]))
                "byte" -> if (item.containsKey("text")) Segment.bytes(string(item["text"])) else {
                    val values = item["bytes"]
                    if (values !is List<*> || values.size > 1_000_000) throw bad("bytes must be an integer array")
                    Segment.bytes(ByteArray(values.size) { index ->
                        val n = number(values[index])
                        if (n !in 0..255) throw bad("Byte out of range")
                        n.toByte()
                    })
                }
                "eci" -> Segment.eci(number(item["assignmentNumber"]))
                "fnc1" -> Segment.fnc1()
                "fnc1-second" -> Segment.fnc1Second(string(item["applicationIndicator"]))
                "structured-append" -> Segment.structuredAppend(number(item["index"]), number(item["total"]), number(item["parity"]))
                else -> error("Unreachable mode")
            }
            segments.add(segment)
        }
        return Segments.normalize(segments)
    }
}
