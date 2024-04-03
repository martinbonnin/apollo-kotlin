package com.apollographql.apollo3.ksp

import com.apollographql.apollo3.compiler.sir.Instantiation
import com.apollographql.apollo3.compiler.sir.SirCoercing
import com.apollographql.apollo3.compiler.sir.SirScalarDefinition
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias

/**
 * Returns a list of scalar. [SirScalarDefinition.name] is unique in the list. Duplicate scalar output an error
 */
fun getScalarDefinitions(logger: KSPLogger, declarations: List<KSAnnotated>): List<SirScalarDefinition> {
  return ScalarContext(logger).walk(declarations)
}

private class ScalarContext(val logger: KSPLogger) {
  val typeDefinitions = mutableMapOf<String, SirScalarDefinition>()

  fun walk(scalarDeclarations: List<KSAnnotated>): List<SirScalarDefinition> {
    scalarDeclarations.forEach {declaration ->
      val coercing = declaration.coercing(logger)
      if (coercing == null) {
        return@forEach
      }
      when (declaration) {
        is KSTypeAlias -> {
          val name = declaration.graphqlNameOrNull() ?: declaration.simpleName.asString()
          if (typeDefinitions.containsKey(name)) {
            logger.error("Scalar '$name' is defined multiple times", declaration)
            return@forEach
          }
          typeDefinitions.put(name, SirScalarDefinition(
              name = name,
              description = declaration.docString,
              qualifiedName = declaration.asClassName().asString(),
              aliasedName = declaration.type.resolve().declaration.asClassName().asString(),
              coercing = coercing
          ))
        }
        is KSClassDeclaration -> {
          val name = declaration.graphqlNameOrNull() ?: declaration.simpleName.asString()
          if (typeDefinitions.containsKey(name)) {
            logger.error("Scalar '$name' is defined multiple times", declaration)
            return@forEach
          }
          typeDefinitions.put(name, SirScalarDefinition(
              name = name,
              description = declaration.docString,
              qualifiedName = declaration.asClassName().asString(),
              aliasedName = null,
              coercing = coercing
          ))
        }
      }
    }

    return this.typeDefinitions.values.toList()
  }
}


fun KSAnnotated.coercing(logger: KSPLogger): SirCoercing? {
  val ksType = findAnnotation("GraphQLScalar")?.getArgumentValue("coercing") as KSType
  val declaration = ksType.declaration
  val instantiation = when {
    declaration is KSClassDeclaration -> {
      declaration.instantiation().also {
        if (it == Instantiation.UNKNOWN) {
          logger.error("Coercing implementation must either be objects or have a no-arg constructor", this)
          return null
        }
      }
    }
    else -> {
      logger.error("Coercing must be a class or object", this)
      return null
    }

  }
  return SirCoercing(
      className = declaration.asClassName(),
      instantiation = instantiation
  )
}
