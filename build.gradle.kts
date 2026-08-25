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

/* Messstand-Durchreichung: `./gradlew run -Dskyengine.cullbench=<Weltordner>` lädt die Welt
   automatisch, friert das Chunk-Loading ein und schaltet CPU-/GPU-Cull im festen Takt um
   (s. graphics/world/CullBench). Ohne die Property unverändertes Startverhalten. */
tasks.named<JavaExec>("run") {
    for (schluessel in listOf("skyengine.cullbench", "skyengine.window")) {
        System.getProperty(schluessel)?.let { systemProperty(schluessel, it) }
    }
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
    description = "Konvertiert Sponge-.schem-Dateien einzeln oder als Batch in globale .structure-Dateien"
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

tasks.register<JavaExec>("lodCensus") {
    group = "verification"
    description = "Misst reproduzierbar Surface-Baseline und strukturhaltige LOD-Regionen (Cold/Warm)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "de.skyengine.game.world.lod.LodPerformanceCensus"
}

tasks.register<JavaExec>("lodQuads") {
    group = "verification"
    description = "Zählt die LOD-Quads des kompletten Rings pro Level (Spaltenpfad) — Vorher/Nachher-Beleg für Merge-Änderungen"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "de.skyengine.game.world.lod.LodQuadCensus"
    maxHeapSize = "6g"
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
    "saveTest", "lightTest", "meshTest", "lodCensus", "lodQuads", "mapExport"
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
