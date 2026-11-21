package app.pingboost.device

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.view.Display
import app.pingboost.core.DeviceFacts

/** Читает состояние телефона, от которого зависит FPS. Ничего не меняет и не требует разрешений. */
object DeviceReader {

    fun read(context: Context, gameInstalled: Boolean): DeviceFacts {
        val app = context.applicationContext

        val displays = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display: Display? = displays.getDisplay(Display.DEFAULT_DISPLAY)
        val currentHz = display?.refreshRate ?: 60f
        val maxHz = display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: currentHz

        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus else null

        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val activity = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also { activity.getMemoryInfo(it) }

        return DeviceFacts(
            maxRefreshHz = maxHz,
            currentRefreshHz = currentHz,
            thermalStatus = thermal,
            powerSave = power.isPowerSaveMode,
            batteryPercent = percent,
            charging = charging,
            availRamMb = memory.availMem / MB,
            totalRamMb = memory.totalMem / MB,
            dndOn = isDndOn(app),
            gameInstalled = gameInstalled,
        )
    }

    fun isDndOn(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val filter = nm.currentInterruptionFilter
        return filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    /** Нужно ли сначала отправить пользователя в системные настройки, чтобы он разрешил управлять «Не беспокоить». */
    fun dndAccessGranted(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    /** «Приоритетные»: будильники и избранные контакты проходят, остальное молчит. Требует [dndAccessGranted]. */
    fun setDnd(context: Context, on: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL,
        )
    }

    private const val MB = 1024L * 1024L
}
