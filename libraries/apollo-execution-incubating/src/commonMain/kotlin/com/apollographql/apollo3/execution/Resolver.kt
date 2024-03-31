package com.apollographql.apollo3.execution

import com.apollographql.apollo3.api.CustomScalarAdapters
import com.apollographql.apollo3.api.ExecutionContext
import com.apollographql.apollo3.api.Optional
import com.apollographql.apollo3.api.json.MapJsonReader
import com.apollographql.apollo3.ast.GQLBooleanValue
import com.apollographql.apollo3.ast.GQLEnumValue
import com.apollographql.apollo3.ast.GQLFieldDefinition
import com.apollographql.apollo3.ast.GQLFloatValue
import com.apollographql.apollo3.ast.GQLIntValue
import com.apollographql.apollo3.ast.GQLListType
import com.apollographql.apollo3.ast.GQLListValue
import com.apollographql.apollo3.ast.GQLNamedType
import com.apollographql.apollo3.ast.GQLNonNullType
import com.apollographql.apollo3.ast.GQLNullValue
import com.apollographql.apollo3.ast.GQLObjectValue
import com.apollographql.apollo3.ast.GQLStringValue
import com.apollographql.apollo3.ast.GQLType
import com.apollographql.apollo3.ast.GQLValue
import com.apollographql.apollo3.ast.GQLVariableValue
import com.apollographql.apollo3.ast.Schema
import com.apollographql.apollo3.ast.definitionFromScope

fun interface Resolver {
  /**
   * Resolves a field. A typical implementation is to use [ResolveInfo.parentObject]:
   *
   * ```kotlin
   * fun resolve(resolveInfo: ResolveInfo): Any? {
   *   val parent = resolveInfo.parentObject as Map<String, Any?>
   *   return parent[resolveInfo.fieldName]
   * }
   * ```
   *
   */
  fun resolve(resolveInfo: ResolveInfo): Any?
}

interface Roots {
  fun query(): Any
  fun mutation(): Any
  fun subscription(): Any

  companion object {
    fun create(queryRoot: (() -> Any)?, mutationRoot: (() -> Any)?, subscriptionRoot: (() -> Any)?): Roots {
      return object : Roots {
        override fun query(): Any {
          return queryRoot?.invoke() ?: DefaultQueryRoot
        }

        override fun mutation(): Any {
          return mutationRoot?.invoke() ?: DefaultMutationRoot
        }

        override fun subscription(): Any {
          return subscriptionRoot?.invoke() ?: DefaultSubscriptionRoot
        }
      }
    }
  }
}

/**
 * A resolver that always throws
 */
internal object ThrowingResolver : Resolver {
  override fun resolve(resolveInfo: ResolveInfo): Any? {
    error("No resolver found for '${resolveInfo.coordinates()}' and no defaultResolver set.")
  }
}

interface Instrumentation {
  /**
   * For subscriptions, this is called only once on the root field and then for every data in the nested fields
   */
  fun beforeResolve(resolveInfo: ResolveInfo)
}

class ResolveTypeInfo(
    val type: String,
    val schema: Schema
)

@Suppress("UNCHECKED_CAST")
class ResolveInfo(
    /**
     * The parent object, maybe be [DefaultRoot]
     *
     * @see [ExecutableSchema.Builder.queryRoot]
     * @see [ExecutableSchema.Builder.mutationRoot]
     * @see [ExecutableSchema.Builder.subscriptionRoot]
     */
    val parentObject: Any,
    val executionContext: ExecutionContext,
    val field: MergedField,
    val schema: Schema,
    val variables: Map<String, Any?>,
    val adapters: CustomScalarAdapters,
    val parentType: String,
) {
  val fieldName: String = field.first.name

  fun fieldDefinition(): GQLFieldDefinition {
    return field.first.definitionFromScope(schema, parentType)
        ?: error("Cannot find fieldDefinition $parentType.${field.first.name}")
  }

  fun <T> getArgument(
      name: String,
  ): Optional<T> {
    val fieldDefinition = fieldDefinition()
    val argument = field.first.arguments.firstOrNull { it.name == name }
    val argumentDefinition = fieldDefinition.arguments.first { it.name == name }

    val argumentValue = when {
      argument != null -> argument.value
      else -> argumentDefinition.defaultValue
    }

    if (argumentValue == null) {
      return Optional.absent()
    }

    val jsonMap = argumentValue.toJson(variables)
    return Optional.present(jsonMap.adaptFromJson(argumentDefinition.type)) as Optional<T>
  }

  fun Any?.adaptFromJson(type: GQLType): Any? {
    return when (type) {
      is GQLNonNullType -> {
        check(this != null)
        adaptFromJson(type.type)
      }

      is GQLListType -> {
        if (this == null) {
          null
        } else {
          check(this is List<*>)
          map { it.adaptFromJson(type.type) }
        }
      }

      is GQLNamedType -> {
        if (this == null) {
          null
        } else {
          val adapter = adapters.adapterFor<Any>(type.name)
          when {
            adapter != null -> adapter.fromJson(MapJsonReader(this), CustomScalarAdapters.Empty)
            else -> this
          }
        }
      }
    }
  }

  fun coordinates(): String {
    return "$parentType.$fieldName"
  }
}

internal fun GQLValue.toJson(variables: Map<String, Any?>?): Any? {
  return when (this) {
    is GQLBooleanValue -> value
    is GQLEnumValue -> value
    is GQLFloatValue -> value.toDouble()
    is GQLIntValue -> value.toInt()
    is GQLListValue -> this.values.map {
      it.toJson(variables)
    }

    is GQLNullValue -> null
    is GQLObjectValue -> this.fields.associate {
      it.name to it.value.toJson(variables)
    }

    is GQLStringValue -> this.value
    is GQLVariableValue -> {
      check(variables != null) {
        "Cannot use this value in non-const context"
      }
      check(variables.containsKey(this.name)) {
        "No variable found for '$name'"
      }
      variables.get(this.name)
    }
  }
}
