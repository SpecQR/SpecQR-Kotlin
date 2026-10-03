package io.specqr

/** A validation or capacity failure with a stable machine-readable [code]. */
class SpecQrException(val code: String, message: String) : IllegalArgumentException(message) {
    fun code(): String = code
    private companion object { private const val serialVersionUID = 1L }
}
