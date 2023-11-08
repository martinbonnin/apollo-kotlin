package com.apollographql.apollo3.network.ws2

sealed interface ServerMessage
object ConnectionAck : ServerMessage
object ConnectionKeepAlive : ServerMessage
object Ping : ServerMessage
object Pong : ServerMessage
class ConnectionError(val payload: Any?) : ServerMessage

/**
 * A GraphQL response was received
 *
 * @param response, a GraphQL response, possibly containing errors
 * @param complete, whether this is a terminal message
 */
class Response(val id: String, val response: Any?, val complete: Boolean) : ServerMessage

/**
 * The subscription completed normally
 */
class Complete(val id: String) : ServerMessage

/**
 * There was an error with the operation that cannot be represented by a GraphQL response
 *
 * This is a terminal message
 *
 * @param payload additional information regarding the error
 */
class OperationError(val id: String, val payload: Any?) : ServerMessage

/**
 * Special Server message that indicates a malformed message
 */
class ParseError(val errorMessage: String) : ServerMessage
