package com.apollographql.apollo3.compiler.codegen.java.adapter

import com.apollographql.apollo3.api.BooleanExpression
import com.apollographql.apollo3.compiler.applyIf
import com.apollographql.apollo3.compiler.codegen.Identifier
import com.apollographql.apollo3.compiler.codegen.Identifier.RESPONSE_NAMES
import com.apollographql.apollo3.compiler.codegen.Identifier.__typename
import com.apollographql.apollo3.compiler.codegen.Identifier.customScalarAdapters
import com.apollographql.apollo3.compiler.codegen.Identifier.evaluate
import com.apollographql.apollo3.compiler.codegen.Identifier.fromJson
import com.apollographql.apollo3.compiler.codegen.Identifier.reader
import com.apollographql.apollo3.compiler.codegen.Identifier.typename
import com.apollographql.apollo3.compiler.codegen.Identifier.value
import com.apollographql.apollo3.compiler.codegen.Identifier.writer
import com.apollographql.apollo3.compiler.codegen.java.JavaClassNames
import com.apollographql.apollo3.compiler.codegen.java.JavaContext
import com.apollographql.apollo3.compiler.codegen.java.helpers.codeBlock
import com.apollographql.apollo3.compiler.codegen.java.isNotEmpty
import com.apollographql.apollo3.compiler.codegen.java.joinToCode
import com.apollographql.apollo3.compiler.ir.IrModel
import com.apollographql.apollo3.compiler.ir.IrModelType
import com.apollographql.apollo3.compiler.ir.IrNonNullType
import com.apollographql.apollo3.compiler.ir.IrOptionalType
import com.apollographql.apollo3.compiler.ir.IrProperty
import com.apollographql.apollo3.compiler.ir.IrType
import com.apollographql.apollo3.compiler.ir.isOptional
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.ParameterizedTypeName

internal fun responseNamesFieldSpec(model: IrModel): FieldSpec {
  val initializer = model.properties.filter { !it.isSynthetic }.map {
    CodeBlock.of("%S", it.info.responseName)
  }.joinToCode(prefix = "listOf(", separator = ", ", suffix = ")")

  return FieldSpec.builder(ParameterizedTypeName.get(JavaClassNames.List, JavaClassNames.String), RESPONSE_NAMES)
      .initializer(initializer)
      .build()
}

