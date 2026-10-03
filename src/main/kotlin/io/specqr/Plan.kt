package io.specqr

import java.util.Collections

/** Encoding plan without matrix, Reed–Solomon, or mask work. Collection inputs are copied. */
class Plan(
    val ok: Boolean,
    val version: Int?,
    val capacityVersion: Int,
    val errorCorrectionLevel: String,
    val requestedErrorCorrectionLevel: String,
    val boostedErrorCorrection: Boolean,
    val requiredBits: Long,
    val capacityBits: Int,
    val remainingBits: Long,
    segments: List<Segment>,
    diagnostics: Map<String, Any?>
) {
    val segments: List<Segment> = Collections.unmodifiableList(ArrayList(segments))
    val diagnostics: Map<String, Any?> = SpecQr.freeze(diagnostics)
    val dataBitLength: Long get() = requiredBits
    val selectedVersion: Int? get() = version
    val overflowBits: Long get() = maxOf(0L, -remainingBits)
    val capacityUtilization: Double get() = requiredBits.toDouble() / capacityBits
    fun ok(): Boolean = ok
    fun version(): Int? = version
    fun capacityVersion(): Int = capacityVersion
    fun errorCorrectionLevel(): String = errorCorrectionLevel
    fun requestedErrorCorrectionLevel(): String = requestedErrorCorrectionLevel
    fun boostedErrorCorrection(): Boolean = boostedErrorCorrection
    fun requiredBits(): Long = requiredBits
    fun capacityBits(): Int = capacityBits
    fun remainingBits(): Long = remainingBits
    fun segments(): List<Segment> = segments
    fun diagnostics(): Map<String, Any?> = diagnostics
    fun dataBitLength(): Long = dataBitLength
    fun selectedVersion(): Int? = selectedVersion
    fun overflowBits(): Long = overflowBits
    fun capacityUtilization(): Double = capacityUtilization
}
