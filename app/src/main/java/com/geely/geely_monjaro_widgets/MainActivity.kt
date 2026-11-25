package com.geely.geely_monjaro_widgets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.geely.geely_monjaro_widgets.ambient.AmbientHookBridge
import com.geely.geely_monjaro_widgets.ambient.AmbientMode
import com.geely.geely_monjaro_widgets.core.CarProperties
import com.geely.geely_monjaro_widgets.widget.ambient.AmbientLightWidgetProvider
import com.geely.geely_monjaro_widgets.core.ModeConfig
import com.geely.geely_monjaro_widgets.ui.theme.Geely_Monjaro_WidgetsTheme
import com.geely.os.car.ConnectionListener
import com.geely.os.car.GlyCar
import com.geely.os.car.IGlyCar

/**
 * Экран-хаб управления функциями машины. Дублирует возможности виджетов.
 *
 * Держит собственное соединение, пока экран открыт (connect в onResume, disconnect
 * в onPause), и на время открытия подписывается на те же свойства, что и фоновый
 * CarStateService — чтобы правки из штатного меню сразу отражались на экране.
 */
class MainActivity : ComponentActivity() {

    private var car: IGlyCar? = null

    private val connected = mutableStateOf(false)
    private val wiperServiceOn = mutableStateOf(false)
    private val trunkOpen = mutableStateOf(false)
    private val heatDriverLevel = mutableStateOf(0)
    private val heatPassengerLevel = mutableStateOf(0)
    private val ventDriverLevel = mutableStateOf(0)
    private val ventPassengerLevel = mutableStateOf(0)
    private val steeringHeatLevel = mutableStateOf(0)
    // Включённые режимы цикла (1..3) — раздельно на каждое место. По умолчанию все три;
    // реальные значения читаются из ModeConfig в onCreate.
    private val heatDriverModes = mutableStateOf(setOf(1, 2, 3))
    private val heatPassengerModes = mutableStateOf(setOf(1, 2, 3))
    private val ventDriverModes = mutableStateOf(setOf(1, 2, 3))
    private val ventPassengerModes = mutableStateOf(setOf(1, 2, 3))
    private val steeringHeatModes = mutableStateOf(setOf(1, 2, 3))
    private val recircOn = mutableStateOf(false)
    private val rearDefrostOn = mutableStateOf(false)
    private val massageDriverLevel = mutableStateOf(0)
    private val massageDriverModes = mutableStateOf(setOf(1, 2, 3))
    private val fuelPercent = mutableStateOf(-1)
    private val fuelLiters = mutableStateOf(-1)
    private val ambientMode = mutableStateOf(AmbientMode.OFF)
    private val ambientSpeedMs = mutableStateOf(180)
    private val ambientDoorAlert = mutableStateOf(false)
    private val ambientReverseAlert = mutableStateOf(false)
    private val ambientFuelAlert = mutableStateOf(false)
    private val hookStatusText = mutableStateOf("Нажми «Выдать права суперпользователя (Root)», затем «Проверить связь с LSPosed-модулем»")

