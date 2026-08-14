plugins {
    id("devconsole.android.library")
    id("devconsole.quality")
    id("devconsole.publishing")
}

android { namespace = "io.devconsole.remoteconfig.firebase.noop" }

dependencies {
    api(project(":sdk:remote-config"))
}
