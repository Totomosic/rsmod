plugins {
    id("base-conventions")
    id("integration-test-suite")
}

kotlin {
    explicitApi()
    val mainCompilation = target.compilations.getByName("main")
    target.compilations.configureEach {
        if (name == "integration") associateWith(mainCompilation)
    }
}

dependencies {
    implementation(libs.guice)
    implementation(projects.api.areaChecker)
    implementation(projects.api.combat.combatCommons)
    implementation(projects.api.combat.combatFormulas)
    implementation(projects.api.combat.combatManager)
    implementation(projects.api.combat.combatWeapon)
    implementation(projects.api.config)
    implementation(projects.api.npc)
    implementation(projects.api.player)
    implementation(projects.api.playerOutput)
    implementation(projects.api.random)
    implementation(projects.api.script)
    implementation(projects.api.scriptAdvanced)
    implementation(projects.api.specials)
    implementation(projects.api.spells)
    implementation(projects.api.spellsAutocast)
    implementation(projects.api.type.typeBuilders)
    implementation(projects.api.type.typeReferences)
    implementation(projects.api.utils.utilsVars)
    implementation(projects.api.weapons)
    implementation(projects.engine.events)
    implementation(projects.engine.game)
    implementation(projects.engine.map)
    implementation(projects.engine.plugin)
    integrationImplementation(projects.api.pluginCommons)
    integrationImplementation(projects.api.registry)
    integrationImplementation(projects.content.areas.dungeons.nylocas)
    integrationImplementation(projects.content.other.specialWeapons)
    integrationImplementation(projects.content.other.specialAttacks)
    integrationImplementation(projects.content.skills.magic.spellAttacks)
    integrationImplementation(projects.content.skills.magic.utilitySpells)
    integrationImplementation(projects.api.spellsRunes)
    integrationImplementation(projects.api.hitPlugin)
    integrationImplementation(libs.fastutil)
    integrationImplementation(projects.api.nylocasAgents)
    integrationImplementation(projects.content.interfaces.prayerTab)
    integrationImplementation(projects.content.other.consumables)
}

// Explicit batch entry point; ordinary integration runs skip the simulation test.
tasks.register<Test>("simulateNylocas") {
    val suite = testing.suites.named<JvmTestSuite>("integration")
    testClassesDirs = suite.get().sources.output.classesDirs
    classpath = suite.get().sources.runtimeClasspath
    useJUnitPlatform()
    workingDir = rootDir
    filter { includeTestsMatching("*NylocasSimulationTest") }
    systemProperty("junit.jupiter.extensions.autodetection.enabled", true)
    systemProperty("nylo.simulation", "true")
    for (key in listOf("trials", "seed", "maxTicks", "output", "trace")) {
        providers.gradleProperty("nylo.$key").orNull?.let { systemProperty("nylo.$key", it) }
    }
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = true
    maxHeapSize = "2g"
}
