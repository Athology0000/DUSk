
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
  alias(libs.plugins.kotlin)
  alias(libs.plugins.loom)
  `maven-publish`
}

val baseGroup: String by project
val modVersion: String by project
val modName: String by project
val gradlePropertiesFile = rootProject.file("gradle.properties")
val requestedTaskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }.toSet()
val buildChannel =
  providers.gradleProperty("phantomBuildChannel").orNull?.trim()?.lowercase()
    ?: when {
      requestedTaskNames.any { it in setOf("buildDev", "deployDev", "deploydev") } -> "dev"
      else -> "release"
    }

if (buildChannel !in setOf("dev", "release")) {
  throw GradleException("phantomBuildChannel must be 'dev' or 'release', found '$buildChannel'.")
}

val isDevBuild = buildChannel == "dev"
val artifactDisplayName = if (isDevBuild) "Phantom Client Dev" else "Phantom Client"
val artifactBaseName = if (isDevBuild) "${modName}-dev" else modName

fun shouldAutoBumpBuildVersion(): Boolean =
  !isDevBuild && requestedTaskNames.any { taskName ->
    taskName == "build" || taskName == "buildRelease"
  }

fun incrementMinorVersion(version: String): String {
  val parts = version.split('.')
  if (parts.size !in 2..3 || parts.any { it.toIntOrNull() == null }) {
    throw GradleException("modVersion must use numeric major.minor or major.minor.patch format, found \"$version\".")
  }

  val major = parts[0].toInt()
  val minor = parts[1].toInt() + 1
  return if (parts.size == 3) "$major.$minor.0" else "$major.$minor"
}

fun persistModVersion(nextVersion: String) {
  val content = gradlePropertiesFile.readText()
  val pattern = Regex("""(?m)^modVersion=.*$""")
  if (!pattern.containsMatchIn(content)) {
    throw GradleException("Could not find modVersion in ${gradlePropertiesFile.path}.")
  }
  gradlePropertiesFile.writeText(content.replaceFirst(pattern, "modVersion=$nextVersion"))
}

val resolvedModVersion =
  if (shouldAutoBumpBuildVersion()) {
    incrementMinorVersion(modVersion).also(::persistModVersion)
  } else {
    modVersion
  }

version = if (isDevBuild) "$resolvedModVersion-dev" else resolvedModVersion
group = baseGroup

base {
  archivesName.set(artifactBaseName)
}

repositories {
  mavenCentral()
  maven("https://maven.meteordev.org/releases")
  maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1")
  maven("https://repo.hypixel.net/repository/Hypixel/")
}

dependencies {
  minecraft(libs.minecraft)
  mappings(loom.officialMojangMappings())
  modImplementation(libs.bundles.fabric)

  implementation(libs.nanovg) { include(this) }
  implementation(libs.bundles.included) { include(this) }

  listOf("windows", "linux", "macos", "macos-arm64").forEach {
    implementation(variantOf(libs.nanovg) { classifier("natives-$it") }) {
      include(this)
    }
  }

  modImplementation(libs.hypixel.api) { include(this) }

  runtimeOnly(libs.httpclient)
  modRuntimeOnly(libs.devauth)
}

tasks {
  named<JavaExec>("runClient") {
    val devMockAuth =
      System.getProperty("phantom.devMockAuth")
        ?: System.getenv("PHANTOM_DEV_MOCK_AUTH")
        ?: "true"
    systemProperty("phantom.devMockAuth", devMockAuth)
  }

  processResources {
    val fabricKotlinVersion = libs.versions.fabric.kotlin.get()
    val fabricLoaderVersion = libs.versions.fabric.loader.get()
    val minecraftVersion = libs.versions.minecraft.version.get()

    inputs.property("version", project.version)
    inputs.property("displayName", artifactDisplayName)
    inputs.property("fabricKotlinVersion", fabricKotlinVersion)
    inputs.property("fabricLoaderVersion", fabricLoaderVersion)
    inputs.property("minecraftVersion", minecraftVersion)

    filesMatching("fabric.mod.json") {
      expand(
        "version" to project.version,
        "displayName" to artifactDisplayName,
        "fabricKotlinVersion" to fabricKotlinVersion,
        "fabricLoaderVersion" to fabricLoaderVersion,
        "minecraftVersion" to minecraftVersion,
      )
    }
  }

  compileKotlin {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_21
    }
  }
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
}

// â”€â”€ Native DLL build (Windows, requires VS2026 + CMake) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
val nativesDir = file("natives")
val dllReleasePath = file("natives/build/Release/phantom_pathfinder.dll")
val dllResourceDest = file("src/main/resources/natives/windows")

tasks.register<Exec>("buildNative") {
  group = "build"
  description = "Compiles phantom_pathfinder.dll via CMake."
  workingDir = nativesDir
  commandLine(
    "cmd", "/c",
    "cmake --build build --config Release"
  )
  onlyIf { nativesDir.resolve("build").exists() }
}

tasks.register<Copy>("copyNativeDll") {
  group = "build"
  description = "Copies the built phantom_pathfinder.dll into resources."
  dependsOn("buildNative")
  from(dllReleasePath)
  into(dllResourceDest)
  onlyIf { dllReleasePath.exists() }
}

tasks.named("processResources") {
  dependsOn("copyNativeDll")
}

// â”€â”€ Deploy JAR to Prism mods folder â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
val deployInstanceName = "dutt"
val deployMinecraftVersion = "1.21.11"
val releaseBranchName = "release"

