package com.apollographql.apollo.cli

import com.apollographql.apollo.compiler.DefaultPackageNameProvider
import com.apollographql.apollo.compiler.GraphQLCompiler
import com.apollographql.apollo.compiler.Roots
import com.apollographql.apollo.compiler.ir.Fragment
import com.apollographql.apollo.compiler.ir.IRBuilder
import com.apollographql.apollo.compiler.ir.Operation
import com.apollographql.apollo.compiler.parser.graphql.GraphQLDocumentParser
import com.apollographql.apollo.compiler.parser.introspection.IntrospectionSchema
import com.apollographql.apollo.compiler.parser.sdl.GraphSDLSchemaParser
import com.apollographql.apollo.compiler.parser.sdl.GraphSDLSchemaParser.parse
import com.apollographql.apollo.compiler.parser.sdl.toIntrospectionSchema
import com.apollographql.apollo.compiler.toJson
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.io.File

val generateIr = object: CliktCommand(name = "generateIr", help = "Generates IR for the given schema and GraphQL file") {
  val schema by option().required()
  val query by option().required()

  override fun run() {
    val roots = Roots(listOf(File(schema).parentFile))
    val packageNameProvider = DefaultPackageNameProvider(
        roots = roots,
        rootPackageName = ""
    )

    val files = listOf(File(query))

    val introspectionSchema = if (schema.endsWith("json")) {
      IntrospectionSchema(File(schema))
    } else {
      File(schema).parse().toIntrospectionSchema()
    }

    val parseResult = GraphQLDocumentParser(
        schema = introspectionSchema,
        packageNameProvider = packageNameProvider
    ).parse(files)

    val compilerIr = IRBuilder(
        schema = introspectionSchema,
        schemaPackageName = "",
        incomingMetadata = null,
        alwaysGenerateTypesMatching = null,
        generateMetadata = false
    ).build(parseResult)

    println(mapOf(
        "operations" to compilerIr.operations,
        "fragments" to compilerIr.fragments
    ).toJson())
  }
}

fun main(args: Array<String>) {
  generateIr.main(args)
}