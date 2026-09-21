import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
}

// Tests for invariants that hold across the whole suite rather than inside one module - id collisions,
// comparable-form collisions, and resolution through the combined resolver a real caller builds.
//
// It exists because NO published module can see the others: each depends on `:common` and on whatever it
// genuinely needs, which is the point of the module layout (ADR-0003) and also means there is no vantage
// point from which to ask a question about the suite. This module is that vantage point, and nothing else.
//
// Deliberately NOT published: it applies no publishing plugin, so it never reaches a consumer. It carries
// no production code, and nothing may ever depend on it.
//
// JVM-only on purpose. Every invariant here is about ids and form names, which are constants compiled into
// every target identically - so a multiplatform build would run the same assertions several times over the
// same strings and buy nothing. Anything platform-dependent belongs in the module that owns it.

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)

    // Every published module. Adding a module to the suite means adding it here, or its ids are never
    // checked against anyone else's.
    testImplementation(project(":common"))
    testImplementation(project(":quodlibet"))
    testImplementation(project(":ubilibet"))
    testImplementation(project(":unicode"))
    testImplementation(project(":confusables"))
    testImplementation(project(":phone"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
