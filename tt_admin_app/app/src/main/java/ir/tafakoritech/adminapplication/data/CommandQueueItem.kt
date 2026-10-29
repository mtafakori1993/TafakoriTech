package ir.tafakoritech.adminapplication.data

/**
 * One queued command for a given server+terminal. Items are sent to the terminal
 * front-to-back. A [text] of "/clear" is inserted automatically when the user
 * marks a command as unrelated to the previous one.
 */
data class CommandQueueItem(
    val id: Long,
    val serverId: Long,
    val terminalId: String,
    val text: String,
    val position: Long,
)
