## Thoughts for threading and concurrency 


### Threads

**Request threads:** 

In general Apollo Android will run each `ApolloRequest` in a separate thread. What thread it is depends on the provided `ApolloCoroutineDispatcher`. By default it will be `Dispatchers.IO` on the JVM and `Dispatchers.Default` on iOS. This is made because on iOS/Android, the default dispatcher will be `Dispatcher.Main` most of the time, and we don't want to run the requests there. There will be a `ApolloCoroutineDispatcher(null)` to let the `Flow` use the Dispatcher from the current context without forcing a new one for the users that want this behaviour.  

**HTTP threads:**

NSUrlSession and OkHttp both have their own threadpool. We might want to execute the OkHttp calls synchronously to save some Threads creation and context switching.

**SQLite threads:**

We might want to serialize all accesses to the SQLite database. For K/N this would have the nice side effect of containing all mutable state in the same thread, like [Stately](https://github.com/touchlab/Stately) does. For JVM, this can be a elegant way to make writing to the cache asynchronous since the write operation could become a 'fire & forget' thing and all subsequent reads will be naturally serialized. On top of that, there's a slight probability (but I'm still unsure about that) that the Android SQLite implementation has a big lock serializing all accesses so the performance impact might not be that bad. 

### Mutable & shared state

* Store listeners
* Normalized cache 
* HTTP2 connection: OkHttp locks accesses to the HTTP2 connection so that messages are interleaved properly
* ResponseAdapterCache: This currently caches the `ResponseAdapters` so that they don't have to lookup their field `ResponseAdapters`. The fact that this is mutable and that it doesn't work for recursive models encourages to remove that behaviour and look up the custom scalar adapters every time.
* More?

### Implementation notes

On K/N, the `Stately Isolate` pattern seems to be the way to go. See https://dev.to/touchlab/kotlin-native-isolated-state-50l1 for more details. It has a certain cost and doesn't allow ReadWrite locks for an example so we might want to delegate to something else on the JVM:

``` kotlin
interface SharedState<T> {
  fun write(block: (T) -> Unit)
  fun <R> read(block: (T) -> R): R
  fun dispose()
}

class JvmReadWriteSharedState<T>(producer: () -> T): SharedState<T> {
  private val lock = ReentrantReadWriteLock()
  private val state: T = producer()

  override fun write(block: (T) -> Unit) = lock.write {
    block(state)
  }
  override fun <R> read(block: (T) -> R): R = lock.read {
    block(state)
  }
  override fun dispose() {}
}

class JvmSerialSharedState<T>(producer: () -> T): SharedState<T> {
  private val executor = Executors.newSingleThreadExecutor()
  private val state: T = producer()

  override fun write(block: (T) -> Unit){
    executor.submit { block(state) }
  }
  override fun <R> read(block: (T) -> R): R = executor.submit(
    Callable {
      block(state)
    }
  ).get()

  override fun dispose() {
    executor.shutdown()
  }
}

class NativeSerialSharedState<T>(producer: () -> T): SharedState<T> {
  private val isoState = IsolateState(producer())

  // Could be changed to Fire & Forget
  override fun write(block: (T) -> Unit) = isoState.access { block(it) }
  override fun <R> read(block: (T) -> R): R = isoState.access { block(it) }

  override fun dispose() {
    isoState.shutdown()
  }
}
```



### Non-goal: Atomic Cached Requests


Apollo Android has no concept of "Atomic request". Launching the same request twice in a row will most likely end up in the request being sent to the network twice even if the first one will ultimately cache it (but this is not guaranteed either):

```kotlin
val response1 = launch {
    // Since "hero" is not in cache, this will go to the network
    apolloClient.query(HeroQuery()).first()
}
val response2 = launch {
    // This will most likely go to the network even though it's the same request as above
    // If another request is modifying the cache, what is returned depends the timings of the different request
    apolloClient.query(HeroQuery()).first()
}
```

On the other hand, waiting for one query to complete before launching the next one is guaranteed to have a predictable cache state. Especially if asynchronous cache write is implemented, the second query should wait until the write is written by the first one to read the cache:

```kotlin
val response1 = apolloClient.query(HeroQuery()).first()
// If no other request is executing and the first one was cached, response2 will return the cached result
val response2 = apolloClient.query(HeroQuery()).first()
```
