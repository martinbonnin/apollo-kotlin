package com.apollographql.apollo3.annotations

/**
 * Marks the target class as a GraphQL subscription root.
 *
 * There can be only one [GraphqlSubscriptionRoot] class in a given compilation.
 */
@Target(AnnotationTarget.CLASS)
annotation class GraphqlSubscriptionRoot
