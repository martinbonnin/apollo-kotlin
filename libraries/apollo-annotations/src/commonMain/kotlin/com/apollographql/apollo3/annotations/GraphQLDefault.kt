package com.apollographql.apollo3.annotations


@Target(AnnotationTarget.VALUE_PARAMETER)
@ApolloExperimental
annotation class GraphQLDefault(val value: String)
