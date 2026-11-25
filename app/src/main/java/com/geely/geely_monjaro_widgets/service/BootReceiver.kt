package com.geely.geely_monjaro_widgets.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.geely.geely_monjaro_widgets.ambient.AmbientHookBridge

/** Поднимает сервис синхронизации и фоновый хук подсветки после загрузки системы. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            CarStateService.ensureStarted(context)
            AmbientHookBridge.wakeUpSettingsProcess(context)
            AmbientHookBridge.saveAndPushConfig(context)
        }
    }
}
