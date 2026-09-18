import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 자체 배포 앱은 서명 키가 고정돼야 한다. 키가 바뀌면 기존 설치본에 덮어쓸 수 없다.
// 키와 비밀번호는 저장소에 올리지 않고 local.properties 에서만 읽는다.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val releaseStore = localProps.getProperty("releaseStoreFile")?.let { file(it) }

android {
    namespace = "com.yms.idphoto"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yms.idphoto"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStore != null && releaseStore.exists()) {
            create("release") {
                storeFile = releaseStore
                storePassword = localProps.getProperty("releaseStorePassword")
                keyAlias = localProps.getProperty("releaseKeyAlias")
                keyPassword = localProps.getProperty("releaseKeyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    // ML Kit 모델이 ABI 별 네이티브 라이브러리로 들어가 APK 가 커진다.
    // 배포는 AAB 를 쓰고, 직접 설치용 APK 를 줄이고 싶을 때만 -PabiSplit 으로 켠다.
    // (기본값을 켜 두면 x86_64 에뮬레이터에 설치할 수 없어 개발이 불편해진다.)
    splits {
        abi {
            isEnable = project.hasProperty("abiSplit")
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.mlkit.face.detection)
    implementation(libs.mlkit.segmentation.selfie)

    testImplementation(libs.junit)
    // 안드로이드 JSONObject 를 JVM 테스트에서도 쓰기 위한 실제 구현
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}
