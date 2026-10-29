package ir.tafakoritech.adminapplication.data

/** A saved Commander server: a name, its base URL, and the API token. */
data class UrlEntry(
    val id: Long,
    val title: String,
    val url: String,
    val token: String,
    val projectId: Long? = null,
    val createdAt: Long,
) {
    /** Base URL with any trailing slash removed, ready for path concatenation. */
    val baseUrl: String get() = url.trim().trimEnd('/')
}
