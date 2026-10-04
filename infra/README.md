# infra

CDK stacks that deploy a sample behind every supported event source.

| Stack | Event sources |
| --- | --- |
| `KtorLambdaHttp<Variant>` | API Gateway REST API (stage `prod`), HTTP APIs with payload 2.0 and 1.0 (`$default` and `v1` stages), Lambda Function URL |
| `KtorLambdaAlb<Variant>` | ALB, single-value headers on port 80 and multi-value headers on port 8080 |

`<Variant>` is `Native` or `JvmManaged`, picked with `-c variant=native|jvm-managed` (default `native`).

**Costs and exposure.** Every endpoint is public and unauthenticated. The API Gateway stages are throttled to 10
requests per second, but the Function URL and the ALB are not: anyone who finds them can invoke the function as often
as Lambda allows, using up the account's concurrency and throttling unrelated functions in the same region. Pass
`-c reservedConcurrency=5` to cap the function; it is off by default because reserving concurrency fails on new
accounts, whose limit is 10. The `/echo` route returns only the path and query fields of the event, never headers,
cookies or authorizer data. The ALB stack bills hourly for the load balancer and its two
public IPv4 addresses. Destroy the stacks when you are done.

## Deploy to AWS

Build the sample first, from the repository root:

```sh
./gradlew :native-hello:bootstrapZipLinuxArm64   # variant=native
./gradlew :jvm-managed:lambdaZip                 # variant=jvm-managed
./gradlew :jvm-managed:lambdaZip -PusePublishedSnapshot --refresh-dependencies # published snapshot instead of local projects
```

Then, in this directory:

```sh
npm ci
npx cdk bootstrap -c variant=jvm-managed               # once per account and region; variant must have a built ZIP
npx cdk deploy KtorLambdaHttpNative
npx cdk deploy KtorLambdaAlbNative                     # optional, billed while it exists
npx cdk deploy KtorLambdaHttpJvmManaged -c variant=jvm-managed

npx cdk destroy KtorLambdaAlbNative KtorLambdaHttpNative
npx cdk destroy KtorLambdaHttpJvmManaged -c variant=jvm-managed
```

For a snapshot smoke test without the hourly ALB cost, deploy only `KtorLambdaHttpJvmManaged` with
`-c variant=jvm-managed`. Check each output URL with `curl -i "$URL"` (expect `{"message":"hello",...}`)
and `curl -i "${URL}echo/test?q=hello%20world"` (expect `segments: ["test"]` and query value
`"hello world"`). Check the published Lambda version, not `$LATEST`, for SnapStart:

```sh
aws lambda get-function-configuration --function-name FUNCTION_NAME --qualifier VERSION \
  --query 'SnapStart'
```

The verified AWS deployment on October 4, 2026 used the published `0.1.0-SNAPSHOT` JVM artifacts. Its resources
remain deployed for inspection; their account-specific identifiers and public URLs are in the local,
git-ignored `../.work/deployment-resources.md`. Public endpoints can incur charges until the stack is destroyed.

## Check how event sources encode requests

Every sample has a diagnostic route that returns the raw event next to the path and query Ktor saw:

```sh
curl "$REST_API_URL/echo/a%20b/100%25/a%2Fb?q=a+b"
curl "$HTTP_API_PAYLOAD10_STAGE_URL/echo/x"
```

Use it to check whether REST APIs pass `path` decoded, how `+` in a
query arrives, and whether HTTP API payload 1.0 puts the stage in `path`.
