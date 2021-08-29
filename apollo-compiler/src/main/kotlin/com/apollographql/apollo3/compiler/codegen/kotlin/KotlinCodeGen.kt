package com.apollographql.apollo3.compiler.codegen.kotlin

import com.apollographql.apollo3.compiler.PackageNameGenerator
import com.apollographql.apollo3.compiler.APOLLO_VERSION
import com.apollographql.apollo3.compiler.codegen.CodegenLayout
import com.apollographql.apollo3.compiler.operationoutput.OperationOutput
import com.apollographql.apollo3.compiler.operationoutput.findOperationId
import com.apollographql.apollo3.compiler.codegen.kotlin.file.TypesBuilder
import com.apollographql.apollo3.compiler.codegen.kotlin.file.EnumBuilder
import com.apollographql.apollo3.compiler.codegen.kotlin.adapter.EnumResponseAdapterBuilder
import com.apollographql.apollo3.compiler.codegen.kotlin.file.FragmentBuilder
import com.apollographql.apollo3.compiler.codegen.kotlin.file.FragmentModelsBuilder
import com.apollographql.apollo3.compiler.codegen.kotlin.file.FragmentResponseAdapterBuilder
import com.apollographql.apollo3.compiler.codegen.kotlin.file.FragmentSelectionsBuilder
import com.apollographql.apollo3.compiler.codegen.kotlin.file.FragmentVariablesAdapterBuilder
import com.apollographql.apollo3.compiler.codegen.kotlin.file.InputObjectAdapterBuilder
import com.apollographql.apollo3.compiler.codegen.kotlin.file.InputObjectBuilder
import com.apollographql.apollo3.compiler.codegen.kotlin.file.OperationBuilder
import com.apollographql.apollo3.compiler.codegen.kotlin.file.OperationResponseAdapterBuilder
import com.apollographql.apollo3.compiler.codegen.kotlin.file.OperationSelectionsBuilder
import com.apollographql.apollo3.compiler.codegen.kotlin.file.OperationVariablesAdapterBuilder
import com.apollographql.apollo3.compiler.ir.Ir
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
import java.io.File


