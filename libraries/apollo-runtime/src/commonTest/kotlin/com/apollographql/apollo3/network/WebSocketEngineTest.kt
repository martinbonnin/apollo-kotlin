package com.apollographql.apollo3.network

import com.apollographql.apollo3.mockserver.BinaryMessage
import com.apollographql.apollo3.mockserver.CloseFrame
import com.apollographql.apollo3.mockserver.MockRequestBase
import com.apollographql.apollo3.mockserver.MockServer
import com.apollographql.apollo3.mockserver.PingFrame
import com.apollographql.apollo3.mockserver.PongFrame
import com.apollographql.apollo3.mockserver.TextMessage
import com.apollographql.apollo3.mockserver.WebSocketBody
import com.apollographql.apollo3.mockserver.WebSocketMessage
import com.apollographql.apollo3.mockserver.WebsocketMockRequest
import com.apollographql.apollo3.mockserver.awaitWebSocketRequest
import com.apollographql.apollo3.mockserver.enqueueWebSocket
import com.apollographql.apollo3.mpp.Platform
import com.apollographql.apollo3.mpp.platform
import com.apollographql.apollo3.network.ws2.WebSocket
import com.apollographql.apollo3.network.ws2.WebSocketEngine
import com.apollographql.apollo3.network.ws2.WebSocketListener
import com.apollographql.apollo3.testing.internal.runTest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds


private class Item(
    val message: WebSocketMessage? = null,
    val open: Boolean = false,
    val throwable: Throwable? = null,
)

private class Listener(private val channel: Channel<Item>) : WebSocketListener {
  override fun onOpen() {
    channel.trySend(Item(open = true))
  }

  override fun onMessage(text: String) {
    channel.trySend(TextMessage(text))
  }

  override fun onMessage(data: ByteArray) {
    channel.trySend(BinaryMessage(data))
  }

  override fun onError(throwable: Throwable) {
    channel.trySend(Item(throwable = throwable))
  }

  override fun onClosed(code: Int?, reason: String?) {
    channel.trySend(CloseFrame(code, reason))
  }
}

private fun debug(line: String) {
  if (true) {
    println(line)
  }
}

private val mockServerListener = object : MockServer.Listener {
  override fun onRequest(request: MockRequestBase) {
    debug("Client: ${request.method} ${request.path}")
  }

  override fun onMessage(message: WebSocketMessage) {
    debug("Client: ${message.pretty()}")
  }
}

private fun WebSocketMessage.pretty(): String = when (this) {
  is TextMessage -> {
    "TextMessage(${this.text})"
  }

  is BinaryMessage -> {
    "BinaryMessage(${this.data.toByteString().hex()})"
  }

  is CloseFrame -> {
    "CloseFrame($code, $reason)"
  }

  PingFrame -> "PingFrame"
  PongFrame -> "PongFrame"
}

class WebSocketEngineTest {
  private class Scope(
      val clientReader: Channel<Item>,
      val clientWriter: WebSocket,
      val serverReader: WebsocketMockRequest,
      val serverWriter: WebSocketBody,
  )

  private fun test(block: suspend Scope.() -> Unit) = runTest {
    val mockServer = MockServer.Builder()
        .listener(mockServerListener)
        .build()

    val engine = WebSocketEngine()

    val clientReader = Channel<Item>(Channel.UNLIMITED)

    val clientWriter = engine.newWebSocket(mockServer.url(), emptyList(), Listener(clientReader))

    val serverWriter = mockServer.enqueueWebSocket()

    clientWriter.connect()
    val serverReader = mockServer.awaitWebSocketRequest()

    Scope(clientReader, clientWriter, serverReader, serverWriter).block()

    mockServer.close()
  }

  @Test
  fun simpleSessionWithClientClose() = test {
    clientReader.awaitOpen()

    clientWriter.send("Client Text")
    serverReader.awaitMessage().apply {
      assertIs<TextMessage>(this)
      assertEquals("Client Text", this.text)
    }

    serverWriter.enqueueMessage(TextMessage("Server Text"))
    clientReader.awaitMessage().apply {
      assertIs<TextMessage>(this)
      assertEquals("Server Text", this.text)
    }

    clientWriter.send("Client Data".encodeToByteArray())
    serverReader.awaitMessage().apply {
      assertIs<BinaryMessage>(this)
      assertEquals("Client Data", this.data.decodeToString())
    }

    serverWriter.enqueueMessage(BinaryMessage("Server Data".encodeToByteArray()))
    clientReader.awaitMessage().apply {
      assertIs<BinaryMessage>(this)
      assertEquals("Server Data", this.data.decodeToString())
    }

    clientWriter.close(1003, "Client Bye")
    if (platform() != Platform.Native) {
      // Apple sometimes does not send the Close frame. See https://developer.apple.com/forums/thread/679446
      serverReader.awaitMessage().apply {
        assertIs<CloseFrame>(this)
        assertEquals(1003, this.code)
        assertEquals("Client Bye", this.reason)
      }
    }
  }

  @Test
  fun serverClose() = test {
    clientReader.awaitOpen()

    serverWriter.enqueueMessage(CloseFrame(1002, "Server Bye"))

    clientReader.awaitMessage().apply {
      assertIs<CloseFrame>(this)
      assertEquals(1002, code)
      assertEquals("Server Bye", reason)
    }
  }
}

private suspend fun Channel<Item>.awaitMessage(): WebSocketMessage = withTimeout(1.seconds) {
  val item = receive()
  check(item.message != null) {
    "Expected message item, received $item"
  }
  item.message
}

private suspend fun Channel<Item>.awaitOpen() = withTimeout(1.seconds) {
  val item = receive()
  check(item.open) {
    "Expected open item, received $item"
  }
}

private fun Channel<Item>.trySend(message: WebSocketMessage) {
  debug("Server: ${message.pretty()}")
  trySend(Item(message = message))
}
