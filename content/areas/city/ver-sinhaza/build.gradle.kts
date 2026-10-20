plugins {
    id("base-conventions")
    id("integration-test-suite")
}

dependencies {
    implementation(projects.api.pluginCommons)
    implementation(projects.api.registry)
    implementation(projects.content.areas.dungeons.nylocas)
    implementation(projects.content.interfaces.characterCreator)
    implementation(projects.content.other.commands)
    integrationImplementation(projects.api.net)
    integrationImplementation(projects.api.parsers.json)
    integrationImplementation(projects.api.gameProcess)
    integrationImplementation(projects.api.registry)
    integrationImplementation(projects.content.areas.dungeons.nylocas)
    integrationImplementation(projects.content.interfaces.bank)
    integrationImplementation(projects.content.interfaces.characterCreator)
    integrationImplementation(projects.content.other.commands)
}
