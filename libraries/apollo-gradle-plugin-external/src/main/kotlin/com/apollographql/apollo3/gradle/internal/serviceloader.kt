package com.apollographql.apollo3.gradle.internal

import com.apollographql.apollo3.compiler.ApolloCompiler
import com.apollographql.apollo3.compiler.Plugin
import java.util.ServiceLoader

internal fun apolloCompilerPlugin(): Plugin? {
  val plugins = ServiceLoader.load(Plugin::class.java, Plugin::class.java.classLoader).toList()

  if (plugins.size > 1) {
    error("Apollo: only a single compiler plugin is allowed")
  }

  return plugins.singleOrNull()
}

fun logger() = object : ApolloCompiler.Logger {
  override fun warning(message: String) {
    println(message)
  }
}
