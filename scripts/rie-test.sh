#!/usr/bin/env bash
# Invokes each sample through the Lambda Runtime Interface Emulator inside the official Lambda base images.
#
# Build the samples first:
#   ./gradlew :native-hello:bootstrapZipLinuxArm64 :native-hello:bootstrapZipLinuxX64 \
#             :jvm-runtime:installDist :jvm-managed:lambdaZip
# Usage: scripts/rie-test.sh [native|jvm-runtime|jvm-managed|all]
# Environment: RIE_PORT (default 9000), RIE_ARCH (arm64 or amd64; defaults to the host, others run under emulation)
set -euo pipefail

cd "$(dirname "$0")/.."
target="${1:-all}"
port="${RIE_PORT:-9000}"
case "${RIE_ARCH:-$(uname -m)}" in
  arm64 | aarch64) arch=arm64 kn=linuxArm64 ;;
  *) arch=amd64 kn=linuxX64 ;;
esac
# Kept under the repository: Docker setups such as colima only share the home directory.
work="build/rie"

v2_event='{"version":"2.0","rawPath":"/","rawQueryString":"","headers":{"host":"x.lambda-url.us-east-1.on.aws"},
"requestContext":{"domainName":"x.lambda-url.us-east-1.on.aws","stage":"$default",
"http":{"method":"GET","sourceIp":"192.0.2.1"}},"isBase64Encoded":false}'
sqs_event='{"Records":[{"eventSource":"aws:sqs","body":"x"}]}'

containers=()
# The ${x[@]+...} form keeps bash 3.2 (macOS) from treating an empty array as unbound under set -u.
cleanup() { for c in ${containers[@]+"${containers[@]}"}; do docker rm -f "$c" >/dev/null 2>&1 || true; done; }
trap cleanup EXIT

# Retries cover the runtime still starting; --retry-max-time bounds a runtime that never comes up.
invoke() {
  curl -sS --max-time 60 --retry 30 --retry-all-errors --retry-delay 1 --retry-max-time 120 -XPOST \
    "http://localhost:$port/2015-03-31/functions/function/invocations" -d "$1"
}

expect() {
  local name="$1" payload="$2" pattern="$3" output
  output="$(invoke "$payload")"
  if grep -q -- "$pattern" <<<"$output"; then
    echo "ok   $name"
  else
    echo "FAIL $name: expected /$pattern/ in $output" >&2
    return 1
  fi
}

# Runs the checks against one container and always removes it.
run() {
  local name="$1" unsupported="$2"
  shift 2
  docker rm -f "ksl-rie-$name" >/dev/null 2>&1 || true
  containers+=("ksl-rie-$name")
  docker run -d --name "ksl-rie-$name" --platform "linux/$arch" -p "$port:8080" \
    -e AWS_LAMBDA_FUNCTION_TIMEOUT=30 "$@" >/dev/null
  local status=0
  {
    expect "$name: v2 event" "$v2_event" '"statusCode":200' &&
      expect "$name: response body" "$v2_event" '"message\\":\\"hello' &&
      expect "$name: unsupported event" "$sqs_event" "$unsupported"
  } || status=$?
  mkdir -p "$work" && docker logs "ksl-rie-$name" >"$work/$name.log" 2>&1
  if [[ $status -ne 0 ]]; then tail -20 "$work/$name.log"; fi
  docker rm -f "ksl-rie-$name" >/dev/null
  return "$status"
}

unpack() {
  rm -rf "${work:?}/$2" && mkdir -p "$work/$2" && unzip -q "$1" -d "$work/$2"
  echo "$PWD/$work/$2"
}

native() {
  local dir
  dir="$(unpack "samples/native-hello/build/lambda/bootstrap-$kn.zip" native)"
  run native Runtime.UnsupportedEvent -v "$dir:/var/runtime:ro" public.ecr.aws/lambda/provided:al2023 function.handler
}

jvm_runtime() {
  docker build -q --platform "linux/$arch" -t ksl-jvm-runtime samples/jvm-runtime >/dev/null
  # Same command as the image's ENTRYPOINT, wrapped by the emulator.
  run jvm-runtime Runtime.UnsupportedEvent --entrypoint /usr/local/bin/aws-lambda-rie ksl-jvm-runtime \
    java -cp "/opt/app/lib/*" MainKt
}

jvm_managed() {
  local dir
  dir="$(unpack samples/jvm-managed/build/lambda/jvm-managed.zip jvm-managed)"
  # The managed runtime reports the exception class as the error type.
  run jvm-managed UnsupportedEventException -v "$dir:/var/task:ro" public.ecr.aws/lambda/java:21 Handler
}

case "$target" in
  native) native ;;
  jvm-runtime) jvm_runtime ;;
  jvm-managed) jvm_managed ;;
  all)
    # One command per line: errexit is ignored inside functions called from an && list.
    native
    jvm_runtime
    jvm_managed
    ;;
  *) echo "unknown target: $target" >&2 && exit 2 ;;
esac
