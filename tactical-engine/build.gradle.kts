// Pure-Java game engine: NO Bukkit/Paper dependency. Everything here is unit-testable.
plugins {
    `java-library`
}

dependencies {
    // Provided at runtime by Paper; compileOnly so the engine never bundles them.
    compileOnlyApi(libs.gson)
    compileOnlyApi(libs.snakeyaml)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.gson)
    testImplementation(libs.snakeyaml)
}
