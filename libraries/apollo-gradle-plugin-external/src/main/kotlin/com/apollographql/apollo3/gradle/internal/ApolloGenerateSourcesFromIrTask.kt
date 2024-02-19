package com.apollographql.apollo3.gradle.internal

import com.apollographql.apollo3.compiler.ApolloCompiler
import com.apollographql.apollo3.compiler.codegen.writeTo
import com.apollographql.apollo3.compiler.mergeWith
import com.apollographql.apollo3.compiler.toCodegenMetadata
import com.apollographql.apollo3.compiler.toCodegenOptions
import com.apollographql.apollo3.compiler.toCodegenSchema
import com.apollographql.apollo3.compiler.toIrOperations
import com.apollographql.apollo3.compiler.toUsedCoordinates
import com.apollographql.apollo3.compiler.writeTo
import gratatouille.GInputFile
import gratatouille.GInputFiles
import gratatouille.GManuallyWired
import gratatouille.GOutputDirectory
import gratatouille.GOutputFile
import gratatouille.GTaskAction
import java.io.File

internal fun Iterable<File>.findCodegenSchemaFile(): File {
  return firstOrNull {
    it.length() > 0
  } ?: error("Cannot find CodegenSchema in $this")
}

@GTaskAction
internal fun generateSourcesFromIr(
    codegenSchemas: GInputFiles,
    irOperations: GInputFile,
    downstreamUsedCoordinates: GInputFile,
    upstreamMetadata: GInputFiles,
    codegenOptions: GInputFile,
    operationManifestFile: GOutputFile,
    @GManuallyWired outputDir: GOutputDirectory,
    metadataOutputFile: GOutputFile,
) {
  val codegenSchemaFile = codegenSchemas.findCodegenSchemaFile()

  val codegenSchema = codegenSchemaFile.toCodegenSchema()
  val plugin = apolloCompilerPlugin()

  ApolloCompiler.buildSchemaAndOperationsSourcesFromIr(
      codegenSchema = codegenSchema,
      irOperations = irOperations.toIrOperations(),
      downstreamUsedCoordinates = downstreamUsedCoordinates.toUsedCoordinates(),
      upstreamCodegenMetadata = upstreamMetadata.map { it.toCodegenMetadata() },
      codegenOptions = codegenOptions.toCodegenOptions(),
      layout = plugin?.layout(codegenSchema),
      irOperationsTransform = plugin?.irOperationsTransform(),
      javaOutputTransform = plugin?.javaOutputTransform(),
      kotlinOutputTransform = plugin?.kotlinOutputTransform(),
      operationManifestFile = operationManifestFile,
      operationOutputGenerator = plugin?.toOperationOutputGenerator()
  ).writeTo(outputDir, true, metadataOutputFile)
}

@GTaskAction
internal fun extractUsedCoordinates(
    downstreamIrOperations: GInputFiles,
    usedCoordinates: GOutputFile,
) {
  downstreamIrOperations.map {
    it.toIrOperations()
  }.fold(emptyMap<String, Set<String>>()) { acc, element ->
    acc.mergeWith(element.usedFields)
  }.writeTo(usedCoordinates)
}
