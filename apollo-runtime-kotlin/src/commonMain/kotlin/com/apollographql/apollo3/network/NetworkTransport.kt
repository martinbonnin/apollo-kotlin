package com.apollographql.apollo3.network

import com.apollographql.apollo3.api.ResponseAdapterCache
import com.apollographql.apollo3.api.Operation
import com.apollographql.apollo3.ApolloRequest
import com.apollographql.apollo3.api.Response
import kotlinx.coroutines.flow.Flow

interface NetworkTransport {

  fun <D : Operation.Data> execute(
      request: ApolloRequest<D>,
      responseAdapterCache: ResponseAdapterCache,
  ): Flow<Response<D>>
}