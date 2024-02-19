package com.apollographql.apollo3.gradle.internal

import com.apollographql.apollo3.compiler.ApolloCompiler
import com.apollographql.apollo3.compiler.toCodegenSchemaOptions
import com.apollographql.apollo3.compiler.writeTo
import gratatouille.GInputFile
import gratatouille.GInputFiles
import gratatouille.GInternal
import gratatouille.GOutputFile
import gratatouille.GTaskAction
import com.apollographql.apollo3.compiler.InputFile as ApolloInputFile

@GTaskAction
fun generateCodegenSchema(
    upstreamSchemaFiles: GInputFiles,
    schemaFiles: GInputFiles,
    fallbackSchemaFiles: GInputFiles,
    @GInternal sourceRoots: Set<String>,
    codegenSchemaOptionsFile: GInputFile,
    codegenSchemaFile: GOutputFile,
) {
  if (upstreamSchemaFiles.isNotEmpty()) {
    /**
     * Output an empty file
     */
    codegenSchemaFile.let {
      it.delete()
      it.createNewFile()
    }
    return
  }

  val normalizedSchemaFiles = (schemaFiles.takeIf { it.isNotEmpty() }?: fallbackSchemaFiles).map {
    // this may produce wrong cache results as that computation is not the same as the Gradle normalization
    ApolloInputFile(it, it.normalizedPath(sourceRoots))
  }

  ApolloCompiler.buildCodegenSchema(
      schemaFiles = normalizedSchemaFiles,
      logger = logger(),
      codegenSchemaOptions = codegenSchemaOptionsFile.toCodegenSchemaOptions(),
  ).writeTo(codegenSchemaFile)
}