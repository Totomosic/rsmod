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
    integrationImplementation(projects.api.spellsRunes)
    integrationImplementation(libs.openrs2.cache)
}
