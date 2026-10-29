package pl.kret.w9.trip

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import pl.kret.w9.App
import pl.kret.w9.MainActivity

/** Usługa pierwszoplanowa – trzyma GPS i połączenie BLE przy zgaszonym ekranie. */
class RideService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = Notification.Builder(this, App.CHANNEL_RIDE)
            .setContentTitle("W9 Scooter")
            .setContentText("Nagrywanie trasy…")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(1, n)
            }
        } catch (e: Exception) {
            // brak zgody na lokalizację – nagrywamy tylko gdy aplikacja jest na ekranie
            App.instance.scooter.addLog('•', "Usługa w tle niedostępna: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }
}
