import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig
import org.jetbrains.kotlin.gradle.tasks.KotlinCompileCommon

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.multiplatformLibrary)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = libs.versions.group.get()
version = libs.versions.versionName.get()

//noinspection WrongGradleMethod
kotlin {
    jvmToolchain(17)

    jvm()

    android {
        namespace = "io.github.aughtone.normalize.common"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // See: https://kotlinlang.org/docs/js-project-setup.html
    js {
        browser {
            generateTypeScriptDefinitions()
            webpackTask {
                output.libraryTarget = "commonjs2"
            }
        }
        useEsModules() // Enables ES2015 modules
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    static = (static ?: mutableListOf()).apply {
                        // Serve sources to debug inside browser
                        add(rootDirPath)
                        add(projectDirPath)
                    }
                }
            }
        }
    }
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "AONormalizeCommon"
            isStatic = true
            binaryOption(
                "bundleId",
                "io.github.aughtone.normalize.common"
            )
            binaryOption(
                "bundleShortVersionString",
                libs.versions.versionName.get()
            )
        }
    }

    linuxX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.aughtone.types)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }

    // XXX Remove when this is resolved. This is a workaround.
    //  https://youtrack.jetbrains.com/issue/KT-66568/w-KLIB-resolver-The-same-uniquename...-found-in-more-than-one-library
    metadata {
        compilations.all {
            val compilationName = rootProject.name
            compileTaskProvider.configure {
                if (this is KotlinCompileCommon) {
                    moduleName = "${project.group}:${project.name}_$compilationName"
                }
            }
        }
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)

    if (!project.hasProperty("skip-signing")) {
        signAllPublications()
    }

    coordinates("io.github.aughtone.normalize", "common", version.toString())

    pom {
        name = "Aught One Normalize Common"
        description = "Shared contracts for the Aught One Normalize suite."
        inceptionYear = "2026"
        url = "https://github.com/aughtone/aughtone-normalize"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0"
                distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "bpappin"
                name = "bpappin"
                url = "https://github.com/bpappin"
            }
        }
        scm {
            url = "https://github.com/aughtone/aughtone-normalize"
            connection = "https://github.com/aughtone/aughtone-normalize.git"
            developerConnection = "git@github.com:aughtone/aughtone-normalize.git"
        }
    }
}
