package com.apollographql.apollo3.network.ws2

import com.apollographql.apollo3.api.http.HttpHeader

interface WebSocketEngine {
  fun newWebSocket(
      url: String,
      headers: List<HttpHeader> = emptyList(),
      listener: WebSocketListener
  ): WebSocket
}

interface WebSocketListener {
  /**
   * The HTTP 101 Switching Protocols response has been received
   */
  fun onOpen()

  /**
   * A text message has been received
   */
  fun onMessage(text: String)


  /**
   * A data message has been received
   */
  fun onMessage(data: ByteArray)

  /**
   * An error happened
   */
  fun onError(throwable: Throwable)

  /**
   * The server sent a close frame
   */
  fun onClosed(code: Int?, reason: String?)
}

interface WebSocket {
  /**
   * Opens and starts reading the socket
   */
  fun connect()


  /**
   * Sends a binary message asynchronously.
   *
   * There is no flow control. If the application is sending messages too fast, the connection is closed.
   */
  fun send(data: ByteArray)

  /**
   * Sends a text message asynchronously.
   *
   * There is no flow control. If the application is sending messages too fast, the connection is closed.
   */
  fun send(text: String)

  /**
   * closes the websocket gracefully and asynchronously
   */
  fun close(code: Int, reason: String)
}

expect fun WebSocketEngine() : WebSocketEngine

const val CLOSE_NORMAL = 1000
const val CLOSE_GOING_AWAY = 1001
