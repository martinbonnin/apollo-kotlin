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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen

class WebSocketNetworkTransport(
    private val serverUrl: (suspend () -> String),
    private val headers: List<HttpHeader>,
    private val webSocketEngine: WebSocketEngine = WebSocketEngine(),
    private val idleTimeoutMillis: Long = 60_000,
    private val reopenWhen: (suspend (Throwable, attempt: Long) -> Boolean) = { _, _ -> false },
    private val connectionParams: (suspend () -> Any?) = { null },
) : NetworkTransport {

  private val isConnectedPrivate = MutableStateFlow(false)
  private var attempt = 0L
  private val lock = reentrantLock()
  private var subscribableWebSocket: SubscribableWebSocket? = null

  @ApolloExperimental
  val isConnected = isConnectedPrivate.asStateFlow()

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
              url = serverUrl,
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

      invokeOnClose {
        reg.stop()
      }
    }

    return flow.buffer(Channel.UNLIMITED).retryWhen { cause, _ ->
      cause is RetrySubscription
    }
  }

  override fun dispose() {

  }

  class RetrySubscription(throwable: Throwable) : Exception("The subscription should retry", throwable)
}

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
    producerScope.trySend(ApolloResponse.Builder(request.operation, request.requestUuid, DefaultApolloException("Invalid payload", throwable)).build())
    producerScope.channel.close()
  }

  override fun onComplete() {
    producerScope.channel.close()
  }

  override fun onRetry(throwable: Throwable) {
    producerScope.channel.close(WebSocketNetworkTransport.RetrySubscription(throwable))
  }
}