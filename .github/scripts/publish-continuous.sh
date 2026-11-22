#!/usr/bin/env bash
# Publishes every file in release/ as the pre-release $TAG (default
# continuous) for $GITHUB_SHA on $BRANCH (default main), replacing the
# previous one. Skips when $TAG holds a newer commit, unless $GITHUB_SHA is
# the branch head (a force push). Run from the repository root with GH_TOKEN
# allowed contents: write.
set -euo pipefail

tag="${TAG:-continuous}"
branch="${BRANCH:-main}"
repo="$GITHUB_REPOSITORY"
sha="$GITHUB_SHA"
run_url="$GITHUB_SERVER_URL/$repo/actions/runs/$GITHUB_RUN_ID"
draft="$tag-run-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT"
sbom=$(ls release/*-sbom.cdx.json)
version=$(basename "$sbom" -sbom.cdx.json)
version="${version#qcaret-}"

old=$(gh api "repos/$repo/git/ref/tags/$tag" --jq .object.sha 2>/dev/null) || old=""
if [ -n "$old" ]; then
  status=$(gh api "repos/$repo/compare/$old...$sha" --jq .status)
  head=$(gh api "repos/$repo/commits/$branch" --jq .sha)
  if [ "$status" != ahead ] && [ "$status" != identical ] && [ "$sha" != "$head" ]; then
    echo "::notice::$tag holds $old; $sha is not ahead of it (compare: $status) and not the $branch head. Not published."
    exit 0
  fi
fi

# A canceled run leaves its draft behind.
gh release list --repo "$repo" --json tagName,isDraft \
  --jq ".[] | select(.isDraft and (.tagName | startswith(\"$tag-run-\"))) | .tagName" |
  while read -r stale; do
    gh release delete "$stale" --repo "$repo" --yes
  done

cat > notes.md <<EOF
Built from \`$branch\` at $sha by [CI run $GITHUB_RUN_ID]($run_url) on $(date -u +%Y-%m-%d). \`qcaret -v\` prints \`qcaret $version\`.

Every test and bundle job in that run passed; the build is not tested by hand. The Windows and macOS builds are unsigned: see [First launch of an unsigned build](https://github.com/$repo#first-launch-of-an-unsigned-build). The next push to \`$branch\` that passes CI replaces this pre-release.
EOF

# Assets go up on a draft; the page is missing only between the delete and
# the publish.
gh release create "$draft" release/* --repo "$repo" --draft --prerelease \
  --latest=false --target "$sha" --title "Continuous build $version" \
  --notes-file notes.md
if [ -n "$old" ]; then
  gh api -X PATCH "repos/$repo/git/refs/tags/$tag" -f sha="$sha" -F force=true > /dev/null
else
  gh api -X POST "repos/$repo/git/refs" -f ref="refs/tags/$tag" -f sha="$sha" > /dev/null
fi
if gh release view "$tag" --repo "$repo" > /dev/null 2>&1; then
  gh release delete "$tag" --repo "$repo" --yes
fi
gh release edit "$draft" --repo "$repo" --tag "$tag" --draft=false --prerelease \
  --latest=false
echo "Published $tag at $sha"
