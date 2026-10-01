import org.gradle.kotlin.dsl.the
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension

plugins {
    alias(libs.plugins.multiplatformLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply  false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
}

// Ships each module's agent skill in every one of its sources jars, where a consumer's tooling reads it.
// The skill is written at src/commonMain/skills/<name>/, a directory beside the source that no sources jar
// picks up by itself, and lands at commonMain/skills/<name>/ in each: the root and every per-target jar.
subprojects {
    tasks.withType<Zip>()
        .matching { it.name == "sourcesJar" || it.name.endsWith("SourcesJar") }
        .configureEach {
            from("src/commonMain/skills") {
                include("*/SKILL.md", "*/references/**", "*/assets/**")
                into("commonMain/skills")
            }
        }
}

rootProject.plugins.withType<YarnPlugin> {
    rootProject.the<YarnRootExtension>().apply {
        lockFileDirectory = project.rootDir.resolve("gradle/kotlin-js-store")
        yarnLockMismatchReport = org.jetbrains.kotlin.gradle.targets.js.yarn.YarnLockMismatchReport.WARNING
        reportNewYarnLock = false
        yarnLockAutoReplace = true
    }
}
