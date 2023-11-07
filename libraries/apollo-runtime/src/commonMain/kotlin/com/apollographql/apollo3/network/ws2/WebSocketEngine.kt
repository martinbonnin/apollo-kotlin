package com.apollographql.apollo3.network.ws2

import com.apollographql.apollo3.api.http.HttpHeader

interface WebSocketEngine {
  /**
   * Creates a new [WebSocket].
   *
   * @param url: an url starting with one of:
   * - ws://
   * - wss://
   * - http://
   * - https://
   *
   * If the underlying engine requires a ws or wss, http and https are replaced by ws and wss respectively
   */
  fun newWebSocket(
      url: String,
      headers: List<HttpHeader> = emptyList(),
      listener: WebSocketListener
  ): WebSocket
}

interface WebSocketListener {
  /**
   * The HTTP 101 Switching Protocols response has been received and is valid
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
   * An error happened, no more calls to the listener are made
   */
  fun onError(throwable: Throwable)

  /**
   * The server sent a close frame, no more calls to the listener are made
   */
  fun onClosed(code: Int?, reason: String?)
}

interface WebSocket {
  /**
   * Opens and starts reading the socket. No calls to the listener are made before [connect]
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
   * closes the websocket gracefully and asynchronously. No more calls to the listener are made
   *
   * On Apple, cancelWithCloseCode calls the URLSession delegate with the same (client) code making it impossible to
   * retrieve the server code. For this reason, this call is terminal and does not trigger the listener.
   */
  fun close(code: Int, reason: String)
}

expect fun WebSocketEngine() : WebSocketEngine

const val CLOSE_NORMAL = 1000
const val CLOSE_GOING_AWAY = 1001
