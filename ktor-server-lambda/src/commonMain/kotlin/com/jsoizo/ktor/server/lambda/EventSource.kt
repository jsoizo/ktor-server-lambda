package com.jsoizo.ktor.server.lambda

/** The AWS service that invoked the function, which determines the event and response format. */
public enum class EventSource {
    /**
     * API Gateway REST API proxy integration, or HTTP API with payload format 1.0.
     *
     * See [Lambda proxy integration input format](https://docs.aws.amazon.com/apigateway/latest/developerguide/set-up-lambda-proxy-integrations.html#api-gateway-simple-proxy-for-lambda-input-format).
     */
    ApiGatewayV1,

    /**
     * API Gateway HTTP API with payload format 2.0, or a Lambda Function URL.
     *
     * See [HTTP API payload format](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-develop-integrations-lambda.html)
     * and [Function URL payloads](https://docs.aws.amazon.com/lambda/latest/dg/urls-invocation.html).
     */
    ApiGatewayV2,

    /**
     * Application Load Balancer with a Lambda target group.
     *
     * See [Lambda functions as targets](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/lambda-functions.html).
     */
    Alb,
}
