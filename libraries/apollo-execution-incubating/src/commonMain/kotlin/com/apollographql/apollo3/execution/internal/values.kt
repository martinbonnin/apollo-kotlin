package com.apollographql.apollo3.execution.internal

import com.apollographql.apollo3.api.json.JsonNumber
import com.apollographql.apollo3.ast.GQLBooleanValue
import com.apollographql.apollo3.ast.GQLEnumValue
import com.apollographql.apollo3.ast.GQLFloatValue
import com.apollographql.apollo3.ast.GQLIntValue
import com.apollographql.apollo3.ast.GQLListValue
import com.apollographql.apollo3.ast.GQLNullValue
import com.apollographql.apollo3.ast.GQLObjectValue
import com.apollographql.apollo3.ast.GQLStringValue
import com.apollographql.apollo3.ast.GQLValue
import com.apollographql.apollo3.ast.GQLVariableValue

/**
 * An external value, usually JSON
 * - Numbers are stored as Int, Long, Double or JsonNumber for arbitrary precision
 * - Enums are stored as Strings
 */
internal typealias ExternalValue = Any?

/**
 * An internal value
 * - Numbers are either Int, Double or the result of custom scalar coercion (see below)
 * - Enums are Strings
 * - May contain the internal representation of custom scalars (java.util.Date, kotlin.Long, etc...)
 * - Input objects are Maps
 * - Output objects are Maps
 *
 * Note: the return value of the resolvers are not considered [InternalValue] and are never coerced to [ExternalValue].
 * Only their fields are
 */
internal typealias InternalValue = Any?

/**
 * The result of a resolver
 *
 * For leaf types, it's an [InternalValue]
 * For composite output types, it's an opaque value
 * For list types, it's a kotlin List
 */
internal typealias ResolverValue = Any?

/**
 * This function is a bit weird
 */
internal fun GQLValue.toInternalValue(): InternalValue {
  return when (this) {
    is GQLBooleanValue -> value
    is GQLEnumValue -> value
    is GQLFloatValue -> JsonNumber(value)
    is GQLIntValue -> JsonNumber(value)
    is GQLListValue -> values.map { it.toInternalValue() }
    is GQLNullValue -> null
    is GQLObjectValue -> fields.associate { it.name to it.value.toInternalValue() }
    is GQLStringValue -> value
    is GQLVariableValue -> error("Variables can't be used in const context")
  }
}