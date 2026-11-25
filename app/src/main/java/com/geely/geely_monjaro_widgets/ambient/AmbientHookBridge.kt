package com.geely.geely_monjaro_widgets.ambient

import android.content.Context
import android.content.Intent
import android.provider.Settings

enum class AmbientMode(val id: String, val title: String, val subtitle: String) {
    OFF("off", "Выключено (штатный режим)", "Хук не вмешивается, работает штатное меню авто"),
    RAINBOW("rainbow", "Радуга", "Плавный перелив всех оттенков по кругу"),
    MUSIC("music", "Цветомузыка", "Реакция яркости и цвета на музыку в салоне"),
    SPEED_RPM("speed_rpm", "Спидометр / Обороты", "От синего на малом ходу до красного при разгоне"),
    CLIMATE("climate", "Климат-градиент", "Цвет зависит от заданной температуры климата"),
    BIORHYTHM("biorhythm", "Биоритм (время суток)", "Бодрый холодный утром, мягкий янтарный ночью"),
    POLICE("police", "Полицейский / Стробоскоп", "Чередование красного и синего (шоу-режим)"),
}

/**
 * Надёжный мост управления для LSPosed-модуля Monjaro Ambient Light Pro.
 * Использует Settings.Global (через ContentResolver или root su при необходимости) + резервный Broadcast.
 */
object AmbientHookBridge {
    const val KEY_MODE = "monjaro_ambient_mode"
    const val KEY_SPEED = "monjaro_ambient_speed"
    const val KEY_DOOR = "monjaro_ambient_door"
    const val KEY_REV = "monjaro_ambient_rev"
    const val KEY_FUEL = "monjaro_ambient_fuel"
    const val KEY_CMD = "monjaro_ambient_cmd"
    const val KEY_STATUS = "monjaro_ambient_status"

    const val ACTION_CMD = "com.geely.monjaro.ambienthook.ACTION_CMD"
    const val ACTION_STATUS = "com.geely.monjaro.ambienthook.ACTION_STATUS"

    private val TARGET_PKGS = listOf(
        "com.geely.settings",
        "com.geely.hmi.settings",
        "com.ecarx.settings",
    )

    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private const val PREFS = "ambient_ui_prefs"
    private const val PREF_MODE = "mode"
    private const val PREF_LAST_ACTIVE = "last_active"
    private const val PREF_SPEED = "speed_ms"
    private const val PREF_DOOR = "door_alert"
    private const val PREF_REV = "reverse_alert"
    private const val PREF_FUEL = "fuel_alert"

    fun getMode(context: Context): AmbientMode {
        val id = prefs(context).getString(PREF_MODE, AmbientMode.OFF.id)
        return AmbientMode.entries.firstOrNull { it.id == id } ?: AmbientMode.OFF
    }

    fun getLastActiveMode(context: Context): AmbientMode {
        val id = prefs(context).getString(PREF_LAST_ACTIVE, AmbientMode.RAINBOW.id)
        val parsed = AmbientMode.entries.firstOrNull { it.id == id }
        return if (parsed != null && parsed != AmbientMode.OFF) parsed else AmbientMode.RAINBOW
    }

    fun getSpeedMs(context: Context): Int = prefs(context).getInt(PREF_SPEED, 180).coerceIn(120, 1200)
    fun isDoorAlert(context: Context): Boolean = prefs(context).getBoolean(PREF_DOOR, false)
    fun isReverseAlert(context: Context): Boolean = prefs(context).getBoolean(PREF_REV, false)
    fun isFuelAlert(context: Context): Boolean = prefs(context).getBoolean(PREF_FUEL, false)

