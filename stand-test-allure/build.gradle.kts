// stand-test-allure — Allure reporting adapter; see README.md. Two non-obvious dependency choices:
//
//  - allure-java-commons is `implementation`, not `api`: Allure types never leak through this module's
//    public surface (the mapping is hidden behind AllureLifecycleFacade), so a consumer does not have to
//    compile against Allure to depend on the adapter.
//  - allure-junit5 is deliberately NOT a dependency: it would put JUnit on a pure reporting adapter's
//    classpath. The consumer adds it — without it the lifecycle has no test case to fill (README).

dependencies {
    api(project(":stand-test-core"))

    implementation(libs.allure.java.commons)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
