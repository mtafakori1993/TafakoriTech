package ir.tafakoritech.adminapplication

import ir.tafakoritech.adminapplication.api.Lead
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object IncomingCallManager {
    private val _incomingPhone = MutableStateFlow<String?>(null)
    val incomingPhone: StateFlow<String?> = _incomingPhone.asStateFlow()

    // Non-null when the incoming number matched a Harbor lead (set after DB/network lookup).
    private val _incomingLead = MutableStateFlow<Lead?>(null)
    val incomingLead: StateFlow<Lead?> = _incomingLead.asStateFlow()

    // Set when user tapped "بله" in the notification (skip the dialog, go straight to create form)
    private val _createPhone = MutableStateFlow<String?>(null)
    val createPhone: StateFlow<String?> = _createPhone.asStateFlow()

    // Pulsed to true when a background notification for a known lead is tapped → open Harbor
    private val _openHarbor = MutableStateFlow(false)
    val openHarbor: StateFlow<Boolean> = _openHarbor.asStateFlow()

    fun onIncomingCall(number: String) {
        _incomingPhone.value = number
        _incomingLead.value = null
    }

    fun setIncomingLead(lead: Lead?) {
        _incomingLead.value = lead
    }

    fun dismissIncoming() {
        _incomingPhone.value = null
        _incomingLead.value = null
    }

    fun onNotificationCreate(number: String) {
        _createPhone.value = number
    }

    fun clearCreate() {
        _createPhone.value = null
    }

    fun onNotificationOpenHarbor() {
        _openHarbor.value = true
    }

    fun clearOpenHarbor() {
        _openHarbor.value = false
    }
}