internal fun readFromResponseCodeBlock(
    model: IrModel,
    context: JavaContext,
    hasTypenameArgument: Boolean,
): CodeBlock {
  val (regularProperties, syntheticProperties) = model.properties.partition { !it.isSynthetic }
  val prefix = regularProperties.map { property ->
    val variableInitializer = when {
      hasTypenameArgument && property.info.responseName == "__typename" -> CodeBlock.of(typename)
      (property.info.type is IrNonNullType && property.info.type.ofType is IrOptionalType) -> CodeBlock.of("%T", JavaClassNames.Absent)
      else -> CodeBlock.of("null")
    }

    CodeBlock.of(
        "var·%L:·%T·=·%L",
        context.layout.variableName(property.info.responseName),
        context.resolver.resolveIrType(property.info.type),
        variableInitializer
    )
  }.joinToCode(separator = "\n", suffix = "\n")

  /**
   * Read the regular properties
   */
  val loop = CodeBlock.builder()
      .beginControlFlow("while(true)")
      .beginControlFlow("when·($reader.selectName($RESPONSE_NAMES))")
      .add(
          regularProperties.mapIndexed { index, property ->
            CodeBlock.of(
                "%L·->·%L·=·%L.$fromJson($reader, $customScalarAdapters)",
                index,
                context.layout.variableName(property.info.responseName),
                context.resolver.adapterInitializer(property.info.type, property.requiresBuffering)
            )
          }.joinToCode(separator = "\n", suffix = "\n")
      )
      .addStatement("else -> break")
      .endControlFlow()
      .endControlFlow()
      .build()

  val checkedProperties = mutableSetOf<String>()

  /**
   * Read the synthetic properties
   */
  val checkTypename = if (syntheticProperties.isNotEmpty()) {
    checkedProperties.add(__typename)
    CodeBlock.builder()
        .beginControlFlow("check($__typename·!=·null)")
        .add("%S\n", "__typename was not found")
        .endControlFlow()
        .build()
  } else {
    CodeBlock.of("")
  }

  val syntheticLoop = syntheticProperties.map { property ->
    CodeBlock.builder()
        .add("$reader.rewind()\n")
        .apply {
          if (property.condition != BooleanExpression.True) {
            add(
                "var·%L:·%T·=·null\n",
                context.layout.variableName(property.info.responseName),
                context.resolver.resolveIrType(property.info.type),
            )
            beginControlFlow("if·(%T.$evaluate(%L, emptySet(),·$__typename))", JavaClassNames.BooleanExpressions, property.condition.codeBlock())
          } else {
            checkedProperties.add(property.info.responseName)
            add("val·")
          }
        }
        .add(
            CodeBlock.of(
                "%L·=·%L.$fromJson($reader, $customScalarAdapters)\n",
                context.layout.variableName(property.info.responseName),
                context.resolver.resolveModelAdapter(property.info.type.modelPath())
            )
        )
        .applyIf(property.condition != BooleanExpression.True) {
          endControlFlow()
        }
        .build()
  }.joinToCode("\n")

  val suffix = CodeBlock.builder()
      .addStatement("return·%T(", context.resolver.resolveModel(model.id))
      .indent()
      .add(model.properties.filter { !it.hidden }.map { property ->
        val maybeAssertNotNull = if (
            property.info.type is IrNonNullType
            && !property.info.type.isOptional()
            && !checkedProperties.contains(property.info.responseName)
        ) {
          "!!"
        } else {
          ""
        }
        CodeBlock.of(
            "%L·=·%L%L",
            context.layout.propertyName(property.info.responseName),
            context.layout.variableName(property.info.responseName),
            maybeAssertNotNull
        )
      }.joinToCode(separator = ",\n", suffix = "\n"))
      .unindent()
      .addStatement(")")
      .build()

  return CodeBlock.builder()
      .add(prefix)
      .applyIf(prefix.isNotEmpty()) { add("\n") }
      .add(loop)
      .applyIf(loop.isNotEmpty()) { add("\n") }
      .add(checkTypename)
      .applyIf(checkTypename.isNotEmpty()) { add("\n") }
      .add(syntheticLoop)
      .applyIf(syntheticLoop.isNotEmpty()) { add("\n") }
      .add(suffix)
      .build()
}

private fun IrType.modelPath(): String {
  return when (this) {
    is IrNonNullType -> ofType.modelPath()
    is IrModelType -> path
    else -> error("Synthetic field has an invalid type: $this")
  }
}

internal fun writeToResponseCodeBlock(model: IrModel, context: JavaContext): CodeBlock {
  return model.properties.filter { !it.hidden }.map { it.writeToResponseCodeBlock(context) }.joinToCode("\n")
}

private fun IrProperty.writeToResponseCodeBlock(context: JavaContext): CodeBlock {
  val builder = CodeBlock.builder()
  val propertyName = context.layout.propertyName(info.responseName)

  if (!isSynthetic) {
    val adapterInitializer = context.resolver.adapterInitializer(info.type, requiresBuffering)
    builder.addStatement("${writer}.name(%S)", info.responseName)
    builder.addStatement(
        "%L.${Identifier.toJson}($writer, $customScalarAdapters, $value.$propertyName)",
        adapterInitializer
    )
  } else {
    val adapterInitializer = context.resolver.resolveModelAdapter(info.type.modelPath())

    /**
     * Output types do not distinguish between null and absent
     */
    if (this.info.type !is IrNonNullType) {
      builder.beginControlFlow("if·($value.$propertyName·!=·null)")
    }
    builder.addStatement(
        "%L.${Identifier.toJson}($writer, $customScalarAdapters, $value.$propertyName)",
        adapterInitializer
    )
    if (this.info.type !is IrNonNullType) {
      builder.endControlFlow()
    }
  }

  return builder.build()
}


internal fun List<String>.toClassName() = ClassName.get(
    first(),
    get(1),
    *drop(1).toTypedArray()
)

internal fun CodeBlock.obj(buffered: Boolean): CodeBlock {
  return CodeBlock.of("new %T(%L, %L)", JavaClassNames.ObjectAdapter, this, if(buffered) "true" else "false")
}