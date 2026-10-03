package io.specqr

/**
 * QR Code Model 2 capacities and block parameters for versions 1 through 40.
 * Tables and formulas ported from SpecQR at 15ad15e5c770ea0e39072f8f88b2733018f02ffd (MIT).
 */
public object Tables {
    private val ECC_PER_BLOCK = arrayOf(
        intArrayOf(
            -1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28,
            28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
            30
        ),
        intArrayOf(
            -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26,
            26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
            28
        ),
        intArrayOf(
            -1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26,
            30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
            30
        ),
        intArrayOf(
            -1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26,
            28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30,
            30
        ),
    )

    private val BLOCKS = arrayOf(
        intArrayOf(
            -1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7,
            8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24,
            25
        ),
        intArrayOf(
            -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14,
            16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47,
            49
        ),
        intArrayOf(
            -1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21,
            20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65,
            68
        ),
        intArrayOf(
            -1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25,
            25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77,
            81
        ),
    )

    /** Immutable block parameters; total parity length is [blocks] * [eccPerBlock]. */
    public data class BlockInfo(
        public val blocks: Int,
        public val eccPerBlock: Int,
        public val rawCodewords: Int,
        public val dataCodewords: Int,
    ) {
        public fun blocks(): Int = blocks
        public fun eccPerBlock(): Int = eccPerBlock
        public fun rawCodewords(): Int = rawCodewords
        public fun dataCodewords(): Int = dataCodewords
    }

    /** Reject versions outside the Model 2 range before capacity calculations. */
    @JvmStatic
    public fun validateVersion(version: Int) {
        if (version !in 1..40) {
            throw SpecQrException("INVALID_VERSION", "QR version must be an integer from 1 to 40")
        }
    }

    /** Accept exactly the uppercase error-correction levels L, M, Q, and H. */
    @JvmStatic
    public fun validateLevel(level: String?) {
        levelIndex(level)
    }

    private fun levelIndex(level: String?): Int = when (level) {
        "L" -> 0
        "M" -> 1
        "Q" -> 2
        "H" -> 3
        else -> throw SpecQrException("INVALID_INPUT", "Error correction level must be one of L, M, Q, H")
    }

    @JvmStatic
    public fun formatBits(level: String?): Int = when (levelIndex(level)) {
        0 -> 1
        1 -> 0
        2 -> 3
        else -> 2
    }

    /** Width and height, in modules, excluding the quiet zone. */
    @JvmStatic
    public fun size(version: Int): Int {
        validateVersion(version)
        return version * 4 + 17
    }

    /** Total data and error-correction codewords, excluding remainder bits. */
    @JvmStatic
    public fun rawCodewords(version: Int): Int {
        validateVersion(version)
        var bits = (16 * version + 128) * version + 64
        if (version >= 2) {
            val count = version / 7 + 2
            bits -= (25 * count - 10) * count - 55
            if (version >= 7) bits -= 36
        }
        return bits / 8
    }

    /** Data capacity in bytes, including segment headers and padding. */
    @JvmStatic
    public fun dataCodewords(version: Int, level: String?): Int = blockInfo(version, level).dataCodewords

    @JvmStatic
    public fun blockInfo(version: Int, level: String?): BlockInfo {
        validateVersion(version)
        val index = levelIndex(level)
        val blocks = BLOCKS[index][version]
        val ecc = ECC_PER_BLOCK[index][version]
        val raw = rawCodewords(version)
        return BlockInfo(blocks, ecc, raw, raw - blocks * ecc)
    }

    /** A fresh sorted array of alignment-pattern center coordinates. */
    @JvmStatic
    public fun alignmentPositions(version: Int): IntArray {
        validateVersion(version)
        if (version == 1) return intArrayOf()
        val count = version / 7 + 2
        val denominator = count * 2 - 2
        val step = if (version == 32) 26 else (version * 4 + 4 + denominator - 1) / denominator * 2
        val positions = IntArray(count)
        positions[0] = 6
        var position = size(version) - 7
        for (index in count - 1 downTo 1) {
            positions[index] = position
            position -= step
        }
        return positions
    }

    /** Width of the character-count field for a data mode and version. */
    @JvmStatic
    public fun countBits(mode: String?, version: Int): Int {
        validateVersion(version)
        return when (mode) {
            null -> throw SpecQrException("INVALID_INPUT", "Mode must be numeric, alphanumeric, byte, or kanji")
            "numeric" -> if (version <= 9) 10 else if (version <= 26) 12 else 14
            "alphanumeric" -> if (version <= 9) 9 else if (version <= 26) 11 else 13
            "byte" -> if (version <= 9) 8 else 16
            "kanji" -> if (version <= 9) 8 else if (version <= 26) 10 else 12
            else -> throw SpecQrException("INVALID_MODE", "Mode must be numeric, alphanumeric, byte, or kanji")
        }
    }
}
