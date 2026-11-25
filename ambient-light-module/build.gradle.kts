plugins {
    alias(libs.plugins.android.application)
}

val ambientModuleVersionName = "1.9"

android {
    namespace = "com.geely.monjaro.ambienthook"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.geely.monjaro.ambienthook"
        minSdk = 28
        targetSdk = 36
        versionCode = 9
        versionName = ambientModuleVersionName
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName?.set("ambient-light-module-$ambientModuleVersionName.apk")
        }
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
