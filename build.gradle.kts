plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}
group = "com.netonstream.app"
version = "1.0.0"
repositories { mavenCentral() }
kotlin {
    macosArm64(); linuxX64(); linuxArm64()
    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            implementation("com.netonstream.app:module-system")
            implementation("com.netonstream.app:module-infra")
            implementation("com.netonstream.app:module-account")
            listOf("core", "routing", "security", "http", "database", "logging", "validation", "storage").forEach {
                implementation("com.netonstream:neton-$it")
            }
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
        }
        commonTest.dependencies { implementation(kotlin("test")); implementation(libs.kotlinx.coroutines.test) }
    }
}
dependencies {
    add("kspMacosArm64", "com.netonstream:neton-ksp")
    add("kspLinuxX64", "com.netonstream:neton-ksp")
    add("kspLinuxArm64", "com.netonstream:neton-ksp")
}
ksp { arg("neton.moduleId", "sticker") }
afterEvaluate {
    kotlin.sourceSets.named("commonMain") { kotlin.srcDir(file("build/generated/ksp/macosArm64/macosArm64Main/kotlin")) }
    listOf("macosArm64Main", "linuxX64Main", "linuxArm64Main").forEach { name ->
        kotlin.sourceSets.findByName(name)?.let { ss -> ss.kotlin.setSrcDirs(ss.kotlin.srcDirs.filter { !it.path.contains("generated/ksp") }) }
    }
}
tasks.matching { it.name == "compileCommonMainKotlinMetadata" || it.name.matches(Regex("compileKotlin(MacosArm64|LinuxX64|LinuxArm64)")) }.configureEach { dependsOn("kspKotlinMacosArm64") }
tasks.matching { it.name.matches(Regex("kspKotlin(LinuxX64|LinuxArm64)")) }.configureEach { dependsOn("kspKotlinMacosArm64") }
extra["neton.migration.moduleId"] = "sticker"
extra["neton.migration.dialects"] = listOf("postgresql")
extra["neton.migration.sqlSourceDir"] = file("sql")
apply(from = "../../Neton/neton/scripts/embed-migration-resources.gradle.kts")
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    System.getenv("STICKER_TEST_DATABASE_URI")?.let { environment("STICKER_TEST_DATABASE_URI", it) }
}
