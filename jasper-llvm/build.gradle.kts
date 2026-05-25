plugins {
    kotlin("jvm") version "2.1.0"
}

group = "jasper"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":jasper-compiler"))
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
}

tasks.test {
    useJUnit()
}
