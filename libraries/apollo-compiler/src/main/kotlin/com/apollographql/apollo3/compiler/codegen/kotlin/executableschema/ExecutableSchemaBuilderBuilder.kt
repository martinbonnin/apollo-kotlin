package com.apollographql.apollo3.compiler.codegen.kotlin.executableschema

import com.apollographql.apollo3.compiler.capitalizeFirstLetter
import com.apollographql.apollo3.compiler.codegen.kotlin.CgFile
import com.apollographql.apollo3.compiler.codegen.kotlin.CgFileBuilder
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinExecutableSchemaContext
import com.apollographql.apollo3.compiler.codegen.kotlin.KotlinSymbols
import com.apollographql.apollo3.compiler.sir.SirObjectDefinition
import com.apollographql.apollo3.compiler.sir.SirTypeDefinition
import com.apollographql.apollo3.compiler.sir.asKotlinPoet
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName

internal class ExecutableSchemaBuilderBuilder(
    private val context: KotlinExecutableSchemaContext,
    private val serviceName: String,
    private val schemaDocument: MemberName,
    private val sirTypeDefinitions: List<SirTypeDefinition>,
) : CgFileBuilder {
  val simpleName = "${serviceName}ExecutableSchemaBuilder".capitalizeFirstLetter()
  override fun prepare() {

  }

  override fun build(): CgFile {
    return CgFile(
        packageName = context.layout.packageName(),
        fileName = simpleName,
        funSpecs = listOf(funSpec())
    )
  }

  private fun funSpec(): FunSpec {
    val rootTypeDefinitions = listOf("query", "mutation", "subscription").map { operationType ->
      sirTypeDefinitions.find { it is SirObjectDefinition && it.operationType == operationType }
    }.filterIsInstance<SirObjectDefinition>()

    return FunSpec.builder(simpleName)
        .returns(KotlinSymbols.ExecutableSchemaBuilder)
        .addCode(
            buildCode {
              add("@Suppress(\"UNCHECKED_CAST\")")
              add("fun <T> Any?.cast(): T = this as T\n\n")
              // Use a variable so that we don't get an expression return
              add("val schemaBuilder = %L()\n", KotlinSymbols.ExecutableSchemaBuilder)
              indent()
              add(".schema(%M)\n", schemaDocument)
              sirTypeDefinitions.filterIsInstance<SirObjectDefinition>().forEach { sirObjectDefinition ->
                add(".addTypeChecker(%S)·{·it·is·%T·}\n", sirObjectDefinition.name, sirObjectDefinition.targetClassName.asKotlinPoet())
                sirObjectDefinition.fields.forEach { irTargetField ->
                  val coordinates = "${sirObjectDefinition.name}.${irTargetField.name}"
                  add(".addResolver(%S)·{\n%L\n}\n", coordinates, resolverBody(sirObjectDefinition, irTargetField))
                }
              }
              unindent()
              add("return schemaBuilder")
            }
        )
//        .apply {
//          addParameter(ParameterSpec.builder("schema", KotlinSymbols.AstSchema).build())
//          rootTypeDefinitions.forEach { typeDefinition ->
//            addParameter(
//                ParameterSpec.builder(
//                    name = "root${typeDefinition.operationType?.capitalizeFirstLetter()}Object",
//                    type = LambdaTypeName.get(parameters = emptyList(), returnType = typeDefinition.targetClassName.asKotlinPoet())
//                ).apply {
//                  if (typeDefinition.isSingleton) {
//                    defaultValue(CodeBlock.of("{ %L }", typeDefinition.targetClassName.asKotlinPoet()))
//                  } else if (typeDefinition.hasNoArgsConstructor) {
//                    defaultValue(CodeBlock.of("{ %L() }", typeDefinition.targetClassName.asKotlinPoet()))
//                  }
//                }.build())
//          }
//        }
//        .addCode(
//            CodeBlock.builder()
//                .add("val schemaBuilder = %L()\n", KotlinSymbols.ExecutableSchemaBuilder)
//                .apply {
//                  sirObjectDefinitions.forEach { irTargetObject ->
//                  }
//                }
//                .add(".adapterRegistry(%L)\n", adapterRegistry)
//                .apply {
//                  rootTypeDefinitions.map { irTargetObject ->
//                    if (irTargetObject != null) {
//                      val name = irTargetObject.operationType!!
//                      add(".${name}Root(root${name.capitalizeFirstLetter()}Object)\n")
//                    }
//                  }
//                }
//                .add("return schemaBuilder")
//                .build()
//        )
        .build()
  }

}