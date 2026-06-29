plugins {
  `java-platform`
  `maven-publish`
}

// Aggregate BOM (platform) for the stand-test SDK.
//
// Dependency constraints are intentionally omitted at this stage: there is no approved
// dependency list yet. Sibling-module and third-party version constraints will be added in
// a later phase, e.g.:
//
//   dependencies {
//     constraints {
//       api(project(":stand-test-core"))
//       // ... other modules / curated third-party versions
//     }
//   }
//
// IMPORTANT: modules constrained by this BOM must NOT import it back (that would create a
// `core -> bom -> core` cycle). Only EXTERNAL consumers import it, e.g.:
//   testImplementation(platform("ru.alfa.stand.test:stand-test-bom:<version>"))

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["javaPlatform"])
    }
  }
}
