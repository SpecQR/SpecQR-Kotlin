package io.specqr

import kotlin.math.abs

/** Model 2 padding, Reed–Solomon coding, placement and exact SpecQR masking. Matrices are [row][column]. */
object Core {
    private const val MAX_CODEWORDS = 3706
    private val divisors = arrayOfNulls<IntArray>(256)

    /** Owned data and parity bytes, exposed only through defensive copies. */
    class CodewordBlock internal constructor(data: ByteArray, ecc: ByteArray) {
        private val dataBytes = data.copyOf()
        private val parityBytes = ecc.copyOf()
        val data: ByteArray get() = dataBytes.copyOf()
        val ecc: ByteArray get() = parityBytes.copyOf()
        fun data(): ByteArray = data
        fun ecc(): ByteArray = ecc
    }

    /** Interleaved symbol bytes and immutable per-block diagnostics. */
    class InterleavedResult internal constructor(codewords: ByteArray, blocks: List<CodewordBlock>, val dataCodewords: Int) {
        private val encoded = codewords.copyOf()
        val blocks: List<CodewordBlock> = buildList { addAll(blocks) }
        val codewords: ByteArray get() = encoded.copyOf()
        val errorCorrectionCodewords: ByteArray get() = encoded.copyOfRange(dataCodewords, encoded.size)
        val errorCorrectionCodewordCount: Int get() = encoded.size - dataCodewords
        val totalCodewords: Int get() = encoded.size
        fun codewords(): ByteArray = codewords
        fun errorCorrectionCodewords(): ByteArray = errorCorrectionCodewords
        fun blocks(): List<CodewordBlock> = blocks
        fun dataCodewords(): Int = dataCodewords
        fun errorCorrectionCodewordCount(): Int = errorCorrectionCodewordCount
        fun totalCodewords(): Int = totalCodewords
    }

    data class MaskPenalty(val maskPattern: Int, val penalty: Int) {
        fun maskPattern(): Int = maskPattern
        fun penalty(): Int = penalty
    }

    /** A completed immutable symbol and penalties in candidate-mask order. */
    class MatrixResult internal constructor(matrix: Array<BooleanArray>, val maskPattern: Int, val penalty: Int, penalties: List<MaskPenalty>) {
        private val modules = copyMatrix(matrix)
        val matrix: Array<BooleanArray> get() = copyMatrix(modules)
        val maskPenalties: List<MaskPenalty> = buildList { addAll(penalties) }
        fun matrix(): Array<BooleanArray> = matrix
        fun maskPattern(): Int = maskPattern
        fun penalty(): Int = penalty
        fun maskPenalties(): List<MaskPenalty> = maskPenalties
    }

    /** Appends a terminator, zero alignment bits and alternating EC/11 codewords. */
    @JvmStatic fun padDataBits(bits: IntArray?, version: Int, level: String?): ByteArray {
        val capacity = Tables.dataCodewords(version, level)
        if (bits == null) throw invalid("Bits must not be null")
        if (bits.size > capacity * 8) {
            throw SpecQrException("DATA_TOO_LONG", "Input requires ${bits.size} bits, but version $version-$level has ${capacity * 8} data bits")
        }
        val data = ByteArray(capacity)
        for ((index, value) in bits.withIndex()) {
            if (value != 0 && value != 1) throw invalid("Bits must contain only 0 or 1")
            data[index / 8] = (data[index / 8].toInt() or (value shl (7 - index % 8))).toByte()
        }
        val terminatedBits = bits.size + minOf(4, capacity * 8 - bits.size)
        val paddedBytes = (terminatedBits + 7) / 8
        for (index in paddedBytes until capacity) data[index] = (if ((index - paddedBytes) % 2 == 0) 0xEC else 0x11).toByte()
        return data
    }

    /** Multiplies bytes in GF(256), using the reduction polynomial 0x11D. */
    @JvmStatic fun gfMultiply(left: Int, right: Int): Int {
        range(left, 0, 255, "Left operand")
        range(right, 0, 255, "Right operand")
        return multiply(left, right)
    }
    private fun multiply(left: Int, right: Int): Int {
        var a = left
        var b = right
        var result = 0
        while (b != 0) {
            if (b and 1 != 0) result = result xor a
            b = b ushr 1
            a = a shl 1
            if (a and 0x100 != 0) a = a xor 0x11D
        }
        return result
    }

