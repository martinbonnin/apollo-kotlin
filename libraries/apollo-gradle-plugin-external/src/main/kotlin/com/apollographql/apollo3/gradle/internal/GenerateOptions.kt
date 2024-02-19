package com.apollographql.apollo3.gradle.internal

import com.apollographql.apollo3.compiler.CodegenOptions
import com.apollographql.apollo3.compiler.CodegenSchemaOptions
import com.apollographql.apollo3.compiler.ExpressionAdapterInitializer
import com.apollographql.apollo3.compiler.GeneratedMethod
import com.apollographql.apollo3.compiler.IrOptions
import com.apollographql.apollo3.compiler.JavaNullable
import com.apollographql.apollo3.compiler.MODELS_OPERATION_BASED
import com.apollographql.apollo3.compiler.MODELS_OPERATION_BASED_WITH_INTERFACES
import com.apollographql.apollo3.compiler.MODELS_RESPONSE_BASED
import com.apollographql.apollo3.compiler.RuntimeAdapterInitializer
import com.apollographql.apollo3.compiler.ScalarInfo
import com.apollographql.apollo3.compiler.TargetLanguage
import com.apollographql.apollo3.compiler.writeTo
import gratatouille.GInputFiles
import gratatouille.GOutputFile
import gratatouille.GTaskAction
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File


@GTaskAction
fun generateOptions(
    packageName: String?,
    rootPackageName: String?,
    generateKotlinModels: Boolean?,
    languageVersion: String?,
    javaPluginApplied: Boolean,
    kgpVersion: String?,
    kmp: Boolean,
    alwaysGenerateTypesMatching: Set<String>?,
    generateAllTypes: Boolean,
    codegenModels: String?,
    scalarTypeMapping: Map<String, String>,
    scalarAdapterMapping: Map<String, String>,
    generateDataBuilders: Boolean?,
    addTypename: String?,
    fieldsOnDisjointTypesMustMerge: Boolean?,
    decapitalizeFields: Boolean?,
    flattenModels: Boolean?,
    warnOnDeprecatedUsages: Boolean?,
    failOnWarnings: Boolean?,
    generateOptionalOperationVariables: Boolean?,
    useSemanticNaming: Boolean?,
    generateMethods: List<String>?,
    operationManifestFormat: String?,
    generateSchema: Boolean?,
    generatedSchemaName: String?,
    sealedClassesForEnumsMatching: List<String>?,
    generateAsInternal: Boolean?,
    generateInputBuilders: Boolean?,
    addJvmOverloads: Boolean?,
    requiresOptInAnnotation: String?,
    jsExport: Boolean?,
    generateModelBuilders: Boolean?,
    classesForEnumsMatching: List<String>?,
    generatePrimitiveTypes: Boolean?,
    nullableFieldStyle: String?,
    generateFragmentImplementations: Boolean?,
    generateQueryDocument: Boolean?,
    upstreamOtherOptions: GInputFiles,
    codegenSchemaOptionsFile: GOutputFile,
    irOptionsFile: GOutputFile,
    codegenOptions: GOutputFile,
    otherOptions: GOutputFile,
) {
  check(
      packageName == null || rootPackageName == null
  ) {
    """
            |Apollo: specify 'packageName':
            |apollo {
            |  service("service") {
            |    packageName.set("com.example")
            |  }
            |}
          """.trimMargin()
  }

  val upstreamOtherOptions = upstreamOtherOptions.firstOrNull()?.toOtherOptions()
  val upstreamTargetLanguage = upstreamOtherOptions?.targetLanguage
  val targetLanguage = targetLanguage(generateKotlinModels, languageVersion, javaPluginApplied, kgpVersion, upstreamTargetLanguage)
  val generateFilterNotNull = generateFilterNotNull(targetLanguage, kmp)
  val alwaysGenerateTypesMatching = alwaysGenerateTypesMatching(alwaysGenerateTypesMatching, generateAllTypes)
  val upstreamCodegenModels = upstreamOtherOptions?.codegenModels
  val codegenModels = codegenModels(codegenModels, upstreamCodegenModels)

  CodegenSchemaOptions(
      scalarMapping = scalarMapping(scalarTypeMapping, scalarAdapterMapping),
      generateDataBuilders = generateDataBuilders,
  ).writeTo(codegenSchemaOptionsFile)

  IrOptions(
      codegenModels = codegenModels,
      addTypename = addTypename,
      fieldsOnDisjointTypesMustMerge = fieldsOnDisjointTypesMustMerge,
      decapitalizeFields = decapitalizeFields,
      flattenModels = flattenModels,
      warnOnDeprecatedUsages = warnOnDeprecatedUsages,
      failOnWarnings = failOnWarnings,
      generateOptionalOperationVariables = generateOptionalOperationVariables,
      alwaysGenerateTypesMatching = alwaysGenerateTypesMatching
  ).writeTo(irOptionsFile)

  CodegenOptions(
      targetLanguage = targetLanguage,
      useSemanticNaming = useSemanticNaming,
      generateFragmentImplementations = generateFragmentImplementations,
      generateMethods = generateMethods?.map { GeneratedMethod.fromName(it) ?: error("U") },
      generateQueryDocument = generateQueryDocument,
      generateSchema = generateSchema,
      generatedSchemaName = generatedSchemaName,
      operationManifestFormat = operationManifestFormat,
      nullableFieldStyle = nullableFieldStyle?.let { JavaNullable.fromName(it) },
      generateModelBuilders = generateModelBuilders,
      classesForEnumsMatching = classesForEnumsMatching,
      generatePrimitiveTypes = generatePrimitiveTypes,
      generateAsInternal = generateAsInternal,
      generateFilterNotNull = generateFilterNotNull,
      sealedClassesForEnumsMatching = sealedClassesForEnumsMatching,
      addJvmOverloads = addJvmOverloads,
      requiresOptInAnnotation = requiresOptInAnnotation,
      jsExport = jsExport,
      generateInputBuilders = generateInputBuilders,
      decapitalizeFields = decapitalizeFields,
      addDefaultArgumentForInputObjects = true,
      addUnknownForEnums = true,
      packageName = packageName,
      rootPackageName = rootPackageName
  ).writeTo(codegenOptions)

  OtherOptions(targetLanguage, codegenModels).writeTo(otherOptions)
}

