package com.amiigo.harmattan

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Feeds the events view with the device's notifications (user enables it in Home settings). */
class EventsListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        N9.init(this)
        Apps.init(this)
        EventStore.init(this)
        EventStore.service = this
        runCatching { EventStore.setActive(activeNotifications ?: emptyArray()) }
        MediaWatcher.init(this)
        MediaWatcher.attach() // sessions are only readable once this listener is connected
    }

    override fun onListenerDisconnected() {
        if (EventStore.service === this) EventStore.service = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = EventStore.onPosted(sbn)
    override fun onNotificationRemoved(sbn: StatusBarNotification) = EventStore.onRemoved(sbn)
}
