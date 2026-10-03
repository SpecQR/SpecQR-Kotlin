package io.specqr

import java.io.ByteArrayOutputStream
import java.net.IDN
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale

/** Bounded 50-AI catalog and local, non-networking GS1 Digital Link adapter.
 * Hosts use JDK IDNA2003 (not UTS #46); deviation/newer labels can therefore differ.
 * GS1 dot-only values survive parsing and are emitted in query parameters.
 */
object Gs1 {
    const val FNC1_SEPARATOR = "\u001d"
    const val GS1_FNC1_SEPARATOR = FNC1_SEPARATOR
    const val MAX_INPUT_CHARACTERS = 1_000_000
    const val MAX_ELEMENTS = 16_384
    private val primaryAis = setOf("00", "01", "414")
    private val invalidPercent = Regex("%(?![0-9a-fA-F]{2})")
    private val schemePattern = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
    private fun <T> immutable(values: List<T>): List<T> {
        val copy = ArrayList(values)
        copy.forEach { java.util.Objects.requireNonNull(it) }
        return Collections.unmodifiableList(copy)
    }

    data class Element(val ai: String?, val value: String?) { fun ai() = ai; fun value() = value 
        override fun toString(): String = "Element[ai=${ai}, value=${value}]"
    }
    data class AiLength(val type: String?, val exact: Int?, val min: Int?, val max: Int?) {
        fun type() = type; fun exact() = exact; fun min() = min; fun max() = max
        fun isVariable() = type == "variable"
    
        override fun toString(): String = "AiLength[type=${type}, exact=${exact}, min=${min}, max=${max}]"
    }
    class AiInfo(val ai: String?, val label: String?, val length: AiLength?, val valueKind: String?,
                 val checkDigitRule: String?, val digitalLinkRole: String?, val separator: String?,
                 digitalLinkPathForPrimary: List<String>?) {
        val digitalLinkPathForPrimary = digitalLinkPathForPrimary?.let(::immutable)
        fun ai() = ai; fun label() = label; fun length() = length; fun valueKind() = valueKind
        fun checkDigitRule() = checkDigitRule; fun digitalLinkRole() = digitalLinkRole
        fun separator() = separator; fun digitalLinkPathForPrimary() = digitalLinkPathForPrimary
    
        override fun equals(other: Any?): Boolean = other is AiInfo && ai == other.ai && label == other.label && length == other.length && valueKind == other.valueKind && checkDigitRule == other.checkDigitRule && digitalLinkRole == other.digitalLinkRole && separator == other.separator && digitalLinkPathForPrimary == other.digitalLinkPathForPrimary
        override fun hashCode(): Int = listOf<Any?>(ai, label, length, valueKind, checkDigitRule, digitalLinkRole, separator, digitalLinkPathForPrimary).fold(0) { hash, value -> 31 * hash + (value?.hashCode() ?: 0) }
        override fun toString(): String = "AiInfo[ai=${ai}, label=${label}, length=${length}, valueKind=${valueKind}, checkDigitRule=${checkDigitRule}, digitalLinkRole=${digitalLinkRole}, separator=${separator}, digitalLinkPathForPrimary=${digitalLinkPathForPrimary}]"
    }
    class ElementStringParseResult(elements: List<Element>, val hasSeparators: Boolean) {
        val elements = immutable(elements)
        fun elements() = elements; fun hasSeparators() = hasSeparators
    
        override fun equals(other: Any?): Boolean = other is ElementStringParseResult && elements == other.elements && hasSeparators == other.hasSeparators
        override fun hashCode(): Int = listOf<Any?>(elements, hasSeparators).fold(0) { hash, value -> 31 * hash + (value?.hashCode() ?: 0) }
        override fun toString(): String = "ElementStringParseResult[elements=${elements}, hasSeparators=${hasSeparators}]"
    }
    class ValidationIssue(val code: String?, val message: String?, val reason: String?, val ai: String?,
                          val value: String?, val key: String?, val offset: Int?, val elementIndex: Int?,
                          expected: Any?, val count: Int?) {
        val expected = SpecQr.freeze(SpecQr.map("expected", expected))["expected"]
        fun code() = code; fun message() = message; fun reason() = reason; fun ai() = ai
        fun value() = value; fun key() = key; fun offset() = offset; fun elementIndex() = elementIndex
        fun expected() = expected; fun count() = count
    
        override fun equals(other: Any?): Boolean = other is ValidationIssue && code == other.code && message == other.message && reason == other.reason && ai == other.ai && value == other.value && key == other.key && offset == other.offset && elementIndex == other.elementIndex && expected == other.expected && count == other.count
        override fun hashCode(): Int = listOf<Any?>(code, message, reason, ai, value, key, offset, elementIndex, expected, count).fold(0) { hash, value -> 31 * hash + (value?.hashCode() ?: 0) }
        override fun toString(): String = "ValidationIssue[code=${code}, message=${message}, reason=${reason}, ai=${ai}, value=${value}, key=${key}, offset=${offset}, elementIndex=${elementIndex}, expected=${expected}, count=${count}]"
    }
    data class ValidationOptions(val context: String?, val collectAllErrors: Boolean, val allowUnsupportedAi: Boolean) {
        fun context() = context; fun collectAllErrors() = collectAllErrors; fun allowUnsupportedAi() = allowUnsupportedAi
        companion object { @JvmStatic fun defaults() = ValidationOptions("element-string", true, false) }
    
