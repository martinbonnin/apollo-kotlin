package com.apollographql.apollo.compiler.frontend.ir

import com.apollographql.apollo.compiler.frontend.GQLEnumTypeDefinition
import com.apollographql.apollo.compiler.frontend.GQLInterfaceTypeDefinition
import com.apollographql.apollo.compiler.frontend.GQLObjectField
import com.apollographql.apollo.compiler.frontend.GQLObjectTypeDefinition
import com.apollographql.apollo.compiler.frontend.GQLScalarTypeDefinition
import com.apollographql.apollo.compiler.frontend.GQLUnionTypeDefinition
import com.apollographql.apollo.compiler.frontend.ir.FrontendIrBuilder.Companion.extractTypes
import com.apollographql.apollo.compiler.frontend.ir.FrontendIrBuilder.Companion.extractVariables
import com.apollographql.apollo.compiler.toUpperCamelCase
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName

internal fun FrontendIr.toSimpleModels(): String {
  val stringBuilder = StringBuilder()
  val builder = FileSpec.builder("com.example", "FrontendIR")

  operations.forEach {
    builder.addType(it.toTypeSpec())
  }
  fragmentDefinitions.forEach {
    builder.addType(it.toTypeSpec())
  }

  builder.build().writeTo(stringBuilder)

  return stringBuilder.toString()
}

private fun FrontendIr.Operation.toTypeSpec(): TypeSpec {
  val builder = TypeSpec.interfaceBuilder(name.toUpperCamelCase())
  builder.addType(fieldSet.toTypeSpec())
  return builder.build()
}

private fun FrontendIr.NamedFragmentDefinition.toTypeSpec(): TypeSpec {
  val builder = TypeSpec.interfaceBuilder(name.toUpperCamelCase())
  builder.addType(fieldSet.toTypeSpec())
  return builder.build()
}

private fun FrontendIr.FieldSet.toTypeSpec() : TypeSpec{
  val name = (condition.extractTypes().sorted() + condition.extractVariables().sorted()).map {
    it.toUpperCamelCase()
  }.joinToString("")

  val builder = TypeSpec.interfaceBuilder(name)

  fields.forEach { field ->
    builder.addProperty(field.toPropertySpec())
  }

  fields.forEach { field ->
    field.fieldSets.forEach {
      builder.addType(it.toTypeSpec())
    }
  }

  return builder.build()
}

private fun FrontendIr.Field.toPropertySpec(): PropertySpec {
  val typeName = type.toTypeName().let {
    it.copy(nullable = it.isNullable || canBeSkipped)
  }
  val builder = PropertySpec.builder(responseName, typeName)

  return builder.build()
}

private fun FrontendIr.Type.toTypeName(): TypeName = when(this) {
  is FrontendIr.Type.NonNull -> ofType.toTypeName().copy(nullable = false)
  is FrontendIr.Type.List -> ClassName("kotlin.collections", "List")
      .parameterizedBy(ofType.toTypeName())
      .copy(nullable = true)
  is FrontendIr.Type.Named -> when {
    typeDefinition.name == "String" -> String::class.asClassName()
    typeDefinition.name == "ID" -> String::class.asClassName()
    typeDefinition.name == "Float" -> Float::class.asClassName()
    typeDefinition.name == "Int" -> Int::class.asClassName()
    typeDefinition.name == "Boolean" -> Boolean::class.asClassName()
    typeDefinition is GQLEnumTypeDefinition -> ClassName("com.example", this.typeDefinition.name.toUpperCase() + "Enum")
    typeDefinition is GQLScalarTypeDefinition -> Any::class.asClassName()
    typeDefinition is GQLUnionTypeDefinition ||
        typeDefinition is GQLObjectTypeDefinition ||
        typeDefinition is GQLInterfaceTypeDefinition-> ClassName("com.example", this.typeDefinition.name.toUpperCamelCase())
    else -> error("Not sure what to do with ${typeDefinition.name}")
  }.copy(nullable = true)
}