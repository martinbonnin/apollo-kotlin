package com.apollographql.apollo3.annotations

/**
 * Marks the target class as a GraphQL mutation root.
 *
 * There can be only one [GraphqlMutationRoot] class in a given compilation.
 */
@Target(AnnotationTarget.CLASS)
annotation class GraphqlMutationRoot
