plugins {
    kotlin("jvm") version "2.4.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("io.ktor:ktor-bom:3.6.1"))
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.21.8"))

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("io.ktor:ktor-server-auth-jvm")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("io.ktor:ktor-serialization-jackson-jvm")
    implementation("io.ktor:ktor-server-status-pages-jvm")
    implementation("io.ktor:ktor-server-call-id-jvm")
    implementation("io.ktor:ktor-server-call-logging-jvm")
    implementation("io.ktor:ktor-server-cors-jvm")

    implementation("com.nimbusds:nimbus-jose-jwt:10.10")

    implementation("org.postgresql:postgresql:42.7.14")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.flywaydb:flyway-core:13.13.0")
    runtimeOnly("org.flywaydb:flyway-database-postgresql:13.13.0")

    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
    runtimeOnly("org.xerial:sqlite-jdbc:3.54.1.0")

    testImplementation(kotlin("test-junit"))
    testImplementation("io.ktor:ktor-server-test-host-jvm")
    testImplementation("ch.qos.logback:logback-classic:1.5.18")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    sourceSets {
        main {
            kotlin.setSrcDirs(listOf("adapter", "application", "bootstrap", "domain"))
            resources.setSrcDirs(listOf("resources"))
        }
        test {
            kotlin.setSrcDirs(listOf("test"))
        }
    }


    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.nexora.backend.MainKt")
}

tasks.register<JavaExec>("runSaas") {
    group = "application"
    description = "Runs the Ktor SaaS backend entrypoint (separate from the legacy SQLite demo)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.nexora.backend.bootstrap.SaasMainKt")
}

tasks.register<JavaExec>("migrate") {
    group = "database"
    description = "Runs Flyway migrations explicitly using dedicated app_migrator credentials (FLYWAY_*)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.nexora.backend.bootstrap.MigrateMainKt")
}

tasks.withType<Test> {
    systemProperty("nexora.test.localAuth", System.getProperty("nexora.test.localAuth", "false"))
    System.getProperty("nexora.test.localAuthUrl")?.let {
        systemProperty("nexora.test.localAuthUrl", it)
    }
    systemProperty("nexora.test.localPostgres", System.getProperty("nexora.test.localPostgres", "false"))
    System.getProperty("nexora.test.localPostgresUrl")?.let {
        systemProperty("nexora.test.localPostgresUrl", it)
    }
}
