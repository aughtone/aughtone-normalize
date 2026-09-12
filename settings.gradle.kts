pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}


dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}


rootProject.name = "AONormalize"
include(":common")
include(":quodlibet")
include(":unicode")
include(":ubilibet")
include(":confusables")
include(":phone")

// Build tooling, never published: it turns the pinned Unicode data into the frozen tables.
include(":tools:ucd-generator")
