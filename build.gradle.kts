plugins {
    kotlin("jvm") version "2.1.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.dudebehinddude"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("ch.qos.logback:logback-classic:1.4.12")
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")
    implementation("com.discord4j:discord4j-core:3.2.8")
    implementation("org.reflections:reflections:0.10.2")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.61.0")
    implementation("org.jetbrains.exposed:exposed-core:0.61.0")
    implementation("org.xerial:sqlite-jdbc:3.50.2.0")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    manifest {
        attributes["Main-Class"] = "com.dudebehinddude.MainKt"
    }
}