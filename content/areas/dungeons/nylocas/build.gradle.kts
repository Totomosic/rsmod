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
    integrationImplementation(projects.api.deathPlugin)
    integrationImplementation(projects.api.gameProcess)
    integrationImplementation(projects.api.combat.combatWeapon)
    integrationImplementation(projects.api.combat.combatCommons)
    integrationImplementation(projects.api.areaChecker)
    integrationImplementation(libs.openrs2.cache)
    integrationImplementation(projects.content.areas.misc.multiways)
}
