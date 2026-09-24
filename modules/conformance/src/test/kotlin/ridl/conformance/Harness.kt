package ridl.conformance

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText

/** What the build hands the tests: the pinned `ridl`, the installed plugin, the corpus. */
object Harness {
    val ridl: Path = Path.of(System.getProperty("ridl.bin"))
    val release: String = System.getProperty("ridl.release")
    val plugin: Path = Path.of(System.getProperty("plugin.bin"))
    val corpus: Path = Path.of(System.getProperty("corpus.dir"))

    /** Every corpus package: one directory holding a `ridl.toml`. */
    fun packages(): List<String> =
        corpus.listDirectoryEntries().filter { it.resolve("ridl.toml").isRegularFile() }.map { it.fileName.toString() }.sorted()

    /** A fresh copy of a corpus package, so a build never writes into the source tree. */
    fun copyOf(name: String, into: Path): Path {
        val source = corpus.resolve(name)
        val target = into.resolve("src-$name")
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val dest = target.resolve(path.relativeTo(source).toString())
                if (Files.isDirectory(path)) dest.createDirectories() else path.copyTo(dest)
            }
        }
        return target
    }

    class Run(val exit: Int, val stdout: String, val stderr: String)

    fun run(command: List<String>, stdin: String = "", dir: Path? = null): Run {
        val process = ProcessBuilder(command).apply { if (dir != null) directory(dir.toFile()) }.start()
        val out = process.inputStream.readAllBytesAsync()
        val err = process.errorStream.readAllBytesAsync()
        process.outputStream.use { it.write(stdin.toByteArray()) }
        check(process.waitFor(120, TimeUnit.SECONDS)) { "$command did not finish" }
        return Run(process.exitValue(), out.get().decodeToString(), err.get().decodeToString())
    }

    /** Runs the installed `ridlc-gen-kotlin` script on one request. */
    fun plugin(request: String): Run = run(listOf(plugin.absolutePathString()), request)

    /**
     * `ridl build` over a copy of the package, with the extra arguments, into
     * a fresh output directory, which is returned.
     */
    fun build(pkg: Path, out: Path, vararg args: String): Path {
        val result = run(listOf(ridl.absolutePathString(), "build", "--out-dir", out.absolutePathString(), *args, "."), dir = pkg)
        check(result.exit == 0) { "ridl build ${args.toList()} exited ${result.exit}:\n${result.stderr}" }
        return out
    }

    /**
     * The request the pinned `ridl` writes to a plugin for the package, byte
     * for byte: a stand-in plugin records its standard input and answers with
     * an empty response. The IR specification §8's fixture rule.
     */
    fun capturedRequest(name: String, work: Path): String {
        val pkg = copyOf(name, work)
        val recorded = work.resolve("request-$name.json")
        val capture = work.resolve("capture-$name.sh")
        capture.writeText("#!/bin/sh\ncat > '${recorded.absolutePathString()}'\nprintf '{}'\n")
        Files.setPosixFilePermissions(capture, PosixFilePermissions.fromString("rwxr-xr-x"))
        build(pkg, work.resolve("capture-out-$name"), "--plugin", "kotlin=${capture.absolutePathString()}")
        return recorded.readText()
    }

    /** Every file under [dir], by `/`-separated relative path. */
    fun files(dir: Path): Map<String, ByteArray> =
        if (!Files.exists(dir)) {
            emptyMap()
        } else {
            Files.walk(dir).use { paths ->
                paths.filter { it.isRegularFile() }.toList()
                    .associate { it.relativeTo(dir).joinToString("/") to it.readBytes() }
            }
        }

    private fun java.io.InputStream.readAllBytesAsync() =
        java.util.concurrent.CompletableFuture.supplyAsync { readAllBytes() }
}
