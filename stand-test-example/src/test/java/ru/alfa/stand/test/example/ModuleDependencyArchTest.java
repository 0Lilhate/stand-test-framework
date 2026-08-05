package ru.alfa.stand.test.example;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.scenario.Scenario;

/**
 * Pins the module dependency graph (CLAUDE.md "Module graph"): a future edit that added a forbidden Gradle
 * edge AND used it — {@code implementation(project(":stand-test-kafka"))} in core, an adapter-to-adapter
 * edge, a non-core dependency in {@code allure}/{@code scenario-yaml}/{@code ai-schema}/{@code config}, or
 * anything depending on the starter — would otherwise compile and pass the green build with nothing failing.
 * This module is the only one with every SDK module on its test classpath, so it is where the whole graph
 * can be analysed at once. ArchUnit inspects the main bytecode of the SDK modules (tests excluded), so it
 * catches an ACTUAL cross-module use, not merely an unused Gradle declaration.
 */
class ModuleDependencyArchTest {

    private static final String BASE = "ru.alfa.stand.test.";
    private static final String CORE = BASE + "core..";
    private static final String AWAIT = BASE + "await..";
    private static final String JUNIT = BASE + "junit..";
    private static final String REST = BASE + "rest..";
    private static final String KAFKA = BASE + "kafka..";
    private static final String DB = BASE + "db..";
    private static final String GRPC = BASE + "grpc..";
    private static final String UI = BASE + "ui..";
    private static final String UI_DRIVER = BASE + "ui.playwright..";
    private static final String PLAYWRIGHT = "com.microsoft.playwright..";
    private static final String ALLURE = BASE + "allure..";
    private static final String SCENARIO = BASE + "scenario..";
    private static final String AI = BASE + "ai..";
    private static final String CONFIG = BASE + "config..";
    private static final String STARTER = BASE + "starter..";
    private static final String EXAMPLE = BASE + "example..";

    private static final JavaClasses SDK = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("ru.alfa.stand.test");

    @Test
    @DisplayName("the ArchUnit import loaded the SDK modules' main classes, so the graph rules are not vacuous")
    void importIsNonVacuous() {
        // The whole point is that the SDK modules' bytecode is analysed; if importPackages found nothing,
        // every noClasses() rule would pass trivially. Guard that with a floor on the imported class count.
        assertThat(SDK.size()).as("ArchUnit must import the SDK modules' main classes from the test classpath").isGreaterThan(100);
    }

    @Test
    @DisplayName("stand-test-core is a pure sink: it depends on no sibling SDK module (only slf4j-api)")
    void coreIsASink() {
        noClasses().that().resideInAPackage(CORE)
                .should().dependOnClassesThat().resideInAnyPackage(
                        AWAIT, JUNIT, REST, KAFKA, DB, GRPC, UI, ALLURE, SCENARIO, AI, CONFIG, STARTER, EXAMPLE)
                .as("stand-test-core must depend on no sibling module (it is the dependency-graph sink)")
                .check(SDK);
    }

