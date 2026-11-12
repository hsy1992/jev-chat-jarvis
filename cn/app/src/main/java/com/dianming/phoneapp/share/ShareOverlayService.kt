package com.dianming.phoneapp.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import com.dianming.phoneapp.R
import com.dianming.phoneapp.SettingsActivity
import kotlin.math.roundToInt

/** Owns the user-opened floating editor independently of the accessibility service. */
class ShareOverlayService : Service() {
    private val windowContext by lazy {
        val display = checkNotNull(getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY))
        createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    }
    private val windows by lazy { windowContext.getSystemService(WindowManager::class.java) }
    private var panel: ShareTaskPanel? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (panel != null) return START_NOT_STICKY
        val result = runCatching {
            startForeground(NOTIFICATION_ID, notification())
            check(Settings.canDrawOverlays(this) && ShareTasks.selected != null)
            val view = ShareTaskPanel(ContextThemeWrapper(windowContext, R.style.Theme_JevProbe),
                "收起", { stopSelf() }, onSettings = {
                    stopSelf()
                    startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
            windows.addView(view, layout())
            panel = view
        }
        if (result.isSuccess) ShareTasks.overlayState(true)
        else {
            ShareTasks.overlayState(false, "无法打开悬浮窗")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        panel?.let { runCatching { windows.updateViewLayout(it, layout()) } }
    }

    private fun layout(): WindowManager.LayoutParams {
        val bounds = windows.currentWindowMetrics.bounds
        val density = windowContext.resources.displayMetrics.density
        return WindowManager.LayoutParams(
            minOf((380 * density).roundToInt(), (bounds.width() * 0.94).roundToInt()),
            minOf((570 * density).roundToInt(), (bounds.height() * 0.75).roundToInt()),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (24 * density).roundToInt()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }

    private fun notification(): Notification {
        val channel = "jev_share_overlay"
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(channel, "Jev 分享悬浮窗", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, ShareImportActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Jev 分享悬浮窗")
            .setContentText("查看和校正已分享的聊天内容")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        panel?.let { runCatching { windows.removeView(it) } }
        panel = null
        ShareTasks.overlayState(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val NOTIFICATION_ID = 2
    }
}