    fun hasSecureSettingsPermission(context: Context): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * Интерактивно запрашивает root (su) для выдачи WRITE_SECURE_SETTINGS и записи тестовой команды.
     */
    fun requestRootAndGrantPermission(context: Context, onResult: (Boolean, String) -> Unit) {
        Thread {
            try {
                val proc = ProcessBuilder("su").redirectErrorStream(true).start()
                val os = java.io.DataOutputStream(proc.outputStream)
                val pkg = context.packageName
                os.writeBytes("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS\n")
                os.writeBytes("settings put global $KEY_CMD 'probe_status:${System.currentTimeMillis()}'\n")
                os.writeBytes("exit\n")
                os.flush()
                val output = proc.inputStream.bufferedReader().readText().trim()
                val exitCode = proc.waitFor()
                val granted = hasSecureSettingsPermission(context)
                val msg = when {
                    granted -> "Права суперпользователя получены! WRITE_SECURE_SETTINGS выдан."
                    exitCode == 0 -> "Команда su выполнена (код 0). Проверьте статус ниже."
                    else -> "Не удалось получить root (код $exitCode${if (output.isNotEmpty()) ": $output" else ""}). Введите команду вручную в терминале."
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onResult(granted || exitCode == 0, msg)
                }
            } catch (t: Throwable) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onResult(false, "Ошибка вызова su: ${t.message ?: "нет доступа"}")
                }
            }
        }.start()
    }

    fun readHookStatus(context: Context): String? {
        return try {
            Settings.Global.getString(context.contentResolver, KEY_STATUS)
        } catch (_: Throwable) {
            null
        }
    }

    fun saveAndPushConfig(
        context: Context,
        mode: AmbientMode = getMode(context),
        speedMs: Int = getSpeedMs(context),
        doorAlert: Boolean = isDoorAlert(context),
        reverseAlert: Boolean = isReverseAlert(context),
        fuelAlert: Boolean = isFuelAlert(context),
    ) {
        val clampedSpeed = speedMs.coerceIn(120, 1200)
        val ed = prefs(context).edit()
            .putString(PREF_MODE, mode.id)
            .putInt(PREF_SPEED, clampedSpeed)
            .putBoolean(PREF_DOOR, doorAlert)
            .putBoolean(PREF_REV, reverseAlert)
            .putBoolean(PREF_FUEL, fuelAlert)
        if (mode != AmbientMode.OFF) {
            ed.putString(PREF_LAST_ACTIVE, mode.id)
        }
        ed.apply()

        val cmdToken = "apply_config:${System.currentTimeMillis()}"
        writeGlobalPairs(
            context,
            mapOf(
                KEY_MODE to mode.id,
                KEY_SPEED to clampedSpeed.toString(),
                KEY_DOOR to (if (doorAlert) "1" else "0"),
                KEY_REV to (if (reverseAlert) "1" else "0"),
                KEY_FUEL to (if (fuelAlert) "1" else "0"),
                KEY_CMD to cmdToken,
            )
        )
        sendFallbackBroadcasts(context, "apply_config")
    }

    fun sendLightShowCommand(context: Context, cmd: String) {
        val cmdToken = "$cmd:${System.currentTimeMillis()}"
        writeGlobalPairs(context, mapOf(KEY_CMD to cmdToken))
        sendFallbackBroadcasts(context, cmd)
    }

    /**
     * Тихо будит процесс com.geely.settings в фоне (без открытия окна настроек на экране),
     * чтобы LSPosed-модуль внутри него был активен даже сразу после перезагрузки ГУ.
     */
    fun wakeUpSettingsProcess(context: Context) {
        ioExecutor.execute {
            try {
                // 1. Посылаем явный Broadcast (с FLAG_INCLUDE_STOPPED_PACKAGES)
                sendFallbackBroadcastsSync(context, "probe_status")
                // 2. Если процесс com.geely.settings ещё не запущен, поднимаем его фоновый сервис через su без вывода UI
                val running = isProcessRunning(context, "com.geely.settings")
                if (!running) {
                    val cmd = "am broadcast -a $ACTION_CMD -p com.geely.settings --include-stopped-packages >/dev/null 2>&1; " +
                        "am startservice -n com.geely.settings/.service.CarSettingService >/dev/null 2>&1 || " +
                        "am startservice -n com.geely.settings/com.geely.settings.service.SettingService >/dev/null 2>&1"
                    val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                    proc.waitFor()
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun isProcessRunning(context: Context, pkg: String): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return false
            am.runningAppProcesses?.any { it.processName == pkg } == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun writeGlobalPairs(context: Context, pairs: Map<String, String>) {
        // Вся межпроцессная запись выполняется строго в фоновом потоке, чтобы UI никогда не подвисал
        ioExecutor.execute {
            var directOk = true
            for ((k, v) in pairs) {
                try {
                    if (!Settings.Global.putString(context.contentResolver, k, v)) {
                        directOk = false
                    }
                } catch (_: Throwable) {
                    directOk = false
                }
            }
            if (!directOk) {
                try {
                    val sb = StringBuilder()
                    sb.append("pm grant ").append(context.packageName).append(" android.permission.WRITE_SECURE_SETTINGS 2>/dev/null; ")
                    for ((k, v) in pairs) {
                        val safeV = v.replace("'", "")
                        sb.append("settings put global ").append(k).append(" '").append(safeV).append("'; ")
                    }
                    val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", sb.toString()))
                    proc.waitFor()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun sendFallbackBroadcasts(context: Context, cmd: String) {
        ioExecutor.execute {
            sendFallbackBroadcastsSync(context, cmd)
        }
    }

    private fun sendFallbackBroadcastsSync(context: Context, cmd: String) {
        for (pkg in TARGET_PKGS) {
            try {
                val intent = Intent(ACTION_CMD).apply {
                    setPackage(pkg)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra("cmd", cmd)
                }
                context.sendBroadcast(intent)
            } catch (_: Throwable) {
            }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
