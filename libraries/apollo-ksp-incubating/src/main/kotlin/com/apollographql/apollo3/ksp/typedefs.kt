package com.apollographql.apollo3.ksp

import com.apollographql.apollo3.compiler.sir.SirArgument
import com.apollographql.apollo3.compiler.sir.SirEnumDefinition
import com.apollographql.apollo3.compiler.sir.SirEnumValueDefinition
import com.apollographql.apollo3.compiler.sir.SirErrorType
import com.apollographql.apollo3.compiler.sir.SirExecutionContextArgument
import com.apollographql.apollo3.compiler.sir.SirFieldDefinition
import com.apollographql.apollo3.compiler.sir.SirGraphQLArgument
import com.apollographql.apollo3.compiler.sir.SirInputFieldDefinition
import com.apollographql.apollo3.compiler.sir.SirInputObjectDefinition
import com.apollographql.apollo3.compiler.sir.SirInterfaceDefinition
import com.apollographql.apollo3.compiler.sir.SirListType
import com.apollographql.apollo3.compiler.sir.SirNamedType
import com.apollographql.apollo3.compiler.sir.SirNonNullType
import com.apollographql.apollo3.compiler.sir.SirObjectDefinition
import com.apollographql.apollo3.compiler.sir.SirScalarDefinition
import com.apollographql.apollo3.compiler.sir.SirType
import com.apollographql.apollo3.compiler.sir.SirTypeDefinition
import com.apollographql.apollo3.compiler.sir.SirUnionDefinition
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.isConstructor
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier

fun getTypeDefinitions(
    logger: KSPLogger,
    scalarDefinitions: List<SirScalarDefinition>,
    query: KSClassDeclaration,
    mutation: KSClassDeclaration?,
    subscription: KSClassDeclaration?,
): List<SirTypeDefinition> {
  return TypeDefinitionContext(logger, scalarDefinitions).walk(query, mutation, subscription)
}

private class TypeDefinitionContext(val logger: KSPLogger, val scalarDefinitions: List<SirScalarDefinition>) {
  /**
   * key is qualifiedName or aliased name
   * null is a sentinel for an aliased type that is used by 2 different scalars and can therefore not be resolved.
   */
  val scalars: Map<String, SirScalarDefinition?>

  /**
   * key is qualifiedName
   * null is a sentinel for a declaration that failed analysis and that we shouldn't try analysing again.
   */
  val typeDefinitions = mutableMapOf<String, SirTypeDefinition?>()

  val declarationsToVisit = mutableListOf<DeclarationToVisit>()

  init {
    /**
     * Build the mapping from qualifiedName -> scalar definition
     *
     * If the same aliased type is used by 2 different scalars it won't be possible to reference it in code
     */
    scalars = buildList {
      scalarDefinitions.forEach {
        add(it.qualifiedName to it)
        if (it.aliasedName != null) {
          add(it.aliasedName!! to it)
        }
      }
    }.groupBy(
        keySelector = {
          it.first
        },
        valueTransform = {
          it.second
        }
    ).mapValues {
      if (it.value.size > 1) {
        null
      } else {
        it.value.single()
      }
    }
  }

  fun walk(query: KSClassDeclaration, mutation: KSClassDeclaration?, subscription: KSClassDeclaration?): List<SirTypeDefinition> {
    declarationsToVisit.add(DeclarationToVisit(query, VisitContext.OUTPUT, "query"))
    if (mutation != null) {
      declarationsToVisit.add(DeclarationToVisit(mutation, VisitContext.OUTPUT, "mutation"))
    }
    if (subscription != null) {
      declarationsToVisit.add(DeclarationToVisit(subscription, VisitContext.OUTPUT, "subscription"))
    }

    while (declarationsToVisit.isNotEmpty()) {
      val declarationToVisit = declarationsToVisit.removeFirst()
      val declaration = declarationToVisit.declaration
      val context = declarationToVisit.context

      val qualifiedName = declaration.asClassName().asString()
      if (typeDefinitions.containsKey(qualifiedName)) {
        // Already handled
        continue
      }

      val sirTypeDefinition = when {
        declaration.classKind == ClassKind.ENUM_CLASS -> {
          declaration.toSirEnum()
        }

        context == VisitContext.INPUT -> {
          declaration.toSirInputObject()
        }

        context == VisitContext.OUTPUT -> {
          declaration.toSirComposite(declarationToVisit.isoperationType)
        }

        else -> error("not reachable")
      }
      typeDefinitions.put(qualifiedName, sirTypeDefinition)
    }
    return this.typeDefinitions.values.filterNotNull().toList()
  }

