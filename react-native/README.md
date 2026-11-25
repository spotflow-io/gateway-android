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
