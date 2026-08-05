// stand-test-ui — browser / UI adapter. Owns the typed `UiStep` model and the UI `StepExecutor`
// (registered via the core SPI in META-INF/services). It is the single point of real browser IO to a
// stand, and the ONLY module allowed to see Playwright (ADR-UI-001).
//
// Internal dependencies follow the target graph (docs/ui-test-generation/10-target-architecture.md §3):
// ui -> core, ui -> await. Exactly the shape of stand-test-rest.
//   - core is `api`: UiStep produces core `ScenarioStep`s and UiStepExecutor implements the core
//     `StepExecutor` SPI, so those types are part of this module's public surface.
//   - await is `implementation`: the Awaiter drives `ui.expectEventually` polling, an internal detail.
//
// External dependency: Playwright for Java, `implementation` (never `api`) and physically confined to
// the `ru.alfa.stand.test.ui.playwright` package — pinned by `playwrightIsConfinedToDriverPackage` in
// stand-test-example. No adapter-to-adapter edge, and no consumer of another adapter pays for a browser.
//
// Shared Java / checkstyle / jacoco / publishing configuration comes from the root `subprojects { }`.

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
    // The browser-backed tests serve their fixture from a local `com.sun.net.httpserver` app — JDK only,
    // no extra dependency, the same trick RecordingHttpServer plays in stand-test-rest.
}

// Browser-backed tests are tagged `browser` and run in their own task. Rationale: `test` must stay
// runnable on a machine (or a locked-down CI image) where the Chromium binaries have not been
// downloaded, while a tagged test that silently "passes" when the browser is missing would be exactly
// the vacuous green this repository has been bitten by before. Splitting the task keeps both honest:
// `./gradlew :stand-test-ui:test` never needs a browser, `./gradlew :stand-test-ui:browserTest` always
// does.
//
// It does NOT, however, fail loudly when the browsers are absent — this comment claimed it did, and the
// UITG-SP002 spike measured otherwise: the Java client auto-installs on the first `Playwright.create()`
// and pulls ALL FIVE default browser sets (1.1 GB, Firefox and WebKit included) straight from the CDN,
// mid-test. `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` is what turns that into the loud failure — with it the
// suite fails 24/24 having downloaded nothing. Setting it is a CI-job decision (UITG-S026), not a build
// one: a developer's first `browserTest` legitimately wants the download. See the module README, section
// "Закрытый контур", and docs/ui-test-generation/planning/32-playwright-closed-contour-spike.md.
val browserTest = tasks.register<Test>("browserTest") {
    group = "verification"
    description = "Runs the Playwright-backed tests (tag 'browser'); requires the Chromium binaries."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("browser")
    }
    // Headed debugging: ./gradlew :stand-test-ui:browserTest -Dstand.test.ui.headless=false
    systemProperty("stand.test.ui.headless", providers.systemProperty("stand.test.ui.headless").getOrElse("true"))
    // Parallelism for the browser suite, overriding junit-platform.properties (an explicit system property
    // wins over the file). The model is the same — classes concurrent, methods same-thread — but the number
    // is not: a thread here costs a Chromium plus its driver process, so the default is deliberately small
    // enough to run on a laptop and on a 2-vCPU agent, while still being >1 so the parallel path is really
    // exercised. Raise it locally with -Pstand.test.ui.browser.parallelism=4 when the machine can afford it.
    systemProperty(
        "junit.jupiter.execution.parallel.config.fixed.parallelism",
        (project.findProperty("stand.test.ui.browser.parallelism") as String?) ?: "2",
    )
    // One fork, many threads. Several JVMs would each build their own in-process account pool and hand the
    // same test account to two runs at once — exactly the guarantee the pool exists to make.
    maxParallelForks = 1
    shouldRunAfter(tasks.named("test"))
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("browser")
    }
    // Same rule as browserTest: the account pool is per JVM, so parallelism must be threads in one fork.
    maxParallelForks = 1
}

// Downloads the browser binaries the `browserTest` task needs (one-off; Playwright caches them under
// ~/Library/Caches/ms-playwright or ~/.cache/ms-playwright). In a closed network this is the task that
// has to be pointed at an internal mirror via PLAYWRIGHT_DOWNLOAD_HOST, or replaced by preinstalling
// the binaries into the CI image.
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

// Keep `browserTest` out of `check` — which is what the split above claims, and what was NOT true.
//
// The root build wires every JaCoCo task to `dependsOn(tasks.withType<Test>())` and `check` to the JaCoCo
// verification. `withType<Test>()` matches `browserTest` too, so `check` (and therefore `./gradlew build`)
// transitively required a browser: exactly the thing the two-task split exists to prevent, silently true on
// any machine that happens to have Chromium installed and a build failure on the CI image that does not.
// Narrowing the two JaCoCo tasks to the default suite breaks that edge for this module only, without
// touching the root wiring every other module relies on.
// One fork is load-bearing, so it is checked at execution time rather than merely assigned above: the
// account pool lives in the process, and a second JVM would build its own and hand the same test account to
// a second run — the one guarantee the pool exists to make. The assignments are local to this file, but the
// value is not: a routine `maxParallelForks = …` added to the root `subprojects` block for CI speed would
// override them silently. This turns that into a build failure naming the invariant.
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
}

// JaCoCo: the `…ui.playwright` package is exercised by `browserTest`, which is deliberately NOT part of
// `check` (see above), so its coverage would read as 0 % on a browser-less build and fail the 80 %
// INSTRUCTION gate for reasons that have nothing to do with the code under test. Everything the module
// can verify without a browser — model, builder, parameters, executor, resolvers — stays under the gate.
// The exclusion is applied to the verification task's class directories (the report keeps showing the
// whole truth, including the uncovered driver package).
tasks.withType<JacocoCoverageVerification>().configureEach {
    classDirectories.setFrom(
        files(
            classDirectories.files.map { directory ->
                fileTree(directory) { exclude("ru/alfa/stand/test/ui/playwright/**") }
            },
        ),
    )
}
