package vn.servio.partner.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import vn.servio.partner.MainActivity
import vn.servio.partner.R
import vn.servio.partner.data.location.DeviceLocation
import vn.servio.partner.data.repository.OrderRepository
import javax.inject.Inject

/**
 * DT-4: while the partner is ON_THE_WAY, sends the position to the customer every 10 seconds
 * (hub method UpdateLocation, F-ORD-05). Runs as a foreground service of type location so it keeps going
 * with the screen off. Xiaomi/Oppo/Vivo may still kill it: turn off battery optimisation before a demo.
 */
@AndroidEntryPoint
class LocationTrackingService : Service() {

    @Inject lateinit var deviceLocation: DeviceLocation
    @Inject lateinit var orderRepository: OrderRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val assignmentId = intent?.getStringExtra(EXTRA_ASSIGNMENT_ID)
        val orderId = intent?.getStringExtra(EXTRA_ORDER_ID)
        if (intent?.action == ACTION_STOP || assignmentId == null || orderId == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(orderId), type)

        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                deviceLocation.current(maxAgeMillis = INTERVAL_MILLIS, timeoutMillis = INTERVAL_MILLIS)
                    ?.let { orderRepository.sendLocation(assignmentId, it) }
                delay(INTERVAL_MILLIS)
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(orderId: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.tracking_channel), NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).putExtra(EXTRA_ORDER_ID, orderId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_location_pin)
            .setContentTitle(getString(R.string.tracking_title))
            .setContentText(getString(R.string.tracking_text))
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "trip_tracking"
        private const val NOTIFICATION_ID = 2001
        private const val INTERVAL_MILLIS = 10_000L
        private const val ACTION_STOP = "vn.servio.partner.tracking.STOP"
        const val EXTRA_ORDER_ID = "orderId"
        const val EXTRA_ASSIGNMENT_ID = "assignmentId"

        fun start(context: Context, orderId: String, assignmentId: String) {
            val intent = Intent(context, LocationTrackingService::class.java)
                .putExtra(EXTRA_ORDER_ID, orderId)
                .putExtra(EXTRA_ASSIGNMENT_ID, assignmentId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationTrackingService::class.java))
        }
    }
}
