# React Native packages

- [`@spotflow/react-native-ble-gateway`](packages/ble-gateway): the gateway as a React Native module
  (Android). The native module wraps `SpotflowGateway` from this repository and implements `BleLink` by
  forwarding each GATT operation to a JS driver.
- [`@spotflow/react-native-ble-gateway-ble-manager`](packages/ble-gateway-ble-manager): the driver for
  `react-native-ble-manager`, as used by SimpleLink Connect.

## Development

Needs Node 22.18 or newer; bundling the AAR also needs a JDK 17 and the Android SDK.

```sh
npm install
npm run typecheck
npm test               # runs the TypeScript sources with Node's test runner
npm run build          # lib/ of both packages
npm run bundle-android # builds the gateway AAR into packages/ble-gateway/android/maven
```

`npm pack` in `packages/ble-gateway` bundles the AAR and builds `lib/` first. The gateway version the
module expects is `spotflowGatewayVersion` in `packages/ble-gateway/android/build.gradle`; the bundle
script refuses to run if it differs from `spotflowVersion` in the root `gradle.properties`.

To try the packages in an app before they are published, run `npm run build` and
`npm run bundle-android`, then install both package directories into the app (`npm install
<path>/packages/ble-gateway <path>/packages/ble-gateway-ble-manager`) and rebuild it.

The Kotlin module (`packages/ble-gateway/android`) builds only inside a React Native app, which
provides the React Native Gradle plugin and codegen. Its unit tests run there too, for example:

```sh
./gradlew :spotflow_react-native-ble-gateway:testDebugUnitTest   # in the app's android/ directory
```

## Publishing

The Android AAR, POM and Gradle metadata ship inside `@spotflow/react-native-ble-gateway`. Consumers such
as SimpleLink Connect install both packages from npm; they do not need to check in tarballs or configure
a remote Maven registry for the gateway.

### First npm release

1. Protect `main` and the `v*` tags in GitHub (restrict tag creation to release maintainers and prevent
   tag updates/deletions). Create a GitHub environment named `npm-release`; allow deployments only from
   `v*` tags, require maintainer approval, and prevent self-approval. Leave the repository Actions
   variable `NPM_PUBLISH_ENABLED` unset for the bootstrap release.
2. After the release commit is on `main`, push `v<spotflowVersion>` (for example, `v0.4.0`). The release
   workflow tests, packs, verifies and attaches both npm tarballs to the GitHub Release alongside the
   Android artifacts. An `@spotflow` maintainer with npm 2FA enabled should download and publish those
   exact tarballs, bridge first. For the initial npm versions:

   ```sh
   npm publish ./spotflow-react-native-ble-gateway-0.1.0.tgz --access public --ignore-scripts
   npm publish ./spotflow-react-native-ble-gateway-ble-manager-0.1.0.tgz --access public --ignore-scripts
   ```

3. For each npm package, configure a [GitHub Actions trusted publisher](https://docs.npmjs.com/trusted-publishers/)
   with owner `spotflow-io`, repository `gateway-android`, workflow filename `release.yml`, environment
   `npm-release`, and permission to run `npm publish`. Set the GitHub **repository** Actions variable
   `NPM_PUBLISH_ENABLED=true` before the next release. No npm token is needed. Set up the trusted
   publishers close to the next release: npm expires new configurations that aren't promptly used.
   After verifying OIDC publishing, restrict token-based publishing in each package's settings.

### Subsequent releases

- Update `spotflowVersion` in the root `gradle.properties` and `spotflowGatewayVersion` in
  `packages/ble-gateway/android/build.gradle` together. Bump both npm package versions in their manifests
  and refresh the workspace lockfile (`npm install --package-lock-only --ignore-scripts`). Check that the
  driver's peer range accepts the bridge version. Keep the native and npm version numbers independent.
- Tag the merged commit. Only the protected publishing job can request the npm OIDC token; it publishes
  the checked tarballs without a checkout, dependency install, or package scripts.
- npm versions are immutable and each package publishes separately. If only the bridge publishes,
  resolve the failure and publish just the missing driver from the same GitHub Release tarball; don't
  rebuild or reuse an already-published version for different content.
