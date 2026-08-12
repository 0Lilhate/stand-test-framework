dependencies {
  api(project(":stand-test-core"))
  implementation(project(":stand-test-await"))

  implementation(libs.playwright)
  implementation(libs.slf4j.api)

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.assertj.core)
  testImplementation(libs.logback.classic)
  testRuntimeOnly(libs.junit.platform.launcher)
}

val browserTest = tasks.register<Test>("browserTest") {
  group = "verification"
  description = "Runs the Playwright-backed tests (tag 'browser'); requires the Chromium binaries."
  testClassesDirs = sourceSets["test"].output.classesDirs
  classpath = sourceSets["test"].runtimeClasspath
  useJUnitPlatform {
    includeTags("browser")
  }
  systemProperty("stand.test.ui.headless", providers.systemProperty("stand.test.ui.headless").getOrElse("true"))
  systemProperty(
    "junit.jupiter.execution.parallel.config.fixed.parallelism",
    (project.findProperty("stand.test.ui.browser.parallelism") as String?) ?: "2",
  )
  maxParallelForks = 1
  shouldRunAfter(tasks.named("test"))
}

tasks.named<Test>("test") {
  useJUnitPlatform {
    excludeTags("browser")
  }
  maxParallelForks = 1

  inputs.dir(rootDir.resolve("docs/agent-evaluation")).withPropertyName("standTestAnalysisDataset")
  systemProperty("stand.test.dataset.dir", rootDir.resolve("docs/agent-evaluation").absolutePath)
}

tasks.register<JavaExec>("installPlaywrightBrowsers") {
  group = "verification"
  description = "Installs the Chromium build Playwright drives (required once before browserTest)."
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("com.microsoft.playwright.CLI")
  // No `--with-deps`: on Linux it shells out to the system package manager and needs root, which
  // would make the documented install command fail on exactly the CI agents that need it most. OS-level
  // libraries belong in the image; this task fetches the browser build only.
  args("install", "chromium")
}

tasks.withType<Test>().configureEach {
  doFirst {
    check(maxParallelForks == 1) {
      "$path runs with maxParallelForks=$maxParallelForks. The UI account pool is in-process: a second JVM " +
        "builds its own pool and leases the same account to a second run at the same time. Parallelise this " +
        "module with THREADS (junit-platform.properties), never with forks."
    }
  }
}

val defaultTestSuite = tasks.named("test")

tasks.withType<JacocoReport>().configureEach {
  setDependsOn(listOf(defaultTestSuite))
}

tasks.withType<JacocoCoverageVerification>().configureEach {
  setDependsOn(listOf(defaultTestSuite))

  classDirectories.setFrom(
    files(
      classDirectories.files.map { directory ->
        fileTree(directory) { exclude("ru/alfa/stand/test/ui/playwright/**") }
      },
    ),
  )
}
