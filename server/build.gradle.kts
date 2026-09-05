plugins {
    application
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

application {
    mainClass = "de.skyengine.server.DedicatedServerLauncher"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(project(":skyengine-shared"))
    implementation(project(":skyengine-gameplay"))
    implementation(platform("io.netty:netty-bom:4.2.17.Final"))
    implementation("io.netty:netty-buffer")
    implementation("io.netty:netty-codec")
    implementation("io.netty:netty-common")
    implementation("io.netty:netty-handler")
    implementation("io.netty:netty-transport")
    implementation("com.github.luben:zstd-jni:1.5.7-16")
    implementation("com.google.code.gson:gson:2.14.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

val serverJar = tasks.register<Jar>("serverJar") {
    group = "distribution"
    description = "Builds a self-contained headless SkyEngine server jar"
    archiveBaseName = "skyengine-server-all"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest.attributes["Main-Class"] = application.mainClass.get()
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

val verifyHeadlessServerJar = tasks.register("verifyHeadlessServerJar") {
    group = "verification"
    description = "Rejects client, rendering, audio, and LWJGL classes in the dedicated-server artifact"
    dependsOn(serverJar)
    doLast {
        val forbiddenPrefixes = listOf(
            "org/lwjgl/",
            "de/skyengine/client/",
            "de/skyengine/graphics/",
            "de/skyengine/audio/"
        )
        // These explicitly selected gameplay data/profiling types are headless-safe despite
        // their historical package names. Keep the broad package rejection for every other
        // renderer/audio implementation instead of disabling the artifact boundary check.
        val headlessSafeTypes = listOf(
            "de/skyengine/audio/BlockOpenSound*.class",
            "de/skyengine/audio/BlockSoundGroup*.class",
            "de/skyengine/graphics/PerformanceProfiler*.class",
            "de/skyengine/graphics/Colors*.class",
            "de/skyengine/graphics/color/Color3*.class",
            "de/skyengine/graphics/color/Color4*.class",
            "de/skyengine/graphics/gui/font/FontStyle*.class",
            "de/skyengine/graphics/gui/text/RichText*.class",
            "de/skyengine/graphics/gui/text/Span*.class",
            "de/skyengine/graphics/gui/text/TextColors*.class"
        )
        val archive = serverJar.get().archiveFile.get().asFile
        val forbidden = zipTree(archive).matching {
            forbiddenPrefixes.forEach { include("$it**") }
            headlessSafeTypes.forEach { exclude(it) }
        }.files.sortedBy { it.path }
        check(forbidden.isEmpty()) {
            "Dedicated-server jar contains forbidden client/runtime classes:\n${
                forbidden.take(30).joinToString("\n") { it.path }
            }"
        }
    }
}

tasks.named("check") { dependsOn(verifyHeadlessServerJar) }
