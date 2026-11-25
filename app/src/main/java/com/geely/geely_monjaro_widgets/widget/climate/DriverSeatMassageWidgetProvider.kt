package com.geely.geely_monjaro_widgets.widget.climate

import com.geely.geely_monjaro_widgets.R
import com.geely.geely_monjaro_widgets.core.CarProperties
import com.geely.geely_monjaro_widgets.core.CycleLevelWidgetProvider
import com.geely.os.car.IGlyCar

/**
 * Виджет массажа сиденья водителя (цикл OFF → 3 → 2 → 1 → OFF, настройка уровней в приложении).
 * Оформлен в стиле вентиляции сидений: голубой/синий круг и 3-ступенчатая индикация.
 */
class DriverSeatMassageWidgetProvider : CycleLevelWidgetProvider() {

    override val layoutRes = R.layout.widget_seat_climate
    override val iconViewId = R.id.climateIcon
    override val propertyId = CarProperties.SEAT_MASSAGE
    override val areaId = CarProperties.AREA_SEAT_DRIVER
    override val actionName = "com.geely.geely_monjaro_widgets.action.MASSAGE_DRIVER"
    override val topLabelRes = R.string.climate_driver
    override val bottomLabelRes = R.string.climate_massage
    override val activeCircleRes = R.drawable.climate_circle_on_vent

    override fun iconRes(level: Int): Int = when (level) {
        1 -> R.drawable.seat_massage_level1_status
        2 -> R.drawable.seat_massage_level2_status
        3 -> R.drawable.seat_massage_level3_status
        else -> R.drawable.seat_massage_level0_status
    }

    override fun readEncoded(car: IGlyCar): Int {
        // Если главный выключатель массажа (0x10050a00) выключен, считаем уровень = 0
        val switchOn = car.getIntProperty(CarProperties.SEAT_MASSAGE_SWITCH, CarProperties.AREA_SEAT_DRIVER) == 1
        if (!switchOn) return 0
        val raw = car.getIntProperty(CarProperties.SEAT_MASSAGE, CarProperties.AREA_SEAT_DRIVER)
        return if (CarProperties.decodeSeatLevel(raw) > 0) raw else CarProperties.encodeSeatLevel(CarProperties.SEAT_MASSAGE, 1)
    }

    override fun writeEncoded(car: IGlyCar, encoded: Int): Boolean {
        val level = CarProperties.decodeSeatLevel(encoded)
        return if (level == 0) {
            car.setIntProperty(CarProperties.SEAT_MASSAGE_SWITCH, CarProperties.AREA_SEAT_DRIVER, 0)
        } else {
            val okLevel = car.setIntProperty(CarProperties.SEAT_MASSAGE, CarProperties.AREA_SEAT_DRIVER, encoded)
            val okSwitch = car.setIntProperty(CarProperties.SEAT_MASSAGE_SWITCH, CarProperties.AREA_SEAT_DRIVER, 1)
            okLevel || okSwitch
        }
    }
}