    @Test
    @DisplayName("stand-test-core depends on nothing but the JDK and the slf4j facade — no Playwright, no IO library, ever")
    void coreHasNoUiOrIoDependencies() {
        // The sink rule above pins the SDK-internal edges; this one pins the EXTERNAL ones, which nothing
        // checked before: `implementation(libs.playwright)` in core would have compiled and passed the
        // whole suite. The allow-list is deliberately explicit — widening it is a reviewed decision.
        classes().that().resideInAPackage(CORE)
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "javax..", "org.slf4j..", CORE, "")
                .as("stand-test-core must depend only on the JDK and slf4j-api")
                .check(SDK);
    }

    @Test
    @DisplayName("the Scenario model carries no UI fields: a browser, a viewport and a base URL are configuration, not scenario")
    void scenarioHasNoUiFields() {
        assertThat(Arrays.stream(Scenario.class.getDeclaredFields()).filter(field -> !field.isSynthetic()).map(Field::getName))
                .as("a new Scenario component is an architectural decision, not a refactoring — update this list deliberately")
                .containsExactlyInAnyOrder("id", "environment", "steps", "tags", "title", "description", "cleanupPolicy");
    }

    @Test
    @DisplayName("Playwright is confined to the ui.playwright package: no other SDK type may import com.microsoft.playwright")
    void playwrightIsConfinedToDriverPackage() {
        noClasses().that().resideOutsideOfPackage(UI_DRIVER)
                .should().dependOnClassesThat().resideInAnyPackage(PLAYWRIGHT)
                .as("only ru.alfa.stand.test.ui.playwright may see Playwright — otherwise a browser lands on every consumer's classpath")
                .check(SDK);
    }

    @Test
    @DisplayName("the confinement rule is not vacuous: the driver package really does use Playwright")
    void playwrightConfinementIsNotVacuous() {
        // A rule of the form "nobody outside package P may use X" passes trivially if X is absent from the
        // import altogether. Prove X is there before trusting the rule that constrains it.
        assertThat(SDK.stream()
                .filter(imported -> imported.getPackageName().startsWith("ru.alfa.stand.test.ui.playwright"))
                .anyMatch(imported -> imported.getDirectDependenciesFromSelf().stream()
                        .anyMatch(dependency -> dependency.getTargetClass().getPackageName().startsWith("com.microsoft.playwright"))))
                .as("the ui.playwright package must actually depend on Playwright, or playwrightIsConfinedToDriverPackage proves nothing")
                .isTrue();
    }

    @Test
    @DisplayName("adapter modules (rest/kafka/db/grpc/ui) do not depend on each other")
    void adaptersDoNotDependOnEachOther() {
        noClasses().that().resideInAPackage(REST).should().dependOnClassesThat().resideInAnyPackage(KAFKA, DB, GRPC, UI).check(SDK);
        noClasses().that().resideInAPackage(KAFKA).should().dependOnClassesThat().resideInAnyPackage(REST, DB, GRPC, UI).check(SDK);
        noClasses().that().resideInAPackage(DB).should().dependOnClassesThat().resideInAnyPackage(REST, KAFKA, GRPC, UI).check(SDK);
        noClasses().that().resideInAPackage(GRPC).should().dependOnClassesThat().resideInAnyPackage(REST, KAFKA, DB, UI).check(SDK);
        noClasses().that().resideInAPackage(UI).should().dependOnClassesThat().resideInAnyPackage(REST, KAFKA, DB, GRPC).check(SDK);
    }

    @Test
    @DisplayName("nothing depends on stand-test-ui: it joins a run through the core SPI, so no protocol test drags in a browser")
    void nothingDependsOnUi() {
        noClasses().that().resideOutsideOfPackage(UI)
                .should().dependOnClassesThat().resideInAPackage(UI)
                .as("the ui adapter must be a leaf: it is discovered by ServiceLoader, never depended on")
                .check(SDK);
    }

    @Test
    @DisplayName("allure / scenario-yaml / ai-schema / config depend on core only (no adapters, no await/junit, no each other, no starter)")
    void satelliteModulesAreCoreOnly() {
        noClasses().that().resideInAPackage(ALLURE).should().dependOnClassesThat()
                .resideInAnyPackage(AWAIT, JUNIT, REST, KAFKA, DB, GRPC, SCENARIO, AI, CONFIG, STARTER, EXAMPLE).check(SDK);
        noClasses().that().resideInAPackage(SCENARIO).should().dependOnClassesThat()
                .resideInAnyPackage(AWAIT, JUNIT, REST, KAFKA, DB, GRPC, ALLURE, AI, CONFIG, STARTER, EXAMPLE).check(SDK);
        noClasses().that().resideInAPackage(AI).should().dependOnClassesThat()
                .resideInAnyPackage(AWAIT, JUNIT, REST, KAFKA, DB, GRPC, ALLURE, SCENARIO, CONFIG, STARTER, EXAMPLE).check(SDK);
        noClasses().that().resideInAPackage(CONFIG).should().dependOnClassesThat()
                .resideInAnyPackage(AWAIT, JUNIT, REST, KAFKA, DB, GRPC, ALLURE, SCENARIO, AI, STARTER, EXAMPLE).check(SDK);
    }

    @Test
    @DisplayName("nothing depends on the Spring Boot starter (the starter wires the runtime modules, never the reverse)")
    void nothingDependsOnTheStarter() {
        noClasses().that().resideOutsideOfPackage(STARTER)
                .should().dependOnClassesThat().resideInAPackage(STARTER)
                .as("the spring-boot-starter must be a leaf: no SDK module may depend on it")
                .check(SDK);
    }
}
