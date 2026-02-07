plugins {
    kotlin("jvm") version "2.0.21"
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
    ))
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
