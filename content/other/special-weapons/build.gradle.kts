plugins {
    id("base-conventions")
    id("integration-test-suite")
}

dependencies {
    implementation(projects.api.combat.combatManager)
    implementation(projects.api.pluginCommons)
    implementation(projects.api.specials)
    implementation(projects.api.weapons)
    integrationImplementation(projects.api.combat.combatFormulas)
    integrationImplementation(projects.api.combat.combatScripts)
    integrationImplementation(projects.api.hitPlugin)
    integrationImplementation(projects.api.invPlugin)
    integrationImplementation(projects.api.pluginCommons)
    integrationImplementation(projects.api.registry)
    integrationImplementation(projects.api.specials)
    integrationImplementation(projects.api.spells)
    integrationImplementation(projects.api.weapons)
}

// These game tests share a server instance and its listening sockets.
tasks.named<Test>("integration") {
    systemProperty("junit.jupiter.execution.parallel.enabled", false)
}
