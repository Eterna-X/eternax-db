// Database access layer for eternaSync services: one build, one jar (com.eternax:eternax-db).
// src/main/java/com/eternax/db/common holds the shared settings and utilities; each service's
// repositories, row types and migrations live in its own package (com.eternax.db.<service>).
// Services call repository methods and hold no SQL.
plugins {
    `java-library`
    `maven-publish`
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "7.2.1"
}

val eternaxVersion = project.property("eternaxVersion") as String

repositories {
    if (providers.gradleProperty("useMavenLocal").isPresent) mavenLocal()   // local verification only
    maven {
        name = "eternax-core"
        url = uri("https://maven.pkg.github.com/Eterna-X/eternax-core")
        credentials {
            username = System.getenv("PACKAGES_USER") ?: System.getenv("GITHUB_ACTOR")
            password = System.getenv("PACKAGES_TOKEN") ?: System.getenv("GITHUB_TOKEN")
        }
    }
    maven {
        name = "eternax-platform"
        url = uri("https://maven.pkg.github.com/Eterna-X/eternax-platform")
        credentials {
            username = System.getenv("PACKAGES_USER") ?: System.getenv("GITHUB_ACTOR")
            password = System.getenv("PACKAGES_TOKEN") ?: System.getenv("GITHUB_TOKEN")
        }
    }
    mavenCentral()
}

dependencyManagement {
    imports { mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.16") }
}

dependencies {
    // common
    api("org.springframework.boot:spring-boot")
    api("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.yaml:snakeyaml")
    // every service's repositories
    api("com.eternax:eternax-core:$eternaxVersion")
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.springframework.boot:spring-boot-starter-json")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("com.eternax:eternax-test-support:$eternaxVersion")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    withSourcesJar()
}
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial"))
}
tasks.withType<Test> {
    useJUnitPlatform()
    testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat("1.28.0").aosp().reflowLongStrings()
        removeUnusedImports()
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            // write resolved versions into the POM so consumers never depend on our BOM setup
            versionMapping { allVariants { fromResolutionResult() } }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Eterna-X/eternax-db")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
