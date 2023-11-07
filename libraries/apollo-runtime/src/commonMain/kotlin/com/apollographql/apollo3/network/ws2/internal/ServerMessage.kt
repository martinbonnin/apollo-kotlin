package com.apollographql.apollo3.network.ws2.internal

import com.apollographql.apollo3.api.json.jsonReader
import com.apollographql.apollo3.api.json.readAny
import okio.Buffer


internal sealed interface ServerMessage
internal object ConnectionAck : ServerMessage
internal object ConnectionKeepAlive : ServerMessage
internal class ConnectionError(val payload: Any?) : ServerMessage
internal class Data(val id: String, val payload: Any?) : ServerMessage
internal class Complete(val id: String) : ServerMessage
internal class Error(val id: String, val payload: Any?) : ServerMessage

// Special Server message that indicates a malformed message
internal class ParseError(val message: String) : ServerMessage

internal fun String.toServerMessage(): ServerMessage {
  val map = try {
    @Suppress("UNCHECKED_CAST")
    Buffer().writeUtf8(this).jsonReader().readAny() as Map<String, Any?>
  } catch (e: Exception) {
    return ParseError("Cannot parse server message: '$this'")
  }

  val type = map["type"] as? String
  if (type == null) {
    return ParseError("No 'type' found in server message: '$this'")
  }

  return when (type) {
    "connection_ack" -> ConnectionAck
    "connection_error" -> ConnectionError(map["payload"])
    "data", "complete", "error" -> {
      val id = map["id"] as? String
      when {
        id == null -> ParseError("No 'id' found in message: '$this'")
        type == "data" -> Data(id, map["payload"])
        type == "complete" -> Complete(id)
        type == "error" -> Error(id, map)
        else -> error("") // make the compiler happy
      }
    }

    else -> ParseError("Unknown type: '$type' found in server message: '$this'")
  }
}