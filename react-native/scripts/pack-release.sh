#!/usr/bin/env bash
set -euo pipefail

# Run from react-native/ after the frozen install. Publish only the tarballs checked here.
destination=${1:?Usage: bash scripts/pack-release.sh <pack-destination>}
native_version=$(sed -n 's/^spotflowVersion=//p' ../gradle.properties)
mkdir -p "$destination"
destination=$(cd "$destination" && pwd)

pack_and_check() {
    local package_dir=$1
    shift
    local filename tarball entries entry
    # Use the manifest rather than parsing pack's output, which also includes lifecycle logs.
    filename=$(node -e 'const { name, version } = require(process.argv[1]);
        console.log(name.replace(/^@/, "").replace("/", "-") + "-" + version + ".tgz");' "./$package_dir/package.json")
    pnpm --dir "$package_dir" pack --pack-destination "$destination"
    tarball="$destination/$filename"
    entries=$(tar -tzf "$tarball")
    for entry in LICENSE.md package.json lib/index.js lib/index.d.ts "$@"; do
        if ! grep -Fxq "package/$entry" <<< "$entries"; then
            echo "$tarball is missing $entry" >&2
            return 1
        fi
    done
    tar -xOzf "$tarball" package/LICENSE.md | cmp ../LICENSE.md -
}

native_module="android/maven/io/spotflow/spotflow-ble-gateway/$native_version/spotflow-ble-gateway-$native_version"
pack_and_check packages/ble-gateway android/build.gradle src/NativeSpotflowGateway.ts \
    "$native_module.aar" "$native_module.pom" "$native_module.module"
pack_and_check packages/ble-gateway-ble-manager
