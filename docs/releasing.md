# Publishing a release

The [Release workflow](../.github/workflows/release.yml) runs when a tag such as `v0.1.0` or
`v0.1.0-rc.1` is pushed to `jsoizo/ktor-server-lambda`. It runs the existing unit, integration,
lint, ABI and CDK checks before signing and publishing all four library modules, including
their JVM and Linux Kotlin/Native publications. Samples and test fixtures are not published.

The tag supplies `VERSION_NAME` for the publishing command: `v0.1.0` publishes `0.1.0`.
There is no need to replace the development `VERSION_NAME=0.1.0-SNAPSHOT` in `gradle.properties`.
The release workflow does not publish snapshots or create a GitHub Release.

## One-time setup

Verify ownership of the `com.jsoizo` namespace in the Central Portal and
[generate a publishing user token](https://central.sonatype.org/publish/generate-portal-token/).
Use the token's username and password, rather than your Portal login credentials.

Create a GPG signing key and
[distribute its public key](https://central.sonatype.org/publish/requirements/gpg/#distributing-your-public-key).
In the repository's **Settings → Secrets and variables → Actions**, configure these repository secrets:

| Secret | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Central Portal user token username; also used by snapshot publishing |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user token password; also used by snapshot publishing |
| `SIGNING_IN_MEMORY_KEY` | Complete ASCII-armored GPG private key, preserving line breaks |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | Key passphrase; omit for an unprotected key |
| `SIGNING_IN_MEMORY_KEY_ID` | Optional signing key ID; needed when selecting a particular subkey |

Export the private key with `gpg --export-secret-keys --armor <key-id>` and store the output
directly in `SIGNING_IN_MEMORY_KEY`. Keep the private key out of commits and build logs.
See the [publishing plugin documentation](https://vanniktech.github.io/gradle-maven-publish-plugin/central/)
for the signing properties and Central Portal publishing process.

## Release steps

1. Merge the release changes into `main` and confirm CI passes.
2. From a clean checkout of that commit, push a new version tag:

   ```sh
   git switch main
   git pull --ff-only
   git tag -a v0.1.0 -m "Release 0.1.0"
   git push origin v0.1.0
   ```

3. Watch the **Release** run in GitHub Actions. The publish job calls
   `publishAndReleaseToMavenCentral` and waits for the deployment to reach `PUBLISHED`.
   Gradle's configuration cache is disabled for this command and its cache is not saved by the job.
4. Confirm the release is available from Maven Central before announcing it. For example, resolve
   `com.jsoizo:ktor-server-lambda-handler:0.1.0` in a consuming project with `mavenCentral()`.
5. In a subsequent change, update the README's release status and bump `VERSION_NAME` to the next
   development snapshot.

Release publishing runs are serialized and are not cancelled by a newer tag. If a run fails after
uploading, inspect the deployment in the [Central Portal](https://central.sonatype.com/publishing/deployments)
before retrying. If it already reached `PUBLISHED`, do not rerun publishing for that version:
Maven Central releases cannot be overwritten. If it has not published, resolve or remove the failed
deployment before rerunning the failed job. Use a new version for fixes to an already published release.
