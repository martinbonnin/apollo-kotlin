package com.apollographql.apollo3.network.ws

import com.apollographql.apollo3.api.http.HttpHeader
import com.apollographql.apollo3.exception.ApolloNetworkException
import com.apollographql.apollo3.exception.ApolloWebSocketClosedException
import com.apollographql.apollo3.network.toNSData
import kotlinx.cinterop.convert
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import okio.ByteString
import okio.ByteString.Companion.toByteString
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSString
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionWebSocketCloseCode
import platform.Foundation.NSURLSessionWebSocketDelegateProtocol
import platform.Foundation.NSURLSessionWebSocketMessage
import platform.Foundation.NSURLSessionWebSocketMessageTypeData
import platform.Foundation.NSURLSessionWebSocketMessageTypeString
import platform.Foundation.NSURLSessionWebSocketTask
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.NSObject

actual class DefaultWebSocketEngine : WebSocketEngine {

  private fun newWebSocketTask(request: NSURLRequest, delegate: NSURLSessionWebSocketDelegateProtocol): NSURLSessionWebSocketTask {
    val delegateQueue = if (NSThread.isMainThread) {
      runBlocking(Dispatchers.Default) { NSOperationQueue.currentQueue() }
    } else {
      NSOperationQueue.currentQueue()
    }

    return NSURLSession.sessionWithConfiguration(
        configuration = NSURLSessionConfiguration.defaultSessionConfiguration,
        delegate = delegate,
        delegateQueue = delegateQueue
    ).webSocketTaskWithRequest(request)
  }

  actual override suspend fun open(
      url: String,
      headers: List<HttpHeader>,
  ): WebSocketConnection {

    val serverUrl = NSURL(string = url)

    val request = NSMutableURLRequest.requestWithURL(serverUrl).apply {
      headers.forEach { setValue(it.value, forHTTPHeaderField = it.name) }
      setHTTPMethod("GET")
    }

    val messageChannel = Channel<String>(Channel.UNLIMITED)
    val isOpen = CompletableDeferred<Boolean>()

    val delegate = object : NSObject(), NSURLSessionWebSocketDelegateProtocol {
      override fun URLSession(session: NSURLSession, webSocketTask: NSURLSessionWebSocketTask, didOpenWithProtocol: String?) {
        if (!isOpen.complete(true)) {
          webSocketTask.cancel()
        }
      }

      override fun URLSession(
          session: NSURLSession,
          webSocketTask: NSURLSessionWebSocketTask,
          didCloseWithCode: NSURLSessionWebSocketCloseCode,
          reason: NSData?,
      ) {
        isOpen.cancel()
        messageChannel.close()
      }
    }

    val webSocketTask = newWebSocketTask(request, delegate )
        .apply {
          resume()
        }

    try {
      isOpen.await()
      return WebSocketConnectionImpl(
          webSocket = webSocketTask,
          messageChannel = messageChannel
      )
    } catch (e: Exception) {
      webSocketTask.cancel()
      throw e
    }
  }
}

private class WebSocketConnectionImpl(
    val webSocket: NSURLSessionWebSocketTask,
    val messageChannel: Channel<String>,
) : WebSocketConnection {
  init {
    messageChannel.invokeOnClose {
    }
    receiveNext()
  }

  override suspend fun receive(): String {
    return messageChannel.receive()
  }

  override fun send(data: ByteString) {
    @OptIn(DelicateCoroutinesApi::class)
    if (!messageChannel.isClosedForSend) {
      val message = NSURLSessionWebSocketMessage(data.toByteArray().toNSData())
      val completionHandler = { error: NSError? ->
        if (error != null) handleError(error)
      }
      webSocket.sendMessage(message, completionHandler)
    }
  }

  override fun send(string: String) {
    @OptIn(DelicateCoroutinesApi::class)
    if (!messageChannel.isClosedForSend) {
      val message = NSURLSessionWebSocketMessage(string)
      val completionHandler = { error: NSError? ->
        if (error != null) handleError(error)
      }
      webSocket.sendMessage(message, completionHandler)
    }
  }

  override fun close() {
    println("NSURLSessionWebSocketEngine::close")
    webSocket.cancelWithCloseCode(
        closeCode = 1001,
        reason = "Oopsie5".encodeToByteArray().toNSData()
    )
  }

  fun receiveNext() {
    val completionHandler = { message: NSURLSessionWebSocketMessage?, error: NSError? ->
      if (error != null) {
        handleError(error)
      } else if (message != null) {
        requestNext(message)
      }
    }
    webSocket.receiveMessageWithCompletionHandler(completionHandler)
  }

  private fun handleError(error: NSError) {
    messageChannel.close(
        if (webSocket.closeCode.convert<Int>() != 0) ApolloWebSocketClosedException(
            code = webSocket.closeCode.convert(),
            reason = webSocket.closeReason?.toKotlinString(),
        ) else {
          ApolloNetworkException(
              message = "Web socket communication error",
              platformCause = error
          )
        }
    )
    webSocket.cancel()
  }

  private fun requestNext(webSocketMessage: NSURLSessionWebSocketMessage) {
    val data = when (webSocketMessage.type) {
      NSURLSessionWebSocketMessageTypeData -> {
        webSocketMessage.data?.toByteString()?.utf8()
      }

      NSURLSessionWebSocketMessageTypeString -> {
        webSocketMessage.string
      }

      else -> null
    }

    try {
      if (data != null) messageChannel.trySend(data)
    } catch (e: Exception) {
      webSocket.cancel()
      return
    }

    receiveNext()
  }

}

private fun NSData.toKotlinString() = NSString.create(this, NSUTF8StringEncoding) as String?
