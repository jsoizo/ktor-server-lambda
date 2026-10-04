package com.jsoizo.ktor.server.lambda.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

// Trimmed from the event examples in the AWS documentation to the fields detection and mapping use.
internal object Fixtures {
    fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    const val REST_V1 = """
    {
      "resource": "/{proxy+}",
      "path": "/users/42",
      "httpMethod": "POST",
      "headers": { "Host": "abc.execute-api.ap-northeast-1.amazonaws.com", "X-Single": "last" },
      "multiValueHeaders": {
        "Host": ["abc.execute-api.ap-northeast-1.amazonaws.com"],
        "Accept": ["text/html", "application/json"],
        "Cookie": ["a=1; b=2"]
      },
      "queryStringParameters": { "q": "b" },
      "multiValueQueryStringParameters": { "q": ["a b", "b"], "lang": ["ja"] },
      "pathParameters": { "proxy": "users/42" },
      "stageVariables": null,
      "requestContext": {
        "stage": "prod",
        "path": "/prod/users/42",
        "domainName": "abc.execute-api.ap-northeast-1.amazonaws.com",
        "identity": { "sourceIp": "203.0.113.10" }
      },
      "body": "aGVsbG8=",
      "isBase64Encoded": true
    }
    """

    const val HTTP_V2 = """
    {
      "version": "2.0",
      "routeKey": "ANY /{proxy+}",
      "rawPath": "/prod/items",
      "rawQueryString": "a=1&a=2&b=%20x",
      "cookies": ["c1=v1", "c2=v2"],
      "headers": { "host": "xyz.execute-api.ap-northeast-1.amazonaws.com", "accept": "text/html,application/json" },
      "queryStringParameters": { "a": "1,2", "b": " x" },
      "requestContext": {
        "domainName": "xyz.execute-api.ap-northeast-1.amazonaws.com",
        "stage": "prod",
        "http": { "method": "GET", "path": "/prod/items", "protocol": "HTTP/1.1", "sourceIp": "198.51.100.7" }
      },
      "isBase64Encoded": false
    }
    """

    val functionUrl = """
    {
      "version": "2.0",
      "routeKey": "${'$'}default",
      "rawPath": "/hello",
      "rawQueryString": "",
      "headers": { "host": "abcdefg.lambda-url.us-east-1.on.aws" },
      "requestContext": {
        "domainName": "abcdefg.lambda-url.us-east-1.on.aws",
        "stage": "${'$'}default",
        "http": { "method": "GET", "path": "/hello", "sourceIp": "198.51.100.8" }
      },
      "isBase64Encoded": false
    }
    """

    const val ALB_MULTI = """
    {
      "requestContext": { "elb": { "targetGroupArn": "arn:aws:elasticloadbalancing:ap-northeast-1:123456789012:targetgroup/tg/abc" } },
      "httpMethod": "GET",
      "path": "/search",
      "multiValueQueryStringParameters": { "q": ["a%20b", "c%2Bd"] },
      "multiValueHeaders": {
        "host": ["alb.example.com"],
        "x-forwarded-for": ["198.51.100.1, 10.0.0.5"],
        "x-forwarded-proto": ["http"],
        "x-forwarded-port": ["80"]
      },
      "body": "",
      "isBase64Encoded": false
    }
    """

    const val ALB_SINGLE = """
    {
      "requestContext": { "elb": { "targetGroupArn": "arn:aws:elasticloadbalancing:ap-northeast-1:123456789012:targetgroup/tg/abc" } },
      "httpMethod": "GET",
      "path": "/",
      "queryStringParameters": { "q": "a%20b" },
      "headers": { "host": "alb.example.com", "x-forwarded-for": "198.51.100.1" },
      "body": "",
      "isBase64Encoded": false
    }
    """
}
