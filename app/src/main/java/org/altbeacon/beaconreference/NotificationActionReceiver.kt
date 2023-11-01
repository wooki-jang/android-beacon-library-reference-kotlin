package org.altbeacon.beaconreference

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import org.altbeacon.beacon.BeaconManager

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d("[Receiver]", "[Receiver] onReceive")

        if (intent == null || context == null) return

        if (NOTIFICATION_ACTION_STRING == intent.action) {
            val manager = BeaconManager.getInstanceForApplication(context)
            manager.stopMonitoring(BeaconReferenceApplication.region)
            manager.stopRangingBeacons(BeaconReferenceApplication.region)
            manager.disableForegroundServiceScanning()

            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.cancelAll()
        }
    }
}