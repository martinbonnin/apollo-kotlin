package com.apollographql.apollo3.network.ws2.internal

import com.apollographql.apollo3.api.ApolloRequest
import com.apollographql.apollo3.api.Operation
import com.apollographql.apollo3.api.http.DefaultHttpRequestComposer
import com.apollographql.apollo3.api.json.buildJsonString
import com.apollographql.apollo3.api.json.writeAny

internal fun <D : Operation.Data> ApolloRequest<D>.toStartMessage(): String {
  return buildJsonString {
    writeAny(
        mapOf(
            "id" to requestUuid.toString(),
            "type" to "start",
            "payload" to DefaultHttpRequestComposer.composePayload(this@toStartMessage)
        )
    )
  }
}

internal fun <D : Operation.Data> ApolloRequest<D>.toStopMessage(): String {
  return buildJsonString {
    writeAny(
        mapOf(
            "id" to requestUuid.toString(),
            "type" to "stop",
        )
    )
  }
}

internal fun apolloConnectionInitMessage(connectionParams: Any?): String {
  val map = mutableMapOf<String, Any?>()
  map.put("type", "connection_init")
  if (connectionParams != null) {
    map.put("payload", connectionParams)
  }

  return buildJsonString {
    writeAny(map)
  }
}