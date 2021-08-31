package com.apollographql.apollo3.compiler.codegen.java.file

import com.apollographql.apollo3.api.Mutation
import com.apollographql.apollo3.api.Query
import com.apollographql.apollo3.api.QueryDocumentMinifier
import com.apollographql.apollo3.api.Subscription
import com.apollographql.apollo3.compiler.applyIf
import com.apollographql.apollo3.compiler.codegen.java.JavaContext
import com.apollographql.apollo3.compiler.codegen.java.CodegenJavaFile
import com.apollographql.apollo3.compiler.codegen.java.JavaClassBuilder
import com.apollographql.apollo3.compiler.codegen.Identifier.OPERATION_DOCUMENT
import com.apollographql.apollo3.compiler.codegen.Identifier.OPERATION_ID
import com.apollographql.apollo3.compiler.codegen.Identifier.OPERATION_NAME
import com.apollographql.apollo3.compiler.codegen.Identifier.document
import com.apollographql.apollo3.compiler.codegen.Identifier.id
import com.apollographql.apollo3.compiler.codegen.Identifier.name
import com.apollographql.apollo3.compiler.codegen.java.helpers.makeDataClass
import com.apollographql.apollo3.compiler.codegen.java.helpers.maybeAddDescription
import com.apollographql.apollo3.compiler.codegen.java.helpers.toNamedType
import com.apollographql.apollo3.compiler.codegen.java.helpers.toParameterSpec
import com.apollographql.apollo3.compiler.codegen.maybeFlatten
import com.apollographql.apollo3.compiler.codegen.java.model.ModelBuilder
import com.apollographql.apollo3.compiler.ir.IrOperation
import com.apollographql.apollo3.compiler.ir.IrOperationType
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.KModifier
import com.squareup.javapoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import com.squareup.javapoet.asClassName
import com.squareup.javapoet.asTypeName

class OperationBuilder(
    private val context: JavaContext,
    private val generateFilterNotNull: Boolean,
    private val operationId: String,
    private val generateQueryDocument: Boolean,
    private val operation: IrOperation,
    flatten: Boolean,
    flattenNamesInOrder: Boolean
): JavaClassBuilder {
  private val layout = context.layout
  private val packageName = layout.operationPackageName(operation.filePath)
  private val simpleName = layout.operationName(operation)

  private val dataSuperClassName = when (operation.operationType) {
    IrOperationType.Query -> Query.Data::class
    IrOperationType.Mutation -> Mutation.Data::class
    IrOperationType.Subscription -> Subscription.Data::class
  }.asClassName()

  private val modelBuilders = operation.dataModelGroup.maybeFlatten(flatten, flattenNamesInOrder).flatMap {
    it.models
  }.map {
    ModelBuilder(
        context = context,
        model = it,
        superClassName = if (it.id == operation.dataModelGroup.baseModelId) dataSuperClassName else null,
        path = listOf(packageName, simpleName)
    )
  }
  
  override fun prepare() {
    context.resolver.registerOperation(
        operation.name,
        ClassName.get(packageName, simpleName)
    )
    modelBuilders.forEach { it.prepare() }
  }

  override fun build(): CodegenJavaFile {
    return CodegenJavaFile(
        packageName = packageName,
        fileName = simpleName,
        typeSpec = typeSpec()
    )
  }

  fun typeSpec(): TypeSpec {
    return TypeSpec.classBuilder(layout.operationName(operation))
        .addSuperinterface(superInterfaceType())
        .maybeAddDescription(operation.description)
        .makeDataClass(operation.variables.map { it.toNamedType().toParameterSpec(context) })
        .addFunction(operationIdMethodSpec())
        .addFunction(queryDocumentMethodSpec(generateQueryDocument))
        .addFunction(nameMethodSpec())
        .addFunction(serializeVariablesMethodSpec())
        .addFunction(adapterMethodSpec())
        .addFunction(fieldSetsMethodSpec())
        .addTypes(dataTypeSpecs())
        .addType(companionTypeSpec())
        .build()
        .maybeAddFilterNotNull(generateFilterNotNull)
  }

  private fun serializeVariablesMethodSpec(): MethodSpec = serializeVariablesMethodSpec(
      adapterClassName = context.resolver.resolveOperationVariablesAdapter(operation.name),
      emptyMessage = "This operation doesn't have any variable"
  )

  private fun adapterMethodSpec(): MethodSpec {
    return adapterMethodSpec(
        adapterTypeName = context.resolver.resolveModelAdapter(operation.dataModelGroup.baseModelId),
        adaptedTypeName = context.resolver.resolveModel(operation.dataModelGroup.baseModelId)
    )
  }

  private fun dataTypeSpecs(): List<TypeSpec> {
    return modelBuilders.map {
      it.build()
    }
  }

  private fun superInterfaceType(): TypeName {
    return when (operation.operationType) {
      IrOperationType.Query -> Query::class.asTypeName()
      IrOperationType.Mutation -> Mutation::class.asTypeName()
      IrOperationType.Subscription -> Subscription::class.asTypeName()
    }.parameterizedBy(
        context.resolver.resolveModel(operation.dataModelGroup.baseModelId)
    )
  }

  private fun operationIdMethodSpec() = MethodSpec.builder(id)
      .addModifiers(KModifier.OVERRIDE)
      .returns(String::class)
      .addStatement("return $OPERATION_ID")
      .build()

  private fun queryDocumentMethodSpec(generateQueryDocument: Boolean) = MethodSpec.builder(document)
      .addModifiers(KModifier.OVERRIDE)
      .returns(String::class)
      .apply {
        if (generateQueryDocument) {
          addStatement("return $OPERATION_DOCUMENT")
        } else {
          addStatement("error(\"The query document was removed from this operation. Use generateQueryDocument = true if you need it\"")
        }
      }
      .build()

  private fun nameMethodSpec() = MethodSpec.builder(name)
      .addModifiers(KModifier.OVERRIDE)
      .returns(String::class)
      .addStatement("return OPERATION_NAME")
      .build()

  private fun companionTypeSpec(): TypeSpec {
    return TypeSpec.companionObjectBuilder()
        .addField(FieldSpec.builder(OPERATION_ID, String::class)
            .addModifiers(KModifier.CONST)
            .initializer("%S", operationId)
            .build()
        )
        .applyIf(generateQueryDocument) {
          addField(FieldSpec.builder(OPERATION_DOCUMENT, String::class)
              .addModifiers(KModifier.CONST)
              .initializer("%S", QueryDocumentMinifier.minify(operation.sourceWithFragments))
              .addKdoc("""
                The minimized GraphQL document being sent to the server to save a few bytes.
                The un-minimized version is:


                """.trimIndent() + operation.sourceWithFragments.escapeKdoc()
              )
              .build()
          )
        }
        .addField(FieldSpec
            .builder(OPERATION_NAME, String::class)
            .addModifiers(KModifier.CONST)
            .initializer("%S", operation.name)
            .build()
        )
        .build()
  }

  /**
   * Things like `[${'$'}oo]` do not compile. See https://youtrack.jetbrains.com/issue/KT-43906
   */
  private fun String.escapeKdoc(): String {
    return replace("[", "\\[").replace("]", "\\]")
  }

  private fun fieldSetsMethodSpec(): MethodSpec {
    return selectionsMethodSpec(
        context, context.resolver.resolveOperationSelections(operation.name)
    )
  }
}

