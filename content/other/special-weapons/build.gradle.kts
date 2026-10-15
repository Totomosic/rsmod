plugins {
    id("base-conventions")
    id("integration-test-suite")
}

dependencies {
    implementation(projects.api.combat.combatManager)
    implementation(projects.api.pluginCommons)
    implementation(projects.api.weapons)
    integrationImplementation(projects.api.hitPlugin)
    integrationImplementation(projects.api.pluginCommons)
    integrationImplementation(projects.api.weapons)
}
