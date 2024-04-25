package com.compiler.server.compiler.components

import com.compiler.server.model.*
import com.fasterxml.jackson.databind.ObjectMapper
import component.KotlinEnvironment
import org.jetbrains.kotlin.cli.js.K2JsIrCompiler
import org.jetbrains.kotlin.psi.KtFile
import org.springframework.stereotype.Service
import kotlin.io.path.div
import kotlin.io.path.readBytes
import kotlin.io.path.readText

@Service
class KotlinToJSTranslator(
  private val kotlinEnvironment: KotlinEnvironment,
) {
  companion object {
    private const val JS_IR_CODE_BUFFER = "moduleId.output?.buffer_1;\n"

    private val JS_IR_OUTPUT_REWRITE = """
        if (typeof get_output !== "undefined") {
          get_output();
          output = new BufferedOutput();
          _.output = get_output();
        }
        """.trimIndent()

    const val BEFORE_MAIN_CALL_LINE = 4
  }

  fun translateJs(
    files: List<KtFile>,
    arguments: List<String>,
    translate: (List<KtFile>, List<String>) -> CompilationResult<String>
  ): TranslationJSResult = try {
    val compilationResult = translate(files, arguments)
    val jsCode = when (compilationResult) {
      is Compiled<String> -> compilationResult.result
      is NotCompiled -> null
    }
    TranslationJSResult(jsCode = jsCode, compilerDiagnostics = compilationResult.compilerDiagnostics)
  } catch (e: Exception) {
    TranslationJSResult(exception = e.toExceptionDescriptor())
  }

  fun translateWasm(
    files: List<KtFile>,
    debugInfo: Boolean,
    projectType: ProjectType,
    translate: (List<KtFile>, List<String>, List<String>, List<String>) -> CompilationResult<WasmTranslationSuccessfulOutput>
  ): TranslationResultWithJsCode {
    return try {
      val (dependencies, compilerPlugins, compilerPluginOptions) = when (projectType) {
        ProjectType.WASM -> listOf(
          kotlinEnvironment.WASM_LIBRARIES,
          emptyList(),
          emptyList()
        )
        ProjectType.COMPOSE_WASM -> listOf(
          kotlinEnvironment.COMPOSE_WASM_LIBRARIES,
          kotlinEnvironment.COMPOSE_WASM_COMPILER_PLUGINS,
          kotlinEnvironment.composeWasmCompilerPluginOptions
        )
        else -> throw IllegalStateException("Wasm should have wasm or compose-wasm project type")
      }
      val compilationResult = translate(
        files,
        dependencies,
        compilerPlugins,
        compilerPluginOptions
      )
      val wasmCompilationOutput = when (compilationResult) {
        is Compiled<WasmTranslationSuccessfulOutput> -> compilationResult.result
        is NotCompiled -> return TranslationJSResult(compilerDiagnostics = compilationResult.compilerDiagnostics)
      }
      TranslationWasmResult(
        jsCode = wasmCompilationOutput.jsCode,
        jsInstantiated = wasmCompilationOutput.jsInstantiated,
        compilerDiagnostics = compilationResult.compilerDiagnostics,
        wasm = wasmCompilationOutput.wasm,
        wat = if (debugInfo) wasmCompilationOutput.wat else null
      )
    } catch (e: Exception) {
      TranslationJSResult(exception = e.toExceptionDescriptor())
    }
  }

  fun doTranslateWithIr(files: List<KtFile>, arguments: List<String>): CompilationResult<String> =
    usingTempDirectory { inputDir ->
      val moduleName = "moduleId"
      usingTempDirectory { outputDir ->
        val ioFiles = files.writeToIoFiles(inputDir)
        val k2JsIrCompiler = K2JsIrCompiler()
        val filePaths = ioFiles.map { it.toFile().canonicalPath }
        val klibPath = (outputDir / "klib").toFile().canonicalPath
        val additionalCompilerArgumentsForKLib = listOf(
          "-Xir-only",
          "-Xir-produce-klib-dir",
          "-libraries=${kotlinEnvironment.JS_LIBRARIES.joinToString(PATH_SEPARATOR)}",
          "-ir-output-dir=$klibPath",
          "-ir-output-name=$moduleName",
        )
        k2JsIrCompiler.tryCompilation(inputDir, ioFiles, filePaths + additionalCompilerArgumentsForKLib)
          .flatMap {
            k2JsIrCompiler.tryCompilation(inputDir, ioFiles, listOf(
              "-Xir-only",
              "-Xir-produce-js",
              "-Xir-dce",
              "-Xinclude=$klibPath",
              "-libraries=${kotlinEnvironment.JS_LIBRARIES.joinToString(PATH_SEPARATOR)}",
              "-ir-output-dir=${(outputDir / "js").toFile().canonicalPath}",
              "-ir-output-name=$moduleName",
            ))
          }
          .map { (outputDir / "js" / "$moduleName.js").readText() }
          .map { it.withMainArgumentsIr(arguments, moduleName) }
          .map(::redirectOutput)
      }
    }

  private fun redirectOutput(code: String): String {
    val listLines = code
      .lineSequence()
      .toMutableList()

    listLines.add(listLines.size - BEFORE_MAIN_CALL_LINE, JS_IR_OUTPUT_REWRITE)
    listLines.add(listLines.size - 1, JS_IR_CODE_BUFFER)
    return listLines.joinToString("\n")
  }


  fun doTranslateWithWasm(
    files: List<KtFile>,
    dependencies: List<String>,
    compilerPlugins: List<String>,
    compilerPluginOptions: List<String>,
  ): CompilationResult<WasmTranslationSuccessfulOutput> =
    usingTempDirectory { inputDir ->
      val moduleName = "moduleId"
      usingTempDirectory { outputDir ->
        val ioFiles = files.writeToIoFiles(inputDir)
        val k2JsIrCompiler = K2JsIrCompiler()
        val filePaths = ioFiles.map { it.toFile().canonicalPath }
        val klibPath = (outputDir / "klib").toFile().canonicalPath
          val compilerPluginsArgs: List<String> = compilerPlugins
              .takeIf { it.isNotEmpty() }
              ?.let { plugins ->
                  plugins.map {
                      "-Xplugin=$it"
                  } + compilerPluginOptions.map {
                      "-P=$it"
                  }
              } ?: emptyList()
          val additionalCompilerArgumentsForKLib: List<String> = listOf(
          "-Xwasm",
          "-Xir-produce-klib-dir",
          "-libraries=${dependencies.joinToString(PATH_SEPARATOR)}",
          "-ir-output-dir=$klibPath",
          "-ir-output-name=$moduleName",
        ) + compilerPluginsArgs

        k2JsIrCompiler.tryCompilation(inputDir, ioFiles, filePaths + additionalCompilerArgumentsForKLib)
          .flatMap {
            k2JsIrCompiler.tryCompilation(inputDir, ioFiles, listOf(
              "-Xwasm",
              "-Xwasm-generate-wat",
              "-Xir-produce-js",
              "-Xir-dce",
              "-Xinclude=$klibPath",
              "-libraries=${dependencies.joinToString(PATH_SEPARATOR)}",
              "-ir-output-dir=${(outputDir / "wasm").toFile().canonicalPath}",
              "-ir-output-name=$moduleName",
            ))
          }
          .map {
            WasmTranslationSuccessfulOutput(
              jsCode = (outputDir / "wasm" / "$moduleName.uninstantiated.mjs").readText(),
              jsInstantiated = (outputDir / "wasm" / "$moduleName.mjs").readText(),
              wasm = (outputDir / "wasm" / "$moduleName.wasm").readBytes(),
              wat = (outputDir / "wasm" / "$moduleName.wat").readText(),
            )
          }
      }
    }
}

private fun String.withMainArgumentsIr(arguments: List<String>, moduleName: String): String {
  val postfix = """|  main([]);
                   |  return _;
                   |}(typeof $moduleName === 'undefined' ? {} : $moduleName);
                   |""".trimMargin()
  if (!endsWith(postfix)) return this
  val objectMapper = ObjectMapper()
  return this.removeSuffix(postfix) + """|  main([${arguments.joinToString { objectMapper.writeValueAsString(it) }}]);
                   |  return _;
                   |}(typeof $moduleName === 'undefined' ? {} : $moduleName);
                   |""".trimMargin()
}

data class WasmTranslationSuccessfulOutput(
  val jsCode: String,
  val jsInstantiated: String,
  val wasm: ByteArray,
  val wat: String?,
)
