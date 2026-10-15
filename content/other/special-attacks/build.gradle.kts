plugins {
    id("base-conventions")
    id("integration-test-suite")
}

dependencies {
    implementation(projects.api.pluginCommons)
    implementation(projects.api.specials)
    integrationImplementation(projects.api.combat.combatFormulas)
    integrationImplementation(libs.fastutil)
    integrationImplementation(projects.api.hitPlugin)
    integrationImplementation(projects.api.pluginCommons)
    integrationImplementation(projects.api.specials)
}

// These game tests share a server instance and its listening sockets.
tasks.named<Test>("integration") {
    systemProperty("junit.jupiter.execution.parallel.enabled", false)
}
