package com.apollographql.apollo3.debugserver.internal.graphql

import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.annotations.GraphQLName
import com.apollographql.apollo3.annotations.GraphQLQueryRoot
import com.apollographql.apollo3.annotations.GraphQLScalar
import com.apollographql.apollo3.api.ExecutionContext
import com.apollographql.apollo3.ast.GQLValue
import com.apollographql.apollo3.cache.normalized.api.CacheKey
import com.apollographql.apollo3.cache.normalized.api.Record
import com.apollographql.apollo3.cache.normalized.apolloStore
import com.apollographql.apollo3.execution.Coercing
import com.apollographql.apollo3.execution.ExecutableSchema
import com.apollographql.apollo3.execution.internal.ExternalValue
import com.apollographql.apollo3.execution.parsePostGraphQLRequest
import okio.Buffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass

internal expect fun getExecutableSchema(): String

internal class GraphQL(
    private val apolloClients: AtomicReference<Map<ApolloClient, String>>,
) {
  private val executableSchema: ExecutableSchema by lazy {
    ApolloDebugServerExecutableSchemaBuilder()
        .queryRoot { Query(apolloClients) }
        .build()
  }

  fun executeGraphQL(jsonBody: String): String {
    val result = Buffer().writeUtf8(jsonBody).parsePostGraphQLRequest()
    val request = result.fold(
        onFailure = { return it.message!! },
        onSuccess = { it }
    )

    val graphQlResponse = executableSchema.execute(request, ExecutionContext.Empty)

    val buffer = Buffer()
    graphQlResponse.serialize(buffer)
    return buffer.readUtf8()
  }
}

@GraphQLQueryRoot
internal class Query(private val apolloClients: AtomicReference<Map<ApolloClient, String>>) {
  private fun graphQLApolloClients() =
      apolloClients.get().map { (apolloClient, apolloClientId) ->
        GraphQLApolloClient(
            id = apolloClientId,
            apolloClient = apolloClient
        )
      }

  fun apolloClients(): List<GraphQLApolloClient> {
    return graphQLApolloClients()
  }

  fun apolloClient(id: String): GraphQLApolloClient? {
    return graphQLApolloClients().firstOrNull { it.id() == id }
  }
}

@GraphQLName("ApolloClient")
internal class GraphQLApolloClient(
    private val id: String,
    private val apolloClient: ApolloClient,
) {
  fun id() = id

  fun displayName() = id

  fun normalizedCaches(): List<NormalizedCache> {
    val apolloStore = runCatching { apolloClient.apolloStore }.getOrNull() ?: return emptyList()
    return apolloStore.dump().map {
      NormalizedCache(id, it.key, it.value)
    }
  }

  fun normalizedCache(id: String): NormalizedCache? {
    return normalizedCaches().firstOrNull { it.id() == id }
  }
}

internal class NormalizedCache(
    apolloClientId: String,
    private val clazz: KClass<*>,
    private val records: Map<String, Record>,
) {
  private val id: String = "$apolloClientId:${clazz.normalizedCacheName()}"
  fun id() = id

  fun displayName() = clazz.normalizedCacheName()

  fun recordCount() = records.count()

  fun records(): List<GraphQLRecord> = records.map { GraphQLRecord(it.value) }
}

@GraphQLName("Record")
internal class GraphQLRecord(
    private val record: Record,
) {
  fun key(): String = record.key

  fun fields(): Map<String, Any?> = record.fields

  fun sizeInBytes(): Int = record.sizeInBytes
}

@GraphQLScalar(coercing = FieldsAdapter::class)
typealias Fields = Map<String, Any?>

internal class FieldsAdapter : Coercing<Fields> {
  override fun serialize(internalValue: Fields): ExternalValue {
    return internalValue.toExternalValue()
  }

  override fun deserialize(value: ExternalValue): Fields {
    error("Fields are never used in input position")
  }

  override fun parseLiteral(gqlValue: GQLValue): Fields {
    error("Fields are never used in input position")
  }

  // Taken from JsonRecordSerializer
  private fun Any?.toExternalValue(): Any? {
    return when (this) {
      null -> null
      is String -> this
      is Boolean -> this
      is Int -> this
      is Long -> this
      is Double -> this
      is CacheKey -> serialize()
      is List<*> -> {
        this.map { it.toExternalValue() }
      }

      is Map<*, *> -> {
        this.mapValues { it.toExternalValue() }
      }

      else -> error("Unsupported record value type: '$this'")
    }
  }
}

private fun KClass<*>.normalizedCacheName(): String = qualifiedName ?: toString()
