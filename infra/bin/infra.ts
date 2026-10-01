import { App } from "aws-cdk-lib";
import { AlbStack } from "../lib/alb-stack";
import { HttpStack } from "../lib/http-stack";
import { Variant, variants } from "../lib/sample-function";

const app = new App();
const variant = app.node.getContext("variant") as Variant;
if (!variants.includes(variant)) {
  throw new Error(`Unknown variant "${variant}"; expected one of ${variants.join(", ")}`);
}
const suffix = variant.replace(/(^|-)(\w)/g, (_, __, c: string) => c.toUpperCase());

new HttpStack(app, `KtorLambdaHttp${suffix}`, variant);
new AlbStack(app, `KtorLambdaAlb${suffix}`, variant);
