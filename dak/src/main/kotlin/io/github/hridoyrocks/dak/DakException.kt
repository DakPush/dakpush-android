package io.github.hridoyrocks.dak

/**
 * A change the server refused, such as a wrong auth hash or a bad tag, for
 * the app to fix. Network trouble never ends up here: changes are kept and
 * sent later.
 */
public class DakException internal constructor(
    message: String,
    /**
     * The server's error code, like `identity_verification_failed` or
     * `invalid`.
     */
    public val code: String,
    /** The field the server refused, like `tags` or `external_id`, if it said. */
    public val field: String? = null,
    /** The HTTP status of the refusal, or null when the SDK refused it itself. */
    public val status: Int? = null,
) : Exception(message) {
    override fun toString(): String = "DakException($code${field?.let { " $it" } ?: ""}: $message)"
}
