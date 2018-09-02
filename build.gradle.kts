import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URL
import org.jetbrains.kotlin.gradle.dsl.Coroutines

plugins {
    java
    kotlin("jvm") version "1.2.61"
    `maven-publish`
    maven
    id("com.github.johnrengelman.shadow") version "2.0.2"
}

group = "com.rnett.ligraph.eve"
version = "1.0.4"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://dl.bintray.com/kotlin/exposed")
    maven("https://dl.bintray.com/kotlin/ktor")
    jcenter()
}



fun getNewestCommit(gitURL: String, default: String = ""): String {
    try {
        return URL("https://api.github.com/repos/$gitURL/commits").readText()
                .substringAfter("\"sha\":\"").substringBefore("\",").substring(0, 10)
    } catch (e: Exception) {
        return default
    }
}

val sde_version = getNewestCommit("rnett/sde", "7c4dad0f8e")
val core_version = getNewestCommit("rnett/core", "c54915eb12")

dependencies {
    compile(kotlin("stdlib-jdk8"))
    testCompile("junit", "junit", "4.12")
    implementation("com.github.rnett:sde:$sde_version")
    implementation("com.github.rnett:core:$core_version") {
        isForce = true
    }
}

configure<JavaPluginConvention> {
    sourceCompatibility = JavaVersion.VERSION_1_8
}
tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

val sourcesJar by tasks.creating(Jar::class) {
    classifier = "sources"
    from(java.sourceSets["main"].allSource)
}

artifacts.add("archives", sourcesJar)

publishing {
    publications {
        create("default", MavenPublication::class.java) {
            from(components["java"])
            artifact(sourcesJar)
        }
        create("mavenJava", MavenPublication::class.java) {
            from(components["java"])
            artifact(sourcesJar)
        }
    }
    repositories {
        maven {
            url = uri("$buildDir/repository")
        }
    }
}
kotlin {
    experimental.coroutines = Coroutines.ENABLE
}

val jar: Jar by tasks
jar.apply {
    manifest.attributes.apply {
        put("Main-Class", "com.rnett.ligraph.eve.contracts.UpdaterMain")
    }
}