    /** Bounded logarithmic-time exponentiation for a nonnegative exponent. */
    @JvmStatic fun gfPow(base: Int, exponent: Int): Int {
        range(base, 0, 255, "Base")
        if (exponent < 0) throw invalid("Exponent must be nonnegative")
        var factor = base
        var power = exponent
        var result = 1
        while (power != 0) {
            if (power and 1 != 0) result = multiply(result, factor)
            factor = multiply(factor, factor)
            power = power ushr 1
        }
        return result
    }

    /** Descending-power generator coefficients, including the leading monic coefficient. */
    @JvmStatic fun reedSolomonDivisor(degree: Int): ByteArray {
        range(degree, 1, 255, "Reed-Solomon degree")
        val generator = divisor(degree)
        return ByteArray(generator.size) { generator[it].toByte() }
    }

    @Synchronized private fun divisor(degree: Int): IntArray {
        divisors[degree]?.let { return it }
        val coefficients = IntArray(degree + 1)
        coefficients[0] = 1
        var root = 1
        for (factor in 0 until degree) {
            for (index in factor + 1 downTo 1) coefficients[index] = coefficients[index] xor multiply(coefficients[index - 1], root)
            root = multiply(root, 2)
        }
        divisors[degree] = coefficients
        return coefficients
    }

    @JvmStatic fun reedSolomonRemainder(data: ByteArray?, degree: Int): ByteArray {
        validateBytes(data, "Data")
        range(degree, 1, 255, "Reed-Solomon degree")
        return remainder(data!!, divisor(degree))
    }
    private fun remainder(data: ByteArray, divisor: IntArray): ByteArray {
        val degree = divisor.size - 1
        val result = ByteArray(degree)
        for (value in data) {
            val factor = (value.toInt() xor result[0].toInt()) and 0xFF
            for (index in 0 until degree - 1) result[index] = (result[index + 1].toInt() xor multiply(divisor[index + 1], factor)).toByte()
            result[degree - 1] = multiply(divisor[degree], factor).toByte()
        }
        return result
    }

    /** Splits padded bytes into QR blocks, computes parity, then interleaves both portions. */
    @JvmStatic fun interleaveCodewords(data: ByteArray?, version: Int, level: String?): InterleavedResult {
        val info = Tables.blockInfo(version, level)
        validateBytes(data, "Data codewords")
        val source = data!!
        if (source.size != info.dataCodewords) throw invalid("Expected ${info.dataCodewords} data codewords; got ${source.size}")
        val shortCount = info.blocks - info.rawCodewords % info.blocks
        val shortDataLength = info.rawCodewords / info.blocks - info.eccPerBlock
        val generator = divisor(info.eccPerBlock)
        val dataBlocks = ArrayList<ByteArray>(info.blocks)
        val parityBlocks = ArrayList<ByteArray>(info.blocks)
        var offset = 0
        val blocks = buildList {
            for (index in 0 until info.blocks) {
                val length = shortDataLength + if (index < shortCount) 0 else 1
                val block = source.copyOfRange(offset, offset + length)
                val parity = remainder(block, generator)
                dataBlocks.add(block)
                parityBlocks.add(parity)
                add(CodewordBlock(block, parity))
                offset += length
            }
        }
        val result = ByteArray(info.rawCodewords)
        var output = 0
        for (column in 0..shortDataLength) for (block in dataBlocks) if (column < block.size) result[output++] = block[column]
        for (column in 0 until info.eccPerBlock) for (parity in parityBlocks) result[output++] = parity[column]
        check(offset == source.size && output == result.size) { "Inconsistent QR block interleaving length" }
        return InterleavedResult(result, blocks, info.dataCodewords)
    }

    /** The data-mask predicate at a coordinate in the largest Model 2 symbol. */
    @JvmStatic fun maskCondition(mask: Int, x: Int, y: Int): Boolean {
        range(mask, 0, 7, "Mask pattern")
        range(x, 0, 176, "Column")
        range(y, 0, 176, "Row")
        return maskAt(mask, x, y)
    }
    private fun maskAt(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> (y / 2 + x / 3) % 2 == 0
        5 -> x * y % 2 + x * y % 3 == 0
        6 -> (x * y % 2 + x * y % 3) % 2 == 0
        else -> ((x + y) % 2 + x * y % 3) % 2 == 0
    }

