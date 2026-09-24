# ridl-rt-kt-coroutines

## Responsibility

This module is the `suspend` adapter over the polling face (docs/design.md §5,
D-K6): one function, `await(port: Wakeable, poll: () -> T?): T`, which suspends
until a polling read of a face answers, woken by the runtime's `Wakeable`
extension of [`ridl-rt-kt`](../ridl-rt-kt/README.md). It is a JVM library over
`kotlinx-coroutines-core`. The repository is licensed under the root
[MIT License](../../LICENSE).

```kotlin
val correlation = client.average(Window.of(10))
val reply = await(port) { client.averageReply(correlation) }
```

`poll` is called once, then again after every change the port reports, until it
answers something other than `null`. The wake-up is registered before the second
poll, so an outcome that lands between the two is not lost; the registration is
closed when `await` returns, throws or is cancelled. A wait is bounded with
`withTimeout`, as nothing in a port times out.

## Status

Stage K5. `AwaitTest` pins the five properties above, over a hand-driven
`Wakeable`; moving the second poll after the first wait turns it red. The cabin
sample's `CoroutineDemo.kt` runs a consumer and a provider on two threads over
the loopback with it.

## O-K3: no `*Await` extension, generated or hand-written

docs/design.md §5 has two generated one-line extensions per call,
`suspend fun <Iface>Client<P>.averageAwait(window)`, and O-K3 leaves open
whether they are generated or written by hand in the sample, to be decided by
writing the sample both ways and keeping the shorter. Measured on the cabin
sample's two calls:

| Form                                    | Lines at the call sites | Lines written beside them                                                                                                |
| --------------------------------------- | ----------------------: | ------------------------------------------------------------------------------------------------------------------------ |
| `await(port) { client.<call>Reply(c) }` |                       4 | none                                                                                                                     |
| hand-written `<call>Await` extensions   |                       2 | about 4 per call, each taking the port the client keeps private                                                          |
| generated `<call>Await` extensions      |                       2 | none, but every generated package then depends on `kotlinx-coroutines`, or a third plugin option decides whether it does |

The recommendation, for disposition, is the first: the generic `await` is the
whole adapter, the generated face stays free of any coroutine dependency, and a
call's suspending form is its send and one `await`.
