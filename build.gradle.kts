plugins {
    kotlin("jvm") version "2.0.21"
}

val extractedLunaLibJar = layout.buildDirectory.file("tmp/lunalib/LunaLib.jar")

val extractLunaLibJar by tasks.registering(Copy::class) {
    from(zipTree("possibly-useful-libs/LunaLib.zip")) {
        include("LunaLib/jars/LunaLib.jar")
        eachFile { path = name }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("tmp/lunalib"))
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    compileOnly(files(
        "0.98a-official-jars/starfarer.api.jar",
        "0.98a-official-jars/starfarer_obf.jar",
        "0.98a-official-jars/xstream-1.4.10.jar",
        "0.98a-official-jars/json.jar",
        "0.98a-official-jars/log4j-1.2.9.jar",
        "0.98a-official-jars/lwjgl.jar",
        "0.98a-official-jars/lwjgl_util.jar",
        "possibly-useful-libs/Nexerelin/jars/ExerelinCore.jar",
        "libs/LazyLib.jar",
        "libs/LazyLib-Kotlin.jar",
        "libs/Kotlin-Runtime.jar",
        extractedLunaLibJar,
    ))
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(extractLunaLibJar)
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(extractLunaLibJar)
}

tasks.jar {
    archiveFileName.set("TheInvisibleHand.jar")
    destinationDirectory.set(file("jars"))
}

tasks.register<Zip>("packageMod") {
    dependsOn(tasks.jar)
    archiveFileName.set("TheInvisibleHand.zip")
    destinationDirectory.set(file("dist"))
    into("TheInvisibleHand") {
        from("mod_info.json")
        from("jars") { into("jars") }
        from("data") { into("data") }
        from("graphics") { into("graphics") }
    }
}

kotlin {
    jvmToolchain(17)
}
