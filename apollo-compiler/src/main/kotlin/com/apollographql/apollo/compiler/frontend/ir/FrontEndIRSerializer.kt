package com.apollographql.apollo.compiler.frontend.ir

import com.apollographql.apollo.compiler.frontend.GQLEnumTypeDefinition
import com.apollographql.apollo.compiler.frontend.GQLInterfaceTypeDefinition
import com.apollographql.apollo.compiler.frontend.GQLObjectTypeDefinition
import com.apollographql.apollo.compiler.frontend.GQLScalarTypeDefinition
import com.apollographql.apollo.compiler.frontend.GQLUnionTypeDefinition
import com.apollographql.apollo.compiler.frontend.ir.FrontendIrBuilder.Companion.extractTypes
import com.apollographql.apollo.compiler.frontend.ir.FrontendIrBuilder.Companion.extractVariables
import com.apollographql.apollo.compiler.toUpperCamelCase
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asClassName
import okio.Buffer

internal fun FrontendIr.serialize(): String {
  val buffer = Buffer()

  val writer = FileWriter(buffer)

  operations.forEach {
    it.serialize(writer)
  }

  return buffer.readUtf8()
}

private fun FrontendIr.Operation.serialize(writer: FileWriter) {
  writer.write("$name {\n")
  writer.indent()
  fieldSets.forEach {
    it.serialize(writer)
  }
  writer.unindent()
  writer.write("}\n")
}

private fun FrontendIr.FieldSet.serialize(writer: FileWriter) {
  val name = (condition.extractTypes().sorted() + condition.extractVariables().sorted()).map {
    it.toUpperCamelCase()
  }.joinToString("")

  writer.write("$name {\n")
  writer.indent()
  fields.forEach {
    it.serialize(writer)
  }
  writer.unindent()
  writer.write("}\n")
}

private fun FrontendIr.Type.name(responseName: String): String = when (this) {
  is FrontendIr.Type.NonNull -> ofType.name(responseName).removeSuffix("?")
  is FrontendIr.Type.List -> "List<${ofType.name(responseName)}>?"
  is FrontendIr.Type.Named -> when {
    typeDefinition is GQLScalarTypeDefinition && typeDefinition.isBuiltIn() -> typeDefinition.name
    typeDefinition is GQLScalarTypeDefinition -> "Any"
    typeDefinition is GQLEnumTypeDefinition -> typeDefinition.name
    typeDefinition is GQLUnionTypeDefinition
        || typeDefinition is GQLObjectTypeDefinition
        || typeDefinition is GQLInterfaceTypeDefinition -> responseName.toUpperCase()
    else -> error("Not sure what to do with ${typeDefinition.name}")
  } + "?"
}

private fun FrontendIr.Field.serialize(writer: FileWriter) {
  writer.write(responseName)
  writer.indent()
  writer.unindent()
  writer.write("\n")
}