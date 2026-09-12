import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
}

// Build tooling. It is deliberately NOT published: it applies no publishing plugin, so
// `publishToMavenCentral` never sees it and it can never reach a consumer's dependency graph.

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

/** Where the pinned Unicode data lives. Generated sources go to the module that carries each table. */
val ucdDirectory = rootProject.layout.projectDirectory.dir("ucd/17.0.0")
val repositoryRoot = rootProject.layout.projectDirectory

/**
 * Regenerate the frozen tables from the pinned Unicode data. Run by a human when moving to a new
 * Unicode release; the output is checked in, so an ordinary build never runs it.
 */
val generateUnicodeTables by tasks.registering(JavaExec::class) {
    group = "unicode"
    description = "Regenerate the frozen Unicode tables from the pinned UCD files."
    mainClass.set("io.github.aughtone.normalize.tools.ucd.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf(ucdDirectory.asFile.absolutePath, repositoryRoot.asFile.absolutePath)
}

/**
 * Prove the checked-in tables are what the pinned data produces. Wired into `check`, so a table
 * edited by hand, or left behind when the data moved, fails the build rather than shipping.
 */
val verifyUnicodeTables by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Fail if the checked-in Unicode tables differ from what the pinned UCD produces."
    mainClass.set("io.github.aughtone.normalize.tools.ucd.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf(ucdDirectory.asFile.absolutePath, repositoryRoot.asFile.absolutePath, "--verify")
}

tasks.named("check") {
    dependsOn(verifyUnicodeTables)
}
