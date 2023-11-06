package com.apollographql.apollo3.network.ws2

import com.apollographql.apollo3.api.http.HttpHeader
import com.apollographql.apollo3.exception.DefaultApolloException
import org.khronos.webgl.Uint8Array
import org.w3c.dom.CloseEvent
import org.w3c.dom.WebSocket as PlatformWebSocket

internal class JsWebSocketEngine: WebSocketEngine {
  override fun newWebSocket(url: String, headers: List<HttpHeader>, listener: WebSocketListener): WebSocket {
    return JsWebSocket(url, headers, listener)
  }
}

internal class JsWebSocket(
    private val url: String,
    private val headers: List<HttpHeader>,
    private val listener: WebSocketListener,
) : WebSocket {
  private lateinit var platformWebSocket: PlatformWebSocket
  private var disposed = false

  override fun connect() {
    platformWebSocket = createWebSocket(url, headers)
    platformWebSocket.onopen = {
      listener.onOpen()
    }

    platformWebSocket.onmessage = {
      when (val data = it.data) {
        is String -> listener.onMessage(data)
        else -> {
          if (!disposed) {
            disposed = true
            listener.onError(DefaultApolloException("The JS WebSocket implementation only support text messages"))
            platformWebSocket.close(CLOSE_GOING_AWAY.toShort(), "Unsupported message received")
          }
        }
      }
    }

    platformWebSocket.onerror = {
      if (!disposed) {
        disposed = true
        listener.onError(DefaultApolloException("Error while reading websocket"))
      }
    }

    platformWebSocket.onclose = {
      if (!disposed) {
        disposed = true
        it as CloseEvent
        if (it.wasClean) {
          listener.onClosed(it.code.toInt(), it.reason)
        } else {
          listener.onError(DefaultApolloException("WebSocket was closed"))
        }
      }
    }
  }

  override fun send(data: ByteArray) {
    check(::platformWebSocket.isInitialized) {
      "You must call connect() before send()"
    }

    if (platformWebSocket.bufferedAmount.toDouble() > MAX_BUFFERED) {
      if (!disposed) {
        disposed = true
        listener.onError(DefaultApolloException("Too much data queued"))
      }
    }
    platformWebSocket.send(Uint8Array(data.toTypedArray()))
  }

  override fun send(text: String) {
    check(::platformWebSocket.isInitialized) {
      "You must call connect() before send()"
    }

    if (platformWebSocket.bufferedAmount.toDouble() > MAX_BUFFERED) {
      if (!disposed) {
        disposed = true
        listener.onError(DefaultApolloException("Too much data queued"))
      }
    }

    platformWebSocket.send(text)
  }

  override fun close(code: Int, reason: String) {
    check(::platformWebSocket.isInitialized) {
      "You must call connect() before close()"
    }

    if (!disposed) {
      platformWebSocket.close(code.toShort(), "Going away")
    }
  }
}

/**
 * This is probably far too high and problems will probably appear much sooner but this is used to signal the intent
 * of this code as well as a last resort
 */
private val MAX_BUFFERED = 100_000_000

/**
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 * From https://github.com/ktorio/ktor/blob/6cd529b2dcedfcfc4ca2af0f62704764e160d7fd/ktor-utils/js/src/io/ktor/util/PlatformUtilsJs.kt#L16
 */
fun isNode(): Boolean {
  return js(
      """
                (typeof process !== 'undefined' 
                    && process.versions != null 
                    && process.versions.node != null) ||
                (typeof window !== 'undefined' 
                    && typeof window.process !== 'undefined' 
                    && window.process.versions != null 
                    && window.process.versions.node != null)
                """
  ) as Boolean
}

/*
 * The function below works due to ktor being used for the HTTP engine
 * and how ktor imports ws, if this changes, ws would need to be added
 * as a direct dependency.
 *
 * The following applies for lines below
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 * Some lines have been added/modified from the original source (https://github.com/ktorio/ktor/blob/f723638afd4d36024c390c5b79108b53ab513943/ktor-client/ktor-client-core/js/src/io/ktor/client/engine/js/JsClientEngine.kt#L62)
 * in order to fix an issue with subprotocols on Node (https://youtrack.jetbrains.com/issue/KTOR-4001)
 */
// Adding "_capturingHack" to reduce chances of JS IR backend to rename variable,
// so it can be accessed inside js("") function
@Suppress("UNUSED_PARAMETER", "UnsafeCastFromDynamic", "UNUSED_VARIABLE", "LocalVariableName")
private fun createWebSocket(urlString_capturingHack: String, headers: List<HttpHeader>): PlatformWebSocket {
  val (protocolHeaders, otherHeaders) = headers.partition { it.name.equals("sec-websocket-protocol", true) }
  val protocols = protocolHeaders.mapNotNull { it.value }.toTypedArray()
  return if (isNode()) {
    val ws_capturingHack = js("eval('require')('ws')")
    val headers_capturingHack: dynamic = object {}
    headers.forEach {
      headers_capturingHack[it.name] = it.value
    }
    js("new ws_capturingHack(urlString_capturingHack, protocols, { headers: headers_capturingHack })")
  } else {
    check(otherHeaders.isEmpty()) {
      "Apollo: the WebSocket browser API doesn't allow passing headers. Use connectionPayload or other mechanisms."
    }
    js("new WebSocket(urlString_capturingHack, protocols)")
  }
}

actual fun WebSocketEngine(): WebSocketEngine = JsWebSocketEngine()