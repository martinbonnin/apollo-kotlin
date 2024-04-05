package com.apollographql.apollo3.compiler.codegen.kotlin.executableschema

import com.apollographql.apollo3.compiler.capitalizeFirstLetter
import com.apollographql.apollo3.compiler.codegen.kotlin.CgFile
import com.apollographql.apollo3.compiler.codegen.kotlin.CgFileBuilder
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinExecutableSchemaContext
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinSymbols
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinSymbols.AstEnumValue
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinSymbols.AstValue
import com.apollographql.apollo3.compiler.decapitalizeFirstLetter
import com.apollographql.apollo3.compiler.sir.SirEnumDefinition
import com.apollographql.apollo3.compiler.sir.SirInputObjectDefinition
import com.apollographql.apollo3.compiler.sir.SirNonNullType
import com.apollographql.apollo3.compiler.sir.SirTypeDefinition
import com.apollographql.apollo3.compiler.sir.asKotlinPoet
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeVariableName

internal class CoercingsBuilder(
    private val context: KotlinExecutableSchemaContext,
    serviceName: String,
    sirTypeDefinitions: List<SirTypeDefinition>,
) : CgFileBuilder {
  private val sirEnumDefinitions = sirTypeDefinitions.filterIsInstance<SirEnumDefinition>()
  private val sirInputObjectDefinitions = sirTypeDefinitions.filterIsInstance<SirInputObjectDefinition>()
  val fileName = "${serviceName}Coercings".capitalizeFirstLetter()
  override fun prepare() {
    sirEnumDefinitions.forEach {
      context.coercings.put(it.name, MemberName(context.layout.packageName(), it.name.coercingName()))
    }
    sirInputObjectDefinitions.forEach {
      context.coercings.put(it.name, MemberName(context.layout.packageName(), it.name.coercingName()))
    }
  }

  private fun String.coercingName() = "${this}Coercing".decapitalizeFirstLetter()

  override fun build(): CgFile {
    return CgFile(
        packageName = context.layout.packageName(),
        fileName = fileName,
        funSpecs = listOf(getInputFunSpec(), getRequiredInputFunSpec()),
        propertySpecs = sirEnumDefinitions.map { it.propertySpec() } + sirInputObjectDefinitions.map { it.propertySpec() }
    )
  }

  private fun inputFunSpecInternal(name: String, block: CodeBlock.Builder.() -> Unit): FunSpec {
    return FunSpec.builder(name)
        .addTypeVariable(TypeVariableName("T"))
        .receiver(KotlinSymbols.Any.copy(nullable = true))
        .addModifiers(KModifier.PRIVATE)
        .addParameter(ParameterSpec("key", KotlinSymbols.String))
        .returns(TypeVariableName("T"))
        .addCode(
            buildCode {
              add("if (this == null) return null as T\n")
              add("check(this is %T<*,*>)\n", KotlinSymbols.Map)
              block()
            }
        )
        .build()
  }

  private fun getInputFunSpec(): FunSpec {
    return inputFunSpecInternal("getInput") {
      add("return if(containsKey(key)) {\n")
      indent {
        add("%T(get(key))\n", KotlinSymbols.Present)
      }
      add("} else {\n")
      indent {
        add("%T\n", KotlinSymbols.Absent)
      }
      add("} as T\n")
    }
  }

  private fun getRequiredInputFunSpec(): FunSpec {
    return inputFunSpecInternal("getRequiredInput") {
      add("return get(key) as T\n")
    }
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
                  add("return %T.valueOf((gqlValue as %T).value)\n", targetClassName.asKotlinPoet(), AstEnumValue)
                }
                add("}\n")
              }
              add("}\n")
            }
        )
        .build()
  }

  private fun SirInputObjectDefinition.propertySpec(): PropertySpec {
    return PropertySpec.builder(name.coercingName(), KotlinSymbols.Coercing.parameterizedBy(targetClassName.asKotlinPoet()))
        .initializer(
            buildCode {
              add("object: %T<%T> {\n", KotlinSymbols.Coercing, targetClassName.asKotlinPoet())
              indent {
                add("override fun serialize(internalValue: %T): Any?{\n", targetClassName.asKotlinPoet())
                indent {
                  add("error(\"Input objects cannot be serialized\")")
                }
                add("}\n")
                add("override fun deserialize(value: Any?): %T {\n", targetClassName.asKotlinPoet())
                indent {
                  add("return %T(\n", targetClassName.asKotlinPoet())
                  indent {
                    inputFields.forEach {
                      val getInput = if (it.defaultValue == null && it.type !is SirNonNullType) {
                        "getInput"
                      } else {
                        "getRequiredInput"
                      }
                      add("%L = value.$getInput(%S),\n", it.name, it.name)
                    }
                  }
                  add(")\n")
                }
                add("}\n")
                add("override fun parseLiteral(gqlValue: %T): %T {\n", AstValue, targetClassName.asKotlinPoet())
                indent {
                  add("error(\"Input objects cannot be parsed from literals\")")
                }
                add("}\n")
              }
              add("}\n")
            }
        )
        .build()
  }
}