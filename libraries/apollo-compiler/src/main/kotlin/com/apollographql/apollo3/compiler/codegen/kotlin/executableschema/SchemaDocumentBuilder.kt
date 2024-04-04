package com.apollographql.apollo3.compiler.codegen.kotlin.executableschema

import com.apollographql.apollo3.ast.GQLDirective
import com.apollographql.apollo3.ast.GQLFieldDefinition
import com.apollographql.apollo3.ast.SourceLocation
import com.apollographql.apollo3.compiler.capitalizeFirstLetter
import com.apollographql.apollo3.compiler.codegen.kotlin.CgFile
import com.apollographql.apollo3.compiler.codegen.kotlin.CgFileBuilder
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinExecutableSchemaContext
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinSymbols
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinSymbols.AstDocument
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinSymbols.AstFieldDefinition
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinSymbols.AstInputObjectTypeDefinition
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinSymbols.AstInputValueDefinition
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinSymbols.AstInterfaceTypeDefinition
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinSymbols.AstObjectTypeDefinition
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinSymbols.AstOperationTypeDefinition
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinSymbols.AstSchemaDefinition
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinSymbols.AstUnionTypeDefinition
import com.apollographql.apollo3.compiler.decapitalizeFirstLetter
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
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.joinToCode

internal class SchemaDocumentBuilder(
    val context: KotlinExecutableSchemaContext,
    serviceName: String,
    val sirTypeDefinitions: List<SirTypeDefinition>,
) : CgFileBuilder {
  private val simpleName = "${serviceName.decapitalizeFirstLetter()}SchemaDocument"

  val schemaDocument: MemberName
    get() = MemberName(packageName = context.layout.packageName(), simpleName)

  override fun prepare() {}

  private fun propertySpec(): PropertySpec {
    return PropertySpec.builder(simpleName, AstDocument)
        .initializer(
            buildCode {
              add("%T(\n", AstDocument)
              indent {
                add("")
                add("definitions = listOf(\n")
                indent {
                  sirTypeDefinitions.forEach {
                    when (it) {
                      is SirScalarDefinition -> add("%L,\n", it.codeBlock())
                      is SirEnumDefinition -> add("%L,\n", it.codeBlock())
                      is SirInputObjectDefinition -> add("%L,\n", it.codeBlock())
                      is SirInterfaceDefinition -> add("%L,\n", it.codeBlock())
                      is SirObjectDefinition -> add("%L,\n", it.codeBlock())
                      is SirUnionDefinition -> add("%L,\n", it.codeBlock())
                    }
                  }
                  add("%L,\n", sirTypeDefinitions.schemaDefinitionCodeBlock())
                }
                add("),\n")
                add("sourceLocation = null,\n")
              }
              add(")\n")
            }
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

/**
 * ```
 * fun foo() {
 *   GQLSchemaDefinition(
 *       rootOperationTypeDefinitions = listOf(
 *           GQLOperationTypeDefinition(
 *               operationType = "",
 *               namedType = ""
 *           )
 *       )
 *   )
 * }
 * ``
 */
private fun List<SirTypeDefinition>.schemaDefinitionCodeBlock(): CodeBlock {
  return  buildCode {
    add("%T(\n", AstSchemaDefinition)
    indent {
      add("sourceLocation = null,\n")
      add("description = null,\n")
      add("directives = emptyList(),\n")
      add("rootOperationTypeDefinitions = listOf(\n")
      indent {
        filterIsInstance<SirObjectDefinition>().filter { it.operationType != null }.forEach {
          add("%T(\n", AstOperationTypeDefinition)
          indent {
            add("operationType = %S,\n", it.operationType)
            add("namedType = %S,\n", it.name)
          }
          add("),\n")
        }
      }
      add(")\n")
    }
    add(")")
  }
}
private fun SirInputObjectDefinition.codeBlock(): CodeBlock {
  return buildCommon(AstInputObjectTypeDefinition, name, description) {
    add("inputFields = listOf(\n")
    indent()
    inputFields.forEach {
      add("%L,\n", it.codeBlock())
    }
    unindent()
    add(")")
  }
}

private fun SirInputFieldDefinition.codeBlock(): CodeBlock {
  return buildCommon(AstInputValueDefinition, name, description) {
    add("type = %L,\n", type.codeBlock())
    if (defaultValue != null) {
      add("defaultValue = %M(%S)", KotlinSymbols.AstParseAsGQLValue, defaultValue)
    }
  }
}

private fun SirUnionDefinition.codeBlock(): CodeBlock {
  return buildCommon(AstUnionTypeDefinition, name, description) {
    add("memberTypes = listOf(")
    memberTypes.forEach {
      add("%S,·", it)
    }
    add(")")
  }
}

private fun SirObjectDefinition.codeBlock(): CodeBlock {
  return buildCommon(AstObjectTypeDefinition, name, description) {
    add("implementsInterfaces = listOf(%L),\n", interfaces.map { CodeBlock.builder().add("%S", it).build() }.joinToCode(",·"))
    add("fields = listOf(\n")
    indent()
    fields.forEach {
      add("%L,\n", it.codeBlock())
    }
    unindent()
    add("),\n")
  }
}

private fun SirInterfaceDefinition.codeBlock(): CodeBlock {
  return buildCommon(AstInterfaceTypeDefinition, name, description) {
    add("implementsInterfaces = listOf(%L)\n", interfaces.map { CodeBlock.builder().add("%S", it).build() }.joinToCode(",·"))
    add("fields = listOf(\n")
    indent()
    fields.forEach {
      add("%L,\n", it.codeBlock())
    }
    unindent()
    add("),\n")
  }
}

private fun SirFieldDefinition.codeBlock(): CodeBlock {
  return buildCommon(AstFieldDefinition, name, description) {
    add("arguments = listOf(\n")
    indent()
    arguments.forEach {
      when (it) {
        SirExecutionContextArgument -> Unit
        is SirGraphQLArgument -> {
          add("%L,\n", it.codeBlock())
        }
      }
    }
    unindent()
    add("),\n")
    add("type = %L,\n", type.codeBlock())
  }
}

private fun SirGraphQLArgument.codeBlock(): CodeBlock {
  return buildCommon(AstInputValueDefinition, name = name, description = description) {
    add("type = %L,\n", type.codeBlock())
    if (defaultValue != null) {
      add("defaultValue = %S.%M().getOrThrow(),\n", defaultValue, KotlinSymbols.AstParseAsGQLValue, )
    } else {
      add("defaultValue = null,\n", KotlinSymbols.AstParseAsGQLValue)
    }
  }
}

private fun SirType.codeBlock(): CodeBlock {
  return when (this) {
    SirErrorType -> CodeBlock.of("%T", "kotlin.Nothing")
    is SirListType -> CodeBlock.of("%T(type·=·%L)", KotlinSymbols.AstListType, type.codeBlock())
    is SirNamedType -> CodeBlock.of("%T(name·=·%S)", KotlinSymbols.AstNamedType, name)
    is SirNonNullType -> CodeBlock.of("%T(type·=·%L)", KotlinSymbols.AstNonNullType, type.codeBlock())
  }
}

private fun buildCommon(className: ClassName, name: String, description: String?, block: CodeBlock.Builder.() -> Unit = {}): CodeBlock {
  return buildCode {
    add("%T(\n", className)
    indent()
    add("sourceLocation = null,\n")
    add("description = %S,\n", description)
    add("name = %S,\n", name)
    add("directives = emptyList(),\n")
    block()
    unindent()
    add(")")
  }
}

private fun SirEnumDefinition.codeBlock(): CodeBlock {
  return buildCommon(KotlinSymbols.AstEnumTypeDefinition, name, description) {
    add("enumValues = listOf(\n")
    indent()
    add("%L", values.map { it.codeBlock() }.joinToCode(",\n", suffix = ",\n"))
    unindent()
    add("),\n")
  }
}

internal fun SirEnumValueDefinition.codeBlock(): CodeBlock {
  return buildCommon(KotlinSymbols.AstEnumValueDefinition, name, description = description)
}

internal fun buildCode(block: CodeBlock.Builder.() -> Unit): CodeBlock {
  return CodeBlock.builder()
      .apply(block)
      .build()
}

private fun SirScalarDefinition.codeBlock(): CodeBlock {
  return buildCommon(KotlinSymbols.AstScalarTypeDefinition, name, description)
}