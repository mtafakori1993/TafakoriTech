package ir.tafakoritech.adminapplication

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import ir.tafakoritech.adminapplication.api.Lead

class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_RINGING) return

        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            ?.takeIf { it.isNotBlank() } ?: return

        if (MainActivity.isInForeground) {
            // App is visible — let App() handle the lead lookup and dialog
            IncomingCallManager.onIncomingCall(number)
            return
        }

        // Background: no local cache — show unknown caller notification
        showCallNotification(context, number, null)
    }

    private fun showCallNotification(context: Context, number: String, lead: Lead?) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Incoming Calls", NotificationManager.IMPORTANCE_HIGH)
        )

        if (lead != null) {
            showKnownLeadNotification(context, nm, number, lead)
        } else {
            showUnknownCallerNotification(context, nm, number)
        }
    }

    private fun showKnownLeadNotification(
        context: Context,
        nm: NotificationManager,
        number: String,
        lead: Lead,
    ) {
        val openHarborIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_HARBOR, true)
        }
        val openPi = PendingIntent.getActivity(
            context, 2, openHarborIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val statusText = leadStatusFa(lead.status)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(lead.fullName)
            .setContentText("$number  •  $statusText")
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_view, "View in Harbor", openPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun showUnknownCallerNotification(
        context: Context,
        nm: NotificationManager,
        number: String,
    ) {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            context, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val yesIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_HARBOR_CREATE, true)
            putExtra(EXTRA_PHONE_NUMBER, number)
        }
        val yesPi = PendingIntent.getActivity(
            context, 1, yesIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Incoming Call")
            .setContentText("$number — Is this a customer?")
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_add, "Yes — Add to Harbor", yesPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "incoming_call"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_OPEN_HARBOR_CREATE = "open_harbor_create"
        const val EXTRA_OPEN_HARBOR = "open_harbor"
        const val EXTRA_PHONE_NUMBER = "phone_number"

        private fun leadStatusFa(status: String) = when (status) {
            "new"          -> "New"
            "contacted"    -> "Contacted"
            "qualified"    -> "Qualified"
            "converted"    -> "Converted"
            "lost"         -> "Lost"
            "disqualified" -> "Disqualified"
            else           -> status
        }
    }
}
