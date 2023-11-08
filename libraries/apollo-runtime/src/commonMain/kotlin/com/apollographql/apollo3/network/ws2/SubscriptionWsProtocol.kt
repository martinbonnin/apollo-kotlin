package com.apollographql.apollo3.network.ws2

import com.apollographql.apollo3.api.ApolloRequest
import com.apollographql.apollo3.api.Operation
import com.apollographql.apollo3.api.http.DefaultHttpRequestComposer
import com.apollographql.apollo3.api.json.jsonReader
import com.apollographql.apollo3.api.json.readAny
import okio.Buffer

class SubscriptionWsProtocol(
    val connectionParams: suspend () -> Any?,
) : WsProtocol {
  override val name: String
    get() = "graphql-ws"

  override suspend fun connectionInit(): ClientMessage {
    val map = mutableMapOf<String, Any?>()
    map.put("type", "connection_init")
    val paramas = connectionParams()
    if (paramas != null) {
      map.put("payload", paramas)
    }

    return map.toClientMessage()
  }

  override suspend fun <D : Operation.Data> operationStart(request: ApolloRequest<D>): ClientMessage {
    return mapOf(
        "id" to request.requestUuid.toString(),
        "type" to "start",
        "payload" to DefaultHttpRequestComposer.composePayload(request)
    ).toClientMessage()
  }

  override suspend fun <D : Operation.Data> operationStop(request: ApolloRequest<D>): ClientMessage {
    return mapOf(
        "type" to "stop",
        "id" to request.requestUuid.toString(),
    ).toClientMessage()
  }

  override suspend fun ping(): ClientMessage? {
    return null
  }

  override suspend fun pong(): ClientMessage? {
    return null
  }

  override fun parseServerMessage(text: String): ServerMessage {
    val map = try {
      @Suppress("UNCHECKED_CAST")
      Buffer().writeUtf8(text).jsonReader().readAny() as Map<String, Any?>
    } catch (e: Exception) {
      return ParseError("Cannot parse server message: '$text'")
    }

    val type = map["type"] as? String
    if (type == null) {
      return ParseError("No 'type' found in server message: '$text'")
    }

    return when (type) {
      "connection_ack" -> ConnectionAck
      "connection_error" -> ConnectionError(map["payload"])
      "data", "complete", "error" -> {
        val id = map["id"] as? String
        when {
          id == null -> ParseError("No 'id' found in message: '$text'")
          type == "data" -> Response(id, map["payload"], false)
          type == "complete" -> Complete(id)
          type == "error" -> OperationError(id, map)
          else -> error("") // make the compiler happy
        }
      }

      else -> ParseError("Unknown type: '$type' found in server message: '$text'")
    }
  }
}

