package com.apollographql.apollo3.network.ws2

import com.apollographql.apollo3.api.http.HttpHeader
import okio.ByteString.Companion.toByteString
import platform.Foundation.NSData
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionWebSocketCloseCode
import platform.Foundation.NSURLSessionWebSocketDelegateProtocol
import platform.Foundation.NSURLSessionWebSocketTask
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.NSObject

class AppleWebSocketEngine(): WebSocketEngine {
  override fun newWebSocket(url: String, headers: List<HttpHeader>, listener: WebSocketListener): WebSocket {
    return AppleWebSocket(url, headers, listener)
  }

}

class AppleWebSocket(
    private val url: String,
    private val headers: List<HttpHeader>,
    private val listener: WebSocketListener,
) : WebSocket, NSObject(), NSURLSessionWebSocketDelegateProtocol {
  private lateinit var nsurlSessionWebSocketTask: NSURLSessionWebSocketTask

  override fun connect() {
    val serverUrl = NSURL(string = url)

    val request = NSMutableURLRequest.requestWithURL(serverUrl).apply {
      headers.forEach { setValue(it.value, forHTTPHeaderField = it.name) }
      setHTTPMethod("GET")
    }

    nsurlSessionWebSocketTask = NSURLSession.sessionWithConfiguration(
        configuration = NSURLSessionConfiguration.defaultSessionConfiguration,
        delegate = this,
        delegateQueue = NSOperationQueue.currentQueue()
    ).webSocketTaskWithRequest(request)
  }

  override fun URLSession(session: NSURLSession, webSocketTask: NSURLSessionWebSocketTask, didOpenWithProtocol: String?) {
    listener.onOpen()
  }

  override fun URLSession(
      session: NSURLSession,
      webSocketTask: NSURLSessionWebSocketTask,
      didCloseWithCode: NSURLSessionWebSocketCloseCode,
      reason: NSData?,
  ) {
    listener.onClosed(didCloseWithCode, reason?.toByteString()?.utf8())
  }

  override fun send(data: ByteArray) {
    check(::nsurlSessionWebSocketTask.isInitialized) {
      "You must call connect() before send()"
    }
  }

  override fun send(text: String) {
    check(::nsurlSessionWebSocketTask.isInitialized) {
      "You must call connect() before send()"
    }
  }

  override fun close(code: Int, reason: String) {
    check(::nsurlSessionWebSocketTask.isInitialized) {
      "You must call connect() before close()"
    }

  }
}

actual fun WebSocketEngine(): WebSocketEngine = AppleWebSocketEngine()
