plugins {
    id("base-conventions")
    id("integration-test-suite")
}

dependencies {
    implementation(projects.api.pluginCommons)
    implementation(projects.api.spells)
    integrationImplementation(projects.api.pluginCommons)
    integrationImplementation(projects.api.spells)
    integrationImplementation(projects.api.hitPlugin)
    integrationImplementation(projects.api.registry)
    integrationImplementation(projects.api.spellsRunes)
    integrationImplementation(projects.api.net)
    integrationImplementation(projects.content.skills.magic.teleports)
    integrationImplementation(libs.openrs2.cache)
}
