package com.apollographql.apollo3.execution

import com.apollographql.apollo3.api.json.JsonReader
import com.apollographql.apollo3.api.json.JsonWriter
import com.apollographql.apollo3.ast.GQLValue
import com.apollographql.apollo3.execution.internal.InternalValue

/**
 * See https://www.graphql.de/blog/scalars-in-depth/
 */
interface Coercing {
  fun serialize(jsonWriter: JsonWriter, internalValue: InternalValue)
  fun deserialize(jsonReader: JsonReader): InternalValue
  fun parseLiteral(gqlValue: GQLValue): InternalValue
}