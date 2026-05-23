plugins {
    id("fabric-loom")
    kotlin("jvm")
    kotlin("plugin.serialization")
}

version = property("mod_version") as String
group = property("group") as String

base {
    archivesName.set("phantom")
}

val clientPublicLayer by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")

    // Public-layer client mod (mixins + API + native), consumed from mavenLocal.
    // Merged into phantom.jar so the loader owns the single Fabric mod id.
    modImplementation("org.phantom:phantom-client-public:${property("phantom_client_version")}")
    clientPublicLayer("org.phantom:phantom-client-public:${property("phantom_client_version")}")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${property("serialization_version")}")
    include("org.jetbrains.kotlinx:kotlinx-serialization-json:${property("serialization_version")}")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        clientPublicLayer.map { zipTree(it) }
    }) {
        exclude(
            "fabric.mod.json",
            "META-INF/MANIFEST.MF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "META-INF/*.SF"
        )
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
