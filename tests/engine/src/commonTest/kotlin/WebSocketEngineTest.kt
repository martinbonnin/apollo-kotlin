
import com.apollographql.apollo3.api.http.HttpHeader
import com.apollographql.apollo3.exception.ApolloException
import com.apollographql.apollo3.exception.ApolloNetworkException
import com.apollographql.apollo3.exception.ApolloWebSocketClosedException
import com.apollographql.apollo3.mockserver.BinaryMessage
import com.apollographql.apollo3.mockserver.CloseFrame
import com.apollographql.apollo3.mockserver.MockServer
import com.apollographql.apollo3.mockserver.TextMessage
import com.apollographql.apollo3.mockserver.awaitWebSocketRequest
import com.apollographql.apollo3.mockserver.enqueueWebSocket
import com.apollographql.apollo3.mpp.Platform
import com.apollographql.apollo3.mpp.platform
import com.apollographql.apollo3.testing.internal.runTest
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

