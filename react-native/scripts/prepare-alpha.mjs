import { readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const reactNative = new URL('../', import.meta.url);
const stableVersion = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const bridge = 'packages/ble-gateway';
const driver = 'packages/ble-gateway-ble-manager';
const bridgeName = '@spotflow/react-native-ble-gateway';
const driverName = '@spotflow/react-native-ble-gateway-ble-manager';

/** Update only version fields in a disposable checkout. Do not resolve new dependencies. */
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
  const lock = json('package-lock.json');
  for (const [path, manifest, name] of [[bridge, module, bridgeName], [driver, adapter, driverName]]) {
    const entry = lock.packages[path];
    if (manifest.name !== name || !stableVersion.test(manifest.version) ||
        entry?.name !== name || entry?.version !== manifest.version) {
      throw new Error(`Expected ${path} and its lockfile entry to agree on name and stable version`);
    }
    manifest.version += alpha;
    entry.version = manifest.version;
  }
  const lockedDriver = lock.packages[driver];
  const peer = adapter.peerDependencies?.[bridgeName];
  if (!peer || lockedDriver.peerDependencies?.[bridgeName] !== peer) {
    throw new Error('The driver and its lockfile entry must agree on the bridge peer dependency');
  }
  adapter.peerDependencies[bridgeName] = module.version;
  lockedDriver.peerDependencies[bridgeName] = module.version;

  const nativeAlpha = native + alpha;
  write(propertiesPath, properties.replace(/^spotflowVersion=.+$/m, `spotflowVersion=${nativeAlpha}`));
  write(gradlePath, gradle.replace(/^def spotflowGatewayVersion = ".+"$/m, `def spotflowGatewayVersion = "${nativeAlpha}"`));
  writeJson(`${bridge}/package.json`, module);
  writeJson(`${driver}/package.json`, adapter);
  writeJson('package-lock.json', lock);
  return { nativeVersion: nativeAlpha, bridgeVersion: module.version, driverVersion: adapter.version };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  console.log(prepareAlpha(process.argv[2]));
}
