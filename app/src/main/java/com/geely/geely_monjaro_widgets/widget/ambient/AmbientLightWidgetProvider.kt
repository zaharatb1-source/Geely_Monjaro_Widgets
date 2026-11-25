package com.geely.geely_monjaro_widgets.widget.ambient

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.widget.RemoteViews
import com.geely.geely_monjaro_widgets.R
import com.geely.geely_monjaro_widgets.ambient.AmbientHookBridge
import com.geely.geely_monjaro_widgets.ambient.AmbientMode
import com.geely.geely_monjaro_widgets.service.CarStateService

class AmbientLightWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        val mode = AmbientHookBridge.getMode(context)
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context, id, mode))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE -> {
                val current = AmbientHookBridge.getMode(context)
                val next = if (current == AmbientMode.OFF) AmbientHookBridge.getLastActiveMode(context) else AmbientMode.OFF
                AmbientHookBridge.saveAndPushConfig(context, mode = next)
                refreshAll(context)
            }
            ACTION_CYCLE -> {
                val current = AmbientHookBridge.getMode(context)
                val all = AmbientMode.entries
                val next = all[(all.indexOf(current) + 1) % all.size]
                AmbientHookBridge.saveAndPushConfig(context, mode = next)
                refreshAll(context)
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.geely.geely_monjaro_widgets.action.AMBIENT_TOGGLE"
        const val ACTION_CYCLE = "com.geely.geely_monjaro_widgets.action.AMBIENT_CYCLE"

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, AmbientLightWidgetProvider::class.java)
            val mode = AmbientHookBridge.getMode(context)
            for (id in manager.getAppWidgetIds(component)) {
                manager.updateAppWidget(id, buildViews(context, id, mode))
            }
        }

        private fun buildViews(context: Context, appWidgetId: Int, mode: AmbientMode): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_ambient_light)
            val active = mode != AmbientMode.OFF
            views.setTextViewText(R.id.ambientTopText, "Подсветка")
            views.setTextViewText(R.id.ambientBottomText, shortModeLabel(mode))
            views.setTextViewTextSize(R.id.ambientTopText, TypedValue.COMPLEX_UNIT_SP, 20f)
            views.setTextViewTextSize(R.id.ambientBottomText, TypedValue.COMPLEX_UNIT_SP, 16f)

            views.setImageViewResource(
                R.id.ambientCircle,
                if (active) R.drawable.ambient_circle_on else R.drawable.climate_circle_off
            )
            views.setImageViewResource(
                R.id.ambientIcon,
                if (active) R.drawable.ic_ambient_on else R.drawable.ic_ambient_off
            )

            views.setOnClickPendingIntent(
                R.id.ambientIcon,
                buildPendingIntent(context, appWidgetId * 2, ACTION_TOGGLE, "ambient_toggle/$appWidgetId")
            )
            views.setOnClickPendingIntent(
                R.id.ambientBottomText,
                buildPendingIntent(context, appWidgetId * 2 + 1, ACTION_CYCLE, "ambient_cycle/$appWidgetId")
            )
            return views
        }

        private fun shortModeLabel(mode: AmbientMode): String = when (mode) {
            AmbientMode.OFF -> "Штатно"
            AmbientMode.RAINBOW -> "Радуга"
            AmbientMode.MUSIC -> "Музыка"
            AmbientMode.SPEED_RPM -> "Обороты"
            AmbientMode.CLIMATE -> "Климат"
            AmbientMode.BIORHYTHM -> "Биоритм"
            AmbientMode.POLICE -> "Стробоскоп"
        }

        private fun buildPendingIntent(
            context: Context,
            requestCode: Int,
            action: String,
            tag: String,
        ): PendingIntent = CarStateService.actionPendingIntent(
            context = context,
            requestCode = requestCode,
            target = AmbientLightWidgetProvider::class.java,
            action = action,
            uniqueTag = tag,
        )
    }
}
