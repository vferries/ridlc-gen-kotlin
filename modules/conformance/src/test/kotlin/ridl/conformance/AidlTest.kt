package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import ridl.codegen.kotlin.types.AidlEmitter
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * docs/design.md §7, "The AIDL is valid": the AIDL the plugin generates for
 * every corpus package with an interface. Its shape is checked here on every
 * run — each call on its ordinal, the control plane on its four codes — and
 * the files are written to `build/aidl`, which `scripts/check-aidl.sh` hands
 * the Android SDK's `aidl` tool in the CI job that carries the build-tools.
 * Where this machine has the SDK, the same check runs here too.
 */
class AidlTest {
    @TempDir
    lateinit var work: Path

    private val out: Path = Path.of(System.getProperty("aidl.dir", "build/aidl"))

    /** Every generated `.aidl` file of the corpus, by path, written under [out]. */
    private fun generated(): Map<String, String> {
        val files = Harness.packages().flatMap { name ->
            Harness.capturedRequests(name, work.resolve(name)).values.map(Wire::readRequest).flatMap { request ->
                val response = Generator.generate(request)
                assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
                response.filesList.filter { it.path.endsWith(".aidl") }.map { it.path.removePrefix("aidl/") to it.text }
            }
        }.toMap()
        for ((path, text) in files) {
            val file = out.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        return files
    }

    @Test
    fun `every call is on its ordinal and the control plane on its four codes`() {
        val files = generated()
        assertTrue("veh/cabin/ICabin.aidl" in files && "ridl/rt/Frame.aidl" in files, files.keys.toString())
        val cabin = files.getValue("veh/cabin/ICabin.aidl")
        assertTrue("oneway void setLevel(in Frame args) = 3;" in cabin, cabin)
        assertTrue("Frame average(in Frame args) = 4;" in cabin, cabin)
        for ((method, id) in listOf("attach" to AidlEmitter.ATTACH, "subscribe" to AidlEmitter.SUBSCRIBE, "unsubscribe" to AidlEmitter.UNSUBSCRIBE, "read" to AidlEmitter.READ)) {
            assertTrue(Regex("""\b$method\(.*\) = $id;""").containsMatchIn(cabin), "$method on $id")
        }
        val horn = files.getValue("veh/cabin/IHorn.aidl")
        assertTrue(Regex("""\) = \d+;""").findAll(horn).count() == 4, "Horn has no call, only the control plane")
        val probe = files.getValue("kt/values/IProbe.aidl")
        assertTrue("oneway void bump(in Frame args) = 3;" in probe && "Frame half(in Frame args) = 4;" in probe, probe)
    }

    @Test
    fun `the Android SDK's aidl tool accepts every generated file`() {
        val tool = aidlTool()
        assumeTrue(tool != null, "no Android SDK build-tools on this machine; the CI aidl job runs this check")
        val files = generated()
        val java = work.resolve("java")
        for (path in files.keys) {
            val process = ProcessBuilder(tool!!.path, "--lang=java", "-I", out.toString(), "-o", java.toString(), out.resolve(path).toString())
                .redirectErrorStream(true).start()
            val output = process.inputStream.readAllBytes().decodeToString()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "aidl did not finish on $path")
            assertEquals(0, process.exitValue(), "aidl refused $path:\n$output")
        }
        assertTrue(Files.walk(java).use { s -> s.anyMatch { it.fileName.toString() == "ICabin.java" } }, "aidl wrote ICabin.java")
    }

    /** `aidl` on `PATH`, or the newest under `$ANDROID_HOME` or `$ANDROID_SDK_ROOT`'s build-tools. */
    private fun aidlTool(): File? {
        System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "aidl") }.firstOrNull { it.canExecute() }?.let { return it }
        return listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
            .map { File(it, "build-tools") }
            .flatMap { it.listFiles()?.toList().orEmpty() }
            .sortedByDescending { it.name }
            .map { File(it, "aidl") }
            .firstOrNull { it.canExecute() }
    }
}
