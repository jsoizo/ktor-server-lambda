# The application is its own Runtime API client, so any image with a JVM works; this one also ships the
# Runtime Interface Emulator for local testing (see scripts/rie-test.sh).
FROM public.ecr.aws/lambda/java:21
COPY build/install/jvm-runtime/lib /opt/app/lib
# TieredStopAtLevel=1 favours cold start over the peak throughput C2 gives long-lived, CPU-bound environments.
# Unlike the managed runtime, a JVM started by hand sizes its heap from MaxRAMPercentage (25% by default).
ENV JAVA_TOOL_OPTIONS="-XX:TieredStopAtLevel=1 -XX:MaxRAMPercentage=75"
# Gradle's start script needs xargs, which the Lambda base image lacks, so start the JVM directly.
ENTRYPOINT ["java", "-cp", "/opt/app/lib/*", "MainKt"]
