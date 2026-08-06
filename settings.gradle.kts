pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DevConsole"

includeBuild("build-logic")
includeBuild("gradle-plugin")
include(":sdk:api")
include(":sdk:core")
include(":sdk:full")
include(":sdk:noop")
include(":sdk:security")
include(":sdk:storage-api")
include(":sdk:storage-room")
include(":sdk:export")
include(":sdk:server-api")
include(":sdk:server-ktor")
include(":sdk:timeline")
include(":sdk:logs")
include(":sdk:network")
include(":sdk:network-okhttp")
include(":sdk:network-okhttp-noop")
include(":sdk:network-ktor")
include(":sdk:socket")
include(":sdk:socket-okhttp")
include(":sdk:socket-okhttp-noop")
include(":sdk:socket-paho")
include(":sdk:socket-paho-noop")
include(":sdk:composer")
include(":sdk:mocks")
include(":sdk:mocks-okhttp")
include(":sdk:mocks-okhttp-noop")
include(":sdk:push")
include(":sdk:push-firebase")
include(":sdk:push-firebase-noop")
include(":sdk:state")
include(":sdk:ui-views")
include(":sdk:ui-compose")
include(":samples:foundation-app")
include(":samples:views-java-app")
include(":samples:compose-app")
