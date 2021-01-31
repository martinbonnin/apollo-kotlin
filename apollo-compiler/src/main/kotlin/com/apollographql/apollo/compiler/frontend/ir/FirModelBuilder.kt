package com.apollographql.apollo.compiler.frontend.ir

import com.apollographql.apollo.compiler.toUpperCamelCase
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName

internal fun FIR.toSimpleModels(): String {
  val stringBuilder = StringBuilder()
  val builder = FileSpec.builder("com.example", "FrontendIR")

  operations.forEach {
    builder.addType(it.toTypeSpec())
  }

  builder.build().writeTo(stringBuilder)

  return stringBuilder.toString()
}

private fun Operation.toTypeSpec(): TypeSpec {
  val builder = TypeSpec.interfaceBuilder(name.toUpperCamelCase())
  builder.addType(selectionSet.toInterfacesTypeSpec(typeCondition, "data"))
  builder.addTypes(selectionSet.toImplementationTypeSpecs(typeCondition, "data"))
  return builder.build()
}

private fun SelectionSet.toInterfacesTypeSpec(typeCondition: String, responseName: String): TypeSpec {
  val builder = TypeSpec.interfaceBuilder(typeCondition.toUpperCamelCase() + responseName.toUpperCamelCase())

  selections.forEach {
    when(it) {
      is Field -> {
        builder.addProperty(it.toPropertySpec())
        if (it.selectionSet.selections.isNotEmpty()) {
          builder.addType(it.selectionSet.toInterfacesTypeSpec(it.type.leafName, it.responseName))
        }
      }
      is InlineFragment -> {
        val className = "${it.typeCondition.toUpperCamelCase()}${responseName.toUpperCamelCase()}"
        builder.addProperty("as$className", ClassName("_", className))

        builder.addType(it.selectionSet.toInterfacesTypeSpec(it.typeCondition, responseName))
      }
      is FragmentSpread -> {
        val className = "${it.name.toUpperCamelCase()}Fragment"
        builder.addProperty("as$className", ClassName("_", className))
      }
    }
  }

  return builder.build()
}

private fun Field.toPropertySpec(): PropertySpec {
  val typeName = type.toTypeName(responseName).let {
    val mightBeSkipped = condition != BooleanExpression.True
    it.copy(nullable = it.isNullable || mightBeSkipped)
  }
  val builder = PropertySpec.builder(responseName, typeName)

  return builder.build()
}

private fun Type.toTypeName(responseName: String): TypeName = when (this) {
  is NonNullType -> ofType.toTypeName(responseName).copy(nullable = false)
  is ListType -> ClassName("kotlin.collections", "List")
      .parameterizedBy(ofType.toTypeName(responseName))
      .copy(nullable = true)
  is NamedType -> when(this) {
    is StringType -> String::class.asClassName()
    is IDType -> String::class.asClassName()
    is FloatType -> Float::class.asClassName()
    is IntType -> Int::class.asClassName()
    is BooleanType -> Boolean::class.asClassName()
    is CustomScalarType -> Any::class.asClassName()
    is EnumType -> ClassName("_", this.name.toUpperCase() + "Enum")
    else -> { // Object, Interface, Union
      // In a real world, we want the full package name here but for testing we don't mind
      ClassName("_", this.name.toUpperCamelCase() + responseName.toUpperCamelCase())
    }
  }.copy(nullable = true)
}