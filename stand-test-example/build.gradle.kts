import java.security.MessageDigest

// stand-test-example — technical usage examples (Iteration 8, docs/arch/stand-test-example-implementation-plan.md).
// TEST-ONLY: the scenarios live in src/test and run through the public SDK API as a black box against
// in-process doubles (JDK HttpServer for REST, H2 for DB), so `./gradlew build` is green offline without
// a real DEV/IFT stand (test doubles are allowed by plan §16) — with ONE exception, added deliberately
// and named here so the invariant is not read as wider than it is: TaksaMainScreenTest drives a real
// browser against a real stand and is gated by nothing, so this module is green offline for every
// example EXCEPT that one. See the note beside the test task below. It is a pure consumer (a sink) of the SDK
// modules; nothing depends on it. No business logic, no real-stand config, no published artifact
// (plan §6/§7/§20). Phase 1 covers REST+DB via the manual runner; Phase 2 adds the canonical @StandTest
// path (StandTestExampleTest) and a tagged Kafka example (KafkaExampleTest, requires a broker — excluded
// from the default run).

dependencies {
    testImplementation(project(":stand-test-core"))
    // Declared directly (not just transitively via junit/db): FullStandTestFrameworkExampleTest drives
    // Awaiter/AwaitPolicy/TimeSource deterministically with a fake time source — no wall-clock waits.
    testImplementation(project(":stand-test-await"))
    testImplementation(project(":stand-test-junit"))
    testImplementation(project(":stand-test-rest"))
    testImplementation(project(":stand-test-db"))
    testImplementation(project(":stand-test-kafka"))
    // kafka-clients (test-only) gives KafkaOfflineExampleTest the Apache MockProducer/MockConsumer to run
    // kafka.send/kafka.expect end-to-end with no broker (via the KafkaClientFactory seam), so the offline
    // composition proof covers Kafka too. The published SDK never exposes kafka-clients transitively.
    testImplementation(libs.kafka.clients)
    testImplementation(project(":stand-test-grpc"))
    // The UI adapter is on the classpath for ONE reason: ModuleDependencyArchTest is the only place the
    // whole module graph can be analysed at once, and the rules that keep Playwright out of core and out
    // of every other module are vacuous unless the ui module's bytecode is actually imported.
    testImplementation(project(":stand-test-ui"))
    testImplementation(project(":stand-test-allure"))
    // The @StandTest path resolves its EnvironmentRegistry from the stand.test.environments section of
    // src/test/resources/application.yml through stand-test-config's FileEnvironmentRegistry SPI
    // provider — the same wiring (and the same familiar file) a real consumer uses.
    testImplementation(project(":stand-test-config"))
    // gRPC example: the grpc adapter keeps grpc-api/services/transport as implementation/runtimeOnly, so
    // the example declares what it needs at compile time to stand up a local gRPC double — grpc-api
    // (ServerBuilder) + grpc-services (HealthStatusManager, ProtoReflectionServiceV1) — plus the shaded
    // Netty transport at runtime so the SDK's default channel factory can dial a real loopback port.
    testImplementation(libs.grpc.api)
    testImplementation(libs.grpc.services)
    testRuntimeOnly(libs.grpc.netty.shaded)
    // AI-format parity: the scenario-yaml engine (AiScenarioParser) parses the AI document, and the
    // ai-schema module ships the JSON Schema it must first validate against. Both are core-only and
    // test-only here. The JSON Schema validator (networknt) + Jackson are declared directly: ai-schema
    // keeps them in its own test scope, so they do NOT reach this module transitively.
    testImplementation(project(":stand-test-scenario-yaml"))
    testImplementation(project(":stand-test-ai-schema"))
    testImplementation(libs.networknt.json.schema.validator)
    testImplementation(libs.jackson.databind)
    // Spring Boot starter example: StandTestSpringBootStarterExampleTest wires the auto-configuration
    // through ApplicationContextRunner (spring-boot-test) — offline bean-presence/binding checks only,
    // no bootable app and no real application context.
    testImplementation(project(":stand-test-spring-boot-starter"))
    testImplementation(libs.spring.boot.test)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    // ArchUnit pins the module dependency graph (ModuleDependencyArchTest): this module has every SDK
    // module on its test classpath, so it is the one place the whole graph can be analysed at once.
    testImplementation(libs.archunit)
    testImplementation(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
    // The SDK ships only the SLF4J facade; the CONSUMER supplies a binding. The example plays the
    // consumer: Logback (test-only) turns the SDK's scenario/step/adapter logs and MDC correlation
    // (scenarioId/testRunId/correlationId/stepId) into visible console output — see logback-test.xml.
    // Compile scope rather than runtime-only since UITG-S027: StarterDiscoversUiExecutorTest reads the
    // discovery announcement back out of the log, because that line is how "the auto-configuration
    // really called discovery" is observable from outside. Still test-only — the SDK ships no binding.
    testImplementation(libs.logback.classic)
}

// env-ref wiring for the doubles. DB: DbStepExecutor's no-arg form resolves datasource refs from the
// process environment (its passthrough-resolver ctor is package-private, so env-ref is the only
// cross-module path). REST on the @StandTest path: the default no-arg RestStepExecutor resolves the
// service base URL from CLIENT_SERVICE_URL via System.getenv, so the HTTP double must listen on a fixed
// port — pinned here and overridable in CI with -PexampleRestPort=NNNN (avoids port-collision). The
// manual-runner examples ignore CLIENT_SERVICE_URL (they use the passthrough seam on an ephemeral port).
// H2 stays in-memory for the JVM via DB_CLOSE_DELAY=-1.
// The gRPC example stands up a real (Netty) gRPC double on a fixed loopback port pinned by GRPC_TARGET,
// which the default no-arg GrpcStepExecutor resolves via System.getenv (like CLIENT_SERVICE_URL for REST).
// Override the port in CI with -PexampleGrpcPort=NNNN to avoid collisions.
// Stand-bound variables for TaksaMainScreenTest, read once at configuration time through the provider
// API so Gradle registers each of them as a configuration input: exporting or changing one invalidates
// the configuration cache entry, which plain System.getenv() would not do.
//
// Read by prefix, not by name, so a second technical account (TKS_<ID>_USERNAME/_PASSWORD) needs no
// edit here. PLAYWRIGHT_* carries PLAYWRIGHT_DOWNLOAD_HOST for a closed network and
// PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD for a CI job that wants a loud failure instead of a 1.1 GB
// mid-test download.
//
// Credentials may also arrive as Gradle properties, which is what makes the test pass from an IDE
// without exporting anything per session: put them in ~/.gradle/gradle.properties — a file OUTSIDE
// this repository, so a password never reaches git — and they are forwarded as the environment
// variables the account roster names. An exported variable always wins over the property.
//
//     tksAdminUsername=...
//     tksAdminPassword=...
//
// UI run settings (headed/headless, browser, timeouts, artifacts dir) are read by the SDK from SYSTEM
// properties of the test JVM, and Gradle does not pass its own down to a forked one — so
// `-Dstand.test.ui.headless=false` on the command line silently did nothing here. Forwarded by prefix,
// the same way stand-test-ui's own browserTest task does it, so watching a run with your own eyes is:
//
//     TAKSA_IFT_URL='http://taksa-dev3/' ./gradlew :stand-test-example:test \
//         --tests '*TaksaMainScreenTest' -Dstand.test.ui.headless=false --rerun-tasks
//
// Default: HEADED. The stand-bound example is run by a person watching it, so a visible window is the
// useful default here — unlike stand-test-ui's browserTest, which defaults to headless because it runs
// two dozen browsers on CI. `-Dstand.test.ui.headless=true` (or a CI job setting it) wins over this.
val standUiSystemProperties: Map<String, String> = buildMap {
    put("stand.test.ui.headless", "false")
    putAll(providers.systemPropertiesPrefixedBy("stand.test.ui.").get())
}

val standBoundEnvironment: Map<String, String> = buildMap {
    (findProperty("tksAdminUsername") as String?)?.let { put("TKS_ADMIN_1_USERNAME", it) }
    (findProperty("tksAdminPassword") as String?)?.let { put("TKS_ADMIN_1_PASSWORD", it) }
    listOf("TAKSA_", "TKS_", "PLAYWRIGHT_").forEach { prefix ->
        putAll(providers.environmentVariablesPrefixedBy(prefix).get())
    }
}

// A DIGEST of those variables, not the values: they are declared as a task input below, and a task
// input snapshot holding TKS_*_PASSWORD would write the password under .gradle/. A digest still
// changes when a corrected password is exported, which is the case the input exists for.
val standBoundEnvironmentDigest: String = MessageDigest.getInstance("SHA-256")
    .digest((standBoundEnvironment.toSortedMap().toString() + standUiSystemProperties.toSortedMap()).toByteArray())
    .joinToString("") { part -> "%02x".format(part) }

val exampleRestPort = (findProperty("exampleRestPort") as String?)?.toInt() ?: 18080
val exampleGrpcPort = (findProperty("exampleGrpcPort") as String?)?.toInt() ?: 18090
tasks.withType<Test>().configureEach {
    // The Kafka example needs a live broker (kafka.expect arms a real KafkaConsumer), so it is tagged
    // `requires-broker` and excluded from the default offline run. Opt in with -PincludeRequiresBroker
    // against a reachable broker (override its address with -PkafkaBootstrapServers).
    useJUnitPlatform {
        if (!project.hasProperty("includeRequiresBroker")) {
            excludeTags("requires-broker")
        }
        // The UI example that talks to a real stand (TaksaMainScreenTest) is gated by NOTHING — not by
        // a tag here and not by a JUnit condition on the test. That is the line owner's decision: it
        // runs in an ordinary run, and the price is stated rather than hidden — on a machine without
        // access to the taksa stand, or without trust in the internal CA, `./gradlew build` goes red.
        // The module header says the same; the offline invariant covers every OTHER example.
        //
        // If that decision is ever revisited, the gate belongs on the TEST, not here:
        // @EnabledIfEnvironmentVariable(named = "TAKSA_IFT_URL", ...), the device
        // ClientRequestAcceptedE2eDraftTest uses. A tag excluded in this block is subtracted from a
        // --tests filter as well, so running that one test by name from an IDE reports "No matching
        // tests found" instead of skipping it — a precondition has to be a JUnit condition, which
        // reports itself, rather than a build-side exclusion, which cannot.
    }
    environment("MAIN_DB_URL", "jdbc:h2:mem:exampledb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
    environment("MAIN_DB_USER", "sa")
    environment("MAIN_DB_PASSWORD", "sa")
    environment("CLIENT_SERVICE_URL", "http://127.0.0.1:$exampleRestPort")
    // RFC 7617 example credentials for the registry-driven auth example (RestAuthExampleTest).
    environment("CLIENT_USER", "Aladdin")
    environment("CLIENT_PASSWORD", "open sesame")
    environment("GRPC_TARGET", "127.0.0.1:$exampleGrpcPort")
    environment("KAFKA_BOOTSTRAP_SERVERS", (findProperty("kafkaBootstrapServers") as String?) ?: "localhost:9092")

    // Forward the stand-bound variables to the test JVM. Inheriting them looks like it works and does
    // not: with the configuration cache the task's environment is whatever was computed when the entry
    // was written, so a variable exported afterwards never reaches the tests.
    environment(standBoundEnvironment)
    standUiSystemProperties.forEach { (name, value) -> systemProperty(name, value) }

    // Forwarding alone is still not enough, and the gap reads as a bug in the test rather than in the
    // build. Gradle does not treat a task's environment as an INPUT, so after one run the task stays
    // UP-TO-DATE: export the variables, run again, and the previous result — a skip — is replayed
    // verbatim while `env` in the same shell plainly shows the values. Declaring the digest as an input
    // is what makes the next run actually re-execute.
    inputs.property("standBoundEnvironment", standBoundEnvironmentDigest)
}

// Examples are demonstrations, not production code: src/main is empty, so the 80% coverage gate is not
// applicable. The module is not a consumable artifact either — the root subprojects block creates no
// maven publication for it at all.
tasks.withType<JacocoCoverageVerification>().configureEach { enabled = false }

// AuthoringCribApiCoverageTest and UiAuthoringCribApiCoverageTest read the authoring cribs from
// docs/ai-agent (outside this module) — the protocol one from `skills/**`, the UI one from `skills/**`
// AND `rules/**`, because the UI surface is written down in both — so declare them as a test input:
// editing a crib must re-run the tests instead of hitting a stale FROM-CACHE result. Only the curated
// asset tree: the bundle directory also holds gitignored machine-local files whose contents must not
// enter the cache key.
//
// The other half of both tests needs no declaration and it is worth knowing why: they resolve the SDK
// side by REFLECTION over classes on the test classpath, so Gradle already invalidates them when an
// adapter changes. A test that read the adapter's SOURCE instead would need that path declared too.
tasks.test {
    inputs.files(
        fileTree(rootDir.resolve("docs/ai-agent/.claude")) {
            include("skills/**", "commands/**", "rules/**")
        },
    ).withPropertyName("standTestAuthoringCrib")
}
