package com.apollographql.apollo3.network.ws2

import com.apollographql.apollo3.api.ApolloRequest
import com.apollographql.apollo3.api.Operation
import com.apollographql.apollo3.api.http.HttpHeader
import com.apollographql.apollo3.exception.ApolloWebSocketClosedException
import com.apollographql.apollo3.exception.DefaultApolloException
import com.apollographql.apollo3.network.ws2.internal.Complete
import com.apollographql.apollo3.network.ws2.internal.ConnectionAck
import com.apollographql.apollo3.network.ws2.internal.ConnectionError
import com.apollographql.apollo3.network.ws2.internal.ConnectionKeepAlive
import com.apollographql.apollo3.network.ws2.internal.Data
import com.apollographql.apollo3.network.ws2.internal.Error
import com.apollographql.apollo3.network.ws2.internal.ParseError
import com.apollographql.apollo3.network.ws2.internal.apolloConnectionInitMessage
import com.apollographql.apollo3.network.ws2.internal.toServerMessage
import com.apollographql.apollo3.network.ws2.internal.toStartMessage
import com.apollographql.apollo3.network.ws2.internal.toStopMessage
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
   * The operation terminated successfully
   */
  fun onComplete()

  /**
   * The operation cannot be executed or failed.
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
    private val onInitialized: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onDisposed: () -> Unit,
    private val dispatcher: CoroutineDispatcher,
    private val connectionParams: suspend () -> Any?,
    private val reopenWhen: suspend (Throwable) -> Boolean,
) : WebSocketListener {
  // webSocket is thread safe, no need to lock
  private var webSocket: WebSocket = webSocketEngine.newWebSocket(url, headers, this@SubscribableWebSocket)

  // locked fields, these fields may be accessed from different threads and require locking
  private val lock = reentrantLock()
  private var timeoutJob: Job? = null
  private var state: State = State.Initial
  private var activeListeners = mutableMapOf<String, OperationListener>()
  private var idleJob: Job? = null
  // end of locked fields

  init {
    webSocket.connect()
  }

  private suspend fun disconnect(throwable: Throwable) {
    var listeners: Collection<OperationListener>
    lock.withLock {
      if (state != State.Disconnected) {
        state = State.Disconnected
        listeners = activeListeners.values.toList()
      } else {
        return
      }
    }
    onDisconnected()

    val reopen = reopenWhen.invoke(throwable)

    onDisposed()
    listeners = lock.withLock {
      activeListeners.values.toList()
    }

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
            webSocket.send(apolloConnectionInitMessage(connectionParams()))
          }
          timeoutJob = GlobalScope.launch(dispatcher) {
            delay(10_000)
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
    when (val message = text.toServerMessage()) {
      ConnectionAck -> {
        timeoutJob?.cancel()
        timeoutJob = null
        onInitialized()
        lock.withLock {
          state = State.Connected
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

      is Data -> {
        lock.withLock {
          activeListeners.get(message.id)
        }?.onResponse(message.payload)
      }

      is Complete -> {
        lock.withLock {
          activeListeners.get(message.id)
        }?.onComplete()
      }
      is Error -> {
        lock.withLock {
          activeListeners.get(message.id)
        }?.onError(DefaultApolloException("Server send an error ${message.payload}"))
      }
      is ParseError -> {
        // This is an unknown or malformed message
        // It's not 100% clear what we should do here. Should we terminate the operation?
        println("Cannot parse message: '${message.message}'")
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
    val result = lock.withLock {
      idleJob?.cancel()
      idleJob = null

      if (state == State.Disconnected) {
        // We have disconnected this socket
        AddResult.Disconnected
      } else {
        if (activeListeners.containsKey(request.requestUuid.toString())) {
          AddResult.AlreadyExists
        } else {
          activeListeners.put(request.requestUuid.toString(), listener)
          AddResult.Added
        }
      }
    }

    return when (result) {
      AddResult.Added -> {
        webSocket.send(request.toStartMessage())
        object : StartedOperation {
          override fun stop() {
            lock.withLock {
              val id = request.requestUuid.toString()
              if (activeListeners.remove(id) != null) {
                webSocket.send(request.toStopMessage())
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

      AddResult.Disconnected -> {
        listener.onRetry(DefaultApolloException("The WebSocket was already disconnected"))
        StartedOperationNoOp
      }
      AddResult.AlreadyExists -> {
        listener.onError(DefaultApolloException("There is already a subscription with that id"))
        StartedOperationNoOp
      }
    }
  }
}

private enum class AddResult {
  Disconnected,
  AlreadyExists,
  Added
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