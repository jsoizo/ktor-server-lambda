import * as path from "node:path";
import { Duration, RemovalPolicy } from "aws-cdk-lib";
import * as lambda from "aws-cdk-lib/aws-lambda";
import * as logs from "aws-cdk-lib/aws-logs";
import { Construct } from "constructs";

/** Which sample the stacks deploy; chosen with `cdk deploy -c variant=...`. */
export type Variant = "native" | "jvm-runtime" | "jvm-managed";

export const variants: readonly Variant[] = ["native", "jvm-runtime", "jvm-managed"];

const samples = path.join(__dirname, "..", "..", "samples");

/**
 * The sample application as a Lambda function, built beforehand with Gradle (see infra/README.md).
 * For jvm-managed it returns an alias, because SnapStart only applies to invocations of a published version.
 */
export function sampleFunction(
  scope: Construct,
  id: string,
  variant: Variant,
  reservedConcurrentExecutions?: number,
): lambda.IFunction {
  const logGroup = new logs.LogGroup(scope, `${id}Logs`, {
    retention: logs.RetentionDays.ONE_WEEK,
    removalPolicy: RemovalPolicy.DESTROY,
  });
  const common = {
    architecture: lambda.Architecture.ARM_64,
    memorySize: 512,
    timeout: Duration.seconds(10),
    logGroup,
    reservedConcurrentExecutions,
  };
  switch (variant) {
    case "native":
      return new lambda.Function(scope, id, {
        ...common,
        runtime: lambda.Runtime.PROVIDED_AL2023,
        handler: "bootstrap",
        code: lambda.Code.fromAsset(path.join(samples, "native-hello/build/lambda/bootstrap-linuxArm64.zip")),
      });
    case "jvm-managed": {
      const fn = new lambda.Function(scope, id, {
        ...common,
        memorySize: 1024,
        runtime: lambda.Runtime.JAVA_21,
        handler: "Handler",
        code: lambda.Code.fromAsset(path.join(samples, "jvm-managed/build/lambda/jvm-managed.zip")),
        snapStart: lambda.SnapStartConf.ON_PUBLISHED_VERSIONS,
      });
      return new lambda.Alias(scope, `${id}Live`, { aliasName: "live", version: fn.currentVersion });
    }
    case "jvm-runtime":
      return new lambda.DockerImageFunction(scope, id, {
        ...common,
        memorySize: 1024,
        code: lambda.DockerImageCode.fromImageAsset(path.join(samples, "jvm-runtime")),
      });
  }
}