    /** Exact SpecQR N1–N4 penalty of a square matrix with side 1 through 177. */
    @JvmStatic fun penaltyScore(matrix: Array<out BooleanArray?>?): Int {
        if (matrix == null || matrix.size !in 1..177) throw invalid("Matrix must be square with 1 to 177 rows")
        for (row in matrix) if (row == null || row.size != matrix.size) throw invalid("Matrix rows must have the same length as the matrix")
        @Suppress("UNCHECKED_CAST")
        return score(matrix as Array<BooleanArray>)
    }
    private fun score(matrix: Array<BooleanArray>): Int {
        val side = matrix.size
        var penalty = 0
        var dark = 0
        for (line in 0 until side) {
            penalty += linePenalty(matrix, line, true) + linePenalty(matrix, line, false)
            for (x in 0 until side) {
                if (matrix[line][x]) dark++
                if (line + 1 < side && x + 1 < side && matrix[line][x] == matrix[line][x + 1] &&
                    matrix[line][x] == matrix[line + 1][x] && matrix[line][x] == matrix[line + 1][x + 1]) penalty += 3
            }
        }
        val total = side * side
        return penalty + abs(dark * 20 - total * 10) / total * 10
    }
    private fun linePenalty(matrix: Array<BooleanArray>, line: Int, horizontal: Boolean): Int {
        var penalty = 0
        var runColor = false
        var runLength = 0
        var window = 0
        for (index in matrix.indices) {
            val value = if (horizontal) matrix[line][index] else matrix[index][line]
            if (index == 0 || value != runColor) {
                if (runLength >= 5) penalty += runLength - 2
                runColor = value
                runLength = 1
            } else runLength++
            window = ((window shl 1) or if (value) 1 else 0) and 0x7FF
            if (index >= 10 && (window == 0b10111010000 || window == 0b00001011101)) penalty += 40
        }
        return penalty + if (runLength >= 5) runLength - 2 else 0
    }

    /** Places codewords and selects the lowest-penalty mask (lowest number on a tie). */
    @JvmStatic fun buildMatrix(codewords: ByteArray?, version: Int, level: String?, mask: Int?): MatrixResult {
        val side = Tables.size(version)
        Tables.validateLevel(level)
        if (mask != null) range(mask, 0, 7, "Mask pattern")
        validateBytes(codewords, "Interleaved codewords")
        val source = codewords!!
        val expected = Tables.rawCodewords(version)
        if (source.size != expected) throw invalid("Expected $expected interleaved codewords; got ${source.size}")
        val base = Grid(side)
        base.drawFunctions(version, level!!)
        base.drawCodewords(source)
        var best: Grid? = null
        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        val penalties = buildList {
            for (candidateMask in (mask ?: 0)..(mask ?: 7)) {
                val candidate = base.masked(level, candidateMask)
                val penalty = score(candidate.modules)
                add(MaskPenalty(candidateMask, penalty))
                if (penalty < bestPenalty) {
                    best = candidate
                    bestMask = candidateMask
                    bestPenalty = penalty
                }
            }
        }
        val selected = checkNotNull(best) { "No QR mask candidate was evaluated" }
        return MatrixResult(selected.modules, bestMask, bestPenalty, penalties)
    }
    @JvmStatic fun buildMatrix(codewords: ByteArray?, version: Int, level: String?): MatrixResult = buildMatrix(codewords, version, level, null)

