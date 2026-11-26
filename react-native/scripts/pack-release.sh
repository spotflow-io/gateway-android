#!/usr/bin/env bash
set -euo pipefail

# Run from react-native/ after npm ci. Publish only the tarballs checked here.
destination=${1:?Usage: bash scripts/pack-release.sh <pack-destination>}
native_version=$(sed -n 's/^spotflowVersion=//p' ../gradle.properties)

pack_and_check() {
    local workspace=$1
    shift
    local tarball entries entry
    tarball=$(npm pack --workspace "$workspace" --pack-destination "$destination")
    tarball="$destination/$tarball"
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
pack_and_check @spotflow/react-native-ble-gateway android/build.gradle src/NativeSpotflowGateway.ts \
    "$native_module.aar" "$native_module.pom" "$native_module.module"
pack_and_check @spotflow/react-native-ble-gateway-ble-manager
