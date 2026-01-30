import org.gradle.api.publish.maven.MavenPublication

version = "0.2.0"

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.dokka)
    alias(libs.plugins.nmcp)
    alias(libs.plugins.nmcp.aggregation)
    `maven-publish`
    signing
    jacoco
}

nmcpAggregation {
    centralPortal {
        username = System.getenv("CENTRAL_PORTAL_USERNAME")
        password = System.getenv("CENTRAL_PORTAL_PASSWORD")
        publishingType = "USER_MANAGED"
    }
}

android {
    namespace = "dev.keiji.jp2k"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    nmcpAggregation(project)

    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.javascriptengine)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// Register a custom Javadoc Jar task using Dokka's HTML output
// Note: dokkaJavadocJar might exist if configured by plugin, but we explicitly define one here to be sure it uses V2 task
val dokkaJavadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from(tasks.named("dokkaGeneratePublicationHtml"))
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "dev.keiji.jp2k"
            artifactId = "jp2k-decoder-android"

            afterEvaluate {
                from(components["release"])
            }

            artifact(dokkaJavadocJar)

            pom {
                name.set("JPEG2000 Decoder for Android")
                description.set("The library provides functionality to decode JPEG2000 images on Android.")
                url.set("https://github.com/keiji/jp2k-decoder-android")
                licenses {
                    license {
                        name.set("The 2-Clause BSD License")
                        url.set("https://opensource.org/license/bsd-2-clause")
                    }
                }
                developers {
                    developer {
                        id.set("keiji")
                        name.set("ARIYAMA Keiji")
                        email.set("keiji.ariyama@gmail.com")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/keiji/jp2k-decoder-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/keiji/jp2k-decoder-android.git")
                    url.set("https://github.com/keiji/jp2k-decoder-android.git")
                }
            }
        }
    }
}
signing {
    useGpgCmd()
    sign(publishing.publications)
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    val fileFilter = listOf("**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*", "**/*Test*.*")
    val debugTree = layout.buildDirectory.dir("tmp/kotlin-classes/debug").get().asFileTree.matching {
        exclude(fileFilter)
    }
    val mainSrc = android.sourceSets.getByName("main").java.srcDirs

    classDirectories.setFrom(debugTree)
    sourceDirectories.setFrom(files(mainSrc))
    executionData.setFrom(layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"))
}
