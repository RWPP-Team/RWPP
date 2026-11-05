# Extension builds

Each push builds both RWML extension packs and all four platform-specific API and client JARs.
The workflow uploads those six files and their checksums as a run artifact. Publishing a GitHub
release builds its tag and uploads the same files to that release. No local upload is
needed: the `publish` job attaches the verified artifacts with the workflow token.
Push the source commit before tagging a release, because CI builds from the tag and
cannot see an uncommitted working tree.

The build checks out `RWPP-Team/RWML` and its pinned `work` (Anvil mappings) submodule. Push the
matching RWML and Anvil changes, including the updated RWML submodule pointer, before publishing a
RWPP commit that depends on them. The optional repository variable `RWML_BUILD_REF` can select a
compatible RWML branch, tag or commit; otherwise the workflow uses `master`. The workflow fails
explicitly if the selected checkout is too old, rather than silently producing stale artifacts.
