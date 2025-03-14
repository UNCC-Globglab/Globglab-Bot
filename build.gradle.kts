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
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")
    implementation("com.discord4j:discord4j-core:3.2.7")
    implementation(kotlin("stdlib")) // Explicitly include the Kotlin standard library
    implementation(kotlin("stdlib-jdk8")) // JDK 8+ extensions
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}