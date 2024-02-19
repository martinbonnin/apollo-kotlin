package com.apollographql.apollo3.gradle.internal

import com.apollographql.apollo3.ast.introspection.toGQLDocument
import com.apollographql.apollo3.ast.introspection.toIntrospectionSchema
import com.apollographql.apollo3.ast.introspection.writeTo
import com.apollographql.apollo3.ast.toFullSchemaGQLDocument
import com.apollographql.apollo3.ast.toGQLDocument
import com.apollographql.apollo3.ast.toUtf8
import gratatouille.GInternal
import gratatouille.GTaskAction
import java.io.File

private fun File.isIntrospection() = extension == "json"

@GTaskAction
fun convertSchema(
    @GInternal projectRootDir: String,
    from: String,
    to: String,
) {
  // Files are relative to the root project. It is not possible in a consistent way to have them relative to the current
  // working directory where the gradle command was started
  @Suppress("NAME_SHADOWING")
  val from = File(projectRootDir).resolve(from)

  @Suppress("NAME_SHADOWING")
  val to = File(projectRootDir).resolve(to)

  check(from.isIntrospection() && !to.isIntrospection() || !from.isIntrospection() && to.isIntrospection()) {
    "Cannot convert from ${from.name} to ${to.name}, they are already the same format"
  }

  if (from.isIntrospection()) {
    from.toIntrospectionSchema().toGQLDocument().toUtf8(to)
  } else {
    from.toGQLDocument().toFullSchemaGQLDocument().toIntrospectionSchema().writeTo(to)
  }
}
