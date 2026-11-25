package com.geely.monjaro.ambienthook;

import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Color;
import android.media.audiofx.Visualizer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Модуль Monjaro Ambient Light Pro v9 для Geely Monjaro (GMC firmware).
 * Использует реальные свойства каналов подсветки из CarService/ECarXCarService (0x2140a8bb/a8b9/a8b8/a8ba,
 * 0x2140a9dc/a9da/a9d9/a9db, 0x2140a084), кэшированный вызов setIntProperty без лишней рефлексии
 * в горячем цикле, быструю плавную смену оттенков без спама Settings.Global на каждом тике
 * и мгновенное переключение режимов через поколение задачи (epoch).
 */
public class AmbientLightHook implements IXposedHookLoadPackage {

    private static final String TAG = "MonjaroAmbientHook";
    private static final Set<String> TARGET_PKGS = new HashSet<>(Arrays.asList(
            "com.geely.settings",
            "com.geely.hmi.settings",
            "com.ecarx.settings"
    ));

    public static final String KEY_MODE = "monjaro_ambient_mode";
    public static final String KEY_SPEED = "monjaro_ambient_speed";
    public static final String KEY_DOOR = "monjaro_ambient_door";
    public static final String KEY_REV = "monjaro_ambient_rev";
    public static final String KEY_FUEL = "monjaro_ambient_fuel";
    public static final String KEY_CMD = "monjaro_ambient_cmd";
    public static final String KEY_STATUS = "monjaro_ambient_status";

    public static final String ACTION_AMBIENT_CMD = "com.geely.monjaro.ambienthook.ACTION_CMD";
    public static final String ACTION_AMBIENT_STATUS = "com.geely.monjaro.ambienthook.ACTION_STATUS";

    // Реальные ID свойств из CarService.apk / ECarXCarService.apk (VehicleProperty)
    private static final int PROP_AMBLI_MOD_REQ = 0x2140a084;             // VEH_AMBLIMODREQ
    private static final int PROP_SCENEMOD_AMBLI_SETG = 0x2140a8b7;      // VEH_SCENEMODAMBLISETG
    private static final int PROP_ZONE1_BLUE = 0x2140a8b8;               // VEH_SCENEMODZONE1SETTINGSBLUE
    private static final int PROP_ZONE1_GREEN = 0x2140a8b9;              // VEH_SCENEMODZONE1SETTINGSGREEN
    private static final int PROP_ZONE1_INTENSITY = 0x2140a8ba;          // VEH_SCENEMODZONE1SETTINGSINTENSITY
    private static final int PROP_ZONE1_RED = 0x2140a8bb;                // VEH_SCENEMODZONE1SETTINGSRED
    private static final int PROP_ZONE1_STATUS = 0x2140a8bc;             // VEH_SCENEMODZONE1SETTINGSZONESTATUS

    private static final int PROP_STO_BLUE = 0x2140a9d9;                 // VEH_STOAMBLISTNGBLUE
    private static final int PROP_STO_GREEN = 0x2140a9da;                // VEH_STOAMBLISTNGGREEN
    private static final int PROP_STO_INTENSITY = 0x2140a9db;            // VEH_STOAMBLISTNGINTENSITY
    private static final int PROP_STO_RED = 0x2140a9dc;                  // VEH_STOAMBLISTNGRED

    // Легаси/резервные ID на случай другой ветки HAL
    private static final int LEGACY_PROP_COLOR = 0x2a500000;
    private static final int LEGACY_PROP_BRI = 0x2a010100;

    private static final int ZONE_ALL = Integer.MIN_VALUE; // 0x80000000
    private static final int AREA_AMBIENT_ALL = 0x200a0100;

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicInteger EPOCH = new AtomicInteger(0);

    private volatile Context appContext;
    private volatile ClassLoader targetClassLoader;
    private volatile String hookedPkgName = "";
    private volatile Object glyCarInstance;
    private volatile Method cachedSetIntMethod;
    private volatile Method cachedSetFloatMethod;
    private volatile Method cachedGetIntMethod;
    private volatile Method cachedGetSensorMethod;
    private volatile Object lightingShowInstance;

