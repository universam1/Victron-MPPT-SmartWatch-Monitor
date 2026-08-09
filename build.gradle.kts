// Intentionally empty of `plugins { ... }` declarations.
//
// Declaring the Android Gradle Plugin here (even with `apply false`) makes Gradle resolve
// it from Google's Maven repository while configuring the *root* project, which would
// break `./gradlew :protocol:test` on machines without access to the Android tooling.
// Each module applies the plugins it needs; see gradle/libs.versions.toml.

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
