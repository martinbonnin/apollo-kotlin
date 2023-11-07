package com.apollographql.apollo3.network.ws2

import com.apollographql.apollo3.annotations.ApolloExperimental
import com.apollographql.apollo3.api.ApolloRequest
import com.apollographql.apollo3.api.ApolloResponse
import com.apollographql.apollo3.api.CustomScalarAdapters
import com.apollographql.apollo3.api.Operation
import com.apollographql.apollo3.api.http.HttpHeader
import com.apollographql.apollo3.api.json.jsonReader
import com.apollographql.apollo3.api.toApolloResponse
import com.apollographql.apollo3.exception.DefaultApolloException
import com.apollographql.apollo3.internal.DeferredJsonMerger
import com.apollographql.apollo3.internal.isDeferred
import com.apollographql.apollo3.network.NetworkTransport
import com.benasher44.uuid.uuid4
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen

class WebSocketNetworkTransport private constructor(
    private val serverUrl: (suspend () -> String),
    private val headers: List<HttpHeader>,
    private val webSocketEngine: WebSocketEngine,
    private val idleTimeoutMillis: Long,
    private val reopenWhen: (suspend (Throwable, attempt: Long) -> Boolean),
    private val connectionParams: (suspend () -> Any?),
) : NetworkTransport {

  private val isConnectedPrivate = MutableStateFlow(false)
  private var attempt = 0L
  private val lock = reentrantLock()
  private var subscribableWebSocket: SubscribableWebSocket? = null

  @ApolloExperimental
  val isConnected = isConnectedPrivate.asStateFlow()

  val subscriptionCount = MutableStateFlow(0)

  private fun onWebSocketInitialized() {
    isConnectedPrivate.value = true
    attempt = 0
  }

  private fun onWebSocketDisposed() {
    lock.withLock {
      subscribableWebSocket = null
    }
  }

  private fun onWebSocketDisconnected() {
    isConnectedPrivate.value = false
  }

  private suspend fun reopenWebSocketWhen(throwable: Throwable): Boolean {
    return reopenWhen.invoke(throwable, attempt).also { attempt++ }
  }

  override fun <D : Operation.Data> execute(
      request: ApolloRequest<D>,
  ): Flow<ApolloResponse<D>> {

    var renewUuid = false

    val flow = callbackFlow {


      val newRequest = if (renewUuid) {
        request.newBuilder().requestUuid(uuid4()).build()
      } else {
        request
      }
      renewUuid = true

      val operationListener = DefaultOperationListener(newRequest, this)
      val reg = lock.withLock {
        if (subscribableWebSocket == null) {
          subscribableWebSocket = SubscribableWebSocket(
              webSocketEngine = webSocketEngine,
              url = serverUrl(),
              headers = headers,
              idleTimeoutMillis = idleTimeoutMillis,
              onInitialized = ::onWebSocketInitialized,
              onDisposed = ::onWebSocketDisposed,
              onDisconnected = ::onWebSocketDisconnected,
              dispatcher = Dispatchers.Default,
              connectionParams = connectionParams,
              reopenWhen = ::reopenWebSocketWhen,
          )
        }
        subscribableWebSocket!!.startOperation(newRequest, operationListener)
      }

      awaitClose {
        reg.stop()
      }
    }

    return flow.buffer(Channel.UNLIMITED).retryWhen { cause, _ ->
      cause is RetryException
    }
  }

  override fun dispose() {

  }

  class Builder {
    private var serverUrl: (suspend () -> String)? = null
    private var headers: List<HttpHeader>? = null
    private var webSocketEngine: WebSocketEngine? = null
    private var idleTimeoutMillis: Long? = null
    private var reopenWhen: (suspend (Throwable, attempt: Long) -> Boolean)? = null
    private var connectionParams: (suspend () -> Any?)? = null

    fun serverUrl(serverUrl: suspend () -> String) = apply {
      this.serverUrl = serverUrl
    }
    fun serverUrl(serverUrl: String) = apply {
      this.serverUrl = {serverUrl}
    }

    fun headers(headers: List<HttpHeader>) = apply {
      this.headers = headers
    }

    fun webSocketEngine(webSocketEngine: WebSocketEngine) = apply {
      this.webSocketEngine = webSocketEngine
    }

    fun idleTimeoutMillis(idleTimeoutMillis: Long) = apply {
      this.idleTimeoutMillis = idleTimeoutMillis
    }

    fun reopenWhen(reopenWhen: suspend (Throwable, attempt: Long) -> Boolean) = apply {
      this.reopenWhen = reopenWhen
    }

    fun connectionParams(connectionParams: suspend () -> Any?) = apply {
      this.connectionParams = connectionParams
    }

    fun build() = WebSocketNetworkTransport(
        serverUrl = serverUrl ?: error("You need to set serverUrl"),
        headers = headers ?: emptyList(),
        webSocketEngine = webSocketEngine ?: WebSocketEngine(),
        idleTimeoutMillis = idleTimeoutMillis ?: 60_000,
        reopenWhen = reopenWhen ?: { _, _ -> false },
        connectionParams = connectionParams ?: { null }
    )
  }
}

private class RetryException(throwable: Throwable) : Exception("The subscription should retry", throwable)

private class DefaultOperationListener<D : Operation.Data>(
    private val request: ApolloRequest<D>,
    private val producerScope: ProducerScope<ApolloResponse<D>>,
) : OperationListener {
  val deferredJsonMerger = DeferredJsonMerger()
  val requestCustomScalarAdapters = request.executionContext[CustomScalarAdapters]!!

  override fun onResponse(response: Any?) {
    @Suppress("UNCHECKED_CAST")
    val responseMap = response as? Map<String, Any?>
    if (responseMap == null) {
      producerScope.trySend(ApolloResponse.Builder(request.operation, request.requestUuid, DefaultApolloException("Invalid payload")).build())
      return
    }
    val (payload, mergedFragmentIds) = if (responseMap.isDeferred()) {
      deferredJsonMerger.merge(responseMap) to deferredJsonMerger.mergedFragmentIds
    } else {
      responseMap to null
    }
    val apolloResponse: ApolloResponse<D> = payload.jsonReader().toApolloResponse(
        operation = request.operation,
        requestUuid = request.requestUuid,
        customScalarAdapters = requestCustomScalarAdapters,
        deferredFragmentIdentifiers = mergedFragmentIds
    )

    if (!deferredJsonMerger.hasNext) {
      // Last deferred payload: reset the deferredJsonMerger for potential subsequent responses
      deferredJsonMerger.reset()
    }

    producerScope.trySend(apolloResponse)
  }

  override fun onError(throwable: Throwable) {
    producerScope.trySend(ApolloResponse.Builder(request.operation, request.requestUuid, DefaultApolloException("Error while executing operation", throwable)).build())
    producerScope.channel.close()
  }

  override fun onComplete() {
    producerScope.channel.close()
  }

  override fun onRetry(throwable: Throwable) {
    producerScope.channel.close(RetryException(throwable))
  }
}