plugins {
    `java-library`
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

sourceSets {
    main {
        java.setSrcDirs(listOf(rootProject.file("src/main/java")))
        java.include(
            "de/skyengine/game/Gamemode.java",
            "de/skyengine/game/command/**",
            "de/skyengine/game/entity/**",
            "de/skyengine/game/physics/**",
            "de/skyengine/game/world/**",
            "de/skyengine/utils/**",
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
        java.exclude(
            "de/skyengine/game/world/particle/ParticleEngine.java",
            "de/skyengine/game/world/chunk/debug/**",
            "de/skyengine/utils/SpecsUtil.java",
            "de/skyengine/utils/Utils.java"
        )
        resources.setSrcDirs(listOf(rootProject.file("src/main/resources")))
        resources.include(
            "game/blocks/**",
            // Block-state baking still consumes the shared CPU-side model definitions. The
            // dedicated server never creates GL resources, but these files are required for
            // the same baked state/collision registry IDs as the client.
            "game/models/**",
            "game/items/**",
            "game/creative_tabs.json",
            "game/recipes/**",
            "game/loot_table/**",
            "game/tags/**",
            "game/worldgen/**",
            "game/structures/**"
        )
    }
}

dependencies {
    api(project(":skyengine-shared"))
    api("org.joml:joml:1.10.9")
    implementation("com.google.code.gson:gson:2.14.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }
