import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

// Release signing is read from keystore.properties (kept out of VCS) or, on CI, from env vars.
val keystoreProperties =
  Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
  }

fun signingValue(key: String, env: String): String? =
  keystoreProperties.getProperty(key) ?: System.getenv(env)

android {
  namespace = "com.lentikr.reminder"
  compileSdk = 37

  defaultConfig {
    // Separate from the 1.x app (com.lentikr.reminder), so the two install side by side.
    applicationId = "app.lentikr.reminder"
    minSdk = 29
    targetSdk = 37
    versionCode = 51
    versionName = "3.7.4"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    val storeFilePath = signingValue("storeFile", "REMINDER_STORE_FILE")
    if (storeFilePath != null) {
      create("release") {
        storeFile = rootProject.file(storeFilePath)
        storePassword = signingValue("storePassword", "REMINDER_STORE_PASSWORD")
        keyAlias = signingValue("keyAlias", "REMINDER_KEY_ALIAS")
        keyPassword = signingValue("keyPassword", "REMINDER_KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    debug {
      // Lets a debug build sit next to the release build on the same device.
      applicationIdSuffix = ".debug"
    }
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.findByName("release")
    }
  }

  // Explicit 64-bit and 32-bit builds plus a universal APK (emulators, x86, sideload).
  splits {
    abi {
      isEnable = true
      reset()
      include("arm64-v8a", "armeabi-v7a")
      isUniversalApk = true
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    buildConfig = true
    aidl = false
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
  compilerOptions {
    optIn.addAll(
      "androidx.compose.material3.ExperimentalMaterial3Api",
      "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
    )
    // Opt in to the upcoming default: annotations on constructor properties (e.g. @DrawableRes,
    // @ApplicationContext) go to both the parameter and the field. See KT-73255.
    freeCompilerArgs.add("-Xannotation-default-target=param-property")
  }
}

// 3.7.3：上游纪念日应用的代码已从仓库移除，Hilt / Room 3 / DataStore / Navigation 3 /
// kotlinx.serialization / tyme4kt / SplashScreen / adaptive 等仅由它使用的依赖一并删除。
// 农历换算由本地 Java 源码 cn.nnci.addintimer.Lunar 提供，不依赖任何第三方库。
dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  // androidTest 的 ui-test-junit4 / ui-test-manifest 版本由 BOM 提供，删掉这行会让 lintDebug
  // 解析 debugAndroidTestRuntimeClasspath 时报「Could not find androidx.compose.ui:ui-test-junit4:」。
  androidTestImplementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)

  // TimerApp 自带 AtkTheme 深色主题，只用基础 Compose + Material3。
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.material3)
  debugImplementation(libs.androidx.compose.ui.tooling)

  // 3.6.4：自动更新 / 协程作用域（Dispatchers.Main + IO）需要 Android 版协程。
  implementation(libs.kotlinx.coroutines.android)

  testImplementation(libs.junit)

  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
}
