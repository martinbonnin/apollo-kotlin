package com.apollographql.apollo3.network.ws2

import com.apollographql.apollo3.api.ApolloRequest
import com.apollographql.apollo3.api.Operation
import com.apollographql.apollo3.api.http.HttpHeader
import com.apollographql.apollo3.exception.ApolloWebSocketClosedException
import com.apollographql.apollo3.exception.DefaultApolloException
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


interface StartedOperation {
  /**
   * Sends a message to the server to stop the operation and removes the listener.
   * No further call to the listener are made
   */
  fun stop()
}

interface OperationListener {
  /**
   * A response was received
   *
   * [response] is the Kotlin representation of a GraphQL response.
   *
   * ```kotlin
   * mapOf(
   *   "data" to ...
   *   "errors" to listOf(...)
   * )
   * ```
   */
  fun onResponse(response: Any?)

  /**
   * The operation terminated successfully. No calls to listener will be made
   */
  fun onComplete()

  /**
   * The operation cannot be executed or failed. No calls to listener will be made
   */
  fun onError(throwable: Throwable)

  /**
   * A network error happened and this operation should be restarted
   */
  fun onRetry(throwable: Throwable)
}

@OptIn(DelicateCoroutinesApi::class)
class SubscribableWebSocket(
    webSocketEngine: WebSocketEngine,
    url: String,
    headers: List<HttpHeader>,
    private val idleTimeoutMillis: Long,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onDisposed: () -> Unit,
    private val dispatcher: CoroutineDispatcher,
    private val wsProtocol: WsProtocol,
    private val reopenWhen: suspend (Throwable) -> Boolean,
    private val pingIntervalMillis: Long,
    private val connectionAcknowledgeTimeoutMillis: Long,
) : WebSocketListener {

  // webSocket is thread safe, no need to lock
  private var webSocket: WebSocket = webSocketEngine.newWebSocket(url, headers, this@SubscribableWebSocket)

  // locked fields, these fields may be accessed from different threads and require locking
  private val lock = reentrantLock()
  private var timeoutJob: Job? = null
  private var state: State = State.Initial
  private var activeListeners = mutableMapOf<String, OperationListener>()
  private var idleJob: Job? = null
  private var pingJob: Job? = null
  // end of locked fields

  fun connect() {
    webSocket.connect()
  }

  private suspend fun disconnect(throwable: Throwable) {
    // Tell upstream that this socket is disconnected
    // No new listener are added after this
    onDisconnected()

    val listeners = lock.withLock {
      pingJob?.cancel()
      pingJob = null
      if (state != State.Disconnected) {
        state = State.Disconnected
        activeListeners.values.toList()
      } else {
        return
      }
    }

    val reopen = if (listeners.isNotEmpty()) {
      reopenWhen.invoke(throwable)
    } else {
      false
    }

    onDisposed()

    listeners.forEach {
      if (reopen) {
        it.onRetry(throwable)
      } else {
        it.onError(throwable)
      }
    }
  }

  override fun onOpen() {
    lock.withLock {
      when (state) {
        State.Initial -> {
          GlobalScope.launch(dispatcher) {
            webSocket.send(wsProtocol.connectionInit())
          }
          timeoutJob = GlobalScope.launch(dispatcher) {
            delay(connectionAcknowledgeTimeoutMillis)
            webSocket.close(CLOSE_GOING_AWAY, "Timeout while waiting for connection_ack")
            disconnect(DefaultApolloException("Timeout while waiting for ack"))
          }
          state = State.AwaitAck
        }

        else -> {
          // spurious "open" event
        }
      }
    }
  }

  override fun onMessage(text: String) {
    when (val message = wsProtocol.parseServerMessage(text)) {
      ConnectionAck -> {
        timeoutJob?.cancel()
        timeoutJob = null
        onConnected()
        lock.withLock {
          state = State.Connected
          if (pingIntervalMillis > 0) {
            pingJob = GlobalScope.launch {
              while (true) {
                delay(pingIntervalMillis)
                wsProtocol.ping()?.let { webSocket.send(it) }
              }
            }
          }
        }
      }

      is ConnectionError -> {
        GlobalScope.launch {
          webSocket.close(CLOSE_GOING_AWAY, "Connection Error")
          disconnect(DefaultApolloException("Received connection_error"))
        }
      }

      ConnectionKeepAlive -> {
        // nothing so far?
      }

      is Response -> {
        lock.withLock {
          activeListeners.get(message.id)
        }?.let {
          it.onResponse(message.response)
          if (message.complete) {
            it.onComplete()
          }
        }
      }

      is Complete -> {
        lock.withLock {
          activeListeners.get(message.id)
        }?.onComplete()
      }

      is OperationError -> {
        lock.withLock {
          activeListeners.get(message.id)
        }?.onError(DefaultApolloException("Server send an error ${message.payload}"))
      }

      is ParseError -> {
        // This is an unknown or malformed message
        // It's not 100% clear what we should do here. Should we terminate the operation?
        println("Cannot parse message: '${message.errorMessage}'")
      }

      Ping -> {
        GlobalScope.launch {
          val pong = wsProtocol.pong()
          if (pong != null) {
            webSocket.send(pong)
          }
        }
      }

      Pong -> {
        // nothing to do
      }
    }
  }

  override fun onMessage(data: ByteArray) {
    onMessage(data.decodeToString())
  }

  override fun onError(throwable: Throwable) {
    GlobalScope.launch(dispatcher) {
      disconnect(throwable)
    }
  }

  override fun onClosed(code: Int?, reason: String?) {
    GlobalScope.launch(dispatcher) {
      disconnect(ApolloWebSocketClosedException(code ?: CLOSE_NORMAL, reason))
    }
  }

  fun <D : Operation.Data> startOperation(request: ApolloRequest<D>, listener: OperationListener): StartedOperation {
    val added = lock.withLock {
      idleJob?.cancel()
      idleJob = null

      if (activeListeners.containsKey(request.requestUuid.toString())) {
        false
      } else {
        activeListeners.put(request.requestUuid.toString(), listener)
        true
      }
    }

    if (!added) {
      listener.onError(DefaultApolloException("There is already a subscription with that id"))
      return StartedOperationNoOp
    }

    GlobalScope.launch { webSocket.send(wsProtocol.operationStart(request)) }

    return DefaultStartedOperation(request)
  }

  fun closeConnection(throwable: Throwable) {
    GlobalScope.launch { disconnect(throwable) }
  }

  inner class DefaultStartedOperation<D: Operation.Data>(val request: ApolloRequest<D>): StartedOperation {
    override fun stop() {
      lock.withLock {
        val id = request.requestUuid.toString()
        if (activeListeners.remove(id) != null) {
          GlobalScope.launch { webSocket.send(wsProtocol.operationStop(request)) }
        }

        if (activeListeners.isEmpty()) {
          idleJob?.cancel()
          idleJob = GlobalScope.launch {
            delay(idleTimeoutMillis)
            disconnect(DefaultApolloException("WebSocket is idle"))
          }
        }
      }
    }
  }
}

private enum class State {
  Initial,
  AwaitAck,
  Connected,
  Disconnected

}

private val StartedOperationNoOp = object : StartedOperation {
  override fun stop() {}
}

private fun WebSocket.send(clientMessage: ClientMessage) {
  when (clientMessage) {
    is TextClientMessage -> send(clientMessage.text)
    is DataClientMessage -> send(clientMessage.data)
  }
}