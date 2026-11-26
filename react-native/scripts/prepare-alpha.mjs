import { readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const reactNative = new URL('../', import.meta.url);
const stableVersion = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const bridge = 'packages/ble-gateway';
const driver = 'packages/ble-gateway-ble-manager';
const bridgeName = '@spotflow/react-native-ble-gateway';
const driverName = '@spotflow/react-native-ble-gateway-ble-manager';

/** Stamp a disposable checkout after the frozen install. Leave the lockfile unchanged. */
export function prepareAlpha(suffix, root = reactNative) {
  if (!/^[1-9]\d*\.[1-9]\d*\.[1-9]\d*$/.test(suffix ?? '')) {
    throw new Error('Expected <pr-number>.<run-number>.<run-attempt>');
  }
  const alpha = `-alpha.${suffix}`;
  const read = (path) => readFileSync(new URL(path, root), 'utf8');
  const write = (path, value) => writeFileSync(new URL(path, root), value);
  const json = (path) => JSON.parse(read(path));
  const writeJson = (path, value) => write(path, `${JSON.stringify(value, null, 2)}\n`);

  const propertiesPath = '../gradle.properties';
  const gradlePath = `${bridge}/android/build.gradle`;
  const properties = read(propertiesPath);
  const gradle = read(gradlePath);
  const native = properties.match(/^spotflowVersion=(.+)$/m)?.[1];
  const expected = gradle.match(/^def spotflowGatewayVersion = "(.+)"$/m)?.[1];
  if (native !== expected || !stableVersion.test(native ?? '')) {
    throw new Error('The Gradle and React Native native versions must match and be stable before packing an alpha');
  }

  const module = json(`${bridge}/package.json`);
  const adapter = json(`${driver}/package.json`);
  for (const [path, manifest, name] of [[bridge, module, bridgeName], [driver, adapter, driverName]]) {
    if (manifest.name !== name || !stableVersion.test(manifest.version)) {
      throw new Error(`Expected ${path} to have its usual package name and a stable version`);
    }
    manifest.version += alpha;
  }
  if (!adapter.peerDependencies?.[bridgeName]) {
    throw new Error('The driver must declare the bridge as a peer dependency');
  }
  adapter.peerDependencies[bridgeName] = module.version;

  const nativeAlpha = native + alpha;
  write(propertiesPath, properties.replace(/^spotflowVersion=.+$/m, `spotflowVersion=${nativeAlpha}`));
  write(gradlePath, gradle.replace(/^def spotflowGatewayVersion = ".+"$/m, `def spotflowGatewayVersion = "${nativeAlpha}"`));
  writeJson(`${bridge}/package.json`, module);
  writeJson(`${driver}/package.json`, adapter);
  return { nativeVersion: nativeAlpha, bridgeVersion: module.version, driverVersion: adapter.version };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  console.log(prepareAlpha(process.argv[2]));
}
