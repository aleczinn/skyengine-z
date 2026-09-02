plugins { java }

java { toolchain.languageVersion = JavaLanguageVersion.of(25) }

dependencies {
    implementation(project(":skyengine-shared"))
    implementation(project(":skyengine-server"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

tasks.register<JavaExec>("multiplayerLoadTest") {
    group = "verification"
    description = "Runs the headless local multiplayer bot harness (override with -Pplayers/-Pseconds)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "de.skyengine.tools.network.MultiplayerLoadHarness"
    args((findProperty("players") ?: "8").toString(), (findProperty("seconds") ?: "10").toString())
}
