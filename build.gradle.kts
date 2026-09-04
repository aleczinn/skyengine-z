plugins {
    base
}

group = "de.skyengine"
version = "1.0-SNAPSHOT"

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    group = rootProject.group
    version = rootProject.version
}

tasks.named("check") {
    dependsOn(
        ":skyengine-shared:check",
        ":skyengine-gameplay:check",
        ":skyengine-server:check",
        ":skyengine-client:check",
        ":skyengine-tools:check"
    )
}

// Preserve the repository-root CLI used by existing profiling/documentation scripts.
for (taskName in listOf("run", "saveTest", "lightTest", "meshTest", "meshBench", "mapExport",
    "mcAnalyze", "mcMapReport", "mcImport", "schematicConvert")) {
    tasks.register(taskName) {
        group = if (taskName == "run") "application" else "verification"
        dependsOn(":skyengine-client:$taskName")
    }
}

tasks.register("serverRun") {
    group = "application"
    dependsOn(":skyengine-server:run")
}

tasks.register("serverJar") {
    group = "distribution"
    dependsOn(":skyengine-server:serverJar")
}

tasks.register("multiplayerLoadTest") {
    group = "verification"
    dependsOn(":skyengine-tools:multiplayerLoadTest")
}
