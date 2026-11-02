import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Firebase is set up only with a google-services.json for the example's
// package (git-ignored). Without one the app runs, but gets no push token.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

/** A line in local.properties (git-ignored), or a Gradle property, or [default]. */
fun setting(name: String, default: String): String {
    val local = rootProject.file("local.properties")
    if (local.exists()) {
        val props = Properties().apply { local.inputStream().use { load(it) } }
        props.getProperty(name)?.let { return it }
    }
    return providers.gradleProperty(name).orNull ?: default
}

android {
    namespace = "${providers.gradleProperty("dak.namespace").get()}.example"
    compileSdk = 36

    defaultConfig {
        applicationId = setting("dak.exampleApplicationId", "io.github.hridoyrocks.dak.example")
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = providers.gradleProperty("dak.version").get()
        // Your Dak server and App ID, in local.properties:
        //   dak.exampleAppId=0190…
        //   dak.exampleBaseUrl=https://push.example.com
        // The default URL is a DakPush server on this computer, as the emulator sees it.
        buildConfigField("String", "DAK_APP_ID", "\"${setting("dak.exampleAppId", "")}\"")
        buildConfigField("String", "DAK_BASE_URL", "\"${setting("dak.exampleBaseUrl", "http://10.0.2.2:8080")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        // A demo screen in English only.
        disable += setOf("HardcodedText", "SetTextI18n", "ButtonStyle")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":dak"))
    implementation(libs.androidx.activity)
}
