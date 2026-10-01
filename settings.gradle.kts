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
        mavenLocal()
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
        // TEMPORARY, and it must come out before this is pushed: aughtone-types 4.1.0-SNAPSHOT is
        // published to mavenLocal only, so nothing else can resolve it - CI included. Last in the list, so
        // Central still answers first for everything that is on it.
        //
        // A SNAPSHOT on purpose, not an alpha. The same coordinate is being republished as that library
        // changes, and Gradle treats a release version as immutable once cached - three builds of
        // 4.1.0-alpha1 are cached on this machine, so which bytes a build resolved depended on when it
        // last refreshed. A SNAPSHOT re-resolves, so a green build here means green against what is
        // actually published now.
        mavenLocal()
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
include(":tools:lint-rules")
include(":tools:suite-invariants")
