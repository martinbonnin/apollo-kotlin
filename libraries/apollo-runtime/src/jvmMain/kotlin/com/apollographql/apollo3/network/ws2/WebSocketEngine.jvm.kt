package com.apollographql.apollo3.network.ws2

import com.apollographql.apollo3.api.http.HttpHeader
import com.apollographql.apollo3.network.defaultOkHttpClientBuilder
import com.apollographql.apollo3.network.toOkHttpHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okhttp3.WebSocket as PlatformWebSocket
import okhttp3.WebSocketListener as PlatformWebSocketListener

class JvmWebSocketEngine(private val okHttpClientBuilder: OkHttpClient.Builder): WebSocketEngine {
  override fun newWebSocket(url: String, headers: List<HttpHeader>, listener: WebSocketListener): WebSocket {
    return JvmWebSocket(okHttpClientBuilder.build(), url, headers, listener)
  }
}

internal class JvmWebSocket(
    private val webSocketFactory: PlatformWebSocket.Factory,
    private val url: String,
    private val headers : List<HttpHeader>,
    private val listener: WebSocketListener
): WebSocket, PlatformWebSocketListener() {
  private lateinit var platformWebSocket: PlatformWebSocket

  override fun onOpen(webSocket: PlatformWebSocket, response: Response) {
    listener.onOpen()
  }

  override fun onMessage(webSocket: PlatformWebSocket, bytes: ByteString) {
    listener.onMessage(bytes.toByteArray())
  }

  override fun onMessage(webSocket: PlatformWebSocket, text: String) {
    listener.onMessage(text)
  }

  override fun onFailure(webSocket: PlatformWebSocket, t: Throwable, response: Response?) {
    listener.onError(t)
  }

  override fun onClosed(webSocket: PlatformWebSocket, code: Int, reason: String) {
    listener.onClosed(code, reason)
  }

  override fun connect() {
    val request = Request.Builder()
        .url(url)
        .headers(headers.toOkHttpHeaders())
        .build()

    platformWebSocket = webSocketFactory.newWebSocket(request, this)
  }

  override fun send(data: ByteArray) {
    check(::platformWebSocket.isInitialized) {
      "You must call connect() before send()"
    }

    platformWebSocket.send(data.toByteString())
  }

  override fun send(text: String) {
    check(::platformWebSocket.isInitialized) {
      "You must call connect() before send()"
    }

    platformWebSocket.send(text)
  }

  override fun close(code: Int, reason: String) {
    check(::platformWebSocket.isInitialized) {
      "You must call connect() before close()"
    }

    platformWebSocket.close(code, "Going away")
  }
}

actual fun WebSocketEngine(): WebSocketEngine = JvmWebSocketEngine(defaultOkHttpClientBuilder)