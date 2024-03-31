package com.apollographql.apollo3.annotations

/**
 * Marks the target class as a GraphQL query root.
 *
 * There can be only one [GraphqlQueryRoot] class in a given compilation.
 */
@Target(AnnotationTarget.CLASS)
annotation class GraphqlQueryRoot