    private val hookStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AmbientHookBridge.ACTION_STATUS) {
                val msg = intent.getStringExtra("status_text")
                if (!msg.isNullOrBlank()) {
                    hookStatusText.value = msg
                }
            }
        }
    }

    private val hookStatusObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val latest = AmbientHookBridge.readHookStatus(this@MainActivity)
            if (!latest.isNullOrBlank()) {
                hookStatusText.value = latest
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AmbientHookBridge.wakeUpSettingsProcess(this)
        com.geely.geely_monjaro_widgets.service.CarStateService.ensureStarted(this)
        loadModeConfig()
        AmbientHookBridge.readHookStatus(this)?.takeIf { it.isNotBlank() }?.let {
            hookStatusText.value = it
        }
        try {
            Settings.Global.getUriFor(AmbientHookBridge.KEY_STATUS)?.let { uri ->
                contentResolver.registerContentObserver(uri, false, hookStatusObserver)
            }
        } catch (_: Throwable) {}
        val filter = IntentFilter(AmbientHookBridge.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(hookStatusReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(hookStatusReceiver, filter)
        }
        enableEdgeToEdge()
        setContent {
            Geely_Monjaro_WidgetsTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ControlScreen(
                        modifier = Modifier.padding(innerPadding),
                        connected = connected.value,
                        wiperServiceOn = wiperServiceOn.value,
                        trunkOpen = trunkOpen.value,
                        onWiperToggle = ::onWiperToggle,
                        onTrunkOpen = { onTrunkSet(open = true) },
                        onTrunkClose = { onTrunkSet(open = false) },
                        onDriverProfile = { applySeatProfile(CarProperties.AREA_SEAT_DRIVER, it) },
                        onPassengerProfile = { applySeatProfile(CarProperties.AREA_SEAT_PASSENGER, it) },
                        heatDriverLevel = heatDriverLevel.value,
                        heatPassengerLevel = heatPassengerLevel.value,
                        ventDriverLevel = ventDriverLevel.value,
                        ventPassengerLevel = ventPassengerLevel.value,
                        onHeatDriver = { cycleClimate(CarProperties.SEAT_HEATING, CarProperties.AREA_SEAT_DRIVER, heatDriverLevel) },
                        onHeatPassenger = { cycleClimate(CarProperties.SEAT_HEATING, CarProperties.AREA_SEAT_PASSENGER, heatPassengerLevel) },
                        onVentDriver = { cycleClimate(CarProperties.SEAT_VENTILATION, CarProperties.AREA_SEAT_DRIVER, ventDriverLevel) },
                        onVentPassenger = { cycleClimate(CarProperties.SEAT_VENTILATION, CarProperties.AREA_SEAT_PASSENGER, ventPassengerLevel) },
                        heatDriverModes = heatDriverModes.value,
                        heatPassengerModes = heatPassengerModes.value,
                        ventDriverModes = ventDriverModes.value,
                        ventPassengerModes = ventPassengerModes.value,
                        onHeatDriverModes = { updateModes(CarProperties.SEAT_HEATING, CarProperties.AREA_SEAT_DRIVER, heatDriverModes, it) },
                        onHeatPassengerModes = { updateModes(CarProperties.SEAT_HEATING, CarProperties.AREA_SEAT_PASSENGER, heatPassengerModes, it) },
                        onVentDriverModes = { updateModes(CarProperties.SEAT_VENTILATION, CarProperties.AREA_SEAT_DRIVER, ventDriverModes, it) },
                        onVentPassengerModes = { updateModes(CarProperties.SEAT_VENTILATION, CarProperties.AREA_SEAT_PASSENGER, ventPassengerModes, it) },
                        steeringHeatLevel = steeringHeatLevel.value,
                        onHeatSteering = ::cycleSteeringHeat,
                        steeringHeatModes = steeringHeatModes.value,
                        onSteeringHeatModes = { updateModes(CarProperties.STEERING_WHEEL_HEATING, null, steeringHeatModes, it) },
                        recircOn = recircOn.value,
                        onRecircToggle = ::onRecircToggle,
                        rearDefrostOn = rearDefrostOn.value,
                        onRearDefrostToggle = ::onRearDefrostToggle,
                        massageDriverLevel = massageDriverLevel.value,
                        onMassageDriver = ::cycleMassageDriver,
                        massageDriverModes = massageDriverModes.value,
                        onMassageDriverModes = { updateModes(CarProperties.SEAT_MASSAGE, CarProperties.AREA_SEAT_DRIVER, massageDriverModes, it) },
                        fuelPercent = fuelPercent.value,
                        fuelLiters = fuelLiters.value,
                        ambientMode = ambientMode.value,
                        onAmbientModeChange = ::setAmbientMode,
                        ambientSpeedMs = ambientSpeedMs.value,
                        onAmbientSpeedChange = ::setAmbientSpeed,
                        ambientDoorAlert = ambientDoorAlert.value,
                        onAmbientDoorAlertChange = ::setAmbientDoorAlert,
                        ambientReverseAlert = ambientReverseAlert.value,
                        onAmbientReverseAlertChange = ::setAmbientReverseAlert,
                        ambientFuelAlert = ambientFuelAlert.value,
                        onAmbientFuelAlertChange = ::setAmbientFuelAlert,
                        hookStatus = hookStatusText.value,
                        onHookStatusChange = { hookStatusText.value = it },
                        onProbeHook = {
                            val existing = AmbientHookBridge.readHookStatus(this)
                            hookStatusText.value = if (!existing.isNullOrBlank()) {
                                "$existing\n(Обновление статуса...)"
                            } else {
                                "Запрос статуса у LSPosed-модуля (com.geely.settings)..."
                            }
                            AmbientHookBridge.wakeUpSettingsProcess(this)
                            AmbientHookBridge.sendLightShowCommand(this, "probe_status")
                        },
                        onStartBuiltInShow = { AmbientHookBridge.sendLightShowCommand(this, "lightshow_builtin") },
                        onStartScriptShow = { AmbientHookBridge.sendLightShowCommand(this, "lightshow_script") },
                        onStopShow = { AmbientHookBridge.sendLightShowCommand(this, "lightshow_stop") },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        car = GlyCar.create(this, object : ConnectionListener {
            override fun onConnected() {
                car?.registerValueWatcher(CarProperties.WATCHED_PROPERTIES) { _, _ ->
                    runOnUiThread { readState() }
                }
                car?.registerSensorWatcher(CarProperties.WATCHED_SENSORS) { _, _ ->
                    runOnUiThread { readState() }
                }
                runOnUiThread {
                    connected.value = true
                    readState()
                }
            }

            override fun onDisConnected() = runOnUiThread {
                connected.value = false
            }
        })
    }

    override fun onPause() {
        super.onPause()
        car?.unregisterValueWatcher()
        car?.unregisterSensorWatcher()
        car?.disconnect()
        car = null
        connected.value = false
    }

    private fun readState() {
        val c = car ?: return
        wiperServiceOn.value =
            c.getIntProperty(CarProperties.WIPER_SERVICE_POSITION, CarProperties.AREA_FRONT_WIPER) == 1
        val state = c.getIntProperty(CarProperties.TRUNK_STATE, CarProperties.AREA_TRUNK)
        trunkOpen.value = CarProperties.isTrunkOpenish(state)
        heatDriverLevel.value =
            CarProperties.decodeSeatLevel(c.getIntProperty(CarProperties.SEAT_HEATING, CarProperties.AREA_SEAT_DRIVER))
        heatPassengerLevel.value =
            CarProperties.decodeSeatLevel(c.getIntProperty(CarProperties.SEAT_HEATING, CarProperties.AREA_SEAT_PASSENGER))
        ventDriverLevel.value =
            CarProperties.decodeSeatLevel(c.getIntProperty(CarProperties.SEAT_VENTILATION, CarProperties.AREA_SEAT_DRIVER))
        ventPassengerLevel.value =
            CarProperties.decodeSeatLevel(c.getIntProperty(CarProperties.SEAT_VENTILATION, CarProperties.AREA_SEAT_PASSENGER))
        steeringHeatLevel.value =
            CarProperties.decodeSeatLevel(c.getIntProperty(CarProperties.STEERING_WHEEL_HEATING))
        recircOn.value =
            c.getIntProperty(CarProperties.AIR_CIRCULATION) == CarProperties.CIRCULATION_INNER
        rearDefrostOn.value = c.getIntProperty(CarProperties.DEFROST_REAR) == 1 &&
            !CarProperties.isKnownInactive(c.supportStatus(CarProperties.DEFROST_REAR))
        val massageSw = c.getIntProperty(CarProperties.SEAT_MASSAGE_SWITCH, CarProperties.AREA_SEAT_DRIVER) == 1 &&
            !CarProperties.isKnownInactive(c.supportStatus(CarProperties.SEAT_MASSAGE_SWITCH, CarProperties.AREA_SEAT_DRIVER))
        massageDriverLevel.value = if (massageSw) {
            val lvl = CarProperties.decodeSeatLevel(c.getIntProperty(CarProperties.SEAT_MASSAGE, CarProperties.AREA_SEAT_DRIVER))
            if (lvl > 0) lvl else 1
        } else 0
        val fuel = c.getSensorValue(CarProperties.SENSOR_FUEL_PERCENTAGE)
        fuelPercent.value = if (fuel > 0f) Math.round(fuel) else -1
        val liters = CarProperties.fuelLiters(
            c.getSensorValue(CarProperties.SENSOR_FUEL_LEVEL), fuel, c.getFuelTankCapacityLiters()
        )
        fuelLiters.value = if (liters > 0f) Math.round(liters) else -1
    }

    private fun onRecircToggle() {
        val c = car ?: return
        val active = c.getIntProperty(CarProperties.AIR_CIRCULATION) == CarProperties.CIRCULATION_INNER
        val next =
            if (active) CarProperties.CIRCULATION_OUTSIDE else CarProperties.CIRCULATION_INNER
        if (c.setIntProperty(CarProperties.AIR_CIRCULATION, next)) {
            recircOn.value = !active
        }
    }

    private fun onRearDefrostToggle() {
        val c = car ?: return
        val active = c.getIntProperty(CarProperties.DEFROST_REAR) == 1
        if (c.setIntProperty(CarProperties.DEFROST_REAR, if (active) 0 else 1)) {
            // Как в виджете: на заглушённой машине запись проходит, но функция не работает.
            val available = !CarProperties.isKnownInactive(c.supportStatus(CarProperties.DEFROST_REAR))
            rearDefrostOn.value = !active && available
        }
    }

    private fun cycleMassageDriver() {
        val c = car ?: return
        val switchOn = c.getIntProperty(CarProperties.SEAT_MASSAGE_SWITCH, CarProperties.AREA_SEAT_DRIVER) == 1
        val current = if (switchOn) CarProperties.decodeSeatLevel(c.getIntProperty(CarProperties.SEAT_MASSAGE, CarProperties.AREA_SEAT_DRIVER)) else 0
        val enabled = ModeConfig.enabledLevels(this, CarProperties.SEAT_MASSAGE, CarProperties.AREA_SEAT_DRIVER)
        val next = CarProperties.nextSeatLevel(current, enabled)
        if (next == 0) {
            if (c.setIntProperty(CarProperties.SEAT_MASSAGE_SWITCH, CarProperties.AREA_SEAT_DRIVER, 0)) {
                massageDriverLevel.value = 0
            }
        } else {
            val encoded = CarProperties.encodeSeatLevel(CarProperties.SEAT_MASSAGE, next)
            val ok1 = c.setIntProperty(CarProperties.SEAT_MASSAGE, CarProperties.AREA_SEAT_DRIVER, encoded)
            val ok2 = c.setIntProperty(CarProperties.SEAT_MASSAGE_SWITCH, CarProperties.AREA_SEAT_DRIVER, 1)
            if (ok1 || ok2) {
                massageDriverLevel.value = next
            }
        }
    }

    private fun cycleSteeringHeat() {
        val c = car ?: return
        val raw = c.getIntProperty(CarProperties.STEERING_WHEEL_HEATING)
        val current = CarProperties.decodeSeatLevel(raw)
        val enabled = ModeConfig.enabledLevels(this, CarProperties.STEERING_WHEEL_HEATING, null)
        // AUTO не выбирается отдельной кнопкой руля. Если API всё же вернул это
        // служебное значение, следующее нажатие явно сбрасывает его в OFF.
        val next = if (CarProperties.isAutoLevel(raw)) {
            0
        } else {
            CarProperties.nextSeatLevel(current, enabled)
        }
        val encoded = CarProperties.encodeSeatLevel(CarProperties.STEERING_WHEEL_HEATING, next)
        if (c.setIntProperty(CarProperties.STEERING_WHEEL_HEATING, encoded)) {
            steeringHeatLevel.value = next
        }
    }

    private fun cycleClimate(
        propertyId: Int,
        areaId: Int,
        level: androidx.compose.runtime.MutableState<Int>
    ) {
        val c = car ?: return
        val current = CarProperties.decodeSeatLevel(c.getIntProperty(propertyId, areaId))
        val enabled = ModeConfig.enabledLevels(this, propertyId, areaId)
        val next = CarProperties.nextSeatLevel(current, enabled)

        if (c.setIntProperty(propertyId, areaId, CarProperties.encodeSeatLevel(propertyId, next))) {
            level.value = next
        }
    }

    /** Читает сохранённые наборы режимов в состояние экрана. */
    private fun loadModeConfig() {
        heatDriverModes.value =
            ModeConfig.enabledLevels(this, CarProperties.SEAT_HEATING, CarProperties.AREA_SEAT_DRIVER)
        heatPassengerModes.value =
            ModeConfig.enabledLevels(this, CarProperties.SEAT_HEATING, CarProperties.AREA_SEAT_PASSENGER)
        ventDriverModes.value =
            ModeConfig.enabledLevels(this, CarProperties.SEAT_VENTILATION, CarProperties.AREA_SEAT_DRIVER)
        ventPassengerModes.value =
            ModeConfig.enabledLevels(this, CarProperties.SEAT_VENTILATION, CarProperties.AREA_SEAT_PASSENGER)
        steeringHeatModes.value =
            ModeConfig.enabledLevels(this, CarProperties.STEERING_WHEEL_HEATING, null)
        massageDriverModes.value =
            ModeConfig.enabledLevels(this, CarProperties.SEAT_MASSAGE, CarProperties.AREA_SEAT_DRIVER)
        ambientMode.value = AmbientHookBridge.getMode(this)
        ambientSpeedMs.value = AmbientHookBridge.getSpeedMs(this)
        ambientDoorAlert.value = AmbientHookBridge.isDoorAlert(this)
        ambientReverseAlert.value = AmbientHookBridge.isReverseAlert(this)
        ambientFuelAlert.value = AmbientHookBridge.isFuelAlert(this)
    }

    private fun updateModes(
        propertyId: Int,
        areaId: Int?,
        state: androidx.compose.runtime.MutableState<Set<Int>>,
        levels: Set<Int>,
    ) {
        state.value = levels
        ModeConfig.setEnabledLevels(this, propertyId, areaId, levels)
    }

    private fun setAmbientMode(mode: AmbientMode) {
        ambientMode.value = mode
        AmbientHookBridge.saveAndPushConfig(this, mode = mode)
        AmbientLightWidgetProvider.refreshAll(this)
    }

    private fun setAmbientSpeed(delayMs: Int) {
        val clamped = delayMs.coerceIn(120, 1000)
        ambientSpeedMs.value = clamped
        AmbientHookBridge.saveAndPushConfig(this, speedMs = clamped)
    }

    private fun setAmbientDoorAlert(enabled: Boolean) {
        ambientDoorAlert.value = enabled
        AmbientHookBridge.saveAndPushConfig(this, doorAlert = enabled)
    }

    private fun setAmbientReverseAlert(enabled: Boolean) {
        ambientReverseAlert.value = enabled
        AmbientHookBridge.saveAndPushConfig(this, reverseAlert = enabled)
    }

    private fun setAmbientFuelAlert(enabled: Boolean) {
        ambientFuelAlert.value = enabled
        AmbientHookBridge.saveAndPushConfig(this, fuelAlert = enabled)
    }

    override fun onDestroy() {
        try { unregisterReceiver(hookStatusReceiver) } catch (_: Throwable) {}
        try { contentResolver.unregisterContentObserver(hookStatusObserver) } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun onWiperToggle() {
        val c = car ?: return
        val next = if (wiperServiceOn.value) 0 else 1
        c.setIntProperty(CarProperties.WIPER_SERVICE_POSITION, CarProperties.AREA_FRONT_WIPER, next)
        wiperServiceOn.value = next == 1
    }

    private fun onTrunkSet(open: Boolean) {
        val c = car ?: return
        val command = if (open) CarProperties.TRUNK_OPEN else CarProperties.TRUNK_CLOSE
        c.setIntProperty(CarProperties.TRUNK_COMMAND, CarProperties.AREA_TRUNK, command)
        trunkOpen.value = open
    }

    private fun applySeatProfile(areaId: Int, profileIndex: Int) {
        val c = car ?: return
        c.setIntProperty(
            CarProperties.SEAT_POSITION_RESTORE,
            areaId,
            CarProperties.seatProfileValue(profileIndex)
        )
    }
}

@Composable
private fun ControlScreen(
    modifier: Modifier = Modifier,
    connected: Boolean,
    wiperServiceOn: Boolean,
    trunkOpen: Boolean,
    onWiperToggle: () -> Unit,
    onTrunkOpen: () -> Unit,
    onTrunkClose: () -> Unit,
    onDriverProfile: (Int) -> Unit,
    onPassengerProfile: (Int) -> Unit,
    heatDriverLevel: Int,
    heatPassengerLevel: Int,
    ventDriverLevel: Int,
    ventPassengerLevel: Int,
    onHeatDriver: () -> Unit,
    onHeatPassenger: () -> Unit,
    onVentDriver: () -> Unit,
    onVentPassenger: () -> Unit,
    heatDriverModes: Set<Int>,
    heatPassengerModes: Set<Int>,
    ventDriverModes: Set<Int>,
    ventPassengerModes: Set<Int>,
    onHeatDriverModes: (Set<Int>) -> Unit,
    onHeatPassengerModes: (Set<Int>) -> Unit,
    onVentDriverModes: (Set<Int>) -> Unit,
    onVentPassengerModes: (Set<Int>) -> Unit,
    steeringHeatLevel: Int,
    onHeatSteering: () -> Unit,
    steeringHeatModes: Set<Int>,
    onSteeringHeatModes: (Set<Int>) -> Unit,
    recircOn: Boolean,
    onRecircToggle: () -> Unit,
    rearDefrostOn: Boolean,
    onRearDefrostToggle: () -> Unit,
    massageDriverLevel: Int,
    onMassageDriver: () -> Unit,
    massageDriverModes: Set<Int>,
    onMassageDriverModes: (Set<Int>) -> Unit,
    fuelPercent: Int,
    fuelLiters: Int,
    ambientMode: AmbientMode,
    onAmbientModeChange: (AmbientMode) -> Unit,
    ambientSpeedMs: Int,
    onAmbientSpeedChange: (Int) -> Unit,
    ambientDoorAlert: Boolean,
    onAmbientDoorAlertChange: (Boolean) -> Unit,
    ambientReverseAlert: Boolean,
    onAmbientReverseAlertChange: (Boolean) -> Unit,
    ambientFuelAlert: Boolean,
    onAmbientFuelAlertChange: (Boolean) -> Unit,
    hookStatus: String,
    onHookStatusChange: (String) -> Unit,
    onProbeHook: () -> Unit,
    onStartBuiltInShow: () -> Unit,
    onStartScriptShow: () -> Unit,
    onStopShow: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringRes(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = if (connected) "Подключено к машине" else "Нет соединения с машиной",
            style = MaterialTheme.typography.bodySmall,
        )

        // Дворники
        FunctionCard(title = stringRes(R.string.wiper_service_label)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = if (wiperServiceOn) stringRes(R.string.wiper_service_on) else stringRes(R.string.wiper_service_off))
                Switch(checked = wiperServiceOn, onCheckedChange = { onWiperToggle() }, enabled = connected)
            }
        }

        // Багажник
        FunctionCard(title = stringRes(R.string.trunk_label)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onTrunkOpen, enabled = connected) { Text("Открыть") }
                OutlinedButton(onClick = onTrunkClose, enabled = connected) { Text("Закрыть") }
            }
        }

        // Память сиденья водителя (2 профиля)
        FunctionCard(title = stringRes(R.string.seat_memory_driver)) {
            ProfileRow(enabled = connected, count = 2, onProfile = onDriverProfile)
        }

        // Память сиденья пассажира (3 профиля)
        FunctionCard(title = stringRes(R.string.seat_memory_passenger)) {
            ProfileRow(enabled = connected, count = 3, onProfile = onPassengerProfile)
        }

        // Подогрев / вентиляция (цикл по включённым режимам, см. чипы в карточке)
        FunctionCard(title = stringRes(R.string.heat_driver_label)) {
            ClimateRow(enabled = connected, level = heatDriverLevel, onCycle = onHeatDriver,
                modes = heatDriverModes, onModesChange = onHeatDriverModes)
        }
        FunctionCard(title = stringRes(R.string.heat_passenger_label)) {
            ClimateRow(enabled = connected, level = heatPassengerLevel, onCycle = onHeatPassenger,
                modes = heatPassengerModes, onModesChange = onHeatPassengerModes)
        }
        FunctionCard(title = stringRes(R.string.vent_driver_label)) {
            ClimateRow(enabled = connected, level = ventDriverLevel, onCycle = onVentDriver,
                modes = ventDriverModes, onModesChange = onVentDriverModes)
        }
        FunctionCard(title = stringRes(R.string.vent_passenger_label)) {
            ClimateRow(enabled = connected, level = ventPassengerLevel, onCycle = onVentPassenger,
                modes = ventPassengerModes, onModesChange = onVentPassengerModes)
        }
        FunctionCard(title = stringRes(R.string.heat_steering_label)) {
            ClimateRow(enabled = connected, level = steeringHeatLevel, onCycle = onHeatSteering,
                modes = steeringHeatModes, onModesChange = onSteeringHeatModes)
        }

        // Рециркуляция
        FunctionCard(title = stringRes(R.string.recirc_label)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = if (recircOn) "Включена" else "Выключена")
                Switch(checked = recircOn, onCheckedChange = { onRecircToggle() }, enabled = connected)
            }
        }

        // Обогрев заднего стекла
        FunctionCard(title = stringRes(R.string.rear_defrost_label)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = if (rearDefrostOn) "Включён" else "Выключен")
                Switch(checked = rearDefrostOn, onCheckedChange = { onRearDefrostToggle() }, enabled = connected)
            }
        }

        // Массаж водителя
        FunctionCard(title = stringRes(R.string.massage_driver_label)) {
            ClimateRow(enabled = connected, level = massageDriverLevel, onCycle = onMassageDriver,
                modes = massageDriverModes, onModesChange = onMassageDriverModes)
        }

        // Топливо (только показ)
        FunctionCard(title = stringRes(R.string.fuel_label)) {
            val pctText = if (fuelPercent >= 0) "$fuelPercent%" else "—"
            val text = if (fuelLiters >= 0) "$pctText · $fuelLiters л" else pctText
            Text(text = text)
        }

        // Атмосферная подсветка и световое шоу через отдельный LSPosed-модуль
        FunctionCard(title = stringRes(R.string.ambient_light_label)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = hookStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                val ctx = androidx.compose.ui.platform.LocalContext.current
                var hasPerm by remember { mutableStateOf(AmbientHookBridge.hasSecureSettingsPermission(ctx)) }
                OutlinedButton(onClick = {
                    onHookStatusChange("Запрос прав суперпользователя (su)...")
                    AmbientHookBridge.requestRootAndGrantPermission(ctx) { _, msg ->
                        hasPerm = AmbientHookBridge.hasSecureSettingsPermission(ctx)
                        onHookStatusChange(msg)
                        if (hasPerm) {
                            onProbeHook()
                        }
                    }
                }) {
                    Text(
                        if (hasPerm) "Права Root / WRITE_SECURE_SETTINGS выданы ✓"
                        else "Выдать права суперпользователя (Root)"
                    )
                }
                OutlinedButton(onClick = onProbeHook) {
                    Text("Проверить связь с LSPosed-модулем")
                }

                for (modeItem in AmbientMode.entries) {
                    val selected = ambientMode == modeItem
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAmbientModeChange(modeItem) }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = modeItem.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = modeItem.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = selected,
                            onCheckedChange = { checked ->
                                onAmbientModeChange(if (checked) modeItem else AmbientMode.OFF)
                            },
                        )
                    }
                }

                Column {
                    Text(
                        text = "Скорость перелива: шаг ${ambientSpeedMs} мс",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = ambientSpeedMs.toFloat(),
                        onValueChange = { onAmbientSpeedChange(it.toInt()) },
                        valueRange = 120f..1200f,
                    )
                }

                Text(
                    text = "Световые оповещения (работают только при включении):",
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Открытие дверей / багажника", modifier = Modifier.weight(1f))
                    Switch(checked = ambientDoorAlert, onCheckedChange = onAmbientDoorAlertChange)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Подсветка при заднем ходе (R)", modifier = Modifier.weight(1f))
                    Switch(checked = ambientReverseAlert, onCheckedChange = onAmbientReverseAlertChange)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Вспышка при малом остатке топлива (<12%)", modifier = Modifier.weight(1f))
                    Switch(checked = ambientFuelAlert, onCheckedChange = onAmbientFuelAlertChange)
                }

                Text(
                    text = "Световое шоу наружной оптики (на P, двери закрыты):",
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onStartBuiltInShow) { Text("Запустить шоу") }
                    OutlinedButton(onClick = onStartScriptShow) { Text("Тест-скрипт") }
                    OutlinedButton(onClick = onStopShow) { Text("Стоп") }
                }
            }
        }

        AboutCard()
    }
}

