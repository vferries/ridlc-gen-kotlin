package ridl.codegen.kotlin

import ridl.codegen.v1.Plugin.CodegenRequest
import ridl.codegen.v1.Plugin.CodegenResponse
import ridl.codegen.kotlin.types.CodecEmitter
import ridl.codegen.kotlin.types.TypesEmitter
import ridl.codegen.v1.Plugin.Diagnostic
import ridl.codegen.v1.Plugin.DiagnosticSeverity
import ridl.codegen.v1.Plugin.GeneratedFile

/** The schema this plugin reads (IR specification §7). */
const val SCHEMA: String = "ridl.codegen.v1"

/** The plugin's name, as `ridlc` names it in every message. */
const val PLUGIN: String = "ridlc-gen-kotlin"

/**
 * `generate(CodegenRequest) -> CodegenResponse`: the whole plugin, minus the
 * pipe. It opens no file and writes nothing but its return value.
 */
object Generator {
    fun generate(request: CodegenRequest): CodegenResponse {
        // Step 2: a schema this plugin does not read is a backend failure the
        // host reports, not a host failure, so it is a response.
        if (request.schema != SCHEMA) {
            return failure("$PLUGIN reads `$SCHEMA` and the request is `${request.schema}`")
        }
        // Step 3.
        val options = when (val parsed = Options.parse(request)) {
            is Options.Parsed.Refused -> return failure(parsed.messages)
            is Options.Parsed.Ok -> parsed.options
        }
        // Step 4: the files. Faces.kt lands in K3a.
        val files = listOf(
            TypesEmitter(request.model, options).emit(),
            CodecEmitter(request.model, options).emit(),
        )
        val errors = files.flatMap { it.errors }
        if (errors.isNotEmpty()) return failure(errors)
        return CodegenResponse.newBuilder()
            .addAllFiles(files.map { GeneratedFile.newBuilder().setPath(it.path).setText(it.text).build() })
            .build()
    }

    private fun failure(vararg messages: String): CodegenResponse = failure(messages.toList())

    private fun failure(messages: List<String>): CodegenResponse =
        CodegenResponse.newBuilder()
            .addAllDiagnostics(messages.map { error(it) })
            .build()

    private fun error(message: String): Diagnostic =
        Diagnostic.newBuilder()
            .setSeverity(DiagnosticSeverity.DIAGNOSTIC_SEVERITY_ERROR)
            .setMessage(message)
            .build()
}
