# ktor-server-lambda

A [Ktor](https://ktor.io) server engine for AWS Lambda. It runs your Ktor application once per Lambda invocation,
without opening a socket, on Kotlin/Native (`provided.al2023`) and on the JVM.

> Status: early development (`0.1.0-SNAPSHOT`). APIs may change before 1.0.

## Supported event sources

| Source | Event format |
| --- | --- |
| API Gateway REST API (Lambda proxy integration) | payload 1.0 |
| API Gateway HTTP API | payload 1.0 and 2.0 |
| Lambda Function URL (`BUFFERED`) | payload 2.0 |
| Application Load Balancer | with and without multi-value headers |

Response streaming, VPC Lattice and WebSocket APIs are not supported yet.

## Modules

| Artifact | Platforms | Use it for |
| --- | --- | --- |
| `com.jsoizo:ktor-server-lambda-runtime` | JVM, linuxX64, linuxArm64 | Custom runtimes: Kotlin/Native on `provided.al2023`, or a JVM you ship yourself |
| `com.jsoizo:ktor-server-lambda-handler` | JVM | The managed `java21` / `java25` runtimes, with SnapStart priming |
| `com.jsoizo:ktor-server-lambda` | JVM, linuxX64, linuxArm64 | The engine alone, for driving `handle(event)` from your own loop |
| `com.jsoizo:ktor-server-lambda-events` | JVM, linuxX64, linuxArm64 | Converting Lambda HTTP events to and from a normalized model |

Requires Kotlin 2.3 or later and Ktor 3.6 or later. The JVM artifacts run on Java 17 or later, except
`ktor-server-lambda-handler`, which targets the managed `java21` and `java25` runtimes. Not yet published to Maven
Central; until then, `./gradlew publishToMavenLocal` installs `0.1.0-SNAPSHOT` locally.

## Quick start

### Kotlin/Native or a JVM custom runtime

```kotlin
fun main() = lambdaMain {
    if (isRunningOnLambda()) {
        embeddedServer(AwsLambda) { module() }
    } else {
        embeddedServer(CIO, port = 8080) { module() }
    }
}

fun Application.module() {
    routing { get("/") { call.respondText("hello") } }
}
```

`lambdaMain` reports a failure during module initialization to the Runtime API before exiting. Locally the same
module runs on CIO; use `call.lambdaOrNull` in code that runs on both, since `call.lambda` throws off Lambda.

For Kotlin/Native, name the executable `bootstrap` and link `libcrypt` statically, because the `provided.al2023`
environment has no `libcrypt.so.1`:

```kotlin
kotlin {
    linuxArm64 {
        binaries.executable {
            baseName = "bootstrap"
            linkerOpts("--as-needed", "-Bstatic", "-lcrypt", "-Bdynamic")
        }
    }
}
```

### Managed Java runtime

```kotlin
class Handler : KtorRequestStreamHandler({ module() }) {
    // Optional: requests sent through the pipeline before a SnapStart snapshot.
    override val primingRequests get() = listOf(PrimingRequest.get("/health"))
}
```

Set `Handler` as the function handler on `java21` or `java25`.

## Configuration

```kotlin
embeddedServer(AwsLambda, configure = {
    stripStage = true          // drop the stage segment from HTTP API paths on execute-api hosts
    stripBasePath = "/api"     // drop a custom domain base path
    concurrency = null         // workers; defaults to AWS_LAMBDA_MAX_CONCURRENCY or 1
    errorMode = ErrorMode.HttpResponse  // or LambdaError to report exceptions as Lambda errors
}) { module() }
```

Inside a route, `call.lambda` exposes the invocation (request id, deadline, trace id), the event source, the
`requestContext` (authorizer claims and the like) and the raw event.

## Things to know about Lambda

- **Error messages**: like every Ktor engine, an unhandled exception becomes a 500 whose body is the exception
  message. Install [StatusPages](https://ktor.io/docs/server-status-pages.html) to control what clients see. With
  `ErrorMode.LambdaError`, the message and stack trace go to the Lambda error report and logs instead.
- **Failures after the headers are sent** (an exception inside `respondBytesWriter`, a wrong `Content-Length`) are
  raised as invocation errors in every mode, where a socket-based engine would drop the connection.

- **Binary responses on REST APIs** need `binaryMediaTypes` on the API (for example `*/*`); otherwise clients
  receive the base64 text.
- **ALB** can only carry one value per header unless the target group enables
  `lambda.multi_value_headers.enabled`; without it only the last `Set-Cookie` is sent and a warning is logged.
- **Client address on ALB** comes from the last `X-Forwarded-For` entry. With the ALB's XFF mode set to `preserve`,
  clients control that value.
- **Background work**: coroutines launched with `call.launch` are cancelled once the response is built, because
  Lambda may freeze the environment right after it is sent.

## Samples and tests

| Sample | Shows |
| --- | --- |
| [`samples/native-hello`](samples/native-hello) | Kotlin/Native on `provided.al2023` (`./gradlew :native-hello:bootstrapZipLinuxArm64`) |
| [`samples/jvm-runtime`](samples/jvm-runtime) | JVM custom runtime as a container image |
| [`samples/jvm-managed`](samples/jvm-managed) | Managed `java21` runtime (`./gradlew :jvm-managed:lambdaZip`) |

`./gradlew check` runs unit tests, ktlint, detekt, ABI checks and verifies that native binaries only need libraries
present on `provided.al2023`. `./gradlew :integration-test:integrationTest` builds every sample and runs it in the
official Lambda base images with the Runtime Interface Emulator, through Testcontainers (Docker required).

## License

[MIT](LICENSE)
