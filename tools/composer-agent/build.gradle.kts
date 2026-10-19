plugins {
    id("devconsole.kotlin.jvm")
    id("devconsole.quality")
    application
}

application {
    mainClass.set("io.devconsole.agent.MainKt")
    applicationName = "devconsole-agent"
}
