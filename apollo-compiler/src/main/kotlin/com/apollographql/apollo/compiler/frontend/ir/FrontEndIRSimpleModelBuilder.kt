package com.apollographql.apollo.compiler.frontend.ir

import com.apollographql.apollo.compiler.frontend.GQLEnumTypeDefinition
import com.apollographql.apollo.compiler.frontend.GQLInterfaceTypeDefinition
import com.apollographql.apollo.compiler.frontend.GQLObjectTypeDefinition
import com.apollographql.apollo.compiler.frontend.GQLScalarTypeDefinition
import com.apollographql.apollo.compiler.frontend.GQLUnionTypeDefinition
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

  builder.build().writeTo(stringBuilder)

  return stringBuilder.toString()
}

private fun FrontendIr.Operation.toTypeSpec(): TypeSpec {
  val builder = TypeSpec.interfaceBuilder(name.toUpperCamelCase())
  builder.addType(dataField.inode!!.toTypeSpec(dataField.info.responseName))
  return builder.build()
}


private fun FrontendIr.INode.toTypeSpec(responseName: String): TypeSpec {
  val builder = TypeSpec.interfaceBuilder(typeCondition.toUpperCamelCase() + responseName.toUpperCamelCase())

  ifields.forEach {
    builder.addProperty(it.info.toPropertySpec())
    if (it.inode != null) {
      builder.addType(it.inode.toTypeSpec(it.info.responseName))
    }
  }
  children.forEach {
    builder.addType(it.toTypeSpec(responseName))
  }

  return builder.build()
}


private fun FrontendIr.FieldInfo.toPropertySpec(): PropertySpec {
  val typeName = type.toTypeName(responseName).let {
    it.copy(nullable = it.isNullable || canBeSkipped)
  }
  val builder = PropertySpec.builder(responseName, typeName)

  return builder.build()
}

private fun FrontendIr.Type.toTypeName(responseName: String): TypeName = when (this) {
  is FrontendIr.Type.NonNull -> ofType.toTypeName(responseName).copy(nullable = false)
  is FrontendIr.Type.List -> ClassName("kotlin.collections", "List")
      .parameterizedBy(ofType.toTypeName(responseName))
      .copy(nullable = true)
  is FrontendIr.Type.Named -> when {
    typeDefinition.name == "String" -> String::class.asClassName()
    typeDefinition.name == "ID" -> String::class.asClassName()
    typeDefinition.name == "Float" -> Float::class.asClassName()
    typeDefinition.name == "Int" -> Int::class.asClassName()
    typeDefinition.name == "Boolean" -> Boolean::class.asClassName()
    typeDefinition is GQLEnumTypeDefinition -> ClassName("_", this.typeDefinition.name.toUpperCase() + "Enum")
    typeDefinition is GQLScalarTypeDefinition -> Any::class.asClassName()
    typeDefinition is GQLUnionTypeDefinition ||
        typeDefinition is GQLObjectTypeDefinition ||
        typeDefinition is GQLInterfaceTypeDefinition -> {
      // In a real world, we want the full package name here but for testing we don't mind
      ClassName("_", this.typeDefinition.name.toUpperCamelCase() + responseName.toUpperCamelCase())
    }
    else -> error("Not sure what to do with ${typeDefinition.name}")
  }.copy(nullable = true)
}