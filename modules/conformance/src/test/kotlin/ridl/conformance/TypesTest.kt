package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import ridl.codegen.v1.ModelOuterClass.Model
import java.nio.file.Path

/**
 * docs/design.md §7 for `Types.kt`: for every corpus package, what the plugin
 * generates from the requests the pinned `ridl` writes compiles against
 * `ridl-rt-kt` with warnings as errors, and its value objects refuse what
 * the model's constraints forbid.
 */
class TypesTest {
    @TempDir
    lateinit var work: Path

    private class Generated(val models: List<Model>, val sources: Map<String, String>)

    /** Every package the build of [name] hands the plugin, generated in process. */
    private fun generate(name: String): Generated {
        val requests = Harness.capturedRequests(name, work.resolve("capture-$name")).values.map(Wire::readRequest)
        val sources = requests.associate { request ->
            val response = Generator.generate(request)
            assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message }, request.model.name.dotted)
            val file = response.filesList.single()
            file.path to file.text
        }
        return Generated(requests.map { it.model }, sources)
    }

    @TestFactory
    fun `the generated Types kt compiles`(): List<DynamicTest> = Harness.packages().map { name ->
        DynamicTest.dynamicTest(name) {
            val generated = generate(name)
            assertTrue(generated.sources.keys.all { it.endsWith("/Types.kt") }, generated.sources.keys.toString())
            val compiled = Compiler.compile(generated.sources, work.resolve("compile-$name"))
            assertTrue(compiled.ok, compiled.messages)
        }
    }

    @TestFactory
    fun `the value objects refuse invalid values`(): List<DynamicTest> = Harness.packages().map { name ->
        DynamicTest.dynamicTest(name) {
            val generated = generate(name)
            val probes = mutableMapOf("probe/Probe.kt" to Probe.source(generated.models))
            val own = javaClass.getResource("/probes/$name.kt")
            if (own != null) probes["probe/$name.kt"] = own.readText()
            val compiled = Compiler.compile(generated.sources + probes, work.resolve("probe-$name"))
            assertTrue(compiled.ok, compiled.messages)
            val failures = mutableListOf<String>()
            failures += run(compiled.classLoader!!, "ridl.conformance.probe.Probe")
            if (own != null) failures += run(compiled.classLoader, "ridl.conformance.probe.values.ValuesProbe")
            assertEquals(emptyList<String>(), failures)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun run(loader: ClassLoader, className: String): List<String> =
        loader.loadClass(className).getMethod("probe").invoke(null) as List<String>
}