  private fun KSClassDeclaration.toSirEnum(): SirEnumDefinition? {
    val enumValueDefinitions = this.declarations.filterIsInstance<KSClassDeclaration>().filter {
      it.classKind == ClassKind.ENUM_ENTRY
    }.map {
      SirEnumValueDefinition(
          name = it.graphqlName(),
          description = it.docString,
          className = it.asClassName()
      )
    }.toList()

    return SirEnumDefinition(
        name = graphqlName(),
        description = docString,
        qualifiedName = asClassName().asString(),
        values = enumValueDefinitions
    )
  }

  private fun KSClassDeclaration.toSirComposite(operationType: String?): SirTypeDefinition? {
    val propertyFields = getDeclaredProperties().filter {
      it.isPublic()
    }.mapNotNull {
      it.toSirFieldDefinition(operationType)
    }

    val functionFields = getDeclaredFunctions().filter {
      it.isPublic() && !it.isConstructor()
    }.mapNotNull {
      it.toSirFieldDefinition(operationType)
    }

    val allFields = propertyFields.toList() + functionFields.toList()

    val name = graphqlName()
    val description = docString
    val qualifiedName = asClassName().asString()

    return when {
      classKind == ClassKind.CLASS || classKind == ClassKind.OBJECT -> {
        if (modifiers.contains(Modifier.ABSTRACT)) {
          logger.error("Abstract classes are not supported", this)
          return null
        }
        SirObjectDefinition(
            name = name,
            description = description,
            qualifiedName = qualifiedName,
            interfaces = interfaces(),
            targetClassName = asClassName(),
            isSingleton = classKind == ClassKind.OBJECT,
            hasNoArgsConstructor = hasNoArgsConstructor(),
            operationType = operationType,
            fields = allFields
        )
      }

      classKind == ClassKind.INTERFACE -> {
        if (!modifiers.contains(Modifier.SEALED)) {
          logger.error("Interfaces and unions must be sealed interfaces", this)
          return null
        }

        val subclasses = getSealedSubclasses().map {
          // Look into subclasses
          declarationsToVisit.add(DeclarationToVisit(it, VisitContext.OUTPUT, null))
          it.graphqlName()
        }.toList()

        if (allFields.isEmpty()) {
          SirUnionDefinition(
              name = name,
              description = description,
              qualifiedName = qualifiedName,
              memberTypes = subclasses
          )
        } else {
          SirInterfaceDefinition(
              name = name,
              description = description,
              qualifiedName = qualifiedName,
              interfaces = interfaces(),
              fields = allFields
          )
        }
      }

      else -> {
        logger.error("Not a valid declaration", this)
        null
      }
    }
  }

  private fun KSClassDeclaration.interfaces(): List<String> {
    return getAllSuperTypes().mapNotNull {
      val declaration = it.declaration
      if (it.arguments.isNotEmpty()) {
        logger.error("Generic interfaces are not supported", this)
        null
      } else if (declaration is KSClassDeclaration) {
        if (declaration.asClassName().asString() == "kotlin.Any") {
          null
        } else if (declaration.containingFile == null) {
          logger.error("Class '${simpleName.asString()}' has a super class without a containing file that probably comes from a dependency.", this)
          null
        } else {
          declarationsToVisit.add(DeclarationToVisit(declaration, VisitContext.OUTPUT, null))
          declaration.graphqlName()
        }
      } else {
        logger.error("Unrecognized super class", this)
        null
      }
    }.toList()
  }

  private fun KSFunctionDeclaration.toSirFieldDefinition(operationType: String?): SirFieldDefinition? {
    if (returnType == null) {
      logger.error("No return type?", this)
      return null
    }
    val name = graphqlNameOrNull() ?: simpleName.asString()
    return SirFieldDefinition(
        name = name,
        description = docString,
        targetName = simpleName.asString(),
        isFunction = true,
        type = returnType!!.resolve().toSirType(SirDebugContext(this), VisitContext.OUTPUT, operationType),
        arguments = parameters.mapNotNull {
          it.toSirArgument()
        }
    )
  }

  private fun KSValueParameter.toSirArgument(): SirArgument? {
    if (this.type.resolve().declaration.asClassName() == executionContextClassName) {
      return SirExecutionContextArgument
    }
    val targetName = this.name!!.asString()
    val name = this.graphqlNameOrNull() ?: targetName

    if (this.hasDefault) {
      logger.error("Default argument are not supported")
      return null
    }
    return SirGraphQLArgument(
        name = name,
        description = null,
        targetName = targetName,
        type = type.resolve().toSirType(SirDebugContext(this), VisitContext.OUTPUT, operationType = null),
        defaultValue = null
    )
  }

