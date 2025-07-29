import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.websocket.WebSocketNetworkTransport
import com.example.StringListUpdatesSubscription
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okio.use

suspend fun main() {
  ApolloClient.Builder()
      .serverUrl("wss://apollo-kotlin-demo.onrender.com/graphql")
      .subscriptionNetworkTransport(
          WebSocketNetworkTransport.Builder()
              .serverUrl("wss://apollo-kotlin-demo.onrender.com/subscriptions")
              .build()
      )
      .build()
      .use { apolloClient ->
        coroutineScope {
          launch {
            apolloClient.subscription(StringListUpdatesSubscription())
                .toFlow()
                .collect {
                  println("Subscription returned: ${it.data?.stringListChanges}")
                }
          }
        }
      }
}

