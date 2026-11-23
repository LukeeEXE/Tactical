plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    compileOnly(libs.paper.api)
    implementation(project(":tactical-engine"))
    implementation(libs.hikari)
    implementation(libs.sqlite.jdbc)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.shadowJar {
    archiveBaseName.set("Tactical")
    archiveClassifier.set("")
    relocate("com.zaxxer.hikari", "dev.tactical.lib.hikari")
    // sqlite-jdbc loads native libs by package name; it is NOT relocated (documented in docs/BUILDING.md).
    exclude("META-INF/maven/**")
    mergeServiceFiles()
}

tasks.assemble { dependsOn(tasks.shadowJar) }
