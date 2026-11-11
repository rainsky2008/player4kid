import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    // 按 UTF-8 读，local.properties 里可以写中文
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
}

/**
 * 公开版：./gradlew assembleRelease -PpublicBuild=true
 * 不把 local.properties 里的 AppKey / SecretKey 编进安装包（发给别人用时用这个），用户装好后在家长设置里填自己的。
 */
val publicBuild = providers.gradleProperty("publicBuild").orNull == "true"

fun configValue(name: String): String =
    (localProps.getProperty(name) ?: providers.gradleProperty(name).orNull ?: "")
        .replace("\\", "\\\\").replace("\"", "\\\"")

fun secretValue(name: String): String = if (publicBuild) "" else configValue(name)

android {
    namespace = "com.netdisk.player4kid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.netdisk.player4kid"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"

        // 百度网盘开放平台应用凭证，可在 local.properties 中配置，也可在 App 的家长设置中填写（公开版不带）
        buildConfigField("String", "BAIDU_APP_KEY", "\"${secretValue("baidu.appKey")}\"")
        buildConfigField("String", "BAIDU_SECRET_KEY", "\"${secretValue("baidu.secretKey")}\"")

        // 默认的视频服务器（可选）：只在自己的 local.properties 里配置，
        // 代码仓库里不放任何服务器地址；公开版也不带。例如：
        //   web.defaultUrl=http://服务器地址/
        //   web.defaultName=卡片名称
        buildConfigField("String", "DEFAULT_WEB_URL", "\"${secretValue("web.defaultUrl")}\"")
        buildConfigField("String", "DEFAULT_WEB_NAME", "\"${configValue("web.defaultName")}\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 未配置正式签名时用 debug 签名，方便直接安装
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("com.github.bumptech.glide:glide:4.16.0")

    testImplementation("junit:junit:4.13.2")
}
