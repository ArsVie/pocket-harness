// :core — pure Kotlin/JVM. ALL logic lives here (SPEC §1).
// Hard rule: never add an android.* or AndroidX dependency to this module.
import org.gradle.testing.jacoco.tasks.JacocoReport
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    jacoco
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation("org.yaml:snakeyaml:2.7")

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Instrumentation must actually be attached, and must never be cached away: a test task restored
// from the build cache skips execution, and the coverage file it would have written is not one of
// the task's declared outputs — which reports as 0% coverage and reads exactly like a real failure.
tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.named("jacocoTestReport"))
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> {
        isEnabled = true
    }
}

// Coverage *report*: informational, cheap, always regenerated with the tests.
tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
        csv.required = false
    }
}

/**
 * Wave gate: line coverage of `:core`.
 *
 * Parses the JaCoCo XML report directly instead of using `JacocoCoverageVerification`. Two reasons:
 * the Ant-based check on this toolchain reports `ratio is 0` even against a report it has just
 * produced from a fresh execution file (so it cannot be trusted to mean anything), and a gate that
 * cannot print the number it measured is not a gate — this one prints the ratio and the worst files.
 *
 * Excluded from the measurement are the frozen *declaration* files from `SPEC.md` §2 — data classes,
 * enums, sealed hierarchies and interfaces. Kotlin compiles those into a handful of synthetic lines
 * (`DefaultImpls` bridges for interface default arguments, data-class boilerplate, companion inits)
 * that carry no behaviour. Everything implementing those contracts
 * (ph.model.OpenAiClient, ph.session.*, ph.tools.BashTool & friends, ph.prompt.*, ph.agent.*,
 * ph.ui.DefaultThreadProjector) is fully in scope.
 */
tasks.register("coverageGate") {
    group = "verification"
    description = "Wave gate: report :core line coverage, failing below the required minimum."

    dependsOn(tasks.named("jacocoTestReport"))

    val reportFile = layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml")
    // SPEC §4: 95% line coverage on :core. Ars, 2026-09-10: "We don't need 100% code coverage for
    // this, 95% is enough."
    val minimumRatio = 0.95
    val declarationSources = setOf(
        "Ports.kt",   // ph.ports — interfaces
        "Policy.kt",  // ph.policy — enums, sealed decisions, interfaces
        "Model.kt",   // ph.model — wire data classes + interface
        "Session.kt", // ph.session — sealed event hierarchy + interface
        "Tools.kt",   // ph.tools — sealed outcomes, codes, interfaces
        "Prompt.kt",  // ph.prompt — config data classes + interface
        "Agent.kt",   // ph.agent — interface + LoopEvent hierarchy
        "Ui.kt",      // ph.ui — projection data classes + interface
    )
    inputs.file(reportFile)
    outputs.upToDateWhen { false } // a gate that skips is not a gate

    doLast {
        // JaCoCo's XML carries a DOCTYPE pointing at report.dtd; parse it without fetching anything.
        val factory = DocumentBuilderFactory.newInstance().apply {
            isValidating = false
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val doc = factory.newDocumentBuilder().parse(reportFile.get().asFile)

        var covered = 0
        var missed = 0
        var excludedLines = 0
        val perFile = mutableMapOf<String, IntArray>() // sourceFile -> [covered, missed]
        val classes = doc.getElementsByTagName("class")

        for (i in 0 until classes.length) {
            val element = classes.item(i) as Element
            val source = element.getAttribute("sourcefilename")

            // Direct <counter> children only: nested method counters would double-count.
            val children = element.childNodes
            for (j in 0 until children.length) {
                val node = children.item(j)
                if (node is Element && node.tagName == "counter" && node.getAttribute("type") == "LINE") {
                    val c = node.getAttribute("covered").toIntOrNull() ?: 0
                    val m = node.getAttribute("missed").toIntOrNull() ?: 0
                    if (source in declarationSources) {
                        excludedLines += c + m
                        continue
                    }
                    covered += c
                    missed += m
                    val row = perFile.getOrPut(source) { intArrayOf(0, 0) }
                    row[0] += c
                    row[1] += m
                }
            }
        }

        val total = covered + missed
        val ratio = if (total == 0) 0.0 else covered.toDouble() / total
        logger.lifecycle(
            "coverageGate: :core line coverage %.2f%% (%d/%d measured lines; %d declaration lines excluded)"
                .format(ratio * 100, covered, total, excludedLines),
        )

        val worst = perFile.entries.sortedByDescending { it.value[1] }.take(8)
        if (worst.any { it.value[1] > 0 }) {
            logger.lifecycle("coverageGate: files with uncovered lines:")
            worst.filter { it.value[1] > 0 }.forEach { (file, counts) ->
                logger.lifecycle("  ${counts[1]} missed / ${counts[0] + counts[1]}  $file")
            }
        }

        if (ratio < minimumRatio) {
            throw GradleException(
                "coverageGate failed: :core line coverage is %.2f%%, below the %.0f%% minimum"
                    .format(ratio * 100, minimumRatio * 100),
            )
        }
        logger.lifecycle("coverageGate: OK (minimum %.0f%%)".format(minimumRatio * 100))
    }
}
