# Extension builds

Pull requests and manual checks build both RWML extension packs and all four platform-specific API
and client JARs. The workflow uploads those six files and their checksums as a run artifact.
Publishing a GitHub Release starts one workflow run. It builds the release tag and attaches the same
files to that release. Branch and tag pushes do not start additional runs. The `publish` job then
uploads those four JARs to Cloudflare R2 and rewrites `extensions/registry.json` from their
digests, so the release assets, the R2 objects and the runtime catalog always describe one
build. No local upload or manual download is needed.

The R2 step requires the repository secrets `R2_ENDPOINT`, `R2_ACCESS_KEY_ID` and
`R2_SECRET_ACCESS_KEY`. The optional repository variable `R2_BUCKET` defaults to `rwpp`.
Missing R2 secrets fail the publish job explicitly; a successful release workflow must include R2 publication.
Push the source commit before tagging a release, because CI builds from the tag and
cannot see an uncommitted working tree.

The build resolves `rwml-core` and `game-lib` from Maven Central at the same version, pinned in
`gradle/libs.versions.toml`. Publish both RWML artifacts before building a RWPP release that depends
on them.

## Maven Central

The `publish-central` job runs for a published, stable GitHub Release. The tag must match the
Gradle project version. The job signs and submits two bundles under `io.github.rwpp-team`.
The `rwpp-api` deployment contains the API and its JVM and Android variants. Once it is
published, the job submits the `rwpp-client` deployment with the client and its variants. Configure the
`CENTRAL_USERNAME`, `CENTRAL_TOKEN`, and `GPG_PRIVATE_KEY` repository secrets. Use the Central
Portal user token credentials and an armored private key. Set `GPG_PASSPHRASE` if the key requires it.

The workflow stores an Actions artifact for each deployment ID. Reruns resume each deployment
or skip its coordinates if they already exist in Maven Central. A stored combined deployment is
also resumed. If Central reports that it failed, each new deployment checks its own coordinates
first. You can also run the workflow from the default branch
with `central_tag` set to a published stable release tag. The Central job builds the tagged source
using the workflow revision's publishing tools. If Central does not return a deployment ID after
submission, check the Portal before retrying. The pre-CI 2.0.1.1 deployment is skipped.

To rebuild and replace the packages of a published stable release, run the workflow from the
default branch with `release_tag` and leave `central_tag` blank. Enable `skip_central` to
replace the GitHub Release and R2 packages without submitting a Maven Central deployment.
