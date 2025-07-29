package test

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.annotations.ApolloExperimental
import com.apollographql.apollo.exception.ApolloException
import com.apollographql.apollo.network.websocket.WebSocketEngine
import com.apollographql.apollo.network.websocket.WebSocketListener
import com.apollographql.apollo.network.websocket.WebSocketNetworkTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class EchoServerWebSocketEngineTest {

  @OptIn(ApolloExperimental::class)
  @Test
  fun aryamTest() = runTest {

  }
}

private data object Opened

private suspend fun Channel<Any>.receiveOrTimeout(): Any = withTimeout(10.seconds) { receive() }