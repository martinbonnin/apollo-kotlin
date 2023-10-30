
import com.apollographql.apollo3.mockserver.MockServer
import com.apollographql.apollo3.mockserver.awaitWebSocketRequest
import com.apollographql.apollo3.mockserver.enqueueWebSocket
import com.apollographql.apollo3.network.toNSData
import com.apollographql.apollo3.testing.internal.runTest
import kotlinx.cinterop.convert
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import platform.CoreFoundation.CFRunLoopRun
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionWebSocketMessage
import platform.posix.sleep
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
