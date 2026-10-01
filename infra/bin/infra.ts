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

// Caps the public endpoints' share of the account's concurrency; off by default because reserving any on a new
// account, whose limit is 10, makes the deployment fail.
const reserved = app.node.tryGetContext("reservedConcurrency");
const reservedConcurrency = reserved === undefined ? undefined : Number(reserved);
if (reservedConcurrency !== undefined && !(Number.isInteger(reservedConcurrency) && reservedConcurrency > 0)) {
  throw new Error(`reservedConcurrency must be a positive integer, was "${reserved}"`);
}

new HttpStack(app, `KtorLambdaHttp${suffix}`, variant, reservedConcurrency);
new AlbStack(app, `KtorLambdaAlb${suffix}`, variant, reservedConcurrency);
