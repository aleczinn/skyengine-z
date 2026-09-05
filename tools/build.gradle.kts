plugins { java }

java { toolchain.languageVersion = JavaLanguageVersion.of(25) }

dependencies {
    implementation(project(":skyengine-shared"))
    implementation(project(":skyengine-gameplay"))
    implementation(project(":skyengine-server"))
    implementation(project(":skyengine-client"))
    implementation(platform("io.netty:netty-bom:4.2.17.Final"))
    implementation("io.netty:netty-transport")
    implementation("io.netty:netty-codec")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

tasks.register<JavaExec>("cleanupReferenceLoadTest") {
    group = "verification"
    description = "Measures the non-production direct terrain-to-mesh performance reference."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "de.skyengine.tools.network.DirectChunkPipelineReference"
    args((findProperty("viewDistance") ?: "16").toString(),
        (findProperty("seed") ?: "123456789").toString(),
        (findProperty("report") ?: layout.buildDirectory.file(
            "reports/multiplayer/cleanup-direct-reference.json").get().asFile.absolutePath).toString(),
        (findProperty("timeoutSeconds") ?: "60").toString(),
        (findProperty("workers") ?: maxOf(2, Runtime.getRuntime().availableProcessors() - 2)).toString())
}

tasks.register<JavaExec>("multiplayerLoadTest") {
    group = "verification"
    description = "Runs the headless local multiplayer bot harness (override with -Pplayers/-Pseconds)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "de.skyengine.tools.network.MultiplayerLoadHarness"
    args((findProperty("players") ?: "8").toString(), (findProperty("seconds") ?: "10").toString(),
        (findProperty("report") ?: layout.buildDirectory.file(
            "reports/multiplayer/multiplayer-load.json").get().asFile.absolutePath).toString(),
        (findProperty("runtime") ?: "headless").toString(),
        (findProperty("viewDistance") ?: "16").toString(),
        (findProperty("route") ?: "stationary").toString(),
        (findProperty("bandwidthMiB") ?: "128").toString(),
        (findProperty("seed") ?: "123456789").toString(),
        (findProperty("catchUpSeconds") ?: "10").toString(),
        (findProperty("mutationRate") ?: "100").toString(),
        (findProperty("workers") ?: maxOf(2, Runtime.getRuntime().availableProcessors() - 2)).toString())
}

tasks.register<JavaExec>("multiplayerPipelineLoadTest") {
    group = "verification"
    description = "Runs local bots against the production authoritative chunk pipeline."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "de.skyengine.tools.network.MultiplayerLoadHarness"
    args((findProperty("players") ?: "8").toString(), (findProperty("seconds") ?: "10").toString(),
        (findProperty("report") ?: layout.buildDirectory.file(
            "reports/multiplayer/multiplayer-pipeline-load.json").get().asFile.absolutePath).toString(),
        "authoritative",
        (findProperty("viewDistance") ?: "16").toString(),
        (findProperty("route") ?: "fast").toString(),
        (findProperty("bandwidthMiB") ?: "128").toString(),
        (findProperty("seed") ?: "123456789").toString(),
        (findProperty("catchUpSeconds") ?: "10").toString(),
        (findProperty("mutationRate") ?: "100").toString(),
        (findProperty("workers") ?: maxOf(2, Runtime.getRuntime().availableProcessors() - 2)).toString())
}

tasks.register<JavaExec>("multiplayerDedicatedLoadTest") {
    group = "verification"
    description = "Runs TCP clients against the production dedicated chunk pipeline."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "de.skyengine.tools.network.MultiplayerLoadHarness"
    args((findProperty("players") ?: "8").toString(), (findProperty("seconds") ?: "10").toString(),
        (findProperty("report") ?: layout.buildDirectory.file(
            "reports/multiplayer/multiplayer-dedicated-load.json").get().asFile.absolutePath).toString(),
        "dedicated",
        (findProperty("viewDistance") ?: "16").toString(),
        (findProperty("route") ?: "fast").toString(),
        (findProperty("bandwidthMiB") ?: "128").toString(),
        (findProperty("seed") ?: "123456789").toString(),
        (findProperty("catchUpSeconds") ?: "10").toString(),
        (findProperty("mutationRate") ?: "100").toString(),
        (findProperty("workers") ?: maxOf(2, Runtime.getRuntime().availableProcessors() - 2)).toString())
}

tasks.register<JavaExec>("multiplayerLoadJfr") {
    group = "verification"
    description = "Runs the acknowledged local multiplayer load harness with Java Flight Recorder."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "de.skyengine.tools.network.MultiplayerLoadHarness"
    val reportDir = layout.buildDirectory.dir("reports/multiplayer")
    val recording = reportDir.map { it.file("multiplayer-load.jfr") }
    doFirst { reportDir.get().asFile.mkdirs() }
    jvmArgs("-XX:StartFlightRecording=filename=${recording.get().asFile.absolutePath},settings=profile,dumponexit=true")
    args((findProperty("players") ?: "8").toString(), (findProperty("seconds") ?: "30").toString(),
        (findProperty("report") ?: reportDir.get().file("multiplayer-load-jfr.json").asFile.absolutePath).toString(),
        (findProperty("runtime") ?: "authoritative").toString(),
        (findProperty("viewDistance") ?: "16").toString(),
        (findProperty("route") ?: "fast").toString(),
        (findProperty("bandwidthMiB") ?: "128").toString(),
        (findProperty("seed") ?: "123456789").toString(),
        (findProperty("catchUpSeconds") ?: "10").toString(),
        (findProperty("mutationRate") ?: "100").toString(),
        (findProperty("workers") ?: maxOf(2, Runtime.getRuntime().availableProcessors() - 2)).toString())
}

tasks.register<JavaExec>("multiplayerWarmWorldLoadTest") {
    group = "verification"
    description = "Measures block-delta replication after an Integrated world is fully warm."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "de.skyengine.tools.network.MultiplayerLoadHarness"
    args((findProperty("players") ?: "32").toString(), (findProperty("seconds") ?: "10").toString(),
        (findProperty("report") ?: layout.buildDirectory.file(
            "reports/multiplayer/multiplayer-warm-world-load.json").get().asFile.absolutePath).toString(),
        "authoritative",
        (findProperty("viewDistance") ?: "8").toString(),
        "warm",
        (findProperty("bandwidthMiB") ?: "128").toString(),
        (findProperty("seed") ?: "123456789").toString(),
        "0",
        (findProperty("mutationRate") ?: "100").toString(),
        (findProperty("workers") ?: maxOf(2, Runtime.getRuntime().availableProcessors() - 2)).toString())
}

tasks.register<JavaExec>("multiplayerDedicatedWarmWorldLoadTest") {
    group = "verification"
    description = "Measures block-delta replication over TCP after a Dedicated world is fully warm."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "de.skyengine.tools.network.MultiplayerLoadHarness"
    args((findProperty("players") ?: "32").toString(), (findProperty("seconds") ?: "10").toString(),
        (findProperty("report") ?: layout.buildDirectory.file(
            "reports/multiplayer/multiplayer-dedicated-warm-world-load.json").get().asFile.absolutePath).toString(),
        "dedicated",
        (findProperty("viewDistance") ?: "8").toString(),
        "warm",
        (findProperty("bandwidthMiB") ?: "128").toString(),
        (findProperty("seed") ?: "123456789").toString(),
        "0",
        (findProperty("mutationRate") ?: "100").toString(),
        (findProperty("workers") ?: maxOf(2, Runtime.getRuntime().availableProcessors() - 2)).toString())
}

tasks.register("multiplayerComparisonLoadTest") {
    group = "verification"
    description = "Runs the direct reference, Integrated, and Dedicated fixed-seed benchmarks."
    dependsOn("cleanupReferenceLoadTest", "multiplayerPipelineLoadTest", "multiplayerDedicatedLoadTest")
}

tasks.named("multiplayerDedicatedLoadTest") {
    mustRunAfter("multiplayerPipelineLoadTest")
}

tasks.named("multiplayerPipelineLoadTest") {
    mustRunAfter("cleanupReferenceLoadTest")
}
