import io.specqr.*
import java.nio.file.Files
import java.nio.file.Path

fun main() {
    val options = Options(errorCorrectionLevel = "Q", scale = 4)
    val qr = SpecQr.generate("こんにちは、SpecQR Kotlin!", options)
    Files.writeString(Path.of("example.svg"), qr.toSvg())
    Files.write(Path.of("example.png"), qr.toPng())
    println("Version ${qr.version}, mask ${qr.maskPattern}, ${qr.size} × ${qr.size}")

    val manual = SpecQr.generateSegments(listOf(
        Segment.numeric("1234567890"),
        Segment.alphanumeric(" SPECQR "),
        Segment.kanji("漢字"),
        Segment.bytes("😀")
    ), options.copy(errorCorrectionLevel = "M"))
    println("Manual segment count: ${manual.segments.size}")
}