        override fun toString(): String = "ValidationOptions[context=${context}, collectAllErrors=${collectAllErrors}, allowUnsupportedAi=${allowUnsupportedAi}]"
    }
    class ValidationResult(val ok: Boolean, elements: List<Element>?, val hasSeparators: Boolean?,
                           errors: List<ValidationIssue>, warnings: List<ValidationIssue>) {
        val elements = elements?.let(::immutable); val errors = immutable(errors); val warnings = immutable(warnings)
        fun ok() = ok; fun elements() = elements; fun hasSeparators() = hasSeparators
        fun errors() = errors; fun warnings() = warnings
    
        override fun equals(other: Any?): Boolean = other is ValidationResult && ok == other.ok && elements == other.elements && hasSeparators == other.hasSeparators && errors == other.errors && warnings == other.warnings
        override fun hashCode(): Int = listOf<Any?>(ok, elements, hasSeparators, errors, warnings).fold(0) { hash, value -> 31 * hash + (value?.hashCode() ?: 0) }
        override fun toString(): String = "ValidationResult[ok=${ok}, elements=${elements}, hasSeparators=${hasSeparators}, errors=${errors}, warnings=${warnings}]"
    }
    data class UnknownQuery(val key: String?, val value: String?) { fun key() = key; fun value() = value 
        override fun toString(): String = "UnknownQuery[key=${key}, value=${value}]"
    }
    class DigitalLinkParseResult(elements: List<Element>, val primary: Element?, pathElements: List<Element>,
                                 queryElements: List<Element>, unknownQuery: List<UnknownQuery>) {
        val elements = immutable(elements); val pathElements = immutable(pathElements)
        val queryElements = immutable(queryElements); val unknownQuery = immutable(unknownQuery)
        fun elements() = elements; fun primary() = primary; fun pathElements() = pathElements
        fun queryElements() = queryElements; fun unknownQuery() = unknownQuery
    
        override fun equals(other: Any?): Boolean = other is DigitalLinkParseResult && elements == other.elements && primary == other.primary && pathElements == other.pathElements && queryElements == other.queryElements && unknownQuery == other.unknownQuery
        override fun hashCode(): Int = listOf<Any?>(elements, primary, pathElements, queryElements, unknownQuery).fold(0) { hash, value -> 31 * hash + (value?.hashCode() ?: 0) }
        override fun toString(): String = "DigitalLinkParseResult[elements=${elements}, primary=${primary}, pathElements=${pathElements}, queryElements=${queryElements}, unknownQuery=${unknownQuery}]"
    }
    class DigitalLinkValidationResult(val ok: Boolean, val result: DigitalLinkParseResult?,
                                     errors: List<ValidationIssue>, warnings: List<ValidationIssue>) {
        val errors = immutable(errors); val warnings = immutable(warnings)
        fun ok() = ok; fun result() = result; fun errors() = errors; fun warnings() = warnings
    
        override fun equals(other: Any?): Boolean = other is DigitalLinkValidationResult && ok == other.ok && result == other.result && errors == other.errors && warnings == other.warnings
        override fun hashCode(): Int = listOf<Any?>(ok, result, errors, warnings).fold(0) { hash, value -> 31 * hash + (value?.hashCode() ?: 0) }
        override fun toString(): String = "DigitalLinkValidationResult[ok=${ok}, result=${result}, errors=${errors}, warnings=${warnings}]"
    }
    class DigitalLinkOptions(val baseUrl: String?, val primaryAi: String?, pathAis: List<String?>?,
                             val unknownQuery: String?, val normalize: Boolean, val mode: String?) {
        val pathAis: List<String?>? = pathAis?.let {
            if (it.size > MAX_ELEMENTS) throw failure("GS1 Digital Link pathAis exceeds element limit")
            Collections.unmodifiableList(ArrayList(it))
        }
        fun baseUrl() = baseUrl; fun primaryAi() = primaryAi; fun pathAis() = pathAis
        fun unknownQuery() = unknownQuery; fun normalize() = normalize; fun mode() = mode
        fun withPrimaryAi(ai: String?) = DigitalLinkOptions(baseUrl, ai, pathAis, unknownQuery, normalize, mode)
        fun withPathAis(ais: List<String?>?) = DigitalLinkOptions(baseUrl, primaryAi, ais, unknownQuery, normalize, mode)
        fun withUnknownQuery(policy: String?) = DigitalLinkOptions(baseUrl, primaryAi, pathAis, policy, normalize, mode)
        companion object {
            @JvmStatic fun defaults() = DigitalLinkOptions(null, null, null, "preserve", false, "specqr-deterministic")
            @JvmStatic fun forBaseUrl(url: String?) = DigitalLinkOptions(url, null, null, "preserve", false, "specqr-deterministic")
        }
    
        override fun equals(other: Any?): Boolean = other is DigitalLinkOptions && baseUrl == other.baseUrl && primaryAi == other.primaryAi && pathAis == other.pathAis && unknownQuery == other.unknownQuery && normalize == other.normalize && mode == other.mode
        override fun hashCode(): Int = listOf<Any?>(baseUrl, primaryAi, pathAis, unknownQuery, normalize, mode).fold(0) { hash, value -> 31 * hash + (value?.hashCode() ?: 0) }
        override fun toString(): String = "DigitalLinkOptions[baseUrl=${baseUrl}, primaryAi=${primaryAi}, pathAis=${pathAis}, unknownQuery=${unknownQuery}, normalize=${normalize}, mode=${mode}]"
    }
    private fun failure(message: String) = SpecQrException("INVALID_GS1", message)
    private fun text(value: String?, label: String): String {
        if (value == null) throw failure("$label must be a string")
        if (value.length > MAX_INPUT_CHARACTERS) throw failure("$label must contain at most $MAX_INPUT_CHARACTERS characters")
        return value
    }
    private fun digits(value: String?) = !value.isNullOrEmpty() && value.all { it in '0'..'9' }
    private fun isAi(value: String?) = value != null && value.length in 2..4 && digits(value)
    private fun stringLength(value: Any?) = (value as? String)?.length ?: 0
    private fun bounded(values: Iterable<*>?, label: String): List<Any?> {
        if (values == null) throw failure("$label must be an iterable of elements")
        val result = ArrayList<Any?>(); var work = 0L
        for (value in values) {
            if (result.size == MAX_ELEMENTS) throw failure("$label must contain at most $MAX_ELEMENTS elements")
            work += when (value) {
                is Element -> stringLength(value.ai) + stringLength(value.value)
                is Map<*, *> -> stringLength(value["ai"]) + stringLength(value["value"])
                else -> stringLength(value)
            }
            if (work > MAX_INPUT_CHARACTERS) throw failure("$label aggregate text exceeds the character work budget ($MAX_INPUT_CHARACTERS)")
            result.add(value)
        }
        return result
    }
    private fun catalog(): List<AiInfo> = buildList {
        fun addAi(ai: String, label: String, size: Int, variable: Boolean, kind: String = "numeric", check: String = "none", role: String = "data-attribute") {
            add(AiInfo(ai, label, if (variable) AiLength("variable", null, 1, size) else AiLength("fixed", size, null, null), kind, check, role,
                if (variable) "required-when-followed" else "none", if (role == "key-qualifier") listOf("01") else null))
        }
        addAi("00", "Serial shipping container code", 18, false, check = "sscc", role = "primary-key")
        addAi("01", "Global trade item number", 14, false, check = "gtin", role = "primary-key")
        addAi("02", "Contained trade item GTIN", 14, false, check = "gtin")
        addAi("10", "Batch or lot number", 20, true, "text", role = "key-qualifier")
        listOf("11" to "Production date", "12" to "Due date", "13" to "Packaging date", "15" to "Best before date", "16" to "Sell by date", "17" to "Expiration date").forEach { (ai, label) -> addAi(ai, label, 6, false) }
        addAi("20", "Internal product variant", 2, false)
        addAi("21", "Serial number", 20, true, "text", role = "key-qualifier")
        addAi("22", "Consumer product variant", 20, true, "text", role = "key-qualifier")
        addAi("30", "Variable count", 8, true)
        addAi("37", "Count of contained trade items", 8, true)
        listOf("240" to "Additional product identification", "241" to "Customer part number", "400" to "Customer purchase order number").forEach { (ai, label) -> addAi(ai, label, 30, true, "text") }
        listOf("Ship to global location number", "Bill to global location number", "Purchased from global location number", "Ship for global location number", "Identification of a physical location", "Global location number of the invoicing party").forEachIndexed { i, label -> addAi((410 + i).toString(), label, 13, false, role = if (i == 4) "primary-key" else "data-attribute") }
        addAi("420", "Ship to postal code", 20, true, "text")
        listOf("422" to "Country of origin", "424" to "Country of processing", "425" to "Country of disassembly", "426" to "Country covering full process chain").forEach { (ai, label) -> addAi(ai, label, 3, false) }
        for (start in listOf(3100, 3200)) for (i in 0..5) addAi((start + i).toString(), if (start == 3100) "Net weight in kilograms" else "Net weight in pounds", 6, false)
        for (i in 91..99) addAi(i.toString(), "Company internal information", 90, true, "text")
    }
    private val catalog = immutable(catalog())
    private val ais = catalog.associateBy { it.ai }
    @JvmStatic fun getSupportedAis(): List<AiInfo> = catalog
    @JvmStatic fun getAiInfo(ai: String?): AiInfo? = ais[ai]
    @JvmStatic fun getSupportedGs1Ais() = getSupportedAis()
    @JvmStatic fun getGs1AiInfo(ai: String?) = getAiInfo(ai)
    private fun numeric(value: String?, label: String): String {
        val result = text(value, label)
        if (!digits(result)) throw failure("$label must contain digits only")
        return result
    }
    @JvmStatic fun calculateCheckDigit(body: String?): String {
        val value = numeric(body, "GS1 check digit input"); var total = 0; var weight = 3
        for (i in value.indices.reversed()) { total = (total + (value[i] - '0') * weight) % 10; weight = 4 - weight }
        return ((10 - total) % 10).toString()
    }
    @JvmStatic fun validateCheckDigit(value: String?): Boolean {
        val checked = numeric(value, "GS1 check digit value")
        if (checked.length < 2) throw failure("GS1 check digit value must include body digits and one check digit")
        return calculateCheckDigit(checked.dropLast(1))[0] == checked.last()
    }
    @JvmStatic fun calculateGtinCheckDigit(body: String?): String {
        val value = numeric(body, "GTIN body")
        if (value.length !in setOf(7, 11, 12, 13)) throw failure("GTIN body must be 7, 11, 12, or 13 digits")
        return calculateCheckDigit(value)
    }
    @JvmStatic fun appendGtinCheckDigit(body: String?) = body + calculateGtinCheckDigit(body)
    @JvmStatic fun validateGtinCheckDigit(value: String?): Boolean {
        val checked = numeric(value, "GTIN")
        if (checked.length !in setOf(8, 12, 13, 14)) throw failure("GTIN must be 8, 12, 13, or 14 digits")
        return validateCheckDigit(checked)
    }
    @JvmStatic fun calculateSsccCheckDigit(body: String?): String {
        val value = numeric(body, "SSCC body")
        if (value.length != 17) throw failure("SSCC body must be exactly 17 digits")
        return calculateCheckDigit(value)
    }
    @JvmStatic fun appendSsccCheckDigit(body: String?) = body + calculateSsccCheckDigit(body)
    @JvmStatic fun validateSsccCheckDigit(value: String?): Boolean {
        val checked = numeric(value, "SSCC")
        if (checked.length != 18) throw failure("SSCC must be exactly 18 digits")
        return validateCheckDigit(checked)
    }
    @JvmStatic fun calculateGs1CheckDigit(body: String?) = calculateCheckDigit(body)
    @JvmStatic fun validateGs1CheckDigit(value: String?) = validateCheckDigit(value)
    private fun element(raw: Any?, index: Int): Element {
        val (ai, value) = when (raw) {
            is Element -> raw.ai to raw.value
            is Map<*, *> -> raw["ai"] to raw["value"]
            else -> throw failure("GS1 element $index must be an object")
        }
        if (ai !is String) throw failure("GS1 element $index AI must be a string")
        if (value !is String) throw failure("GS1 element $index value must be a string to preserve leading zeroes")
        text(ai, "GS1 AI"); text(value, "GS1 value")
        if (!isAi(ai)) throw failure("GS1 element $index has invalid AI \"${ai.take(100)}\"; expected 2 to 4 digits")
        val info = ais[ai] ?: throw failure("Unsupported GS1 AI $ai. Add explicit support before using it.")
        val prefix = "GS1 AI $ai value "
        if (value.isEmpty()) throw failure(prefix + "must not be empty")
        if (FNC1_SEPARATOR in value) throw failure(prefix + "must not contain the FNC1 separator")
        if ('(' in value || ')' in value) throw failure(prefix + "must be raw data without human-readable parentheses")
        if (value.any { it !in ' '..'~' }) throw failure(prefix + "must use printable ASCII characters")
        if (info.valueKind == "numeric" && !digits(value)) throw failure(prefix + "must contain digits only")
        if (info.length!!.isVariable()) {
            if (value.length > info.length!!.max!!) throw failure(prefix + "must be at most ${info.length!!.max} characters")
        } else if (value.length != info.length!!.exact) throw failure(prefix + "must be exactly ${info.length!!.exact} characters")
        if (info.checkDigitRule == "gtin" && !validateGtinCheckDigit(value)) throw failure(prefix + "has an invalid GTIN check digit")
        if (info.checkDigitRule == "sscc" && !validateSsccCheckDigit(value)) throw failure(prefix + "has an invalid SSCC check digit")
        return Element(ai, value)
    }
    @JvmStatic fun fromHumanReadable(input: String?): List<Element> {
        val value = text(input, "GS1 human-readable input")
        if (value.isEmpty()) throw failure("GS1 human-readable input must not be empty")
        val result = ArrayList<Element>(); var position = 0
        while (position < value.length) {
            if (result.size == MAX_ELEMENTS) throw failure("GS1 elements exceed element limit")
            if (value[position] != '(') throw failure("GS1 human-readable input must contain an AI in parentheses at offset $position")
            val close = value.indexOf(')', position + 1)
            if (close < 0) throw failure("GS1 AI starting at offset $position is missing a closing parenthesis")
            val end = value.indexOf('(', close + 1).let { if (it < 0) value.length else it }
            result.add(element(Element(value.substring(position + 1, close), value.substring(close + 1, end)), result.size))
            position = end
        }
        return immutable(result)
    }
    @JvmStatic fun parseHumanReadable(input: String?) = fromHumanReadable(input)
    @JvmStatic fun toElementString(elements: Iterable<*>?): String {
        val values = bounded(elements, "GS1 elements")
        if (values.isEmpty()) throw failure("GS1 elements must not be empty")
        val out = StringBuilder()
        values.forEachIndexed { i, raw ->
            val e = element(raw, i); out.append(e.ai).append(e.value)
            if (ais[e.ai]!!.length!!.isVariable() && i + 1 < values.size) out.append(FNC1_SEPARATOR)
            if (out.length > MAX_INPUT_CHARACTERS) throw failure("GS1 element string output exceeds character limit")
        }
        return out.toString()
    }
    @JvmStatic fun createElementString(elements: Iterable<*>?) = toElementString(elements)
    @JvmStatic fun toHumanReadable(elements: Iterable<*>?): String {
        val values = bounded(elements, "GS1 elements")
        if (values.isEmpty()) throw failure("GS1 elements must not be empty")
        val out = StringBuilder()
        values.forEachIndexed { i, raw ->
            val e = element(raw, i); out.append('(').append(e.ai).append(')').append(e.value)
            if (out.length > MAX_INPUT_CHARACTERS) throw failure("GS1 human-readable output exceeds character limit")
        }
        return out.toString()
    }
    @JvmStatic fun toHumanReadable(input: String?) = toHumanReadable(parseElementString(input).elements)
    private fun readAi(input: String, offset: Int): AiInfo? {
        for (size in intArrayOf(4, 3, 2)) if (offset + size <= input.length) ais[input.substring(offset, offset + size)]?.let { return it }
        return null
    }
    @JvmStatic fun parseElementString(input: String?): ElementStringParseResult {
        val value = text(input, "GS1 element string input")
        if (value.isEmpty()) throw failure("GS1 element string input must not be empty")
        if ('(' in value || ')' in value) throw failure("GS1 element string input must be raw data without human-readable parentheses; use fromHumanReadable() and toElementString() first")
        val result = ArrayList<Element>(); var position = 0
        while (position < value.length) {
            if (result.size == MAX_ELEMENTS) throw failure("GS1 elements exceed element limit")
            if (value[position] == '\u001d') throw failure("GS1 element string has an unexpected FNC1 separator at offset $position")
            val info = readAi(value, position) ?: throw failure("Unsupported GS1 AI at offset $position")
            val start = position + info.ai!!.length
            val end = (if (info.length!!.isVariable()) value.indexOf('\u001d', start) else minOf(value.length, start + info.length!!.exact!!)).let { if (it < 0) value.length else it }
            if (info.length!!.isVariable() && end == value.length) {
                for (offset in maxOf(start + 1, end - 22) until end) {
                    val suffix = readAi(value, offset)
                    if (suffix != null && !suffix.length!!.isVariable() && offset + suffix.ai!!.length + suffix.length!!.exact!! == end)
                        throw failure("GS1 variable-length element at offset $start is missing an FNC1 separator before offset $offset")
                }
            }
            result.add(element(Element(info.ai, value.substring(start, end)), result.size)); position = end
            if (position < value.length && value[position] == '\u001d' && info.length!!.isVariable()) {
                position++
                if (position == value.length) throw failure("GS1 element string must not end with an FNC1 separator")
            }
        }
        return ElementStringParseResult(result, FNC1_SEPARATOR in value)
    }
    private fun simpleIssue(code: String, message: String, reason: String, expected: Any?) =
        ValidationIssue(code, message, reason, null, null, null, null, null, expected, null)
    private fun optionsIssue(options: ValidationOptions): ValidationIssue? {
        if (options.context != null && options.context !in setOf("element-string", "digital-link"))
            return simpleIssue("GS1_INVALID_INPUT", "GS1 validation context must be \"element-string\" or \"digital-link\"", "invalid-options", "element-string or digital-link")
        if (options.allowUnsupportedAi) return simpleIssue("GS1_INVALID_INPUT", "GS1 validation allowUnsupportedAi must be false", "invalid-options", false)
        return null
    }
    private fun invalid(issue: ValidationIssue) = ValidationResult(false, null, null, listOf(issue), emptyList())
    @JvmStatic fun validateElements(elements: Iterable<*>?) = validateElements(elements, ValidationOptions.defaults())
    @JvmStatic fun validateElements(elements: Iterable<*>?, options: ValidationOptions?): ValidationResult {
        val opts = options ?: ValidationOptions.defaults()
        optionsIssue(opts)?.let { return invalid(it) }
        val values = try { bounded(elements, "GS1 elements").also { if (it.isEmpty()) throw failure("GS1 elements must not be empty") } }
            catch (error: SpecQrException) { return invalid(issue(error, null, null, null, false)) }
        val normalized = ArrayList<Element>(); val errors = ArrayList<ValidationIssue>()
        for ((i, raw) in values.withIndex()) {
            try { normalized.add(element(raw, i)) }
            catch (error: SpecQrException) { errors.add(issue(error, raw, i, null, false)); if (!opts.collectAllErrors) break }
        }
        if (errors.isNotEmpty()) return ValidationResult(false, null, null, errors, emptyList())
        if (opts.context == "digital-link" && normalized.none { it.ai in primaryAis })
            return invalid(simpleIssue("GS1_INVALID_DIGITAL_LINK_PLACEMENT", "GS1 Digital Link elements must include a primary AI 00, 01, or 414", "invalid-digital-link-placement", "primary AI 00, 01, or 414"))
        return ValidationResult(true, normalized, null, emptyList(), emptyList())
    }
    @JvmStatic fun validateElementString(input: String?) = validateElementString(input, ValidationOptions.defaults())
    @JvmStatic fun validateElementString(input: String?, options: ValidationOptions?): ValidationResult {
        optionsIssue(options ?: ValidationOptions.defaults())?.let { return invalid(it) }
        return try {
            val parsed = parseElementString(input)
            ValidationResult(true, parsed.elements, parsed.hasSeparators, emptyList(), emptyList())
        } catch (error: SpecQrException) { invalid(issue(error, null, null, input, false)) }
    }
    private fun match(pattern: String, value: String) = Regex(pattern).find(value)?.groupValues?.get(1)
    private fun issue(error: SpecQrException, raw: Any?, index: Int?, input: String?, digital: Boolean): ValidationIssue {
        val message = error.message ?: ""; var code = "GS1_INVALID_INPUT"; var reason = "invalid-input"; var expected: Any? = null
        when {
            digital && ("absolute http or https URL" in message || "must use http or https" in message) -> { code = "GS1_DIGITAL_LINK_INVALID_URI"; reason = "invalid-uri"; expected = "absolute http or https URL" }
            digital && "must not include a fragment" in message -> { code = "GS1_DIGITAL_LINK_FRAGMENT_NOT_ALLOWED"; reason = "fragment-not-allowed"; expected = "URI without fragment" }
            digital && "valid percent-encoding" in message -> { code = "GS1_INVALID_PERCENT_ENCODING"; reason = "invalid-percent-encoding"; expected = "percent escapes must use two hexadecimal digits" }
            digital && "query parameter" in message && "is not a GS1 AI" in message -> { code = "GS1_DIGITAL_LINK_UNKNOWN_QUERY"; reason = "unknown-query"; expected = "GS1 AI query parameter or unknownQuery: \"preserve\"" }
            digital && ("primaryAi must be one" in message || "unknownQuery must be" in message) -> { reason = "invalid-options"; expected = if ("primaryAi" in message) "00, 01, or 414" else "preserve or reject" }
            digital && ("path must" in message || "path segment" in message) -> { reason = "malformed-path"; expected = "Digital Link path containing primary AI and AI/value pairs" }
            "Unsupported GS1 AI" in message -> { code = "GS1_UNSUPPORTED_AI"; reason = "unsupported-ai"; expected = "supported GS1 AI" }
            Regex("exactly \\d+ characters|at most \\d+ characters").containsMatchIn(message) -> { code = "GS1_INVALID_LENGTH"; reason = "invalid-length"; expected = match("(exactly \\d+ characters|at most \\d+ characters)", message) }
            "digits only" in message || "printable ASCII" in message -> { code = "GS1_INVALID_CHARSET"; reason = "invalid-charset"; expected = if ("digits only" in message) "digits only" else "printable ASCII" }
            "missing an FNC1 separator" in message -> { code = "GS1_MISSING_SEPARATOR"; reason = "missing-separator"; expected = "FNC1 separator before the next GS1 element" }
            "unexpected FNC1 separator" in message || "must not end with an FNC1 separator" in message || "must not contain the FNC1 separator" in message -> { code = "GS1_UNEXPECTED_SEPARATOR"; reason = "unexpected-separator"; expected = "separator only after a non-final variable-length GS1 element" }
            "invalid GTIN check digit" in message || "invalid SSCC check digit" in message -> { code = "GS1_INVALID_CHECK_DIGIT"; reason = "invalid-check-digit"; expected = if ("SSCC" in message) "valid SSCC check digit" else "valid GTIN check digit" }
            "cannot be placed in the Digital Link path" in message -> { code = "GS1_INVALID_DIGITAL_LINK_PLACEMENT"; reason = "invalid-digital-link-placement" }
            "duplicate AI" in message -> { code = "GS1_DUPLICATE_AI"; reason = "duplicate-ai"; expected = "unique GS1 AI within the Digital Link URI" }
        }
        var ai = match("(?:GS1 AI |duplicate AI )([0-9]{2,4})", message)
        val offset = match("offset ([0-9]+)", message)?.toInt()
        if (ai == null && input != null && offset != null && offset <= input.length) {
            ai = match("^([0-9]{2,4})", input.substring(offset))
            if (ai == null) for (size in 2..4) if (offset >= size && ais.containsKey(input.substring(offset - size, offset))) { ai = input.substring(offset - size, offset); break }
        }
        val elementIndex = match("GS1 element ([0-9]+)", message)?.toInt() ?: index
        val rawValue = when (raw) { is Element -> raw.value; is Map<*, *> -> raw["value"]; else -> null }
        val key = if (code == "GS1_DIGITAL_LINK_UNKNOWN_QUERY") match("(?s)query parameter \"(.*)\" is not a GS1 AI", message) else null
        return ValidationIssue(code, message, reason, ai, rawValue as? String, key, offset, elementIndex, expected, null)
    }
    private data class Url(val scheme: String, val authority: String, val path: String, val query: String?, val fragment: String?) {
        fun serialize() = text("$scheme://$authority$path" + (query?.let { "?$it" } ?: "") + (fragment?.let { "#$it" } ?: ""), "GS1 Digital Link output")
    }
    private fun invalidUri() = failure("GS1 Digital Link URI must be an absolute http or https URL")
    private fun usv(input: String): String = buildString(input.length) {
        var i = 0
        while (i < input.length) {
            val c = input[i++]
            if (c.isHighSurrogate() && i < input.length && input[i].isLowSurrogate()) append(c).append(input[i++])
            else append(if (c.isSurrogate()) '\ufffd' else c)
        }
    }
    private fun escape(out: StringBuilder, value: Int) {
        val hex = "0123456789ABCDEF"
        out.append('%').append(hex[value ushr 4]).append(hex[value and 15])
    }
    private fun encode(value: String, mode: Int): String = buildString {
        for (b in usv(value).toByteArray(StandardCharsets.UTF_8)) {
            val c = b.toInt() and 255; val ch = c.toChar()
            val letterDigit = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9'
            val keep = when (mode) {
                0 -> letterDigit || ch in "~!*'()-._"
                1 -> letterDigit || ch in "*-._"
                else -> c > 32 && c < 127 && ch !in (if (mode == 2) "\"#<>?`{}^" else "\"#'<>")
            }
            if (keep) append(ch) else if (mode == 1 && c == 32) append('+') else escape(this, c)
        }
    }
    private fun asciiHex(c: Char) = when (c) { in '0'..'9' -> c - '0'; in 'a'..'f' -> c - 'a' + 10; in 'A'..'F' -> c - 'A' + 10; else -> -1 }
    private fun percentBytes(value: String, form: Boolean): ByteArray {
        val out = ByteArrayOutputStream(value.length); var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '%' && i + 2 < value.length && asciiHex(value[i + 1]) >= 0 && asciiHex(value[i + 2]) >= 0 -> { out.write(asciiHex(value[i + 1]) * 16 + asciiHex(value[i + 2])); i += 3 }
                form && c == '+' -> { out.write(32); i++ }
                else -> { val cp = value.codePointAt(i); out.write(usv(String(Character.toChars(cp))).toByteArray(StandardCharsets.UTF_8)); i += Character.charCount(cp) }
            }
        }
        return out.toByteArray()
    }
    private fun strictDecode(value: String, label: String): String {
        if (invalidPercent.containsMatchIn(value)) throw failure("GS1 Digital Link path $label must be valid percent-encoding")
        return try { StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(percentBytes(value, false))).toString() }
        catch (_: CharacterCodingException) { throw failure("GS1 Digital Link path $label must be valid percent-encoding") }
    }
    // WHATWG decoding consumes invalid UTF-8 scalar prefixes byte by byte.
    private fun formDecode(value: String): String {
        val bytes = percentBytes(value, true); val out = StringBuilder(); var i = 0
        while (i < bytes.size) {
            val lead = bytes[i].toInt() and 255
            if (lead < 128) { out.append(lead.toChar()); i++; continue }
            val need = when (lead) { in 0xC2..0xDF -> 1; in 0xE0..0xEF -> 2; in 0xF0..0xF4 -> 3; else -> 0 }
            if (need == 0) { out.append('\ufffd'); i++; continue }
            var point = lead and (0x7F shr need); var used = 1; var bad = false
            while (used <= need) {
                if (i + used == bytes.size) { bad = true; break }
                val c = bytes[i + used].toInt() and 255
                val low = if (used == 1 && lead == 0xE0) 0xA0 else if (used == 1 && lead == 0xF0) 0x90 else 0x80
                val high = if (used == 1 && lead == 0xED) 0x9F else if (used == 1 && lead == 0xF4) 0x8F else 0xBF
                if (c !in low..high) { bad = true; break }
                point = (point shl 6) or (c and 63); used++
            }
            if (bad) out.append('\ufffd') else out.appendCodePoint(point)
            i += used
        }
        return out.toString()
    }
    private fun credentials(input: String): String = buildString {
        var colon = false
        for (b in usv(input).toByteArray(StandardCharsets.UTF_8)) {
            val c = b.toInt() and 255; val ch = c.toChar()
            if (ch == ':' && !colon) { append(':'); colon = true }
            else if (c <= 32 || c > 126 || ch in "\"#<>?`{}/:;=@[\\]^|") escape(this, c)
            else append(ch)
        }
        if (isNotEmpty() && last() == ':') setLength(length - 1)
    }
    private fun host(raw: String): String {
        if (raw.isEmpty()) throw invalidUri()
        if (raw.startsWith('[')) { if (!raw.endsWith(']')) throw invalidUri(); return ipv6(raw.substring(1, raw.length - 1)) }
        var value = try { strictDecode(raw, "host") } catch (_: SpecQrException) { throw invalidUri() }
        value = value.replace('\u3002', '.').replace('\uff0e', '.').replace('\uff61', '.')
        val labels = value.split('.').map { label ->
            if (label.any { it.code > 127 }) try { IDN.toASCII(label) } catch (_: IllegalArgumentException) { throw invalidUri() }
            else {
                if (label.startsWith("xn--", true) && IDN.toUnicode(label, IDN.ALLOW_UNASSIGNED).equals(label, true)) throw invalidUri()
                label
            }
        }
        value = labels.joinToString(".").lowercase(Locale.ROOT)
        if (value.isEmpty() || value.any { it.code <= 32 || it.code == 127 || it in "#/:<>?@[\\]^|%" }) throw invalidUri()
        val pieces = value.removeSuffix(".").split('.')
        if (!(digits(pieces.last()) || Regex("0x[0-9a-f]*").matches(pieces.last()))) return value
        if (pieces.size > 4) throw invalidUri()
        var address = 0L
        for ((i, piece) in pieces.withIndex()) {
            if (piece.isEmpty()) throw invalidUri()
            val radix = if (piece.startsWith("0x")) 16 else if (piece.length > 1 && piece[0] == '0') 8 else 10
            val body = piece.substring(if (radix == 16) 2 else if (radix == 8) 1 else 0)
            var number = 0L
            for (c in body) { val digit = Character.digit(c, radix); if (digit < 0) throw invalidUri(); number = number * radix + digit; if (number > 0xFFFFFFFFL) throw invalidUri() }
            if (i + 1 < pieces.size) { if (number > 255) throw invalidUri(); address += number shl (8 * (3 - i)) }
            else { if (number >= (1L shl (8 * (5 - pieces.size)))) throw invalidUri(); address += number }
        }
        return listOf(24, 16, 8, 0).joinToString(".") { ((address ushr it) and 255).toString() }
    }
    private fun ipv6(input: String): String {
        var value = input
        if (value.isEmpty() || '%' in value || !Regex("[0-9a-fA-F:.]+").matches(value)) throw invalidUri()
        if ('.' in value) {
            val colon = value.lastIndexOf(':'); if (colon < 0) throw invalidUri()
            val v4 = value.substring(colon + 1).split('.'); if (v4.size != 4) throw invalidUri()
            val bytes = v4.map { if (!digits(it) || it.length > 3 || it.length > 1 && it[0] == '0') throw invalidUri(); it.toInt().also { n -> if (n > 255) throw invalidUri() } }
            value = value.substring(0, colon + 1) + (bytes[0] * 256 + bytes[1]).toString(16) + ":" + (bytes[2] * 256 + bytes[3]).toString(16)
        }
        val compression = value.indexOf("::")
        if (compression >= 0 && value.indexOf("::", compression + 2) >= 0) throw invalidUri()
        val left = if (compression < 0) value else value.substring(0, compression)
        val right = if (compression < 0) "" else value.substring(compression + 2)
        val first = if (left.isEmpty()) emptyList() else left.split(':')
        val last = if (right.isEmpty()) emptyList() else right.split(':')
        if (if (compression < 0) first.size != 8 else first.size + last.size >= 8) throw invalidUri()
        val groups = IntArray(8)
        for (i in 0 until first.size + last.size) {
            val group = if (i < first.size) first[i] else last[i - first.size]
            if (group.isEmpty() || group.length > 4) throw invalidUri()
            groups[if (i < first.size) i else 8 - last.size + i - first.size] = group.toInt(16)
        }
        var bestStart = -1; var bestLength = 1; var i = 0
        while (i < 8) {
            if (groups[i] != 0) { i++; continue }
            var end = i; while (end < 8 && groups[end] == 0) end++
            if (end - i > bestLength) { bestStart = i; bestLength = end - i }; i = end
        }
        val out = StringBuilder("["); i = 0
        while (i < 8) {
            if (i == bestStart) { out.append("::"); i += bestLength }
            else { if (i > 0 && i != bestStart + bestLength) out.append(':'); out.append(groups[i++].toString(16)) }
        }
        return out.append(']').toString()
    }
    private fun normalizePath(path: String, base: Boolean): String {
        if (path.count { it == '/' } > MAX_ELEMENTS) throw failure("GS1 Digital Link path component count exceeds limit")
        val parts = path.split('/'); val out = ArrayList<String>(); var primarySeen = false
        for ((i, part) in parts.withIndex()) {
            if (!base && part in primaryAis) primarySeen = true
            val dot = part.replace(Regex("(?i)%2e"), ".")
            if (!primarySeen && (dot == "." || dot == "..")) { if (dot == ".." && out.size > 1) out.removeAt(out.lastIndex); if (i + 1 == parts.size) out.add("") }
            else out.add(part)
        }
        val normalized = out.joinToString("/")
        return encode(if (normalized.startsWith('/')) normalized else "/$normalized", 2)
    }
    private fun url(input: String?, base: Boolean): Url {
        val checked = text(input, "GS1 Digital Link URI")
        val clean = usv(checked.trim { it.code <= 32 }).replace("\t", "").replace("\r", "").replace("\n", "")
        val match = schemePattern.find(clean) ?: throw invalidUri()
        val scheme = match.groupValues[1].lowercase(Locale.ROOT); var rest = clean.substring(match.range.last + 1)
        var fragment: String? = null; var query: String? = null
        val hash = rest.indexOf('#'); if (hash >= 0) { fragment = rest.substring(hash + 1); rest = rest.substring(0, hash) }
        val question = rest.indexOf('?'); if (question >= 0) { query = encode(rest.substring(question + 1), 3); rest = rest.substring(0, question) }
        if (scheme !in setOf("http", "https", "ftp", "ws", "wss")) return Url(scheme, "", rest, query, fragment)
        rest = rest.replace('\\', '/').trimStart('/')
        val slash = rest.indexOf('/'); var authority = if (slash < 0) rest else rest.substring(0, slash)
        val path = if (slash < 0) "/" else rest.substring(slash)
        if (authority.isEmpty()) throw invalidUri()
        var user = ""; val at = authority.lastIndexOf('@')
        if (at >= 0) { user = credentials(authority.substring(0, at)); if (user.isNotEmpty()) user += "@"; authority = authority.substring(at + 1) }
        val host: String; var port = ""
        if (authority.startsWith('[')) {
            val bracket = authority.indexOf(']'); if (bracket < 0) throw invalidUri()
            host = authority.substring(0, bracket + 1); val suffix = authority.substring(bracket + 1)
            if (suffix.isNotEmpty()) { if (!suffix.startsWith(':')) throw invalidUri(); port = suffix.substring(1) }
        } else { val colon = authority.indexOf(':'); host = if (colon < 0) authority else authority.substring(0, colon); if (colon >= 0) port = authority.substring(colon + 1) }
        if (port.isNotEmpty()) {
            if (!digits(port)) throw invalidUri()
            var number = 0
            for (c in port) { number = number * 10 + (c - '0'); if (number > 65535) throw invalidUri() }
            val standard = (scheme == "http" || scheme == "ws") && number == 80 || (scheme == "https" || scheme == "wss") && number == 443 || scheme == "ftp" && number == 21
            port = if (standard) "" else ":$number"
        }
        return Url(scheme, user + host(host) + port, normalizePath(path, base), query, fragment)
    }
    private fun checkUri(url: Url, base: Boolean) {
        if (url.scheme != "http" && url.scheme != "https") throw failure("GS1 Digital Link URI must use http or https")
        if (base) {
            if (!url.query.isNullOrEmpty() || !url.fragment.isNullOrEmpty()) throw failure("GS1 Digital Link baseUrl must not include query or fragment components")
        } else if (!url.fragment.isNullOrEmpty()) throw failure("GS1 Digital Link URI must not include a fragment")
    }
    private fun primary(ai: String): String { if (ai !in primaryAis) throw failure("GS1 Digital Link primaryAi must be one of 00, 01, or 414"); return ai }
    private fun policy(value: String?): String {
        if (value == null) return "preserve"
        if (value != "preserve" && value != "reject") throw failure("GS1 Digital Link unknownQuery must be \"preserve\" or \"reject\"")
        return value
    }
    private fun eligible(ai: String, primary: String) = primary == "01" && ai in setOf("10", "21", "22")
    private fun placement(ai: String, primary: String) {
        if (ai !in ais) throw failure("Unsupported GS1 AI $ai. Add explicit support before using it.")
        if (!eligible(ai, primary)) throw failure("GS1 AI $ai cannot be placed in the Digital Link path after primary AI $primary")
    }
    private fun unique(ai: String, seen: MutableSet<String>) { if (!seen.add(ai)) throw failure("GS1 Digital Link input must not contain duplicate AI $ai") }
    @JvmStatic fun createDigitalLink(elements: Iterable<*>?, baseUrl: String?) = createDigitalLink(elements, DigitalLinkOptions.forBaseUrl(baseUrl))
    @JvmStatic fun createDigitalLink(result: ElementStringParseResult?, baseUrl: String?): String {
        if (result == null) throw failure("GS1 elements must not be null")
        return createDigitalLink(result.elements, baseUrl)
    }
    @JvmStatic fun createDigitalLink(elements: Iterable<*>?, options: DigitalLinkOptions?): String {
        val opts = options ?: DigitalLinkOptions.defaults(); val values = bounded(elements, "GS1 elements")
        if (opts.baseUrl.isNullOrEmpty()) throw failure("GS1 Digital Link baseUrl is required")
        val url = url(opts.baseUrl, true); checkUri(url, true)
        val primaryAi = primary(opts.primaryAi ?: "01")
        val paths = opts.pathAis?.let {
            val set = HashSet<String>()
            for (raw in bounded(it, "GS1 Digital Link pathAis")) {
                if (raw !is String || !isAi(raw)) throw failure("GS1 Digital Link pathAis entries must be 2 to 4 digit AI strings")
                if (raw != primaryAi) { placement(raw, primaryAi); set.add(raw) }
            }
            set
        }
        if (values.isEmpty()) throw failure("GS1 Digital Link input elements must not be empty")
        val seen = HashSet<String>(); var primary: Element? = null
        val normalized = values.mapIndexed { i, raw -> element(raw, i).also { unique(it.ai!!, seen); if (it.ai == primaryAi) primary = it } }
        val selected = primary ?: throw failure("GS1 Digital Link input must include primary AI $primaryAi")
        val path = arrayListOf(selected); val query = ArrayList<Element>()
        for (e in normalized) {
            if (e === selected) continue
            val inPath = paths?.contains(e.ai) ?: eligible(e.ai!!, primaryAi)
            (if (inPath && e.value != "." && e.value != "..") path else query).add(e)
        }
        val pathname = StringBuilder(url.path.trimEnd('/'))
        for (e in path) pathname.append('/').append(encode(e.ai!!, 0)).append('/').append(encode(e.value!!, 0))
        query.sortWith(compareBy<Element> { it.ai }.thenBy { it.value })
        val search = query.joinToString("&") { encode(it.ai!!, 1) + "=" + encode(it.value!!, 1) }
        return Url(url.scheme, url.authority, pathname.toString(), search.ifEmpty { null }, null).serialize()
    }
    private fun pathParts(path: String): List<String> {
        val value = path.trim('/')
        if (value.isEmpty()) throw failure("GS1 Digital Link path must include primary AI 00, 01, or 414")
        val parts = value.split('/')
        if ("" in parts) throw failure("GS1 Digital Link path must not contain empty segments")
        return parts
    }
    private fun firstAi(parts: List<String>, selected: String?): Int {
        for ((i, part) in parts.withIndex()) if (if (selected == null) part in primaryAis else selected == part) return i
        throw failure("GS1 Digital Link path must include primary AI 00, 01, or 414")
    }
    private fun parseLink(url: Url, options: DigitalLinkOptions): DigitalLinkParseResult {
        checkUri(url, false); options.primaryAi?.let(::primary)
        val unknownPolicy = policy(options.unknownQuery); val parts = pathParts(url.path); val start = firstAi(parts, options.primaryAi)
        if ((parts.size - start) % 2 != 0) throw failure("GS1 Digital Link path must contain AI/value pairs")
        val path = ArrayList<Element>(); val query = ArrayList<Element>(); val unknown = ArrayList<UnknownQuery>(); val seen = HashSet<String>()
        for (i in start until parts.size step 2) {
            val ai = parts[i]
            if (!isAi(ai)) throw failure("GS1 Digital Link path segment ${i + 1} must be a GS1 AI")
            val e = element(Element(ai, strictDecode(parts[i + 1], "value for AI $ai")), path.size)
            if (path.isNotEmpty()) placement(ai, path[0].ai!!)
            unique(ai, seen); path.add(e)
        }
        if (!url.query.isNullOrEmpty()) {
            val raw = url.query
            if (raw.count { it == '&' } + 1 > MAX_ELEMENTS) throw failure("GS1 Digital Link query component count exceeds limit")
            for (pair in raw.split('&')) {
                if (pair.isEmpty()) continue
                val equal = pair.indexOf('='); val key = formDecode(if (equal < 0) pair else pair.substring(0, equal)); val value = formDecode(if (equal < 0) "" else pair.substring(equal + 1))
                if (isAi(key)) { val e = element(Element(key, value), path.size + query.size); unique(key, seen); query.add(e) }
                else if (unknownPolicy == "preserve") unknown.add(UnknownQuery(key, value))
                else throw failure("GS1 Digital Link query parameter \"$key\" is not a GS1 AI")
            }
        }
        return DigitalLinkParseResult(path + query, path[0], path, query, unknown)
    }
    @JvmStatic fun parseDigitalLink(uri: String?) = parseDigitalLink(uri, DigitalLinkOptions.defaults())
    @JvmStatic fun parseDigitalLink(uri: String?, options: DigitalLinkOptions?) = parseLink(url(uri, false), options ?: DigitalLinkOptions.defaults())
    @JvmStatic fun validateDigitalLink(uri: String?) = validateDigitalLink(uri, DigitalLinkOptions.defaults())
    @JvmStatic fun validateDigitalLink(uri: String?, options: DigitalLinkOptions?): DigitalLinkValidationResult {
        val opts = options ?: DigitalLinkOptions.defaults()
        if (opts.normalize) return DigitalLinkValidationResult(false, null, listOf(simpleIssue("GS1_INVALID_INPUT", "GS1 Digital Link validation normalize is not implemented yet", "unsupported-option", false)), emptyList())
        return try {
            val url = url(uri, false)
            if (invalidPercent.containsMatchIn(url.path) || url.query?.let(invalidPercent::containsMatchIn) == true) throw failure("GS1 Digital Link URI must use valid percent-encoding")
            val result = parseLink(url, opts); val warnings = ArrayList<ValidationIssue>()
            if (url.scheme == "http") warnings.add(simpleIssue("GS1_DIGITAL_LINK_HTTP", "GS1 Digital Link URI uses http. Use https when transport security is required.", "http-uri", null))
            if (result.unknownQuery.isNotEmpty()) warnings.add(ValidationIssue("GS1_DIGITAL_LINK_UNKNOWN_QUERY_PRESERVED", "GS1 Digital Link URI contains non-GS1 query parameters preserved in unknownQuery.", "unknown-query-preserved", null, null, null, null, null, null, result.unknownQuery.size))
            DigitalLinkValidationResult(true, result, emptyList(), warnings)
        } catch (error: SpecQrException) { DigitalLinkValidationResult(false, null, listOf(issue(error, null, null, null, true)), emptyList()) }
    }
    @JvmStatic fun normalizeDigitalLink(uri: String?) = normalizeDigitalLink(uri, DigitalLinkOptions.defaults())
    @JvmStatic fun normalizeDigitalLink(uri: String?, options: DigitalLinkOptions?): String {
        val opts = options ?: DigitalLinkOptions.defaults()
        if (opts.mode != null && opts.mode != "specqr-deterministic") throw failure("GS1 Digital Link normalization mode must be \"specqr-deterministic\"")
        val url = url(uri, false); checkUri(url, false)
        if (invalidPercent.containsMatchIn(url.path) || url.query?.let(invalidPercent::containsMatchIn) == true) throw failure("GS1 Digital Link URI must use valid percent-encoding")
        val parsed = parseLink(url, opts); val parts = pathParts(url.path); val start = firstAi(parts, opts.primaryAi)
        val stem = Url(url.scheme, url.authority, "/" + parts.subList(0, start).joinToString("/"), null, null).serialize()
        val normalized = createDigitalLink(parsed.elements, DigitalLinkOptions.forBaseUrl(stem).withPrimaryAi(parsed.primary!!.ai))
        val out = StringBuilder(normalized); var hasQuery = '?' in normalized
        for (q in parsed.unknownQuery) { out.append(if (hasQuery) '&' else '?'); hasQuery = true; out.append(encode(q.key!!, 1)).append('=').append(encode(q.value!!, 1)) }
        return text(out.toString(), "GS1 Digital Link output")
    }
}