private fun codegenModels(codegenModels: String?, upstreamCodegenModels: String?): String {
  if (codegenModels != null) {
    setOf(MODELS_OPERATION_BASED, MODELS_RESPONSE_BASED, MODELS_OPERATION_BASED_WITH_INTERFACES).apply {
      check(contains(codegenModels)) {
        "Apollo: unknown codegenModels '$codegenModels'. Valid values: $this"
      }
    }

    check(upstreamCodegenModels == null || codegenModels == upstreamCodegenModels) {
      "Apollo: Expected '$upstreamCodegenModels', got '$codegenModels'. Check your codegenModels setting."
    }
    return codegenModels
  }
  if (upstreamCodegenModels != null) {
    return upstreamCodegenModels
  }

  return MODELS_OPERATION_BASED
}

private fun targetLanguage(generateKotlinModels: Boolean?,
                           languageVersion: String?,
                           javaPluginApplied: Boolean,
                           kgpVersion: String?,
                           upstreamTargetLanguage: TargetLanguage?): TargetLanguage {
    return when {
      generateKotlinModels != null -> {
        if (generateKotlinModels) {
          check(kgpVersion != null) {
            "Apollo: generateKotlinModels.set(true) requires to apply a Kotlin plugin"
          }
          val targetLanguage = getKotlinTargetLanguage(kgpVersion, languageVersion)

          check(upstreamTargetLanguage == null || targetLanguage == upstreamTargetLanguage) {
            "Apollo: Expected '$upstreamTargetLanguage', got '$targetLanguage'. Check your generateKotlinModels and languageVersion settings."
          }
          targetLanguage
        } else {
          check(javaPluginApplied) {
            "Apollo: generateKotlinModels.set(false) requires to apply the Java plugin"
          }

          check(upstreamTargetLanguage == null || TargetLanguage.JAVA == upstreamTargetLanguage) {
            "Apollo: Expected '$upstreamTargetLanguage', got '${TargetLanguage.JAVA}'. Check your generateKotlinModels settings."
          }

          TargetLanguage.JAVA
        }
      }

      upstreamTargetLanguage != null -> {
        upstreamTargetLanguage
      }
      kgpVersion != null -> {
        getKotlinTargetLanguage(kgpVersion, languageVersion)
      }

      javaPluginApplied -> {
        TargetLanguage.JAVA
      }
      else -> {
        error("Apollo: No Java or Kotlin plugin found")
      }
    }
}



private fun scalarMapping(
    scalarTypeMapping: Map<String, String>,
    scalarAdapterMapping: Map<String, String>,
): Map<String, ScalarInfo> {
  return scalarTypeMapping.mapValues { (graphQLName, targetName) ->
    val adapterInitializerExpression = scalarAdapterMapping[graphQLName]
    ScalarInfo(targetName, if (adapterInitializerExpression == null) RuntimeAdapterInitializer else ExpressionAdapterInitializer(adapterInitializerExpression))
  }
}

@Serializable
internal class OtherOptions(
    val targetLanguage: TargetLanguage,
    val codegenModels: String
)

internal fun OtherOptions.writeTo(file: File) {
  file.writeText(Json.encodeToString(this))
}

internal fun File.toOtherOptions(): OtherOptions {
  return Json.decodeFromString(readText())
}