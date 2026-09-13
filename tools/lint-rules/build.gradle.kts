import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlinJvm)
}

// Lint checks bundled into the :unicode Android artifact through lintPublish. Deliberately NOT published
// on its own: it applies no publishing plugin, so it only ever reaches a consumer inside that AAR.

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Lint loads these classes beside its own, older Kotlin runtime, so they must not need a newer one.
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
    }
}

dependencies {
    compileOnly(libs.lint.api)

    testImplementation(libs.lint.api)
    testImplementation(libs.lint.tests)
    testImplementation(libs.junit)
    // The rank table is checked against real policies, so the tests fail when it drifts from the runtime.
    testImplementation(project(":unicode"))
}

tasks.jar {
    manifest {
        attributes("Lint-Registry-v2" to "io.github.aughtone.normalize.tools.lint.NormalizeIssueRegistry")
    }
}
