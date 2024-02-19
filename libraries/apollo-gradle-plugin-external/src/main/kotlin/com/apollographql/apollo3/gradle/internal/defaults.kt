@file:Suppress("DEPRECATION")

package com.apollographql.apollo3.gradle.internal

import com.apollographql.apollo3.compiler.OperationIdGenerator
import com.apollographql.apollo3.compiler.OperationOutputGenerator
import com.apollographql.apollo3.compiler.Plugin
import com.apollographql.apollo3.compiler.TargetLanguage
import com.apollographql.apollo3.compiler.operationoutput.OperationDescriptor
import com.apollographql.apollo3.compiler.operationoutput.OperationId
import com.apollographql.apollo3.compiler.operationoutput.OperationOutput

internal fun Plugin.toOperationOutputGenerator(): OperationOutputGenerator {
  return object : OperationOutputGenerator {
    override fun generate(operationDescriptorList: Collection<OperationDescriptor>): OperationOutput {
      var operationIds = operationIds(operationDescriptorList.toList())
      if (operationIds == null) {
        operationIds = operationDescriptorList.map { OperationId(OperationIdGenerator.Sha256.apply(it.source, it.name), it.name) }
      }
      return operationDescriptorList.associateBy { descriptor ->
        val operationId = operationIds.firstOrNull { it.name == descriptor.name } ?: error("No id found for operation ${descriptor.name}")
        operationId.id
      }
    }
  }
}

internal fun generateFilterNotNull(targetLanguage: TargetLanguage, isKmp: Boolean): Boolean? {
  return if (targetLanguage == TargetLanguage.JAVA) {
    null
  } else {
    isKmp
  }
}

internal fun alwaysGenerateTypesMatching(alwaysGenerateTypesMatching: Set<String>?, generateAllTypes: Boolean): Set<String> {
  if (alwaysGenerateTypesMatching != null) {
    // The user specified something, use this
    return alwaysGenerateTypesMatching
  }

  if (generateAllTypes) {
    return setOf(".*")
  } else {
    // get the used coordinates from the downstream dependencies
    return emptySet()
  }
}

fun getKotlinTargetLanguage(kgpVersion: String, userSpecified: String?): TargetLanguage {
  @Suppress("DEPRECATION_ERROR")
  return when (userSpecified) {
    "1.5" -> TargetLanguage.KOTLIN_1_5
    "1.9" -> TargetLanguage.KOTLIN_1_9
    null -> {
      // User didn't specify a version: default to the Kotlin plugin version
      val kotlinPluginVersion = kgpVersion.split("-")[0]
      val versionNumbers = kotlinPluginVersion.split(".").map { it.toInt() }
      val version = KotlinVersion(versionNumbers[0], versionNumbers[1])
      if (version.isAtLeast(1, 9)) {
        TargetLanguage.KOTLIN_1_9
      } else {
        TargetLanguage.KOTLIN_1_5
      }
    }

    else -> error("Apollo: languageVersion '$userSpecified' is not supported, Supported values: '1.5', '1.9'")
  }
}