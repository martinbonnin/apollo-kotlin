package com.apollographql.apollo3.annotations

/**
 * Marks the target class as a GraphQL subscription root.
 *
 * There can be only one [GraphQLSubscriptionRoot] class in a given compilation.
 */
@Target(AnnotationTarget.CLASS)
@ApolloExperimental
annotation class GraphQLSubscriptionRoot
