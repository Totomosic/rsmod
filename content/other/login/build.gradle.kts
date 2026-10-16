plugins {
    id("base-conventions")
    id("integration-test-suite")
}

dependencies {
    implementation(libs.rsprot.api)
    implementation(projects.api.invWeight)
    implementation(projects.api.pluginCommons)
    implementation(projects.api.realm)
    implementation(projects.api.scriptAdvanced)
    integrationImplementation(projects.api.gameProcess)
    integrationImplementation(projects.api.statsPlugin)
}
