plugins {
    `java-library`
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

dependencies {
    api("org.joml:joml:1.10.9")
    implementation("com.google.code.gson:gson:2.14.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }
