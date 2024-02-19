package com.apollographql.apollo3.gradle.internal

import com.apollographql.apollo3.compiler.MANIFEST_OPERATION_OUTPUT
import com.apollographql.apollo3.compiler.MANIFEST_PERSISTED_QUERY
import com.apollographql.apollo3.compiler.toOperationOutput
import com.apollographql.apollo3.compiler.toPersistedQueryManifest
import com.apollographql.apollo3.tooling.CannotModifyOperationBody
import com.apollographql.apollo3.tooling.GraphNotFound
import com.apollographql.apollo3.tooling.PermissionError
import com.apollographql.apollo3.tooling.PersistedQuery
import com.apollographql.apollo3.tooling.PublishOperationsSuccess
import com.apollographql.apollo3.tooling.RegisterOperations
import com.apollographql.apollo3.tooling.publishOperations
import gratatouille.GInputFile
import gratatouille.GTaskAction


@GTaskAction
fun registerOperations(
    listId: String?,
    operationManifestFormat: String,
    operationOutput: GInputFile,
    key: String?,
    graph: String?,
    graphVariant: String?,
) {

  if (listId != null) {
    check(operationManifestFormat == MANIFEST_PERSISTED_QUERY) {
      """Apollo: registering operations to a persisted query list requires operationManifestFormat = "$MANIFEST_PERSISTED_QUERY":
          |apollo {
          |  service("service") {
          |    operationManifestFormat.set("$MANIFEST_PERSISTED_QUERY")
          |  }
          |}
        """.trimMargin()
    }
    val result = publishOperations(
        listId = listId,
        persistedQueries = operationOutput.toPersistedQueryManifest().operations.map {
          PersistedQuery(
              name = it.name,
              id = it.id,
              body = it.body,
              operationType = it.type
          )
        },
        apolloKey = key ?: error("key is required to register operations"),
        graph = graph
    )

    when(result) {
      is PublishOperationsSuccess -> {
        logger().warning("Apollo: persisted query list uploaded successfully")
      }

      is CannotModifyOperationBody -> error("Cannot upload persisted query list: cannot modify operation body ('${result.message}')")
      GraphNotFound ->  error("Cannot upload persisted query list: graph '$graph' not found")
      is PermissionError -> error("Cannot upload persisted query list: permission error ('${result.message}')")
    }
  } else {
    logger().warning("Apollo: registering operations without a listId is deprecated")
    check(operationManifestFormat == MANIFEST_OPERATION_OUTPUT) {
      """Apollo: registering legacy operations requires operationManifestFormat = "$MANIFEST_OPERATION_OUTPUT":
          |apollo {
          |  service("service") {
          |    operationManifestFormat.set("$MANIFEST_OPERATION_OUTPUT")
          |  }
          |}
        """.trimMargin()
    }
    @Suppress("DEPRECATION")
    RegisterOperations.registerOperations(
        key = key ?: error("key is required to register operations"),
        graphID = graph ?: error("graphID is required to register operations"),
        graphVariant = graphVariant ?: error("graphVariant is required to register operations"),
        operationOutput = operationOutput.toOperationOutput()
    )
  }
}