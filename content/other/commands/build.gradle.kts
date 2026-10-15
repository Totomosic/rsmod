plugins {
    id("base-conventions")
    id("integration-test-suite")
}

dependencies {
    implementation(libs.fastutil)
    implementation(libs.simmetrics.core)
    implementation(projects.api.db)
    implementation(projects.api.dbGateway)
    implementation(projects.api.pluginCommons)
    implementation(projects.api.realm)
    implementation(projects.api.realmConfig)
    implementation(projects.api.type.typeSymbols)
    implementation(projects.api.utils.utilsSystem)
    implementation(projects.engine.utilsBits)
    implementation(projects.content.areas.dungeons.nylocas)
    integrationImplementation(projects.api.net)
    integrationImplementation(projects.api.registry)
    integrationImplementation(projects.content.areas.dungeons.nylocas)
}
