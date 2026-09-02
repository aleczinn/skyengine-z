plugins { application }

java { toolchain.languageVersion = JavaLanguageVersion.of(25) }

application {
    mainClass = "de.skyengine.DesktopLauncher"
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow"
    )
}

sourceSets {
    main {
        java.srcDir(rootProject.file("src/main/java"))
        java.exclude(
            "de/skyengine/game/Gamemode.java",
            "de/skyengine/game/command/**",
            "de/skyengine/game/entity/**",
            "de/skyengine/game/physics/**",
            "de/skyengine/game/world/**",
            "de/skyengine/utils/ANSI.java",
            "de/skyengine/utils/TimeUtils.java",
            "de/skyengine/utils/collect/**",
            "de/skyengine/utils/logging/**",
            "de/skyengine/utils/math/**",
            "de/skyengine/core/io/**",
            "de/skyengine/core/file/**",
            "de/skyengine/core/resource/**",
            "de/skyengine/core/i18n/**",
            "de/skyengine/audio/BlockOpenSound.java",
            "de/skyengine/audio/BlockSoundGroup.java",
            "de/skyengine/graphics/PerformanceProfiler.java",
            "de/skyengine/graphics/gui/text/RichText.java",
            "de/skyengine/graphics/gui/text/Span.java",
            "de/skyengine/graphics/gui/text/TextColors.java",
            "de/skyengine/graphics/gui/font/FontStyle.java",
            "de/skyengine/graphics/Colors.java",
            "de/skyengine/graphics/color/Color4.java",
            "de/skyengine/graphics/color/Color3.java",
            "de/skyengine/mcimport/nbt/**",
            "de/skyengine/mcimport/mca/McBlockState.java",
            "de/skyengine/mcimport/mapping/BlockMapper.java"
        )
        resources.srcDir(rootProject.file("src/main/resources"))
    }
    test {
        java.srcDir(rootProject.file("src/test/java"))
        resources.srcDir(rootProject.file("src/test/resources"))
    }
}

val lwjglVersion = "3.4.1"
val lwjglNatives = "natives-windows"

dependencies {
    implementation(project(":skyengine-shared"))
    implementation(project(":skyengine-gameplay"))
    implementation(project(":skyengine-server"))
    implementation(platform("io.netty:netty-bom:4.2.17.Final"))
    implementation("io.netty:netty-handler")
    implementation("io.netty:netty-transport")
    implementation("com.github.luben:zstd-jni:1.5.7-16")
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    implementation("org.lwjgl", "lwjgl")
    implementation("org.lwjgl", "lwjgl-glfw")
    implementation("org.lwjgl", "lwjgl-openal")
    implementation("org.lwjgl", "lwjgl-opengl")
    implementation("org.lwjgl", "lwjgl-stb")
    runtimeOnly("org.lwjgl", "lwjgl", classifier = lwjglNatives)
    runtimeOnly("org.lwjgl", "lwjgl-glfw", classifier = lwjglNatives)
    runtimeOnly("org.lwjgl", "lwjgl-openal", classifier = lwjglNatives)
    runtimeOnly("org.lwjgl", "lwjgl-opengl", classifier = lwjglNatives)
    runtimeOnly("org.lwjgl", "lwjgl-stb", classifier = lwjglNatives)
    implementation("org.joml:joml:1.10.9")
    implementation("org.joml:joml-primitives:1.10.0")
    implementation("com.google.code.gson:gson:2.14.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val isolatedTestGameDirectory = layout.buildDirectory.dir("test-game-directory")

tasks.test {
    useJUnitPlatform()
    workingDir = rootProject.projectDir
    systemProperty("skyengine.gameDirectory", isolatedTestGameDirectory.get().asFile.absolutePath)
    doFirst { delete(isolatedTestGameDirectory) }
}

tasks.named<JavaExec>("run") {
    // Legacy resource loaders still resolve development assets relative to ./src/main/resources.
    // Gradle otherwise uses the client subproject directory after the module split.
    workingDir = rootProject.projectDir
    System.getProperty("skyengine.window")?.let { systemProperty("skyengine.window", it) }
}

// ParticleEngine is client-owned although its historical package still lives below game/world.
// Add that single source explicitly while the rest of game/world comes from skyengine-gameplay.
tasks.named<JavaCompile>("compileJava") {
    source(rootProject.file("src/main/java/de/skyengine/game/world/particle/ParticleEngine.java"))
    source(rootProject.fileTree("src/main/java/de/skyengine/game/world/chunk/debug") {
        include("**/*.java")
    })
}

fun registerVerificationTask(name: String, descriptionText: String, main: String) {
    tasks.register<JavaExec>(name) {
        group = "verification"
        description = descriptionText
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = main
        workingDir = rootProject.projectDir
        val isolatedDirectory = layout.buildDirectory.dir("verification-game-directories/$name")
        systemProperty("skyengine.gameDirectory", isolatedDirectory.get().asFile.absolutePath)
        doFirst { delete(isolatedDirectory) }
    }
}

registerVerificationTask("saveTest", "Serializes and restores a chunk", "de.skyengine.game.world.save.debug.SaveRoundTripTest")
registerVerificationTask("lightTest", "Runs headless lighting probes", "de.skyengine.game.world.light.debug.LightProbe")
registerVerificationTask("meshTest", "Runs the deterministic L0 mesher census", "de.skyengine.game.world.chunk.debug.MesherCensus")
registerVerificationTask("mapExport", "Exports deterministic worldgen maps", "de.skyengine.game.world.generator.debug.GeneratorMapExporter")

tasks.register<JavaExec>("meshBench") {
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "de.skyengine.game.world.chunk.debug.MesherBenchmark"
    workingDir = rootProject.projectDir
    systemProperty("meshBench.warmups", providers.gradleProperty("meshBenchWarmups").getOrElse("10"))
    systemProperty("meshBench.iterations", providers.gradleProperty("meshBenchIterations").getOrElse("30"))
    systemProperty("meshBench.detailIterations", providers.gradleProperty("meshBenchDetailIterations").getOrElse("16"))
    systemProperty("meshBench.fullCubeSampleStride", providers.gradleProperty("meshBenchFullCubeSampleStride").getOrElse("64"))
    systemProperty("meshBench.mode", providers.gradleProperty("meshBenchMode").getOrElse("ALL"))
    systemProperty("meshBench.visibilityPath", providers.gradleProperty("meshBenchVisibilityPath").getOrElse("ROW_MASK"))
    systemProperty("meshBench.overlayPath", providers.gradleProperty("meshBenchOverlayPath").getOrElse("COMPOSITE"))
    val label = providers.gradleProperty("meshBenchLabel").orNull
    val suffix = if (label.isNullOrBlank()) "" else "-$label"
    systemProperty("meshBench.label", label ?: "")
    systemProperty("meshBench.output", layout.buildDirectory.file("reports/meshing/mesh-benchmark$suffix.json").get().asFile.absolutePath)
}

tasks.named("check") { dependsOn("saveTest", "lightTest", "meshTest") }

for ((name, main) in mapOf(
    "mcAnalyze" to "de.skyengine.mcimport.McWorldAnalyzer",
    "mcMapReport" to "de.skyengine.mcimport.McMappingReport",
    "mcImport" to "de.skyengine.mcimport.McWorldImporter",
    "schematicConvert" to "de.skyengine.game.world.structure.SchematicConvertCli"
)) {
    tasks.register<JavaExec>(name) {
        group = "application"
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = main
        workingDir = rootProject.projectDir
    }
}
