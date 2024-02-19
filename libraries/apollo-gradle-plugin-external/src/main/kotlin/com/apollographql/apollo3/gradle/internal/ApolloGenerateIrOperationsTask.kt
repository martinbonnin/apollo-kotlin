package com.apollographql.apollo3.gradle.internal

import com.apollographql.apollo3.compiler.ApolloCompiler
import com.apollographql.apollo3.compiler.toCodegenSchema
import com.apollographql.apollo3.compiler.toIrOperations
import com.apollographql.apollo3.compiler.toIrOptions
import com.apollographql.apollo3.compiler.writeTo
import gratatouille.GInputFile
import gratatouille.GInputFiles
import gratatouille.GInternal
import gratatouille.GOutputFile
import gratatouille.GTaskAction


@GTaskAction
fun generateIrOperations(
    upstreamIrFiles: GInputFiles,
    graphqlFiles: GInputFiles,
    @GInternal sourceRoots: Set<String>,
    codegenSchemaFiles: GInputFiles,
    irOptions: GInputFile,
    irOperationsFile: GOutputFile
) {
  val upstreamIrOperations = upstreamIrFiles.map { it.toIrOperations() }

  val normalizedExecutableFiles = graphqlFiles.map {
    com.apollographql.apollo3.compiler.InputFile(it, it.normalizedPath(sourceRoots))
  }

  ApolloCompiler.buildIrOperations(
      executableFiles = normalizedExecutableFiles,
      codegenSchema = codegenSchemaFiles.findCodegenSchemaFile().toCodegenSchema(),
      upstreamCodegenModels = upstreamIrOperations.map { it.codegenModels },
      upstreamFragmentDefinitions = upstreamIrOperations.flatMap { it.fragmentDefinitions },
      options = irOptions.toIrOptions(),
      logger = logger(),
  ).writeTo(irOperationsFile)
}