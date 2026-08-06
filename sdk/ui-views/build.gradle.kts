plugins {
    id("devconsole.android.library")
    id("devconsole.quality")
    id("devconsole.publishing")
}

android { namespace = "io.devconsole.ui.views" }

dependencies {
    api(project(":sdk:api"))
}