    private class Grid {
        private val side: Int
        val modules: Array<BooleanArray>
        private val functions: Array<BooleanArray>
        constructor(side: Int) {
            this.side = side
            modules = Array(side) { BooleanArray(side) }
            functions = Array(side) { BooleanArray(side) }
        }
        private constructor(base: Grid) {
            side = base.side
            modules = copyMatrix(base.modules)
            functions = base.functions
        }
        private fun function(x: Int, y: Int, dark: Boolean) {
            if (x in 0 until side && y in 0 until side) {
                modules[y][x] = dark
                functions[y][x] = true
            }
        }
        private fun finder(left: Int, top: Int) {
            for (dy in -1..7) for (dx in -1..7) {
                val inside = dx in 0..6 && dy in 0..6
                val dark = inside && (dx == 0 || dx == 6 || dy == 0 || dy == 6 || dx in 2..4 && dy in 2..4)
                function(left + dx, top + dy, dark)
            }
        }
        private fun drawFormat(level: String, mask: Int) {
            val data = (Tables.formatBits(level) shl 3) or mask
            var remainder = data
            repeat(10) { remainder = (remainder shl 1) xor (((remainder ushr 9) and 1) * 0x537) }
            val bits = ((data shl 10) or remainder) xor 0x5412
            for (index in 0 until 6) function(8, index, bit(bits, index))
            function(8, 7, bit(bits, 6))
            function(8, 8, bit(bits, 7))
            function(7, 8, bit(bits, 8))
            for (index in 9 until 15) function(14 - index, 8, bit(bits, index))
            for (index in 0 until 8) function(side - 1 - index, 8, bit(bits, index))
            for (index in 8 until 15) function(8, side - 15 + index, bit(bits, index))
        }
        fun drawFunctions(version: Int, level: String) {
            finder(0, 0)
            finder(side - 7, 0)
            finder(0, side - 7)
            for (index in 8 until side - 8) {
                function(index, 6, index % 2 == 0)
                function(6, index, index % 2 == 0)
            }
            val positions = Tables.alignmentPositions(version)
            val last = positions.lastIndex
            for (yi in positions.indices) for (xi in positions.indices) {
                if (xi == 0 && yi == 0 || xi == last && yi == 0 || xi == 0 && yi == last) continue
                for (dy in -2..2) for (dx in -2..2) function(positions[xi] + dx, positions[yi] + dy, maxOf(abs(dx), abs(dy)) != 1)
            }
            drawFormat(level, 0)
            function(8, side - 8, true)
            if (version >= 7) {
                var remainder = version
                repeat(12) { remainder = (remainder shl 1) xor (((remainder ushr 11) and 1) * 0x1F25) }
                val bits = (version shl 12) or remainder
                for (index in 0 until 18) {
                    val a = side - 11 + index % 3
                    val b = index / 3
                    function(a, b, bit(bits, index))
                    function(b, a, bit(bits, index))
                }
            }
        }
        fun drawCodewords(codewords: ByteArray) {
            var bitIndex = 0
            var right = side - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vertical in 0 until side) {
                    val y = if ((right + 1) and 2 == 0) side - 1 - vertical else vertical
                    for (x in right downTo right - 1) if (!functions[y][x]) {
                        if (bitIndex < codewords.size * 8) modules[y][x] = bit(codewords[bitIndex / 8].toInt() and 0xFF, 7 - bitIndex % 8)
                        bitIndex++
                    }
                }
                right -= 2
            }
            val remaining = bitIndex - codewords.size * 8
            check(remaining in 0..7) { "Inconsistent QR data-module count" }
        }
        fun masked(level: String, mask: Int): Grid {
            val candidate = Grid(this)
            for (y in 0 until side) for (x in 0 until side) {
                if (!functions[y][x] && maskAt(mask, x, y)) candidate.modules[y][x] = !candidate.modules[y][x]
            }
            candidate.drawFormat(level, mask)
            return candidate
        }
    }

    private fun bit(value: Int, index: Int): Boolean = ((value ushr index) and 1) != 0
    private fun copyMatrix(matrix: Array<BooleanArray>): Array<BooleanArray> = Array(matrix.size) { matrix[it].copyOf() }
    private fun validateBytes(bytes: ByteArray?, label: String) {
        if (bytes == null) throw invalid("$label must not be null")
        if (bytes.size > MAX_CODEWORDS) throw invalid("$label exceeds the maximum QR codeword count")
    }
    private fun range(value: Int, lower: Int, upper: Int, label: String) {
        if (value !in lower..upper) throw invalid("$label must be an integer from $lower to $upper")
    }
    private fun invalid(message: String): SpecQrException = SpecQrException("INVALID_INPUT", message)
}
