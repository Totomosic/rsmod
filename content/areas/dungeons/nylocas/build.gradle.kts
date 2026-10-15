plugins {
    id("base-conventions")
    id("integration-test-suite")
}

dependencies {
    implementation(projects.api.pluginCommons)
    implementation(projects.api.registry)
    implementation(projects.api.combat.combatFormulas)
    integrationImplementation(projects.api.registry)
    integrationImplementation(projects.api.hitPlugin)
    integrationImplementation(projects.api.combat.combatWeapon)
}