@Composable
private fun AboutCard() {
    val context = LocalContext.current
    FunctionCard(title = stringRes(R.string.about_title)) {
        Text(text = stringRes(R.string.about_author))
        Text(
            text = "github.com/Schum-io",
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Schum-io"))
                )
            },
        )
    }
}

@Composable
private fun ClimateRow(
    enabled: Boolean,
    level: Int,
    onCycle: () -> Unit,
    modes: Set<Int>,
    onModesChange: (Set<Int>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = if (level == 0) "Выключено" else "Уровень $level")
            Button(onClick = onCycle, enabled = enabled) { Text("Переключить") }
        }
        ModeChips(modes = modes, onModesChange = onModesChange)
    }
}

/**
 * Чипы выбора режимов (1/2/3), участвующих в цикле. Нельзя снять последний включённый —
 * хотя бы один режим всегда доступен.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeChips(modes: Set<Int>, onModesChange: (Set<Int>) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Режимы:", style = MaterialTheme.typography.bodySmall)
        for (levelValue in 1..CarProperties.SEAT_LEVEL_MAX) {
            val selected = levelValue in modes
            FilterChip(
                selected = selected,
                onClick = {
                    val next = if (selected) modes - levelValue else modes + levelValue
                    if (next.isNotEmpty()) onModesChange(next)
                },
                label = { Text("$levelValue") },
            )
        }
    }
}

@Composable
private fun ProfileRow(enabled: Boolean, count: Int, onProfile: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until count) {
            Button(onClick = { onProfile(i) }, enabled = enabled) { Text("${i + 1}") }
        }
    }
}

@Composable
private fun FunctionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun stringRes(id: Int): String = androidx.compose.ui.res.stringResource(id)
