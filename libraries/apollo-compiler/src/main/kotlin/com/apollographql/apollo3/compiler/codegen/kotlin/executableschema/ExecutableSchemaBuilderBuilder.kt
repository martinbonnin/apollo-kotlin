package com.apollographql.apollo3.compiler.codegen.kotlin.executableschema

import com.apollographql.apollo3.compiler.capitalizeFirstLetter
import com.apollographql.apollo3.compiler.codegen.kotlin.CgFile
import com.apollographql.apollo3.compiler.codegen.kotlin.CgFileBuilder
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinExecutableSchemaContext
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinSymbols
import com.apollographql.apollo3.compiler.sir.SirClassName
import com.apollographql.apollo3.compiler.sir.SirObjectDefinition
import com.apollographql.apollo3.compiler.sir.asKotlinPoet
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec

internal class ExecutableSchemaBuilderBuilder(
    private val context: KotlinExecutableSchemaContext,
    private val serviceName: String,
    private val adapterRegistry: MemberName,
    private val schemaDocumentClassName: SirClassName,
    private val sirObjectDefinitions: List<SirObjectDefinition>,
) : CgFileBuilder {
  val simpleName = "${serviceName}ExecutableSchemaBuilder".capitalizeFirstLetter()
  override fun prepare() {

  }

  override fun build(): CgFile {
    return CgFile(packageName = context.layout.packageName(), fileName = simpleName, funSpecs = listOf(funSpec())
    )
  }

  private fun funSpec(): FunSpec {
    val rootIrTargetObjects = listOf("query", "mutation", "subscription").map { operationType ->
      sirObjectDefinitions.find { it.operationType == operationType }
    }

    return FunSpec.builder(simpleName)
        .returns(KotlinSymbols.ExecutableSchemaBuilder)
        .apply {
          addParameter(ParameterSpec.builder("schema", KotlinSymbols.AstSchema).build())
          rootIrTargetObjects.forEach { irTargetObject ->
            if (irTargetObject != null) {
              addParameter(
                  ParameterSpec.builder(
                      name = "root${irTargetObject.operationType?.capitalizeFirstLetter()}Object",
                      type = LambdaTypeName.get(parameters = emptyList(), returnType = irTargetObject.targetClassName.asKotlinPoet())
                  ).apply {
                    if (irTargetObject.isSingleton) {
                      defaultValue(CodeBlock.of("{ %L }", irTargetObject.targetClassName.asKotlinPoet()))
                    } else if (irTargetObject.hasNoArgsConstructor) {
                      defaultValue(CodeBlock.of("{ %L() }", irTargetObject.targetClassName.asKotlinPoet()))
                    }
                  }.build())
            }
          }
        }
        .addCode(
            CodeBlock.builder()
                .add("val schemaBuilder = %L()\n", KotlinSymbols.ExecutableSchemaBuilder)
                .indent()
                .add(".schema(%M)\n", schemaDocumentClassName.asKotlinPoet())
                .apply {
                  sirObjectDefinitions.forEach { irTargetObject ->
                    add(".addTypeChecker(%S)·{·it·is·%T·}\n", irTargetObject.name, irTargetObject.targetClassName.asKotlinPoet())
                    irTargetObject.fields.forEach { irTargetField ->
                      val coordinates = "${irTargetObject.name}.${irTargetField.name}"
                      add(".addResolver(%S)·%L\n", coordinates, resolverBody(irTargetObject, irTargetField, TODO()))
                    }
                  }
                }
                .add(".adapterRegistry(%L)\n", adapterRegistry)
                .apply {
                  rootIrTargetObjects.map { irTargetObject ->
                    if (irTargetObject != null) {
                      val name = irTargetObject.operationType!!
                      add(".${name}Root(root${name.capitalizeFirstLetter()}Object)\n")
                    }
                  }
                }
                .add("return schemaBuilder")
                .build()
        )
        .build()
  }

}