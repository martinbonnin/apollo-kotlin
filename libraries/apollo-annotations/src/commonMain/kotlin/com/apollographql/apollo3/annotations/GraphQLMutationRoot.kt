package com.apollographql.apollo3.annotations

/**
 * Marks the target class as a GraphQL mutation root.
 *
 * There can be only one [GraphQLMutationRoot] class in a given compilation.
 */
@Target(AnnotationTarget.CLASS)
@ApolloExperimental
annotation class GraphQLMutationRoot
