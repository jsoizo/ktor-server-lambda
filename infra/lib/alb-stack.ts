import { CfnOutput, Stack, StackProps } from "aws-cdk-lib";
import * as ec2 from "aws-cdk-lib/aws-ec2";
import * as elbv2 from "aws-cdk-lib/aws-elasticloadbalancingv2";
import { LambdaTarget } from "aws-cdk-lib/aws-elasticloadbalancingv2-targets";
import { Construct } from "constructs";
import { sampleFunction, Variant } from "./sample-function";

/**
 * One function behind an ALB, on port 80 with single-value headers and on port 8080 with multi-value headers.
 * Kept in its own stack because an ALB is billed while it exists; destroy it after trying.
 */
export class AlbStack extends Stack {
  constructor(scope: Construct, id: string, variant: Variant, props?: StackProps) {
    super(scope, id, props);
    const fn = sampleFunction(this, "Function", variant);
    const vpc = new ec2.Vpc(this, "Vpc", {
      maxAzs: 2,
      natGateways: 0,
      subnetConfiguration: [{ name: "Public", subnetType: ec2.SubnetType.PUBLIC }],
    });
    const alb = new elbv2.ApplicationLoadBalancer(this, "Alb", { vpc, internetFacing: true });

    for (const [port, multiValueHeadersEnabled] of [[80, false], [8080, true]] as const) {
      const listener = alb.addListener(`Port${port}`, { port, protocol: elbv2.ApplicationProtocol.HTTP });
      listener.addTargets(`Lambda${port}`, { targets: [new LambdaTarget(fn)] }).setAttribute(
        "lambda.multi_value_headers.enabled",
        String(multiValueHeadersEnabled),
      );
    }

    new CfnOutput(this, "SingleValueUrl", { value: `http://${alb.loadBalancerDnsName}/` });
    new CfnOutput(this, "MultiValueUrl", { value: `http://${alb.loadBalancerDnsName}:8080/` });
  }
}
