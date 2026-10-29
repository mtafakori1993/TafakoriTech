package ir.tafakoritech.adminapplication.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ir.tafakoritech.adminapplication.MainActivity
import ir.tafakoritech.adminapplication.R
import ir.tafakoritech.adminapplication.api.CommanderClient
import ir.tafakoritech.adminapplication.data.UrlDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that drains a terminal's command queue while the app is
 * closed or the phone is locked. After sending each command it waits for the
 * rendered terminal screen to stop changing (Claude has gone idle) before
 * sending the next one.
 */
class QueueRunnerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val serverId = intent?.getLongExtra(EXTRA_SERVER_ID, -1L) ?: -1L
        val baseUrl = intent?.getStringExtra(EXTRA_BASE_URL).orEmpty()
        val token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        val terminalId = intent?.getStringExtra(EXTRA_TERMINAL_ID).orEmpty()
        if (serverId < 0 || baseUrl.isBlank() || terminalId.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(notification("Starting…", ongoing = true))

        job?.cancel()
        job = scope.launch {
            runCatching { drain(serverId, baseUrl, token, terminalId) }
            stopSelf()
        }
        return START_REDELIVER_INTENT
    }

    private suspend fun drain(serverId: Long, baseUrl: String, token: String, terminalId: String) {
        val db = UrlDatabase.get(applicationContext)
        val client = CommanderClient(baseUrl, token)

        while (scope.isActive) {
            val item = db.popCommand(serverId, terminalId) ?: break
            val remaining = db.queuedCommands(serverId, terminalId).size
            update(notification("Sending: ${item.text.take(40)}  ($remaining left)", ongoing = true))

            runCatching { client.terminalInput(terminalId, item.text, submit = true) }
                .onFailure {
                    // Re-queue at the front isn't trivial; surface the failure and stop.
                    update(notification("Failed to send — stopped. ${it.message}", ongoing = false))
                    return
                }

            // /clear is instant; no need to wait for the screen to settle.
            if (item.text.trim() != "/clear") {
                waitForIdle(client, terminalId)
            }
        }
        update(notification("Queue finished", ongoing = false))
    }

    /**
     * Polls the rendered screen until it stops changing for [STABLE_MS], i.e.
     * Claude has finished responding, or until [MAX_WAIT_MS] elapses.
     */
    private suspend fun waitForIdle(client: CommanderClient, terminalId: String) {
        var last: String? = null
        var stableFor = 0L
        var waited = 0L
        // Give the TUI a moment to start reacting before we sample.
        delay(POLL_MS)
        while (scope.isActive && waited < MAX_WAIT_MS) {
            val screen = runCatching { client.getTerminal(terminalId).screenText }.getOrNull()
            if (screen != null && screen == last) {
                stableFor += POLL_MS
                if (stableFor >= STABLE_MS) return
            } else {
                stableFor = 0L
                last = screen
            }
            delay(POLL_MS)
            waited += POLL_MS
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---- notification -----------------------------------------------------

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun update(n: Notification) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n)
    }

    private fun notification(text: String, ongoing: Boolean): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Command queue", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Commander queue")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(ongoing)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 4201
        private const val CHANNEL_ID = "command_queue"
        private const val POLL_MS = 2_000L
        private const val STABLE_MS = 6_000L
        private const val MAX_WAIT_MS = 10 * 60_000L

        const val ACTION_STOP = "ir.tafakoritech.adminapplication.STOP_QUEUE"
        private const val EXTRA_SERVER_ID = "server_id"
        private const val EXTRA_BASE_URL = "base_url"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_TERMINAL_ID = "terminal_id"

        fun start(context: Context, serverId: Long, baseUrl: String, token: String, terminalId: String) {
            val intent = Intent(context, QueueRunnerService::class.java).apply {
                putExtra(EXTRA_SERVER_ID, serverId)
                putExtra(EXTRA_BASE_URL, baseUrl)
                putExtra(EXTRA_TOKEN, token)
                putExtra(EXTRA_TERMINAL_ID, terminalId)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, QueueRunnerService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
