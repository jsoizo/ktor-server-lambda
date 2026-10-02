# Mirrors samples/jvm-runtime/Dockerfile, which no test runs; keep the two in step. The test replaces the entrypoint
# with the emulator wrapping the same command.
FROM public.ecr.aws/lambda/java:21
COPY build/install/fixture-jvm-runtime/lib /opt/app/lib
ENV JAVA_TOOL_OPTIONS="-XX:TieredStopAtLevel=1 -XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-cp", "/opt/app/lib/*", "MainKt"]
