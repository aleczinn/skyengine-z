plugins {
    id("java")
    id("application")
}

group = "de.skyengine"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

application {
    mainClass = "de.skyengine.DesktopLauncher"
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow"
    )
}

/* Optionale Fenstergröße für reproduzierbare Messläufe an den Spielprozess weiterreichen. */
tasks.named<JavaExec>("run") {
    System.getProperty("skyengine.window")?.let { systemProperty("skyengine.window", it) }
}

/* Minecraft-Importer liegt im Haupt-SourceSet (de.skyengine.mcimport), damit die Weltauswahl
   ihn aufrufen kann (GuiImportWorld). Die CLI-Tasks unten bleiben als Kommandozeilen-Weg. */
tasks.register<JavaExec>("mcAnalyze") {
    group = "application"
    description = "Analysiert eine Minecraft-Welt (1.18+): NBT/MCA-Leser mit Histogramm (M4)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "de.skyengine.mcimport.McWorldAnalyzer"
}

tasks.register<JavaExec>("mcMapReport") {
    group = "application"
    description = "Prüft die Block-Mapping-Abdeckung gegen eine Minecraft-Welt (M5)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "de.skyengine.mcimport.McMappingReport"
}

tasks.register<JavaExec>("mcImport") {
    group = "application"
    description = "Konvertiert eine Minecraft-Welt (1.18+) in eine SkyEngine-Welt (M6)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "de.skyengine.mcimport.McWorldImporter"
}

tasks.register<JavaExec>("schematicConvert") {
    group = "application"
    description = "Konvertiert Sponge-.schem und alte WorldEdit-.schematic in globale .structure-Dateien"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "de.skyengine.game.world.structure.SchematicConvertCli"
}

/* Fensterlose Prüfstände: bootstrappen die Block-Registry ohne GL und melden über den
   Exit-Code. Damit lassen sich Block-JSON-Änderungen prüfen, ohne das Spiel zu starten. */
tasks.register<JavaExec>("saveTest") {
    group = "verification"
    description = "Serialisiert einen Chunk und vergleicht ihn nach dem Wiederherstellen (Blöcke, Properties, BlockEntities)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "de.skyengine.game.world.save.debug.SaveRoundTripTest"
}

tasks.register<JavaExec>("lightTest") {
    group = "verification"
    description = "Prüft die Himmelslicht-Ausbreitung an künstlichen Chunks (Heightmap, Säule, Tunnel, Wasser, Chunk-Naht)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "de.skyengine.game.world.light.debug.LightProbe"
}

tasks.register<JavaExec>("meshTest") {
    group = "verification"
    description = "Deterministischer Mesher-Zensus (3×3 Generator-Chunks, Quad-Zähler + Byte-Hash) — Bit-Identitäts-Beweis bei Mesher-Umbauten"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "de.skyengine.game.world.chunk.debug.MesherCensus"
}

tasks.register<JavaExec>("meshBench") {
    group = "verification"
    description = "Misst den L0-Section-Mesher ohne Worldgen/Lighting im Messfenster und schreibt JSON"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "de.skyengine.game.world.chunk.debug.MesherBenchmark"
    systemProperty("meshBench.warmups", providers.gradleProperty("meshBenchWarmups").getOrElse("10"))
    systemProperty("meshBench.iterations", providers.gradleProperty("meshBenchIterations").getOrElse("30"))
    systemProperty("meshBench.detailIterations",
        providers.gradleProperty("meshBenchDetailIterations").getOrElse("16"))
    systemProperty("meshBench.fullCubeSampleStride",
        providers.gradleProperty("meshBenchFullCubeSampleStride").getOrElse("16"))
    systemProperty("meshBench.visibilityPath",
        providers.gradleProperty("meshBenchVisibilityPath").getOrElse("ROW_MASK"))
    val label = providers.gradleProperty("meshBenchLabel").orNull
    val suffix = if (label.isNullOrBlank()) "" else "-$label"
    systemProperty("meshBench.label", label ?: "")
    systemProperty("meshBench.output",
        layout.buildDirectory.file("reports/meshing/mesh-benchmark$suffix.json").get().asFile.absolutePath)
}

tasks.register<JavaExec>("mapExport") {
    group = "verification"
    description = "Exportiert Weltgen-Debugkarten nach debug-maps/ (Bitstabilität der Generierung)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "de.skyengine.game.world.generator.debug.GeneratorMapExporter"
}

val lwjglVersion = "3.4.1"
val jomlVersion = "1.10.9"
val jomlPrimitivesVersion = "1.10.0"
val lwjglNatives = "natives-windows"

repositories {
    mavenCentral()
}

dependencies {
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
    implementation("org.joml", "joml", jomlVersion)
    implementation("org.joml", "joml-primitives", jomlPrimitivesVersion)
    implementation("com.google.code.gson:gson:2.14.0")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val isolatedTestGameDirectory = layout.buildDirectory.dir("test-game-directory")
val isolatedVerificationTasks = setOf(
    "saveTest", "lightTest", "meshTest", "meshBench", "mapExport"
)

tasks.test {
    useJUnitPlatform()
    systemProperty("skyengine.gameDirectory", isolatedTestGameDirectory.get().asFile.absolutePath)
    doFirst {
        delete(isolatedTestGameDirectory)
    }
}

tasks.withType<JavaExec>().configureEach {
    if (name in isolatedVerificationTasks) {
        val isolatedDirectory = layout.buildDirectory.dir("verification-game-directories/$name")
        systemProperty("skyengine.gameDirectory", isolatedDirectory.get().asFile.absolutePath)
        doFirst {
            delete(isolatedDirectory)
        }
    }
}

tasks.named("check") {
    dependsOn("saveTest", "lightTest", "meshTest")
}
