# React Native packages

- [`@spotflow/react-native-ble-gateway`](packages/ble-gateway): the gateway as a React Native module
  (Android). The native module wraps `SpotflowGateway` from this repository and implements `BleLink` by
  forwarding each GATT operation to a JS driver.
- [`@spotflow/react-native-ble-gateway-ble-manager`](packages/ble-gateway-ble-manager): the driver for
  `react-native-ble-manager`, as used by SimpleLink Connect.

## Development

Needs Node 22.18 or newer and the pnpm version pinned in `package.json`. Bundling the AAR also needs
JDK 17 and the Android SDK. CI installs from `pnpm-lock.yaml` without running dependency scripts.
The workspace applies the platform frontend's one-week release delay and trust downgrade checks.

```sh
pnpm install --frozen-lockfile --ignore-scripts --ignore-pnpmfile
pnpm typecheck      # checks both workspaces
pnpm test           # runs the TypeScript sources with Node's test runner
pnpm build          # builds lib/ in both packages
pnpm bundle-android # builds the gateway AAR into packages/ble-gateway/android/maven
```

`pnpm pack` in `packages/ble-gateway` bundles the AAR and builds `lib/` first. The gateway version the
module expects is `spotflowGatewayVersion` in `packages/ble-gateway/android/build.gradle`; the bundle
script refuses to run if it differs from `spotflowVersion` in the root `gradle.properties`.

To try unpublished packages, run `bash scripts/pack-release.sh <destination>`, then install the two
tarballs in the app with `pnpm add <bridge.tgz> <driver.tgz>` and rebuild it.

The Kotlin module (`packages/ble-gateway/android`) builds only inside a React Native app, which
provides the React Native Gradle plugin and codegen. Its unit tests run there too, for example:

```sh
./gradlew :spotflow_react-native-ble-gateway:testDebugUnitTest   # in the app's android/ directory
```

## Publishing

The Android AAR, POM and Gradle metadata ship inside `@spotflow/react-native-ble-gateway`, so apps such
as SimpleLink Connect install both packages from npm and need no Maven repository for the gateway.

GitHub Actions stages both packages with [npm trusted publishing](https://docs.npmjs.com/trusted-publishers/),
without npm tokens. The staging jobs upload tarballs built and tested by CI without running package
code or scripts. The versions go live only after an npm maintainer reviews and approves them with 2FA.

|                 | Alpha                                         | Stable                         |
| --------------- | --------------------------------------------- | ------------------------------ |
| Built by        | CI, for same-repository pull requests          | `release.yml`, for a `v*` tag  |
| Staged by       | **Stage npm alpha**, run from `main`          | `release.yml`, automatically   |
| npm dist-tag    | `alpha`                                       | `latest`                       |
| Version         | `0.1.0-alpha.<pr>.<ci-run>.<attempt>`         | `0.1.0`                        |

For either workflow, open **Staged Packages** on npmjs.com after CI succeeds and approve the bridge
(`@spotflow/react-native-ble-gateway`) before the BLE Manager driver. Alternatively, use the stage IDs
from the job log with `pnpm stage view <stage-id>` and `pnpm stage approve <stage-id>`.

### Alphas

1. Open a pull request into `main` from a branch of this repository and wait for CI to pass. Fork PRs
   can't get alphas: push their reviewed commits to a branch here first.
2. Review the PR commit, then run **Actions → Stage npm alpha → Run workflow** on `main` with the PR
   number and full SHA of the commit you reviewed. For example, for PR #8:

   ```sh
   gh workflow run publish-alpha.yml --repo spotflow-io/gateway-android --ref main \
     -f pr=8 -f head_sha='<reviewed-commit-sha>'
   ```

   Preflight stops if the current PR head doesn't match the supplied SHA. Its summary records the
   commit, CI run, artifact ID, and SHA-256 digest before the staging job starts. The staging
   job uses that specific artifact even if the PR moves or another CI run finishes later. The final
   summary has the `pnpm add` command to run after approval. In apps, pin the exact versions, not the
   `alpha` tag.

Every CI run has its own version, and the Android gateway inside gets one to match (for example
`0.4.0-alpha.8.57.1`). The driver's peer dependency pins the exact bridge alpha. To publish a newer
alpha, push to the PR or re-run all jobs in its CI workflow. Versions can't be published twice.

### Stable releases

1. Update `spotflowVersion` in the root `gradle.properties` and `spotflowGatewayVersion` in
   `packages/ble-gateway/android/build.gradle` together. Bump the npm package versions in their
   manifests when their contents change, and refresh the lockfile (`pnpm install --lockfile-only
   --ignore-scripts --ignore-pnpmfile`). The driver's workspace peer range is converted to a registry
   semver range when packed.
2. Merge, then tag the merged commit `v<spotflowVersion>`. `release.yml` tests and packs both packages,
   attaches the tarballs to the GitHub Release, and stages them on npm with the `latest` dist-tag.

If only one upload succeeds, check **Staged Packages** before retrying. Don't restage a version that's
already staged or live. A maintainer can stage just the missing tarball from the GitHub Release with
`pnpm stage publish <tarball> --tag latest --access public --ignore-scripts --no-git-checks`.

### One-time setup

1. **GitHub** (a repository admin): protect `main` (required reviews) and `v*` tags (restricted
   creation, no updates or deletions). Create two environments, before anything uses them: a workflow
   that names a missing environment creates it without these rules.
   - `npm-alpha`, with deployment branches limited to `main`;
   - `npm-release`, with deployment tags limited to `v*`.
   These environments restrict which Git refs can reach each job, not which versions or dist-tags
   npm accepts. One shared environment would have to admit both kinds of refs.

2. **npm** (an `@spotflow` owner): in each package's **Settings → Trusted publishing**, add two GitHub
   Actions publishers for `spotflow-io/gateway-android`: workflow `publish-alpha.yml` with environment
   `npm-alpha`, and workflow `release.yml` with environment `npm-release`. Leave **Allow npm publish**
   and **Allow npm dist-tag** off; the workflows only need `npm stage publish`. Set up each publisher
   shortly before its first run so it can be validated within npm's two-day window.
3. **npm**: once both publishers have worked, set each package's **Publishing access** to require 2FA
   and disallow tokens.
