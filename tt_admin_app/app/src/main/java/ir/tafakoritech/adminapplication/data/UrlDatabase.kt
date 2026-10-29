package ir.tafakoritech.adminapplication.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ChatMessageRow(
    val id: Long,
    val command: String,
    val response: String,
    val isError: Boolean,
)

/** Lightweight project record cached locally from the Project Manager backend. */
data class CachedProject(val id: Long, val name: String, val color: String)

/**
 * Lightweight offline storage for [UrlEntry] rows, backed by the on-device
 * SQLite database. Exposed as a process-wide singleton via [get].
 */
class UrlDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TITLE TEXT NOT NULL,
                $COL_URL TEXT NOT NULL,
                $COL_TOKEN TEXT NOT NULL DEFAULT '',
                $COL_PROJECT_ID INTEGER,
                $COL_CREATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
        createQueueTable(db)
        createPluginsTable(db)
        createPluginPathsTable(db)
        createChatMessagesTable(db)
        createProjectsCacheTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_TOKEN TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 3) {
            createQueueTable(db)
        }
        if (oldVersion < 4) {
            createPluginsTable(db)
        }
        if (oldVersion < 5) {
            createPluginPathsTable(db)
        }
        if (oldVersion < 6) {
            createChatMessagesTable(db)
        }
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_PROJECT_ID INTEGER")
            createProjectsCacheTable(db)
        }
    }

    private fun createQueueTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $Q_TABLE (
                $Q_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $Q_SERVER_ID INTEGER NOT NULL,
                $Q_TERMINAL_ID TEXT NOT NULL,
                $Q_TEXT TEXT NOT NULL,
                $Q_POSITION INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createPluginsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $P_TABLE (
                $P_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $P_SERVER_ID INTEGER NOT NULL,
                $P_PLUGIN_ID TEXT NOT NULL,
                $P_INSTALLED_AT INTEGER NOT NULL,
                UNIQUE($P_SERVER_ID, $P_PLUGIN_ID)
            )
            """.trimIndent()
        )
    }

    private fun createPluginPathsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $PP_TABLE (
                $PP_SERVER_ID INTEGER PRIMARY KEY,
                $PP_PATH TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    /** Inserts a new entry and returns its generated id. */
    fun insert(title: String, url: String, token: String, projectId: Long? = null): Long {
        val values = ContentValues().apply {
            put(COL_TITLE, title)
            put(COL_URL, url)
            put(COL_TOKEN, token)
            if (projectId != null) put(COL_PROJECT_ID, projectId) else putNull(COL_PROJECT_ID)
            put(COL_CREATED_AT, System.currentTimeMillis())
        }
        return writableDatabase.insert(TABLE, null, values)
    }

    /** Updates a server entry's name, URL, token, and attached project. */
    fun update(id: Long, title: String, url: String, token: String, projectId: Long? = null) {
        val values = ContentValues().apply {
            put(COL_TITLE, title)
            put(COL_URL, url)
            put(COL_TOKEN, token)
            if (projectId != null) put(COL_PROJECT_ID, projectId) else putNull(COL_PROJECT_ID)
        }
        writableDatabase.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    /** Attaches or detaches a project from a server entry. */
    fun setServerProject(serverId: Long, projectId: Long?) {
        val values = ContentValues().apply {
            if (projectId != null) put(COL_PROJECT_ID, projectId) else putNull(COL_PROJECT_ID)
        }
        writableDatabase.update(TABLE, values, "$COL_ID = ?", arrayOf(serverId.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete(TABLE, "$COL_ID = ?", arrayOf(id.toString()))
    }

    /** Returns all entries, newest first. */
    fun getAll(): List<UrlEntry> {
        val entries = mutableListOf<UrlEntry>()
        readableDatabase.query(
            TABLE,
            arrayOf(COL_ID, COL_TITLE, COL_URL, COL_TOKEN, COL_PROJECT_ID, COL_CREATED_AT),
            null, null, null, null,
            "$COL_CREATED_AT DESC"
        ).use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(COL_ID)
            val titleIdx = cursor.getColumnIndexOrThrow(COL_TITLE)
            val urlIdx = cursor.getColumnIndexOrThrow(COL_URL)
            val tokenIdx = cursor.getColumnIndexOrThrow(COL_TOKEN)
            val projIdx = cursor.getColumnIndexOrThrow(COL_PROJECT_ID)
            val createdIdx = cursor.getColumnIndexOrThrow(COL_CREATED_AT)
            while (cursor.moveToNext()) {
                entries += UrlEntry(
                    id = cursor.getLong(idIdx),
                    title = cursor.getString(titleIdx),
                    url = cursor.getString(urlIdx),
                    token = cursor.getString(tokenIdx),
                    projectId = if (cursor.isNull(projIdx)) null else cursor.getLong(projIdx),
                    createdAt = cursor.getLong(createdIdx),
                )
            }
        }
        return entries
    }

    /** Returns entries for a specific project, newest first. */
    fun getByProject(projectId: Long): List<UrlEntry> {
        val entries = mutableListOf<UrlEntry>()
        readableDatabase.query(
            TABLE,
            arrayOf(COL_ID, COL_TITLE, COL_URL, COL_TOKEN, COL_PROJECT_ID, COL_CREATED_AT),
            "$COL_PROJECT_ID = ?",
            arrayOf(projectId.toString()),
            null, null,
            "$COL_CREATED_AT DESC"
        ).use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(COL_ID)
            val titleIdx = cursor.getColumnIndexOrThrow(COL_TITLE)
            val urlIdx = cursor.getColumnIndexOrThrow(COL_URL)
            val tokenIdx = cursor.getColumnIndexOrThrow(COL_TOKEN)
            val projIdx = cursor.getColumnIndexOrThrow(COL_PROJECT_ID)
            val createdIdx = cursor.getColumnIndexOrThrow(COL_CREATED_AT)
            while (cursor.moveToNext()) {
                entries += UrlEntry(
                    id = cursor.getLong(idIdx),
                    title = cursor.getString(titleIdx),
                    url = cursor.getString(urlIdx),
                    token = cursor.getString(tokenIdx),
                    projectId = if (cursor.isNull(projIdx)) null else cursor.getLong(projIdx),
                    createdAt = cursor.getLong(createdIdx),
                )
            }
        }
        return entries
    }

    // ---- command queue ----------------------------------------------------

    /** Appends a command to the end of a terminal's queue; returns its row id. */
    fun enqueueCommand(serverId: Long, terminalId: String, text: String): Long {
        val nextPos = (queueMaxPosition(serverId, terminalId) ?: 0L) + 1
        val values = ContentValues().apply {
            put(Q_SERVER_ID, serverId)
            put(Q_TERMINAL_ID, terminalId)
            put(Q_TEXT, text)
            put(Q_POSITION, nextPos)
        }
        return writableDatabase.insert(Q_TABLE, null, values)
    }

    fun updateCommand(id: Long, text: String) {
        val values = ContentValues().apply { put(Q_TEXT, text) }
        writableDatabase.update(Q_TABLE, values, "$Q_ID = ?", arrayOf(id.toString()))
    }

    fun deleteCommand(id: Long) {
        writableDatabase.delete(Q_TABLE, "$Q_ID = ?", arrayOf(id.toString()))
    }

    fun clearQueue(serverId: Long, terminalId: String) {
        writableDatabase.delete(
            Q_TABLE,
            "$Q_SERVER_ID = ? AND $Q_TERMINAL_ID = ?",
            arrayOf(serverId.toString(), terminalId)
        )
    }

    /** Removes every queued command for a server (used when the server is deleted). */
    fun clearServerQueue(serverId: Long) {
        writableDatabase.delete(Q_TABLE, "$Q_SERVER_ID = ?", arrayOf(serverId.toString()))
    }

    /** All queued commands for a terminal, front (lowest position) first. */
    fun queuedCommands(serverId: Long, terminalId: String): List<CommandQueueItem> {
        val items = mutableListOf<CommandQueueItem>()
        readableDatabase.query(
            Q_TABLE,
            arrayOf(Q_ID, Q_SERVER_ID, Q_TERMINAL_ID, Q_TEXT, Q_POSITION),
            "$Q_SERVER_ID = ? AND $Q_TERMINAL_ID = ?",
            arrayOf(serverId.toString(), terminalId),
            null, null,
            "$Q_POSITION ASC"
        ).use { c ->
            val idIdx = c.getColumnIndexOrThrow(Q_ID)
            val srvIdx = c.getColumnIndexOrThrow(Q_SERVER_ID)
            val termIdx = c.getColumnIndexOrThrow(Q_TERMINAL_ID)
            val textIdx = c.getColumnIndexOrThrow(Q_TEXT)
            val posIdx = c.getColumnIndexOrThrow(Q_POSITION)
            while (c.moveToNext()) {
                items += CommandQueueItem(
                    id = c.getLong(idIdx),
                    serverId = c.getLong(srvIdx),
                    terminalId = c.getString(termIdx),
                    text = c.getString(textIdx),
                    position = c.getLong(posIdx),
                )
            }
        }
        return items
    }

    /** Removes and returns the front command, or null if the queue is empty. */
    fun popCommand(serverId: Long, terminalId: String): CommandQueueItem? {
        val front = queuedCommands(serverId, terminalId).firstOrNull() ?: return null
        deleteCommand(front.id)
        return front
    }

    private fun queueMaxPosition(serverId: Long, terminalId: String): Long? {
        readableDatabase.rawQuery(
            "SELECT MAX($Q_POSITION) FROM $Q_TABLE WHERE $Q_SERVER_ID = ? AND $Q_TERMINAL_ID = ?",
            arrayOf(serverId.toString(), terminalId)
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    // ---- plugins ----------------------------------------------------------

    /** Install a plugin for a server. */
    fun installPlugin(serverId: Long, pluginId: String): Boolean {
        val values = ContentValues().apply {
            put(P_SERVER_ID, serverId)
            put(P_PLUGIN_ID, pluginId)
            put(P_INSTALLED_AT, System.currentTimeMillis())
        }
        return runCatching {
            writableDatabase.insert(P_TABLE, null, values)
            true
        }.getOrElse { false }
    }

    /** Uninstall a plugin from a server. */
    fun uninstallPlugin(serverId: Long, pluginId: String) {
        writableDatabase.delete(
            P_TABLE,
            "$P_SERVER_ID = ? AND $P_PLUGIN_ID = ?",
            arrayOf(serverId.toString(), pluginId)
        )
    }

    /** Get all installed plugins for a server. */
    fun getInstalledPlugins(serverId: Long): List<String> {
        val pluginIds = mutableListOf<String>()
        readableDatabase.query(
            P_TABLE,
            arrayOf(P_PLUGIN_ID),
            "$P_SERVER_ID = ?",
            arrayOf(serverId.toString()),
            null, null,
            "$P_INSTALLED_AT DESC"
        ).use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(P_PLUGIN_ID)
            while (cursor.moveToNext()) {
                pluginIds += cursor.getString(idIdx)
            }
        }
        return pluginIds
    }

    // ---- plugin paths -------------------------------------------------------

    /** Return the saved working-directory path for a server, or null if not set. */
    fun getPluginPath(serverId: Long): String? {
        readableDatabase.query(
            PP_TABLE, arrayOf(PP_PATH),
            "$PP_SERVER_ID = ?", arrayOf(serverId.toString()),
            null, null, null
        ).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    /** Persist the working-directory path for a server (upsert). */
    fun setPluginPath(serverId: Long, path: String) {
        val values = ContentValues().apply {
            put(PP_SERVER_ID, serverId)
            put(PP_PATH, path)
        }
        writableDatabase.insertWithOnConflict(PP_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---- chat messages ------------------------------------------------------

    fun getChatMessages(serverId: Long, sessionId: String): List<ChatMessageRow> {
        val rows = mutableListOf<ChatMessageRow>()
        readableDatabase.query(
            CM_TABLE,
            arrayOf(CM_ID, CM_COMMAND, CM_RESPONSE, CM_IS_ERROR),
            "$CM_SERVER_ID = ? AND $CM_SESSION_ID = ?",
            arrayOf(serverId.toString(), sessionId),
            null, null,
            "$CM_ID ASC"
        ).use { c ->
            val idIdx = c.getColumnIndexOrThrow(CM_ID)
            val cmdIdx = c.getColumnIndexOrThrow(CM_COMMAND)
            val respIdx = c.getColumnIndexOrThrow(CM_RESPONSE)
            val errIdx = c.getColumnIndexOrThrow(CM_IS_ERROR)
            while (c.moveToNext()) {
                rows += ChatMessageRow(
                    id = c.getLong(idIdx),
                    command = c.getString(cmdIdx),
                    response = c.getString(respIdx),
                    isError = c.getInt(errIdx) != 0,
                )
            }
        }
        return rows
    }

    fun insertChatMessage(
        serverId: Long,
        sessionId: String,
        command: String,
        response: String,
        isError: Boolean,
    ) {
        val values = ContentValues().apply {
            put(CM_SERVER_ID, serverId)
            put(CM_SESSION_ID, sessionId)
            put(CM_COMMAND, command)
            put(CM_RESPONSE, response)
            put(CM_IS_ERROR, if (isError) 1 else 0)
            put(CM_CREATED_AT, System.currentTimeMillis())
        }
        writableDatabase.insert(CM_TABLE, null, values)
    }

    fun clearChatMessages(serverId: Long, sessionId: String) {
        writableDatabase.delete(
            CM_TABLE,
            "$CM_SERVER_ID = ? AND $CM_SESSION_ID = ?",
            arrayOf(serverId.toString(), sessionId)
        )
    }

    fun clearServerChatMessages(serverId: Long) {
        writableDatabase.delete(CM_TABLE, "$CM_SERVER_ID = ?", arrayOf(serverId.toString()))
    }

    // ---- projects cache (fetched from Project Manager backend) -----------------

    /** Replace the local project cache with a fresh list from the backend. */
    fun cacheProjects(projects: List<CachedProject>) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete(PC_TABLE, null, null)
            projects.forEach { p ->
                writableDatabase.insert(PC_TABLE, null, ContentValues().apply {
                    put(PC_ID, p.id)
                    put(PC_NAME, p.name)
                    put(PC_COLOR, p.color)
                    put(PC_CACHED_AT, System.currentTimeMillis())
                })
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun getCachedProjects(): List<CachedProject> {
        val result = mutableListOf<CachedProject>()
        readableDatabase.query(PC_TABLE, arrayOf(PC_ID, PC_NAME, PC_COLOR),
            null, null, null, null, "$PC_NAME ASC").use { c ->
            while (c.moveToNext()) {
                result += CachedProject(c.getLong(0), c.getString(1), c.getString(2))
            }
        }
        return result
    }

    fun getCachedProject(id: Long): CachedProject? {
        readableDatabase.query(PC_TABLE, arrayOf(PC_ID, PC_NAME, PC_COLOR),
            "$PC_ID = ?", arrayOf(id.toString()), null, null, null).use { c ->
            return if (c.moveToFirst()) CachedProject(c.getLong(0), c.getString(1), c.getString(2)) else null
        }
    }

    private fun createProjectsCacheTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $PC_TABLE (
                $PC_ID INTEGER PRIMARY KEY,
                $PC_NAME TEXT NOT NULL,
                $PC_COLOR TEXT NOT NULL,
                $PC_CACHED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createChatMessagesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $CM_TABLE (
                $CM_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $CM_SERVER_ID INTEGER NOT NULL,
                $CM_SESSION_ID TEXT NOT NULL,
                $CM_COMMAND TEXT NOT NULL,
                $CM_RESPONSE TEXT NOT NULL,
                $CM_IS_ERROR INTEGER NOT NULL DEFAULT 0,
                $CM_CREATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    /** Check if a plugin is installed for a server. */
    fun isPluginInstalled(serverId: Long, pluginId: String): Boolean {
        readableDatabase.query(
            P_TABLE,
            arrayOf(P_ID),
            "$P_SERVER_ID = ? AND $P_PLUGIN_ID = ?",
            arrayOf(serverId.toString(), pluginId),
            null, null,
            null,
            "1"
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    companion object {
        private const val DB_NAME = "commander.db"
        private const val DB_VERSION = 7
        private const val TABLE = "url_entries"
        private const val COL_ID = "id"
        private const val COL_TITLE = "title"
        private const val COL_URL = "url"
        private const val COL_TOKEN = "token"
        private const val COL_PROJECT_ID = "project_id"
        private const val COL_CREATED_AT = "created_at"

        private const val PC_TABLE = "projects_cache"
        private const val PC_ID = "id"
        private const val PC_NAME = "name"
        private const val PC_COLOR = "color"
        private const val PC_CACHED_AT = "cached_at"

        private const val Q_TABLE = "command_queue"
        private const val Q_ID = "id"
        private const val Q_SERVER_ID = "server_id"
        private const val Q_TERMINAL_ID = "terminal_id"
        private const val Q_TEXT = "text"
        private const val Q_POSITION = "position"

        private const val P_TABLE = "installed_plugins"
        private const val P_ID = "id"
        private const val P_SERVER_ID = "server_id"
        private const val P_PLUGIN_ID = "plugin_id"
        private const val P_INSTALLED_AT = "installed_at"

        private const val PP_TABLE = "plugin_paths"
        private const val PP_SERVER_ID = "server_id"
        private const val PP_PATH = "path"

        private const val CM_TABLE = "chat_messages"
        private const val CM_ID = "id"
        private const val CM_SERVER_ID = "server_id"
        private const val CM_SESSION_ID = "session_id"
        private const val CM_COMMAND = "command"
        private const val CM_RESPONSE = "response"
        private const val CM_IS_ERROR = "is_error"
        private const val CM_CREATED_AT = "created_at"

        @Volatile
        private var instance: UrlDatabase? = null

        fun get(context: Context): UrlDatabase =
            instance ?: synchronized(this) {
                instance ?: UrlDatabase(context).also { instance = it }
            }
    }
}