class KotlinCodeGen(
    private val ir: Ir,
    private val generateAsInternal: Boolean = false,
    private val useSemanticNaming: Boolean,
    private val packageNameGenerator: PackageNameGenerator,
    private val schemaPackageName: String,
    /**
     * The operation id cannot be set in [IrOperation] because it needs access to [IrOperation.sourceWithFragments]
     * So we do this in the codegen step
     */
    private val operationOutput: OperationOutput,
    private val generateFilterNotNull: Boolean,
    private val generateFragmentImplementations: Boolean,
    private val generateQueryDocument: Boolean,
    private val fragmentsToSkip: Set<String>,
    private val enumsToSkip: Set<String>,
    private val inputObjectsToSkip: Set<String>,
    private val generateSchema: Boolean,
    /**
     * Whether to flatten the models. This decision is left to the codegen. For fragments for an example, we
     * want to flatten at depth 1 to avoid nameclashes but it's ok to flatten fragment response adapters at
     * depth 0 for an example
     */
    private val flatten: Boolean,
    private val flattenNamesInOrder: Boolean,
) {
  fun write(outputDir: File) {
    val layout = CodegenLayout(
        useSemanticNaming = useSemanticNaming,
        packageNameGenerator = packageNameGenerator,
        schemaPackageName = schemaPackageName
    )

    val context = KotlinContext(
        layout = layout,
        resolver = KotlinResolver()
    )
    val builders = mutableListOf<CgFileBuilder>()
    val ignoredBuilders = mutableListOf<CgFileBuilder>()

    val typesBuilder = TypesBuilder(
        context,
        ir.customScalars,
        ir.objects,
        ir.interfaces,
        ir.unions,
        ir.allEnums
    )

    builders.add(typesBuilder)
    if (!generateSchema) {
      ignoredBuilders.add(typesBuilder)
    }

    ir.inputObjects.forEach {
      builders.add(InputObjectBuilder(context, it))
      if (inputObjectsToSkip.contains(it.name)) {
        ignoredBuilders.add(builders.last())
      }
      builders.add(InputObjectAdapterBuilder(context, it))
      if (inputObjectsToSkip.contains(it.name)) {
        ignoredBuilders.add(builders.last())
      }
    }

    ir.enums.forEach { enum ->
      builders.add(EnumBuilder(context, enum))
      if (enumsToSkip.contains(enum.name)) {
        ignoredBuilders.add(builders.last())
      }
      builders.add(EnumResponseAdapterBuilder(context, enum))
      if (enumsToSkip.contains(enum.name)) {
        ignoredBuilders.add(builders.last())
      }
    }

    ir.fragments.forEach { fragment ->
      builders.add(
          FragmentModelsBuilder(
              context,
              fragment,
              (fragment.interfaceModelGroup ?: fragment.dataModelGroup),
              fragment.interfaceModelGroup == null,
              flatten,
              flattenNamesInOrder
          )
      )
      if (fragmentsToSkip.contains(fragment.name)) {
        ignoredBuilders.add(builders.last())
      }

      builders.add(FragmentSelectionsBuilder(context, fragment, ir.schema, ir.allFragmentDefinitions))
      if (fragmentsToSkip.contains(fragment.name)) {
        ignoredBuilders.add(builders.last())
      }

      if (generateFragmentImplementations || fragment.interfaceModelGroup == null) {
        builders.add(FragmentResponseAdapterBuilder(context, fragment, flatten, flattenNamesInOrder))
        if (fragmentsToSkip.contains(fragment.name)) {
          ignoredBuilders.add(builders.last())
        }
      }

      if (generateFragmentImplementations) {
        builders.add(
            FragmentBuilder(
                context,
                generateFilterNotNull,
                fragment,
                flatten,
                flattenNamesInOrder
            )
        )
        if (fragmentsToSkip.contains(fragment.name)) {
          ignoredBuilders.add(builders.last())
        }
        if (fragment.variables.isNotEmpty()) {
          builders.add(FragmentVariablesAdapterBuilder(context, fragment))
          if (fragmentsToSkip.contains(fragment.name)) {
            ignoredBuilders.add(builders.last())
          }
        }
      }
    }

    ir.operations.forEach { operation ->
      if (operation.variables.isNotEmpty()) {
        builders.add(OperationVariablesAdapterBuilder(context, operation))
      }

      builders.add(OperationSelectionsBuilder(context, operation, ir.schema, ir.allFragmentDefinitions))
      builders.add(OperationResponseAdapterBuilder(context, operation, flatten, flattenNamesInOrder))

      builders.add(
          OperationBuilder(
              context,
              generateFilterNotNull,
              operationOutput.findOperationId(operation.name),
              generateQueryDocument,
              operation,
              flatten,
              flattenNamesInOrder
          )
      )
    }

    builders.forEach { it.prepare() }
    builders
        .mapNotNull {
          if (!ignoredBuilders.contains(it)) {
            it.build()
          } else {
            null
          }
        }.forEach {
          val builder = FileSpec.builder(
              packageName = it.packageName,
              fileName = it.fileName
          ).addComment(
              """
                
                AUTO-GENERATED FILE. DO NOT MODIFY.
                
                This class was automatically generated by Apollo GraphQL version '$APOLLO_VERSION'.
                
              """.trimIndent()
          )

          it.typeSpecs.map { typeSpec -> typeSpec.internal(generateAsInternal) }.forEach { typeSpec ->
            builder.addType(typeSpec)
          }
          builder
              .build()
              .writeTo(outputDir)
        }
  }

  private fun TypeSpec.internal(generateAsInternal: Boolean): TypeSpec {
    return if (generateAsInternal) {
      this.toBuilder().addModifiers(KModifier.INTERNAL).build()
    } else {
      this
    }
  }
}