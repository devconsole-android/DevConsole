plugins {
    id("devconsole.android.library")
    id("devconsole.quality")
    id("devconsole.publishing")
}

android { namespace = "io.devconsole.push.firebase.noop" }

dependencies {
    implementation(project(":sdk:push"))
}
