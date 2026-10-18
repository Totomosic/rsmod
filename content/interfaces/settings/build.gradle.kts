plugins {
    id("base-conventions")
    id("integration-test-suite")
}

dependencies {
    implementation(projects.api.pluginCommons)
    implementation(projects.content.interfaces.gameframe)
    integrationImplementation(projects.api.player)
    integrationImplementation(projects.api.net)
    integrationImplementation(libs.fastutil)
    integrationImplementation(projects.api.account)
    integrationImplementation(projects.api.db)
    integrationImplementation(libs.flyway.core)
}
