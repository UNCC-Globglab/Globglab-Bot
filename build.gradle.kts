plugins {
    kotlin("jvm") version "2.1.0"
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
    implementation("com.discord4j:discord4j-core:3.2.7")
    implementation("org.reflections:reflections:0.10.2")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}