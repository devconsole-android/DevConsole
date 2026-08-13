plugins {
    id("devconsole.android.library")
    id("devconsole.quality")
    id("devconsole.publishing")
}
android {
    namespace = "io.devconsole.remoteconfig.firebase"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
}
// `api`, not `implementation`: the adapter implements the public RemoteConfigProvider interface and
// hosts must name that type to hand the adapter to DevConsoleConfig.
dependencies { api(project(":sdk:remote-config")) }
