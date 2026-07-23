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

/* Minecraft-Importer: eigenes SourceSet, Abhängigkeit NUR Importer -> Engine.
   Die Engine (main) referenziert KEINE Importer-Klasse. */
sourceSets {
    create("mcimport") {
        java.srcDir("src/mcimport/java")
        compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
        runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
    }
}

tasks.register<JavaExec>("mcAnalyze") {
    group = "application"
    description = "Analysiert eine Minecraft-Welt (1.18+): NBT/MCA-Leser mit Histogramm (M4)"
    classpath = sourceSets["mcimport"].runtimeClasspath
    mainClass = "de.skyengine.mcimport.McWorldAnalyzer"
}

tasks.register<JavaExec>("mcMapReport") {
    group = "application"
    description = "Prüft die Block-Mapping-Abdeckung gegen eine Minecraft-Welt (M5)"
    classpath = sourceSets["mcimport"].runtimeClasspath
    mainClass = "de.skyengine.mcimport.McMappingReport"
}

tasks.register<JavaExec>("mcImport") {
    group = "application"
    description = "Konvertiert eine Minecraft-Welt (1.18+) in eine SkyEngine-Welt (M6)"
    classpath = sourceSets["mcimport"].runtimeClasspath
    mainClass = "de.skyengine.mcimport.McWorldImporter"
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
}