  private fun KSPropertyDeclaration.toSirFieldDefinition(operationType: String?): SirFieldDefinition? {
    return SirFieldDefinition(
        name = graphqlName(),
        description = docString,
        targetName = simpleName.asString(),
        isFunction = false,
        type = type.resolve().toSirType(SirDebugContext(this), VisitContext.OUTPUT, operationType),
        arguments = emptyList()
    )
  }

  private fun KSClassDeclaration.toSirInputObject(): SirInputObjectDefinition? {
    if (classKind != ClassKind.CLASS) {
      logger.error("Input objects must be classes", this)
      return null
    }

    val inputFields = primaryConstructor!!.parameters.map {
      val name = it.graphqlNameOrNull() ?: it.name?.asString()
      if (name == null) {
        logger.error("Cannot find name for parameter", it)
        return null
      }
      if (name.isReserved()) {
        logger.error("Name '$name' is reserved", it)
        return null
      }
      val declaration = it.type.resolve()
      SirInputFieldDefinition(
          name = name,
          description = docString,
          type = declaration.toSirType(SirDebugContext(it), VisitContext.INPUT, null),
          defaultValue = null
      )
    }

    return SirInputObjectDefinition(
        name = graphqlName(),
        description = docString,
        qualifiedName = asClassName().asString(),
        inputFields
    )
  }

  private fun KSType.toSirType(debugContext: SirDebugContext, context: VisitContext, operationType: String?): SirType {
    val type = if (operationType == "subscription") {
      if (declaration.asClassName().asString() != "kotlinx.coroutines.flow.Flow") {
        logger.error("Subscription root fields must be of Flow<T> type", this.declaration)
        return SirErrorType
      }
      arguments.single().type!!.resolve()
    } else {
      if (declaration.asClassName().asString() == "kotlinx.coroutines.flow.Flow") {
        logger.error("Flows are not supported", this.declaration)
        return SirErrorType
      }
      this
    }

    return type.toSirType2(debugContext, context, type.isMarkedNullable)
  }

  private fun KSType.toSirType2(debugContext: SirDebugContext, context: VisitContext, isNullable: Boolean): SirType {
    if (!isNullable) {
      return SirNonNullType(toSirType2(debugContext, context, true))
    }

    return when (val qualifiedName = declaration.asClassName().asString()) {
      "kotlin.collections.List" -> SirListType(arguments.single().type!!.resolve().toSirType(debugContext, context, null))
      "kotlin.String" -> SirNamedType("String")
      "kotlin.Double" -> SirNamedType("Float")
      "kotlin.Int" -> SirNamedType("Int")
      "kotlin.Boolean" -> SirNamedType("Boolean")
      else -> {
        if (scalars.containsKey(qualifiedName)) {
          val definition = scalars.get(qualifiedName)
          if (definition == null) {
            logger.error("Aliased type '$qualifiedName' is used by 2 different scalars. Use the type alias to disambiguate.", declaration)
            SirErrorType
          } else {
            SirNamedType(definition.name)
          }
        } else {
          val declaration = declaration
          if (declaration.containingFile == null) {
            logger.error("'$qualifiedName' doesn't have a containing file and probably comes from a dependency (when analyzing '${debugContext.name}').", debugContext.node)
            SirErrorType
          } else if (unsupportedBasicTypes.contains(qualifiedName)) {
            logger.error("'$qualifiedName' is not a supported built-in type. Either use one of the built-in types (Boolean, String, Int, Double) or use a custom scalar.", declaration)
            SirErrorType
          } else if (declaration is KSClassDeclaration) {
            if (declaration.typeParameters.isNotEmpty()) {
              logger.error("Generic classes are not supported")
              SirErrorType
            } else {
              // Not a scalar, add to the list of types to visit
              declarationsToVisit.add(DeclarationToVisit(declaration, context))
              SirNamedType(declaration.graphqlName())
            }
          } else {
            logger.error("Unsupported type", declaration)
            SirErrorType
          }
        }
      }
    }
  }
}

private fun String.isReserved(): Boolean = this.startsWith("__")

private val unsupportedBasicTypes = listOf("Float", "Byte", "Short", "Long", "UByte", "UShort", "ULong", "Char").map {
  "kotlin.$it"
}.toSet()

private class DeclarationToVisit(val declaration: KSClassDeclaration, val context: VisitContext, val isoperationType: String? = null)
private enum class VisitContext {
  OUTPUT,
  INPUT,
}


private class SirDebugContext(
    val node: KSNode?,
    val name: String?
) {
  constructor(parameter: KSValueParameter): this(parameter.parent, parameter.name?.asString())
  constructor(property: KSPropertyDeclaration): this(property.parentDeclaration, property.simpleName.asString())
  constructor(function: KSFunctionDeclaration): this(function.parentDeclaration, function.simpleName.asString())
}