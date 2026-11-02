import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

val sdkVersion = providers.gradleProperty("dak.version").get()
group = providers.gradleProperty("dak.group").get()
version = sdkVersion

android {
    namespace = providers.gradleProperty("dak.namespace").get()
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // The JVM's HttpURLConnection has no PATCH (Android's has); the
                // SDK sets it by reflection there, which needs this.
                it.jvmArgs("--add-opens=java.base/java.net=ALL-UNNAMED")
                it.testLogging {
                    events("failed")
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
            }
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        // Apps on Kotlin 2.0 and newer can use the SDK.
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.core)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.mockwebserver)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// Local only (./gradlew publishToMavenLocal). Nothing is published anywhere
// else until the names are final.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = providers.gradleProperty("dak.group").get()
            artifactId = providers.gradleProperty("dak.artifact").get()
            version = sdkVersion
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("DakPush Android SDK")
                description.set("Android SDK for DakPush, a self-hosted push notification server.")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        name.set("Md Shoriful Haque Hridoy")
                    }
                }
            }
        }
    }
}