fun prismInstanceRoots(): List<File> {
  val userHome = System.getProperty("user.home")
  return listOfNotNull(
    System.getenv("APPDATA")?.let { File(it, "PrismLauncher/instances") },
    System.getenv("LOCALAPPDATA")?.let { File(it, "PrismLauncher/instances") },
    userHome?.let { File(it, "AppData/Roaming/PrismLauncher/instances") },
    userHome?.let { File(it, ".local/share/PrismLauncher/instances") },
    userHome?.let { File(it, "Library/Application Support/PrismLauncher/instances") },
  ).distinctBy { it.absoluteFile.normalize().path }
}

fun prismInstanceDisplayName(instanceDir: File): String {
  val cfg = File(instanceDir, "instance.cfg")
  if (!cfg.isFile) return instanceDir.name

  return cfg.readLines()
    .firstOrNull { it.startsWith("name=", ignoreCase = true) }
    ?.substringAfter('=')
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: instanceDir.name
}

fun isFabricMinecraftInstance(instanceDir: File, minecraftVersion: String): Boolean {
  val packFile = File(instanceDir, "mmc-pack.json")
  if (!packFile.isFile) return false

  val pack = packFile.readText()
  val hasFabricLoader = pack.contains("\"uid\": \"net.fabricmc.fabric-loader\"") ||
    pack.contains("\"uid\":\"net.fabricmc.fabric-loader\"")
  val hasMinecraftVersion = Regex(
    """"uid"\s*:\s*"net\.minecraft"[\s\S]*?"version"\s*:\s*"$minecraftVersion""""
  ).containsMatchIn(pack)

  return hasFabricLoader && hasMinecraftVersion
}

fun resolvePrismModsDir(instanceName: String, minecraftVersion: String): File {
  val candidates = prismInstanceRoots()
    .filter { it.isDirectory }
    .flatMap { root -> root.listFiles()?.filter { it.isDirectory }.orEmpty() }
    .filter { instance ->
      instance.name.equals(instanceName, ignoreCase = true) ||
        prismInstanceDisplayName(instance).equals(instanceName, ignoreCase = true)
    }

  val match = candidates.firstOrNull { isFabricMinecraftInstance(it, minecraftVersion) }
    ?: throw GradleException(
      "Could not find a Prism Launcher instance named '$instanceName' using Fabric and Minecraft $minecraftVersion. " +
        "Searched: ${prismInstanceRoots().joinToString { it.path }}"
    )

  return File(match, "minecraft/mods")
}

tasks.register("buildDev") {
  group = "build"
  description = "Builds the dev artifact."
  dependsOn("build")
}

tasks.register("buildRelease") {
  group = "build"
  description = "Builds the release artifact."
  dependsOn("build")
}

tasks.register("verifyReleaseBranch") {
  group = "build"
  description = "Fails unless the current git branch is the release branch."
  doLast {
    val process = ProcessBuilder("git", "branch", "--show-current")
      .directory(rootDir)
      .redirectErrorStream(true)
      .start()
    val currentBranch = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    if (currentBranch != releaseBranchName) {
      val branchLabel = if (currentBranch.isBlank()) "unknown" else currentBranch
      throw GradleException("deploy must be run from '$releaseBranchName' branch. Current branch: '$branchLabel'.")
    }
  }
}

tasks.register("copyBuiltMod") {
  group = "build"
  description = "Copies the built JAR to the Prism Launcher mods folder."
  dependsOn("build")
  doLast {
    val modsDir = resolvePrismModsDir(deployInstanceName, deployMinecraftVersion)
    val jarName = "${base.archivesName.get()}-${project.version}.jar"
    val sourceJar = layout.buildDirectory.file("libs/$jarName").get().asFile.toPath()
    val targetJar = modsDir.toPath().resolve(jarName)
    val tempJar = modsDir.toPath().resolve("$jarName.tmp")

    Files.createDirectories(modsDir.toPath())
    Files.newDirectoryStream(modsDir.toPath(), "${modName}*.jar").use { existingJars ->
      existingJars
        .filter { it.fileName.toString() != jarName }
        .forEach {
          runCatching { Files.deleteIfExists(it) }
            .onFailure { error ->
              logger.warn("Could not delete old mod jar ${it.fileName}: ${error.message}")
            }
        }
    }
    Files.copy(sourceJar, tempJar, StandardCopyOption.REPLACE_EXISTING)
    runCatching {
      Files.move(
        tempJar,
        targetJar,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE
      )
    }.getOrElse {
      Files.move(tempJar, targetJar, StandardCopyOption.REPLACE_EXISTING)
    }
  }
}

tasks.register("deployDev") {
  group = "build"
  description = "Builds and copies the dev artifact to the Prism Launcher mods folder."
  dependsOn("copyBuiltMod")
}

tasks.register("deploydev") {
  group = "build"
  description = "Alias for deployDev."
  dependsOn("deployDev")
}

tasks.register("deploy") {
  group = "build"
  description = "Builds and copies the release artifact to the Prism Launcher mods folder. Only allowed on the release branch."
  dependsOn("verifyReleaseBranch", "copyBuiltMod")
}

// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

tasks.register<Copy>("collectObfLibs") {
  group = "build"
  description = "Copies compile classpath jars to build/obf-libs for Skidfuscator -li."
  from(configurations["compileClasspath"].resolvedConfiguration.resolvedArtifacts.map { it.file })
  into(layout.buildDirectory.dir("obf-libs"))
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
