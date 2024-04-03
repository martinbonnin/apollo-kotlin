package com.apollographql.apollo3.execution

import com.apollographql.apollo3.api.json.JsonNumber
import com.apollographql.apollo3.ast.GQLBooleanValue
import com.apollographql.apollo3.ast.GQLFloatValue
import com.apollographql.apollo3.ast.GQLIntValue
import com.apollographql.apollo3.ast.GQLStringValue
import com.apollographql.apollo3.ast.GQLValue
import com.apollographql.apollo3.execution.internal.ExternalValue
import com.apollographql.apollo3.execution.internal.InternalValue

/**
 * See https://www.graphql.de/blog/scalars-in-depth/
 *
 * TODO: add a version that can stream
 */
interface Coercing<T> {
  /**
   * Serializes from an internal value to an external value.
   *
   * For an example Date --> String
   */
  fun serialize(internalValue: T): ExternalValue

  /**
   * Deserializes from an external value to an internal value.
   *
   * For an example String --> Date
   */
  fun deserialize(value: ExternalValue): T
  fun parseLiteral(gqlValue: GQLValue): T
}


internal fun coercingSerialize(value: InternalValue, coercings: Map<String, Coercing<*>>, typename: String): ExternalValue {
  return when (typename) {
    "Int" -> {
      check(value is Int)
      value
    }
    "Float" -> {
      check(value is Double)
      value
    }
    "String" -> {
      check(value is String)
      value
    }
    "Boolean" -> {
      check(value is Boolean)
      value
    }
    "ID" -> {
      when (value) {
        is String -> value
        is Int -> value.toString()
        is Long -> value.toString()
        is JsonNumber -> value.value
        else -> error("Cannot coerce '$value' to an ID")
      }
    }
    else -> {
      @Suppress("UNCHECKED_CAST")
      val coercing = coercings.get(typename) as Coercing<ExternalValue>?
      if (coercing == null) {
        error("Cannot get coercing for '${typename}'")
      }
      coercing.serialize(value)
    }
  }
}


internal fun coercingParseLiteral(value: GQLValue, coercings: Map<String, Coercing<*>>, typename: String): InternalValue {
  return when (typename) {
    "Int" -> {
      check(value is GQLIntValue)
      value.value
    }
    "Float" -> {
      check(value is GQLFloatValue)
      value.value
    }
    "String" -> {
      check(value is GQLStringValue)
      value.value
    }
    "Boolean" -> {
      check(value is GQLBooleanValue)
      value.value
    }
    "ID" -> {
      when (value) {
        is GQLStringValue -> value.value
        is GQLIntValue -> value.value
        else -> error("Cannot parse '$value' to an ID String")
      }
    }
    else -> {
      @Suppress("UNCHECKED_CAST")
      val coercing = coercings.get(typename) as Coercing<ExternalValue>?
      if (coercing == null) {
        error("Cannot get coercing for '${typename}'")
      }
      coercing.parseLiteral(value)
    }
  }
}

internal fun coercingDeserialize(value: ExternalValue, coercings: Map<String, Coercing<*>>, typename: String): InternalValue {
  return when (typename) {
    "Int" -> {
      when(value) {
        is Int -> value
        is JsonNumber -> value.value.toInt()
        else -> error("Cannot deserialize '$value' to an Int")
      }
    }
    "Float" -> {
      when(value) {
        is Int -> value.toDouble()
        is Double -> value
        is JsonNumber -> value.value.toDouble()
        else -> error("Cannot deserialize '$value' to a Double")
      }
    }
    "String" -> {
      check(value is String)
      value
    }
    "Boolean" -> {
      check(value is Boolean)
      value
    }
    "ID" -> {
      when (value) {
        is String -> value
        is Int -> value.toString()
        is JsonNumber -> value.toString()
        else -> error("Cannot deserialize '$value' to an ID String")
      }
    }
    else -> {
      @Suppress("UNCHECKED_CAST")
      val coercing = coercings.get(typename) as Coercing<ExternalValue>?
      if (coercing == null) {
        error("Cannot get coercing for '${typename}'")
      }
      coercing.deserialize(value)
    }
  }
}
