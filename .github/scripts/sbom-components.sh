#!/usr/bin/env bash
# Adds the components the Maven SBOM cannot see to dist/*-sbom.cdx.json: each
# Temurin JDK release dist.xml downloads (bundle runtimes and the macOS
# jpackage launcher) and the launch4j header in qcaret.exe. Versions come from
# qcaret-dist/src/build/dist.xml and THIRD-PARTY.txt. Run from the repository
# root after `package -Psbom`.
set -euo pipefail

sbom=$(ls dist/*-sbom.cdx.json)
dist_xml=qcaret-dist/src/build/dist.xml
notices=qcaret-dist/src/dist/THIRD-PARTY.txt

releases=$(grep -o 'temurin21-binaries/releases/download/jdk-[^"]*' "$dist_xml" |
  sed -e 's|.*/download/jdk-||' -e 's|%2B|+|' | LC_ALL=C sort -u)
launch4j=$(sed -n 's/^Launch4j \([0-9.]*\) executable header.*/\1/p' "$notices")
if [ -z "$releases" ] || [ -z "$launch4j" ]; then
  echo "sbom-components.sh: no Temurin release in $dist_xml or Launch4j version in $notices" >&2
  exit 1
fi

components=$(
  for r in $releases; do
    jq -n --arg v "$r" '{
      type: "platform", name: "eclipse-temurin-jdk", version: $v,
      "bom-ref": ("pkg:generic/adoptium/temurin-jdk@" + ($v | @uri)),
      purl: ("pkg:generic/adoptium/temurin-jdk@" + ($v | @uri)),
      licenses: [{expression: "GPL-2.0-only WITH Classpath-exception-2.0"}],
      description: "Bundle runtime (jlink image) and macOS jpackage launcher"
    }'
  done
  jq -n --arg v "$launch4j" '{
    type: "application", name: "launch4j-header", version: $v,
    "bom-ref": ("pkg:generic/launch4j/launch4j-header@" + $v),
    purl: ("pkg:generic/launch4j/launch4j-header@" + $v),
    licenses: [{license: {id: "MIT"}}],
    description: "Windows executable header in qcaret.exe"
  }'
)

jq --argjson extra "$(jq -s . <<<"$components")" '.components += $extra' "$sbom" > "$sbom.tmp"
mv "$sbom.tmp" "$sbom"
jq -r '.components[] | select(.purl | startswith("pkg:generic/")) | .purl' "$sbom"
