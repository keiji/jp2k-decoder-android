import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.dokka)
    `maven-publish`
    signing
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
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dokka {
    dokkaSourceSets.register("main") {
        sourceRoots.from(file("src/main/java"))
    }
}

version = "0.1.0"

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
            artifactId = "jp2k-decoder"
            version = "0.1.0"

            afterEvaluate {
                from(components["release"])
            }

            artifact(dokkaJavadocJar)

            pom {
                name.set("JPEG2000 Decoder for Android")
                description.set("This library provides functionality to decode JPEG2000 images on Android.")
                url.set("https://github.com/keiji/jp2k-decoder-android")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
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
                    url.set("https://github.com/keiji/jp2k-decoder-android")
                }
            }
        }
    }
    repositories {
        maven {
            name = "sonatype"
            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials {
                username = System.getenv("OSSRH_USERNAME")
                password = System.getenv("OSSRH_PASSWORD")
            }
        }
    }
}

signing {
    val signingKey = System.getenv("SIGNING_KEY")
    val signingPassword = System.getenv("SIGNING_PASSWORD")
    if (!signingKey.isNullOrEmpty() && !signingPassword.isNullOrEmpty()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["release"])
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.javascriptengine)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
