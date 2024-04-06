package com.apollographql.apollo3.ksp

import com.apollographql.apollo3.annotations.ApolloInternal
import com.apollographql.apollo3.compiler.ApolloCompiler
import com.apollographql.apollo3.compiler.codegen.SourceOutput
import com.apollographql.apollo3.compiler.sir.SirClassName
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration


@OptIn(ApolloInternal::class)
class ApolloProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val packageName: String,
    private val serviceName: String,
) : SymbolProcessor {
  private var done = false

  private fun Resolver.getRootSymbol(annotationName: String): KSClassDeclaration? {
    val ret = getSymbolsWithAnnotation(annotationName).toList()

    if (ret.size > 1) {
      val locations = ret.map { it.location }.joinToString("\n")
      logger.error("There can be only one '$annotationName' annotated class, found ${ret.size}:\n$locations", ret.first())
      return null
    }

    ret.forEach {
      if (it !is KSClassDeclaration || it.isAbstract()) {
        logger.error("'$annotationName' cannot be set on node $it", it)
        return null
      }
    }

    return ret.singleOrNull() as KSClassDeclaration?
  }

  override fun process(resolver: Resolver): List<KSAnnotated> {
    if (done) {
      return emptyList()
    }
    done = true
    val scalarDeclarations = resolver.getSymbolsWithAnnotation("com.apollographql.apollo3.annotations.GraphQLScalar").toList()
    val scalarDefinitions = getScalarDefinitions(
        logger,
        scalarDeclarations
    )

    val query = resolver.getRootSymbol("com.apollographql.apollo3.annotations.GraphQLQueryRoot")
    if (query == null) {
      logger.error("No '@GraphqlQueryRoot' class found")
      return emptyList()
    }

    val typeDefinitions = getTypeDefinitions(
        logger,
        scalarDefinitions,
        query,
        resolver.getRootSymbol("com.apollographql.apollo3.annotations.GraphQLMutationRoot"),
        resolver.getRootSymbol("com.apollographql.apollo3.annotations.GraphQLSubscriptionRoot")
    )
    val sourceOutput = ApolloCompiler.buildExecutableSchemaSources(
        typeDefinitions = scalarDefinitions + typeDefinitions,
        packageName = packageName,
        serviceName = serviceName
    )

    sourceOutput.writeTo(codeGenerator)

    return emptyList()
  }
}

private fun SourceOutput.writeTo(codeGenerator: CodeGenerator) {
  files.forEach { sourceFile ->
    codeGenerator.createNewFile(
        // XXX: make more incremental
        Dependencies.ALL_FILES,
        packageName = sourceFile.packageName,
        // SourceFile contains .kt
        fileName = sourceFile.name.substringBeforeLast('.'),

        ).use {
      sourceFile.writeTo(it)
    }
  }
}

internal fun KSClassDeclaration.hasNoArgsConstructor(): Boolean {
  return getConstructors().any {
    it.parameters.isEmpty()
  }
}

internal fun KSAnnotated.deprecationReason(): String? {
  return findAnnotation("Deprecated")?.getArgumentValueAsString("reason")
}
internal fun KSClassDeclaration.graphqlName(): String {
  return graphqlNameOrNull() ?: simpleName.asString()
}
internal fun KSAnnotated.graphqlNameOrNull(): String? {
  return findAnnotation("GraphQLName")?.getArgumentValueAsString("name")
}

internal fun KSPropertyDeclaration.graphqlName(): String {
  return graphqlNameOrNull() ?: simpleName.asString()
}
internal fun KSAnnotated.findAnnotation(name: String): KSAnnotation? {
  return annotations.firstOrNull { it.shortName.asString() == name }
}

internal fun KSAnnotation.getArgumentValue(name: String): Any? {
  return arguments.firstOrNull {
    it.name!!.asString() == name
  }?.value
}

internal fun KSAnnotation.getArgumentValueAsString(name: String): String? {
  return getArgumentValue(name)?.toString()
}

internal fun KSDeclaration.asClassName(): SirClassName {
  return SirClassName(packageName.asString(), listOf(simpleName.asString()))
}

internal val executionContextClassName = SirClassName("com.apollographql.apollo3.api", listOf("ExecutionContext"))



