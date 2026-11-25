import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** A properties file from the project root, or nothing if it isn't there. Both are ignored by git. */
fun rootProperties(name: String) = Properties().apply {
    rootProject.file(name).takeIf { it.exists() }?.inputStream()?.use(::load)
}

// Machine-local settings: the SDK path, and optionally a WebDAV server for the device tests.
val localProperties = rootProperties("local.properties")

// Release signing reads keystore.properties, or on CI the KOBBLO_* environment variables. With
// neither, release builds stay unsigned, which is what F-Droid expects.
val signingProperties = rootProperties("keystore.properties")

fun signingValue(property: String, environment: String): String? =
    signingProperties.getProperty(property) ?: System.getenv(environment)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "KOBBLO_KEYSTORE_FILE")

android {
    namespace = "com.kobblo.gallery.file.explorer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.kobblo.gallery.file.explorer"
        minSdk = 26
        // Targeting 37 would need the ACCESS_LOCAL_NETWORK runtime permission, which the server
        // connections and Nearby Share don't ask for yet.
        targetSdk = 36
        versionCode = 100
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("storePassword", "KOBBLO_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KOBBLO_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KOBBLO_KEY_PASSWORD")
            }
        }
    }

    // Google's dependency report is encrypted for Google alone, so open-source stores refuse APKs that carry it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        debug {
            // Where WebDavClientInstrumentedTest finds a server. Empty unless local.properties names one.
            fun testServer(key: String) = "\"${localProperties.getProperty(key, "")}\""
            buildConfigField("String", "TEST_WEBDAV_URL", testServer("testWebdavUrl"))
            buildConfigField("String", "TEST_WEBDAV_USER", testServer("testWebdavUser"))
            buildConfigField("String", "TEST_WEBDAV_PASSWORD", testServer("testWebdavPassword"))
            buildConfigField("boolean", "TEST_WEBDAV_ALLOW_SELF_SIGNED", localProperties.getProperty("testWebdavAllowSelfSigned", "false"))
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            optimization {
                enable = true
            }
            // x86 is only for emulators. Debug builds keep it, and each CPU type here costs about 13 MB.
            ndk {
                abiFilters += setOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    lint {
        // A call that's newer than minSdk with no guard is a crash on those phones, so a release build refuses it.
        fatal += "NewApi"
    }

    packaging {
        jniLibs {
            // Native libraries are stored compressed and unpacked at install.
            useLegacyPackaging = true
            // The smart features library's subtitle module, which the app doesn't offer. It's loaded only if asked for, and missing is fine.
            excludes += "**/liblumen_speech.so"
        }
        resources {
            // Several libraries ship files with these same names, and the APK can hold only one of each.
            excludes += setOf(
                "META-INF/ASL2.0",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                // Tables for Picnic, a post-quantum signature nothing here uses: 1.2 MB once compressed.
                "org/bouncycastle/pqc/crypto/picnic/*.properties",
            )
        }
    }
}

configurations.all {
    // Bouncy Castle comes in twice, once for each range of Java versions: sshj asks for the newer
    // "jdk18on" artifacts and smbj for the older "jdk15to18" ones. They hold the same classes, so only
    // the newer set may stay.
    for (module in listOf("bcprov", "bcpkix", "bcutil")) {
        exclude(group = "org.bouncycastle", module = "$module-jdk15to18")
    }

    // XML pull parsers that Android already has built in.
    exclude(group = "xpp3", module = "xpp3")
    exclude(group = "xpp3", module = "xpp3_min")
    exclude(group = "xmlpull", module = "xmlpull")
}

dependencies {
    // Screens: Compose with Material 3, and the AndroidX pieces around it.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.compose.material3.windowsizeclass)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.window)
    implementation(libs.backdrop)
    implementation(libs.shapes)
    implementation(libs.kotlinx.collections.immutable)

    // Photos, video and music.
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.coil.video)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.print)

    // Smart Search, text in photos and people, all on the phone. It needs Android 11, so the manifest
    // overrides its minSdk and SmartFeatures keeps every call behind that check.
    implementation(libs.lumen.ai)

    // Files on the phone, with elevated access through Shizuku where the person allows it.
    implementation(libs.androidx.documentfile)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Servers: FTP, SMB, SFTP and WebDAV.
    implementation(libs.commons.net)
    implementation(libs.smbj)
    implementation(libs.sshj)
    implementation(libs.sardine.android)
    implementation(libs.okhttp)

    // Cloud storage: Dropbox, and Google Drive with Google sign-in.
    implementation(libs.dropbox.core.sdk)
    implementation(libs.google.api.client.android)
    implementation(libs.google.api.services.drive) {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation(libs.google.play.services.auth)
    implementation(libs.kotlinx.coroutines.play.services)

    // Sending between devices: the embedded HTTP server, and QR codes with the camera to scan them.
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Archives and documents. Commons Compress handles tar, gzip, bzip2 and 7z, and decodes xz and
    // 7z's default LZMA through the xz library.
    implementation(libs.zip4j)
    implementation(libs.unrar5j)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.pdfbox.android)

    // Encryption: the Vault, saved passwords, certificates for sending, and the biometric prompt.
    implementation(libs.tink.android)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.bouncycastle.bcprov)

    // Saved data and general helpers.
    implementation(libs.gson)
    implementation(libs.guava)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