    private volatile String activeMode = "off";
    private volatile int stepDelayMs = 160;
    private volatile boolean doorAlertEnabled = false;
    private volatile boolean reverseAlertEnabled = false;
    private volatile boolean fuelAlertEnabled = false;
    private volatile String lastProcessedCmd = "";

    private volatile boolean solidModeInitialized = false;
    private volatile int activeAreaId = AREA_AMBIENT_ALL;
    private volatile float hue = 0f;
    private volatile int tick = 0;
    private volatile boolean policePhase = false;
    private volatile boolean lowFuelTriggered = false;
    private volatile int lowFuelFlashTicks = 0;
    private volatile float audioEnergy = 0f;

    private Visualizer visualizer;
    private final ScheduledExecutorService animScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MonjaroAmbientAnim");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> animTask;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!TARGET_PKGS.contains(lpparam.packageName)) {
            return;
        }
        final String pkg = lpparam.packageName;
        final ClassLoader cl = lpparam.classLoader;
        XposedBridge.log(TAG + ": handleLoadPackage -> " + pkg);

        try {
            XposedHelpers.findAndHookMethod(
                    Application.class, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Application app = (Application) param.thisObject;
                            if (app != null) {
                                initOnce(app.getApplicationContext(), cl, pkg);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Application.onCreate failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    Service.class, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Service svc = (Service) param.thisObject;
                            if (svc != null && svc.getApplicationContext() != null) {
                                initOnce(svc.getApplicationContext(), cl, pkg);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Service.onCreate failed: " + t);
        }
    }

    private void initOnce(Context ctx, ClassLoader cl, String pkg) {
        if (ctx == null) return;
        this.appContext = ctx;
        this.targetClassLoader = cl;
        if (this.hookedPkgName.isEmpty()) {
            this.hookedPkgName = pkg;
        }

        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        XposedBridge.log(TAG + ": initializing v9 inside " + pkg);

        try {
            IntentFilter filter = new IntentFilter(ACTION_AMBIENT_CMD);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    String cmd = intent.getStringExtra("cmd");
                    handleExternalCommand(cmd, true);
                }
            };
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(receiver, filter);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": registerReceiver failed: " + t);
        }

        try {
            ContentResolver cr = ctx.getContentResolver();
            Handler mainHandler = new Handler(Looper.getMainLooper());
            ContentObserver observer = new ContentObserver(mainHandler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    syncConfigFromGlobal(false);
                }
            };
            String[] keys = {KEY_MODE, KEY_SPEED, KEY_DOOR, KEY_REV, KEY_FUEL, KEY_CMD};
            for (String k : keys) {
                cr.registerContentObserver(Settings.Global.getUriFor(k), false, observer);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ContentObserver failed: " + t);
        }

        syncConfigFromGlobal(true);
        scheduleAnimationLoop();
    }

    private synchronized void scheduleAnimationLoop() {
        if (animTask != null) {
            animTask.cancel(false);
            animTask = null;
        }
        int delay = Math.max(110, Math.min(1200, stepDelayMs));
        animTask = animScheduler.scheduleWithFixedDelay(this::onAnimationTick, 120L, delay, TimeUnit.MILLISECONDS);
    }

    private void syncConfigFromGlobal(boolean publishDiag) {
        Context ctx = this.appContext;
        if (ctx == null) return;
        try {
            ContentResolver cr = ctx.getContentResolver();
            String mode = Settings.Global.getString(cr, KEY_MODE);
            String spdStr = Settings.Global.getString(cr, KEY_SPEED);
            String doorStr = Settings.Global.getString(cr, KEY_DOOR);
            String revStr = Settings.Global.getString(cr, KEY_REV);
            String fuelStr = Settings.Global.getString(cr, KEY_FUEL);
            String cmd = Settings.Global.getString(cr, KEY_CMD);

            boolean modeChanged = false;
            if (mode != null && !mode.isEmpty() && !mode.equals(activeMode)) {
                activeMode = mode;
                modeChanged = true;
                EPOCH.incrementAndGet();
                solidModeInitialized = false;
                tick = 0;
            }

            if (spdStr != null) {
                try {
                    int parsed = Integer.parseInt(spdStr.trim());
                    // Поддерживаем плавный шаг от 120 мс до 1200 мс (в UI по умолчанию 180 мс)
                    int clamped = Math.max(120, Math.min(1200, parsed));
                    if (clamped != stepDelayMs) {
                        stepDelayMs = clamped;
                        scheduleAnimationLoop();
                    }
                } catch (NumberFormatException ignored) {
                }
            }

            doorAlertEnabled = "1".equals(doorStr) || "true".equalsIgnoreCase(doorStr);
            reverseAlertEnabled = "1".equals(revStr) || "true".equalsIgnoreCase(revStr);
            fuelAlertEnabled = "1".equals(fuelStr) || "true".equalsIgnoreCase(fuelStr);

            if (cmd != null && !cmd.isEmpty() && !cmd.equals(lastProcessedCmd)) {
                lastProcessedCmd = cmd;
                String baseCmd = cmd.contains(":") ? cmd.substring(0, cmd.indexOf(':')) : cmd;
                handleExternalCommand(baseCmd, false);
            } else if (publishDiag || modeChanged) {
                publishStatus(buildDiagnosticStatus(targetClassLoader));
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": syncConfigFromGlobal error: " + t);
        }
    }

    private void handleExternalCommand(String cmd, boolean fromBroadcast) {
        if (cmd == null || cmd.isEmpty()) return;
        if (fromBroadcast) {
            syncConfigFromGlobal(false);
        }
        ClassLoader cl = targetClassLoader;
        if (cl == null && appContext != null) {
            cl = appContext.getClassLoader();
        }
        if ("probe_status".equals(cmd)) {
            publishStatus(buildDiagnosticStatus(cl));
        } else if ("apply_config".equals(cmd)) {
            EPOCH.incrementAndGet();
            solidModeInitialized = false;
            publishStatus(buildDiagnosticStatus(cl) + "
Применён режим: " + activeMode + " (" + stepDelayMs + " мс)");
        } else if ("lightshow_builtin".equals(cmd)) {
            publishStatus(triggerBuiltInLightShow(cl));
        } else if ("lightshow_script".equals(cmd)) {
            publishStatus(triggerDemoScriptLightShow(cl));
        } else if ("lightshow_stop".equals(cmd)) {
            activeMode = "off";
            EPOCH.incrementAndGet();
            solidModeInitialized = false;
            publishStatus(stopBuiltInLightShow(cl));
        }
    }

    private void onAnimationTick() {
        final int myEpoch = EPOCH.get();
        try {
            Context ctx = this.appContext;
            ClassLoader cl = this.targetClassLoader;
            if (ctx == null || cl == null) return;

            Object car = ensureCarInstance(cl);
            if (car == null) return;

            if (doorAlertEnabled && isAnyDoorOrTrunkOpen(car)) {
                if (myEpoch != EPOCH.get()) return;
                initSolidOnceIfNeeded(car);
                int pulse = ((tick % 2) == 0) ? Color.rgb(255, 25, 25) : Color.rgb(120, 0, 0);
                writeRgbChannels(car, pulse, 100);
                tick++;
                return;
            }

            if (reverseAlertEnabled && isReverseGear(car)) {
                if (myEpoch != EPOCH.get()) return;
                initSolidOnceIfNeeded(car);
                int pulse = ((tick % 2) == 0) ? Color.rgb(255, 255, 255) : Color.rgb(255, 160, 0);
                writeRgbChannels(car, pulse, 100);
                tick++;
                return;
            }

            if (fuelAlertEnabled) {
                float fuelPct = readSensorFloat(car, 0x101300);
                if (fuelPct > 0.01f && fuelPct <= 15f) {
                    if (!lowFuelTriggered) {
                        lowFuelTriggered = true;
                        lowFuelFlashTicks = 8;
                    }
                    if (lowFuelFlashTicks > 0) {
                        if (myEpoch != EPOCH.get()) return;
                        lowFuelFlashTicks--;
                        initSolidOnceIfNeeded(car);
                        int bri = ((lowFuelFlashTicks % 2) == 0) ? 95 : 25;
                        writeRgbChannels(car, Color.rgb(255, 110, 0), bri);
                        tick++;
                        return;
                    }
                } else if (fuelPct >= 15f) {
                    lowFuelTriggered = false;
                }
            }

            String mode = activeMode;
            if (mode == null || "off".equals(mode)) {
                stopVisualizer();
                solidModeInitialized = false;
                return;
            }

            if ("music".equals(mode)) {
                ensureVisualizerStarted();
            } else {
                stopVisualizer();
            }

            if (myEpoch != EPOCH.get()) return;
            initSolidOnceIfNeeded(car);

            int rgb;
            int brightness;
            switch (mode) {
                case "rainbow": {
                    rgb = Color.HSVToColor(new float[]{hue, 1.0f, 1.0f});
                    brightness = 90;
                    hue = (hue + 8f) % 360f;
                    break;
                }
                case "music": {
                    float e = Math.max(0f, Math.min(1f, audioEnergy));
                    float dynHue = (220f + e * 160f) % 360f;
                    rgb = Color.HSVToColor(new float[]{dynHue, 1.0f, 1.0f});
                    brightness = Math.round(25f + e * 75f);
                    break;
                }
                case "speed_rpm": {
                    float spd = readSpeedKmh(car);
                    float rpm = readEngineRpm(car);
                    float factor = Math.max(
                            Math.max(0f, Math.min(1f, spd / 130f)),
                            Math.max(0f, Math.min(1f, (rpm - 800f) / 3700f))
                    );
                    float targetHue = Math.max(0f, Math.min(210f, 210f * (1f - factor)));
                    rgb = Color.HSVToColor(new float[]{targetHue, 1.0f, 1.0f});
                    brightness = Math.round(65f + factor * 35f);
                    break;
                }
                case "climate": {
                    float temp = readCabinTargetTemp(car);
                    float ratio = Math.max(0f, Math.min(1f, (temp - 18f) / 9f));
                    float targetHue = 200f * (1f - ratio) + 12f * ratio;
                    rgb = Color.HSVToColor(new float[]{targetHue, 0.92f, 1.0f});
                    brightness = 85;
                    break;
                }
                case "biorhythm": {
                    int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
                    if (hour >= 6 && hour <= 10) {
                        rgb = Color.rgb(180, 225, 255);
                        brightness = 85;
                    } else if (hour >= 11 && hour <= 16) {
                        rgb = Color.rgb(220, 250, 245);
                        brightness = 80;
                    } else if (hour >= 17 && hour <= 20) {
                        rgb = Color.rgb(255, 195, 120);
                        brightness = 70;
                    } else {
                        rgb = Color.rgb(255, 95, 30);
                        brightness = 40;
                    }
                    break;
                }
                case "police": {
                    rgb = policePhase ? Color.rgb(255, 0, 20) : Color.rgb(0, 70, 255);
                    brightness = 100;
                    policePhase = !policePhase;
                    break;
                }
                default: {
                    rgb = Color.WHITE;
                    brightness = 75;
                    break;
                }
            }

            if (myEpoch != EPOCH.get()) return;
            writeRgbChannels(car, rgb, brightness);
            tick++;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": tick error: " + t);
        }
    }

    private void initSolidOnceIfNeeded(Object car) {
        if (solidModeInitialized) return;
        solidModeInitialized = true;
        // Активируем пользовательский режим подсветки (VEH_AMBLIMODREQ) и зону 1
        fastSetInt(car, PROP_AMBLI_MOD_REQ, ZONE_ALL, 1);
        fastSetInt(car, PROP_ZONE1_STATUS, activeAreaId, 1);
        fastSetInt(car, PROP_ZONE1_STATUS, 0, 1);
    }

    private void writeRgbChannels(Object car, int argb, int brightness) {
        int r = Color.red(argb);
        int g = Color.green(argb);
        int b = Color.blue(argb);
        int bri = Math.max(10, Math.min(100, brightness));

        // Записываем раздельные каналы R / G / B из таблицы свойств CarService/ECarXCarService
        // для основного блока (0x2140a8bb/a8b9/a8b8) и резервного блока (0x2140a9dc/a9da/a9d9)
        fastSetInt(car, PROP_ZONE1_RED, activeAreaId, r);
        fastSetInt(car, PROP_ZONE1_GREEN, activeAreaId, g);
        fastSetInt(car, PROP_ZONE1_BLUE, activeAreaId, b);

        fastSetInt(car, PROP_STO_RED, activeAreaId, r);
        fastSetInt(car, PROP_STO_GREEN, activeAreaId, g);
        fastSetInt(car, PROP_STO_BLUE, activeAreaId, b);

        if (tick % 3 == 0) {
            fastSetInt(car, PROP_ZONE1_INTENSITY, activeAreaId, bri);
            fastSetInt(car, PROP_STO_INTENSITY, activeAreaId, bri);
        }

        // Резервный упакованный RGB24 для совместимости, если узел слушает 0x2a500000
        if (tick % 2 == 0) {
            int rgb24 = argb & 0x00FFFFFF;
            fastSetInt(car, LEGACY_PROP_COLOR, activeAreaId, rgb24);
        }
    }

    private void fastSetInt(Object car, int propId, int areaId, int value) {
        if (car == null) return;
        try {
            Method m = cachedSetIntMethod;
            if (m == null) {
                m = car.getClass().getMethod("setIntProperty", int.class, int.class, int.class);
                m.setAccessible(true);
                cachedSetIntMethod = m;
            }
            m.invoke(car, propId, areaId, value);
        } catch (Throwable ignored) {
        }
    }

    private int callGetInt(Object car, int propId, int areaId) {
        if (car == null) return 0;
        try {
            Method m = cachedGetIntMethod;
            if (m == null) {
                m = car.getClass().getMethod("getIntProperty", int.class, int.class);
                m.setAccessible(true);
                cachedGetIntMethod = m;
            }
            Object res = m.invoke(car, propId, areaId);
            return (res instanceof Integer) ? (Integer) res : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private float readSensorFloat(Object car, int sensorId) {
        if (car == null) return 0f;
        try {
            Method m = cachedGetSensorMethod;
            if (m == null) {
                m = car.getClass().getMethod("getSensorValue", int.class);
                m.setAccessible(true);
                cachedGetSensorMethod = m;
            }
            Object res = m.invoke(car, sensorId);
            return (res instanceof Float) ? (Float) res : 0f;
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    private boolean isAnyDoorOrTrunkOpen(Object car) {
        int trunk = callGetInt(car, 0x21020100, 0x40);
        if (trunk == 1 || trunk == 2) return true;
        int[] doorProps = new int[]{0x21030100, 0x21020200};
        int[] zones = new int[]{1, 2, 4, 8};
        for (int p : doorProps) {
            for (int z : zones) {
                if (callGetInt(car, p, z) == 1) return true;
            }
        }
        return false;
    }

    private boolean isReverseGear(Object car) {
        int g1 = callGetInt(car, 0x20070500, ZONE_ALL) & 0xFF;
        int g2 = callGetInt(car, 0x20070800, ZONE_ALL) & 0xFF;
        return g1 == 0x02 || g1 == 0x12 || g2 == 0x02 || g2 == 0x12;
    }

    private float readSpeedKmh(Object car) {
        int[] ids = new int[]{0x100100, 0x100200, 0x200100};
        for (int id : ids) {
            float v = readSensorFloat(car, id);
            if (v > 0.1f && !Float.isNaN(v)) {
                return v < 70f ? v * 3.6f : v;
            }
        }
        return 0f;
    }

    private float readEngineRpm(Object car) {
        int[] ids = new int[]{0x100300, 0x100400, 0x200200};
        for (int id : ids) {
            float v = readSensorFloat(car, id);
            if (v >= 400f && v <= 8000f && !Float.isNaN(v)) return v;
        }
        return 800f;
    }

    private float readCabinTargetTemp(Object car) {
        int[] props = new int[]{0x10010100, 0x10020100, 0x10010200};
        for (int p : props) {
            int raw = callGetInt(car, p, 1);
            if (raw >= 150 && raw <= 320) return raw / 10f;
            if (raw >= 15 && raw <= 32) return (float) raw;
        }
        return 22.0f;
    }

    private Object ensureCarInstance(ClassLoader cl) {
        if (glyCarInstance != null) return glyCarInstance;
        try {
            Object mgr = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.geely.settings.util.GlyCarManager", cl),
                    "getInstance");
            if (mgr != null) {
                Object car = XposedHelpers.callMethod(mgr, "getIGlyCar");
                if (car != null) {
                    glyCarInstance = car;
                    cachedSetIntMethod = null;
                    cachedGetIntMethod = null;
                    cachedGetSensorMethod = null;
                }
            }
        } catch (Throwable ignored) {
        }
        if (glyCarInstance == null && appContext != null) {
            try {
                Class<?> glyCarCls = XposedHelpers.findClass("com.geely.os.car.GlyCar", cl);
                Object car = XposedHelpers.callStaticMethod(glyCarCls, "create", appContext);
                if (car != null) {
                    glyCarInstance = car;
                    cachedSetIntMethod = null;
                    cachedGetIntMethod = null;
                    cachedGetSensorMethod = null;
                }
            } catch (Throwable ignored) {
            }
        }
        return glyCarInstance;
    }

    private Object ensureLightingShowInstance(ClassLoader cl) {
        if (lightingShowInstance != null) return lightingShowInstance;
        if (appContext == null || cl == null) return null;
        try {
            Class<?> lsCls = XposedHelpers.findClass("com.geely.os.lighting.GlyLightingShow", cl);
            Object show = XposedHelpers.callStaticMethod(lsCls, "create", appContext);
            if (show != null) {
                lightingShowInstance = show;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": GlyLightingShow.create failed: " + t);
        }
        return lightingShowInstance;
    }

    private String buildDiagnosticStatus(ClassLoader cl) {
        Object car = ensureCarInstance(cl);
        Object show = ensureLightingShowInstance(cl);
        String carStr = (car != null) ? "OK (системный IGlyCar активен)" : "Ожидание инициализации IGlyCar";
        String showStr = "недоступен";
        if (show != null) {
            try {
                Object en = XposedHelpers.callMethod(show, "isLightingShowEnabled");
                Object st = XposedHelpers.callMethod(show, "getLightingShowState");
                showStr = "enabled=" + en + ", state=" + st;
            } catch (Throwable t) {
                showStr = "ошибка: " + t.getMessage();
            }
        }
        return "LSPosed хук v9 активен в " + hookedPkgName + "!
IGlyCar: " + carStr + "
LightingShow: " + showStr
                + "
Режим: " + activeMode + " (" + stepDelayMs + " мс, R/G/B 0x2140a8bb/a8b9/a8b8)";
    }

    private String triggerBuiltInLightShow(ClassLoader cl) {
        Object show = ensureLightingShowInstance(cl);
        if (show == null) return "Системный GlyLightingShow не инициализирован";
        try {
            Object en = XposedHelpers.callMethod(show, "isLightingShowEnabled");
            XposedHelpers.callMethod(show, "setLightingShowType", 1);
            XposedHelpers.callMethod(show, "setLightingShowMode", 1);
            XposedHelpers.callMethod(show, "startLightingShow");
            Object st = XposedHelpers.callMethod(show, "getLightingShowState");
            return "Системный запуск шоу выполнен (enabled=" + en + ", state=" + st + ")";
        } catch (Throwable t) {
            return "Ошибка запуска шоу: " + t.getMessage();
        }
    }

    private String triggerDemoScriptLightShow(ClassLoader cl) {
        Object show = ensureLightingShowInstance(cl);
        if (show == null) return "Системный GlyLightingShow не инициализирован";
        try {
            Object started = XposedHelpers.callMethod(show, "sendLightingShowSourceStart");
            ByteBuffer buf = ByteBuffer.allocate(12 * 11);
            for (int step = 0; step < 12; step++) {
                boolean isOn = (step % 2 == 0);
                buf.put((byte) 1);
                buf.putShort((short) (step * 400));
                buf.put((byte) (isOn ? 1 : 0));
                buf.put((byte) (isOn ? 100 : 0));
                buf.putShort((short) 300);
                buf.putShort((short) 300);
                buf.put((byte) 0);
            }
            Object sent = XposedHelpers.callMethod(show, "sendLightingShowSource", buf.array());
            Object ended = XposedHelpers.callMethod(show, "sendLightingShowSourceEnd");
            XposedHelpers.callMethod(show, "startLightingShow");
            return "Системный скрипт шоу отправлен (start=" + started + ", data=" + sent + ", end=" + ended + ")";
        } catch (Throwable t) {
            return "Ошибка отправки скрипта: " + t.getMessage();
        }
    }

    private String stopBuiltInLightShow(ClassLoader cl) {
        Object show = ensureLightingShowInstance(cl);
        if (show == null) return "Системный GlyLightingShow не инициализирован";
        try {
            XposedHelpers.callMethod(show, "stopLightingShow");
            return "Световое шоу остановлено.";
        } catch (Throwable t) {
            return "Ошибка остановки: " + t.getMessage();
        }
    }

    private void publishStatus(String text) {
        Context ctx = this.appContext;
        if (ctx == null) return;
        try {
            Settings.Global.putString(ctx.getContentResolver(), KEY_STATUS, text);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to write KEY_STATUS: " + t);
        }
        try {
            Intent out = new Intent(ACTION_AMBIENT_STATUS);
            out.setPackage("com.geely.geely_monjaro_widgets");
            out.putExtra("status_text", text);
            ctx.sendBroadcast(out);
        } catch (Throwable ignored) {
        }
    }

    private void ensureVisualizerStarted() {
        if (visualizer != null) return;
        try {
            Visualizer vis = new Visualizer(0);
            vis.setCaptureSize(Visualizer.getCaptureSizeRange()[0]);
            vis.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int samplingRate) {
                    if (waveform == null || waveform.length == 0) return;
                    double sumSq = 0.0;
                    for (byte b : waveform) {
                        int sample = (b & 0xFF) - 128;
                        sumSq += sample * sample;
                    }
                    double rms = Math.sqrt(sumSq / waveform.length) / 128.0;
                    audioEnergy = (float) Math.min(1.0, rms * 2.8);
                }

                @Override
                public void onFftDataCapture(Visualizer v, byte[] fft, int samplingRate) {
                }
            }, Visualizer.getMaxCaptureRate() / 2, true, false);
            vis.setEnabled(true);
            visualizer = vis;
        } catch (Throwable t) {
            audioEnergy = (Math.abs(System.currentTimeMillis() % 1000L - 500L)) / 500f;
        }
    }

    private void stopVisualizer() {
        Visualizer vis = visualizer;
        if (vis == null) return;
        visualizer = null;
        try {
            vis.setEnabled(false);
            vis.release();
        } catch (Throwable ignored) {
        }
    }
}
