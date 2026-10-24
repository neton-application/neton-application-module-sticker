plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}
group = "com.netonstream.app"
version = "1.0.0"
repositories { mavenCentral() }
kotlin {
    listOf(macosArm64(), linuxX64(), linuxArm64()).forEach { target ->
        target.compilations.getByName("main").cinterops.create("pagprocess") {
            definitionFile.set(file("src/nativeInterop/cinterop/pagprocess.def"))
            includeDirs(file("src/nativeInterop/cinterop"))
        }
    }
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
    listOf("STICKER_TEST_DATABASE_URI", "PAG_TEST_DATABASE_URI", "PAG_TEST_STORAGE_ROOT", "PAG_TEST_INSPECTOR",
        "PAG_TEST_STICKER_ZIP", "PAG_TEST_LEGACY_PNG", "STICKER_PACK_HTTP", "STICKER_PACK_DATABASE_URI",
        "STICKER_PACK_ROOT", "STICKER_PACK_PORT", "STICKER_PACK_INSPECTOR", "STICKER_PACK_PNG").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
}
