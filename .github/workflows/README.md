# Extension builds

Each push builds both RWML extension packs and all four platform-specific API and client JARs.
The workflow uploads those six files and their checksums as a run artifact. Publishing a GitHub
release builds its tag and attaches the same files to that release. The `publish` job then
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
