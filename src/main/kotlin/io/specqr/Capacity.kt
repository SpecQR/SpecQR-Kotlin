package io.specqr

/** Exact single-segment capacity; byte counts are not UTF-8 character counts. */
data class Capacity(
    val version: Int,
    val errorCorrectionLevel: String,
    val size: Int,
    val dataCodewords: Int,
    val totalCodewords: Int,
    val capacityBits: Int,
    val mode: String?,
    val characterCountBits: Int?,
    val modeBits: Int?,
    val controlBits: Long,
    val payloadBits: Long?,
    val maxCharacters: Int?,
    val maxBytes: Int?
) {
    fun version(): Int = version
    fun errorCorrectionLevel(): String = errorCorrectionLevel
    fun size(): Int = size
    fun dataCodewords(): Int = dataCodewords
    fun totalCodewords(): Int = totalCodewords
    fun capacityBits(): Int = capacityBits
    fun mode(): String? = mode
    fun characterCountBits(): Int? = characterCountBits
    fun modeBits(): Int? = modeBits
    fun controlBits(): Long = controlBits
    fun payloadBits(): Long? = payloadBits
    fun maxCharacters(): Int? = maxCharacters
    fun maxBytes(): Int? = maxBytes
    fun maximum(): Int? = if (mode == "byte") maxBytes else maxCharacters
}
