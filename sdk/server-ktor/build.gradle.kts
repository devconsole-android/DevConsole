import java.security.MessageDigest

plugins {
    id("devconsole.android.library")
    id("devconsole.quality")
    id("devconsole.publishing")
}

android {
    namespace = "io.devconsole.server.ktor"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
}

dependencies {
    implementation(project(":sdk:api"))
    implementation(project(":sdk:server-api"))
    implementation(project(":sdk:timeline"))
    implementation(project(":sdk:network"))
    implementation(project(":sdk:socket"))
    implementation(project(":sdk:mocks"))
    implementation(project(":sdk:composer"))
    implementation(project(":sdk:push"))
    implementation(project(":sdk:state"))
    implementation(project(":sdk:export"))
    implementation(project(":sdk:storage-api"))
    implementation("io.ktor:ktor-server-core:3.5.2")
    implementation("io.ktor:ktor-server-cio:3.5.2")
    implementation("io.ktor:ktor-server-websockets:3.5.2")
    testImplementation("io.ktor:ktor-server-test-host:3.5.2")
    testImplementation("io.ktor:ktor-client-websockets:3.5.2")
}

val dashboardAssetFiles: List<File> =
    listOf("index.html", "dashboard.css", "dashboard.js")
        .map { name -> layout.projectDirectory.file("src/main/resources/devconsole-web/$name").asFile }
val dashboardManifest = layout.buildDirectory.file("generated/devconsole-web/asset-manifest.json")

val generateDashboardAssetManifest by tasks.registering {
    group = "build"
    description = "Generates a deterministic manifest for packaged DevConsole dashboard assets."
    inputs.files(dashboardAssetFiles)
    outputs.file(dashboardManifest)
    doLast {
        // Reads exclusively via the task's own `inputs`/`outputs` API (not the build-script `val`s
        // above) so this action closure stays free of script object references the configuration
        // cache cannot serialize.
        val entries =
            inputs.files.files.sortedBy { it.name }.joinToString(",") { file ->
                val bytes = file.readBytes()
                val hash =
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest(bytes)
                        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                """{"path":"devconsole-web/${file.name}","sha256":"$hash","bytes":${bytes.size}}"""
            }
        val manifest = """{"assets":[$entries]}"""
        val output = outputs.files.singleFile
        output.parentFile.mkdirs()
        output.writeText(manifest + "\n")
    }
}

tasks.register("verifyDashboardAssets") {
    group = "verification"
    description = "Verifies the generated dashboard asset manifest is deterministic and complete."
    dependsOn(generateDashboardAssetManifest)
    inputs.file(dashboardManifest)
    doLast {
        val manifest = inputs.files.singleFile.readText()
        check(manifest.contains("devconsole-web/index.html")) { "Dashboard index is missing from asset manifest" }
        check(
            manifest.contains("devconsole-web/dashboard.css"),
        ) { "Dashboard stylesheet is missing from asset manifest" }
        check(manifest.contains("devconsole-web/dashboard.js")) { "Dashboard script is missing from asset manifest" }
        check(manifest.contains("\"sha256\":\"")) { "Dashboard asset manifest is missing a digest" }
    }
}

tasks.named("preBuild").configure { dependsOn("verifyDashboardAssets") }
tasks.matching { it.name == "check" }.configureEach { dependsOn("verifyDashboardAssets") }
