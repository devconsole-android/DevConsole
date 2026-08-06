plugins {
    id("devconsole.kotlin.jvm")
    id("devconsole.quality")
    id("devconsole.publishing")
}

dependencies {
    api(project(":sdk:security"))
}
