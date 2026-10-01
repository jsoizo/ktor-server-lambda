import { CfnOutput, Stack, StackProps } from "aws-cdk-lib";
import * as apigateway from "aws-cdk-lib/aws-apigateway";
import * as apigatewayv2 from "aws-cdk-lib/aws-apigatewayv2";
import { HttpLambdaIntegration } from "aws-cdk-lib/aws-apigatewayv2-integrations";
import * as lambda from "aws-cdk-lib/aws-lambda";
import { Construct } from "constructs";
import { sampleFunction, Variant } from "./sample-function";

// The endpoints are public and unauthenticated; a low limit bounds what a stranger can run up.
const throttle = { throttlingRateLimit: 10, throttlingBurstLimit: 20 };

/** One function behind a REST API, HTTP APIs with payload 2.0 and 1.0, and a Function URL. */
export class HttpStack extends Stack {
  constructor(scope: Construct, id: string, variant: Variant, props?: StackProps) {
    super(scope, id, props);
    const fn = sampleFunction(this, "Function", variant);

    const rest = new apigateway.LambdaRestApi(this, "RestApi", {
      handler: fn,
      // API Gateway decodes base64 bodies only for these types; see "Things to know about Lambda" in the root README.
      binaryMediaTypes: ["*/*"],
      // The default would replace the account-wide API Gateway CloudWatch role and keep it after destroy.
      cloudWatchRole: false,
      deployOptions: { stageName: "prod", ...throttle },
    });

    const http = this.httpApi("HttpApi", fn, apigatewayv2.PayloadFormatVersion.VERSION_2_0);
    // Whether payload 1.0 on HTTP APIs includes the stage in `path` is still unverified.
    const http10 = this.httpApi("HttpApiV10", fn, apigatewayv2.PayloadFormatVersion.VERSION_1_0);

    const url = new lambda.FunctionUrl(this, "Url", { function: fn, authType: lambda.FunctionUrlAuthType.NONE });

    new CfnOutput(this, "RestApiUrl", { value: rest.url });
    new CfnOutput(this, "HttpApiUrl", { value: http.apiEndpoint });
    new CfnOutput(this, "HttpApiStageUrl", { value: `${http.apiEndpoint}/v1/` });
    new CfnOutput(this, "HttpApiPayload10StageUrl", { value: `${http10.apiEndpoint}/v1/` });
    new CfnOutput(this, "FunctionUrl", { value: url.url });
  }

  /** An HTTP API with a `$default` stage and a named `v1` stage, which puts "/v1" in the request path. */
  private httpApi(id: string, fn: lambda.IFunction, payloadFormatVersion: apigatewayv2.PayloadFormatVersion) {
    const api = new apigatewayv2.HttpApi(this, id, {
      defaultIntegration: new HttpLambdaIntegration(`${id}Integration`, fn, { payloadFormatVersion }),
      // Created below instead, because the automatic $default stage cannot be throttled.
      createDefaultStage: false,
    });
    const stageThrottle = { rateLimit: throttle.throttlingRateLimit, burstLimit: throttle.throttlingBurstLimit };
    api.addStage("default", { stageName: "$default", autoDeploy: true, throttle: stageThrottle });
    api.addStage("v1", { stageName: "v1", autoDeploy: true, throttle: stageThrottle });
    return api;
  }
}
