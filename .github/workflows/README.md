# Extension builds

Each push builds both RWML extension packs and all four platform-specific API and client JARs.
The workflow uploads those six files and their checksums as a run artifact. Publishing a GitHub
release builds its tag and attaches the same files to that release. The `publish` job then
uploads those four JARs to Cloudflare R2 and rewrites `extensions/registry.json` from their
digests, so the release assets, the R2 objects and the runtime catalog always describe one
build. No local upload or manual download is needed.

The R2 step requires the repository secrets `R2_ENDPOINT`, `R2_ACCESS_KEY_ID` and
`R2_SECRET_ACCESS_KEY`. The optional repository variable `R2_BUCKET` defaults to `rwpp`.
When the secrets are absent, the step is skipped and the release is still published.
Push the source commit before tagging a release, because CI builds from the tag and
cannot see an uncommitted working tree.

The build checks out `RWPP-Team/RWML` and its pinned `work` (Anvil mappings) submodule. Push the
matching RWML and Anvil changes, including the updated RWML submodule pointer, before publishing a
RWPP commit that depends on them. The optional repository variable `RWML_BUILD_REF` can select a
compatible RWML branch, tag or commit; otherwise the workflow uses `master`. The workflow fails
explicitly if the selected checkout is too old, rather than silently producing stale artifacts.
