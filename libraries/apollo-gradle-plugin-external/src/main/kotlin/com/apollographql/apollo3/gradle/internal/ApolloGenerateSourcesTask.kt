package com.apollographql.apollo3.gradle.internal

import com.apollographql.apollo3.compiler.ApolloCompiler
import com.apollographql.apollo3.compiler.CodegenSchema
import com.apollographql.apollo3.compiler.IrOptions
import com.apollographql.apollo3.compiler.LayoutFactory
import com.apollographql.apollo3.compiler.codegen.SchemaAndOperationsLayout
import com.apollographql.apollo3.compiler.codegen.writeTo
import com.apollographql.apollo3.compiler.toCodegenOptions
import com.apollographql.apollo3.compiler.toCodegenSchemaOptions
import gratatouille.GInputFile
import gratatouille.GInputFiles
import gratatouille.GInternal
import gratatouille.GManuallyWired
import gratatouille.GOutputDirectory
import gratatouille.GOutputFile
import gratatouille.GTaskAction
import java.io.File
import com.apollographql.apollo3.compiler.InputFile as ApolloInputFile

@GTaskAction
internal fun generateApolloSources(
    graphqlFiles: GInputFiles,
    schemaFiles: GInputFiles,
    fallbackSchemaFiles: GInputFiles,
    @GInternal sourceRoots: Set<String>,
    codegenSchemaOptions: GInputFile,
    codegenOptions: GInputFile,
    irOptions: IrOptions,
    @GManuallyWired outputDirectory: GOutputDirectory,
    operationManifestFile: GOutputFile,
) {
  val plugin = apolloCompilerPlugin()

  ApolloCompiler.buildSchemaAndOperationsSources(
      schemaFiles = (schemaFiles.takeIf { it.isNotEmpty() } ?: fallbackSchemaFiles).toInputFiles(sourceRoots),
      executableFiles = graphqlFiles.toInputFiles(sourceRoots),
      codegenSchemaOptions = codegenSchemaOptions.toCodegenSchemaOptions(),
      codegenOptions = codegenOptions.toCodegenOptions(),
      irOptions = irOptions,
      logger = null,
      layoutFactory = object : LayoutFactory {
        override fun create(codegenSchema: CodegenSchema): SchemaAndOperationsLayout? {
          return plugin?.layout(codegenSchema)
        }
      },
      operationOutputGenerator = plugin?.toOperationOutputGenerator(),
      irOperationsTransform = plugin?.irOperationsTransform(),
      javaOutputTransform = plugin?.javaOutputTransform(),
      kotlinOutputTransform = plugin?.kotlinOutputTransform(),
      operationManifestFile = operationManifestFile
  ).writeTo(outputDirectory, true, null)
}

fun Set<File>.toInputFiles(sourceRoots: Set<String>) = map {
  ApolloInputFile(it, it.normalizedPath(sourceRoots))
}

