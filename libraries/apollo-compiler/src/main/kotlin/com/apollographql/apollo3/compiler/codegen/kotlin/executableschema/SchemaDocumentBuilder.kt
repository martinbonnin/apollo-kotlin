package com.apollographql.apollo3.compiler.codegen.kotlin.executableschema

import com.apollographql.apollo3.compiler.capitalizeFirstLetter
import com.apollographql.apollo3.compiler.codegen.kotlin.CgFile
import com.apollographql.apollo3.compiler.codegen.kotlin.CgFileBuilder
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinExecutableSchemaContext
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinSymbols
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinSymbols.AstDocument
import com.apollographql.apollo3.compiler.sir.SirScalarDefinition
import com.apollographql.apollo3.compiler.sir.SirTypeDefinition
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.PropertySpec

internal class SchemaDocumentBuilder(
    val context: KotlinExecutableSchemaContext,
    serviceName: String,
    val sirTypeDefinitions: List<SirTypeDefinition>
): CgFileBuilder {
  private val simpleName = "${serviceName.capitalizeFirstLetter()}SchemaDocument"
  override fun prepare() {}

  private fun propertySpec(): PropertySpec {
    return PropertySpec.builder(simpleName, AstDocument)
        .initializer(CodeBlock.builder()
            .add("%T(\n", AstDocument)
            .indent()
            .add("")
            .add("definitions = listOf(\n")
            .indent()
            .apply {
              sirTypeDefinitions.filterIsInstance<SirScalarDefinition>().forEach {
                add(it.codeBlock())
              }
            }
            .unindent()
            .add("),\n")
            .add("sourceLocation = null,\n")
            .unindent()
            .add(")\n")
            .build()
        )
        .build()
  }

  override fun build(): CgFile {

    return CgFile(
        packageName = context.layout.packageName(),
        propertySpecs = listOf(propertySpec()),
        fileName = simpleName
    )
  }
}

private fun SirScalarDefinition.codeBlock(): CodeBlock {
  return CodeBlock.builder()
      .add("%T(\n", KotlinSymbols.AstScalarTypeDefinition)
      .indent()
      .add("sourceLocation = null,\n")
      .add("description = %S,\n", description)
      .add("name = %S,\n", name)
      .add("directives = emptyList(),\n")
      .unindent()
      .add(")\n")
      .build()
}