package com.apollographql.apollo3.compiler.codegen.kotlin.executableschema

import com.apollographql.apollo3.compiler.capitalizeFirstLetter
import com.apollographql.apollo3.compiler.codegen.kotlin.CgFile
import com.apollographql.apollo3.compiler.codegen.kotlin.CgFileBuilder
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinExecutableSchemaContext
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinSymbols
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinSymbols.AstStringValue
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinSymbols.AstValue
import com.apollographql.apollo3.compiler.decapitalizeFirstLetter
import com.apollographql.apollo3.compiler.sir.SirEnumDefinition
import com.apollographql.apollo3.compiler.sir.SirTypeDefinition
import com.apollographql.apollo3.compiler.sir.asKotlinPoet
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec

internal class CoercingsBuilder(
    private val context: KotlinExecutableSchemaContext,
    serviceName: String,
    sirTypeDefinitions: List<SirTypeDefinition>,
) : CgFileBuilder {
  private val sirEnumDefinitions = sirTypeDefinitions.filterIsInstance<SirEnumDefinition>()
  val fileName = "${serviceName}Coercings".capitalizeFirstLetter()
  override fun prepare() {
    sirEnumDefinitions.forEach {
      context.coercings.put(it.name, MemberName(context.layout.packageName(), it.name.coercingName()))
    }
  }

  private fun String.coercingName() = "${this}Coercing".decapitalizeFirstLetter()

  override fun build(): CgFile {
    return CgFile(
        packageName = context.layout.packageName(),
        fileName = fileName,
        propertySpecs = sirEnumDefinitions.map { it.propertySpec() }
    )
  }

  private fun SirEnumDefinition.propertySpec(): PropertySpec {
    return PropertySpec.builder(name.coercingName(), KotlinSymbols.Coercing.parameterizedBy(targetClassName.asKotlinPoet()))
        .initializer(
            buildCode {
              add("object: %T<%T> {\n", KotlinSymbols.Coercing, targetClassName.asKotlinPoet())
              indent {
                add("override fun serialize(internalValue: %T): Any?{\n", targetClassName.asKotlinPoet())
                indent {
                  add("return internalValue.name\n")
                }
                add("}\n")
                add("override fun deserialize(value: Any?): %T {\n", targetClassName.asKotlinPoet())
                indent {
                  add("return %T.valueOf(value.toString())\n", targetClassName.asKotlinPoet())
                }
                add("}\n")
                add("override fun parseLiteral(gqlValue: %T): %T {\n", AstValue, targetClassName.asKotlinPoet())
                indent {
                  add("return %T.valueOf((gqlValue as %T).toString())\n", targetClassName.asKotlinPoet(), AstStringValue)
                }
                add("}\n")
              }
              add("}\n")
            }
        )
        .build()
  }
}