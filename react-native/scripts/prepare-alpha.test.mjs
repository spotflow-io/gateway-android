import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { prepareAlpha } from './prepare-alpha.mjs';

const bridge = 'packages/ble-gateway';
const driver = 'packages/ble-gateway-ble-manager';
const peer = '@spotflow/react-native-ble-gateway';

function fixture(t) {
  const scratch = mkdtempSync(join(tmpdir(), 'spotflow-alpha-'));
  t.after(() => rmSync(scratch, { recursive: true, force: true }));
  const root = pathToFileURL(`${scratch}/react-native/`);
  const bridgeManifest = { name: peer, version: '0.1.0' };
  const driverManifest = { name: `${peer}-ble-manager`, version: '0.2.0', peerDependencies: { [peer]: '^0.1.0' } };
  const files = {
    '../gradle.properties': 'spotflowVersion=0.4.0\n',
    [`${bridge}/android/build.gradle`]: 'def spotflowGatewayVersion = "0.4.0"\n',
    [`${bridge}/package.json`]: JSON.stringify(bridgeManifest),
    [`${driver}/package.json`]: JSON.stringify(driverManifest),
    'package-lock.json': JSON.stringify({
      packages: { [bridge]: bridgeManifest, [driver]: driverManifest, 'node_modules/react-native': { version: '0.81.5' } },
    }),
  };
  for (const [path, content] of Object.entries(files)) {
    const destination = new URL(path, root);
    mkdirSync(dirname(fileURLToPath(destination)), { recursive: true });
    writeFileSync(destination, content);
  }
  return {
    root,
    read: (path) => readFileSync(new URL(path, root), 'utf8'),
    json: (path) => JSON.parse(readFileSync(new URL(path, root), 'utf8')),
  };
}

test('creates matching native and npm alpha versions and pins the bridge peer', (t) => {
  const f = fixture(t);
  const stableLock = f.json('package-lock.json');
  const stableNative = f.read('../gradle.properties').match(/^spotflowVersion=(.+)$/m)[1];
  const expectedBridge = `${stableLock.packages[bridge].version}-alpha.8.17.2`;
  const expectedDriver = `${stableLock.packages[driver].version}-alpha.8.17.2`;
  const expectedNative = `${stableNative}-alpha.8.17.2`;
  assert.deepEqual(prepareAlpha('8.17.2', f.root), {
    nativeVersion: expectedNative, bridgeVersion: expectedBridge, driverVersion: expectedDriver,
  });
  assert.ok(f.read('../gradle.properties').includes(`spotflowVersion=${expectedNative}\n`));
  assert.ok(f.read(`${bridge}/android/build.gradle`).includes(`spotflowGatewayVersion = "${expectedNative}"`));
  assert.equal(f.json(`${bridge}/package.json`).version, expectedBridge);
  const packagedDriver = f.json(`${driver}/package.json`);
  assert.equal(packagedDriver.version, expectedDriver);
  assert.equal(packagedDriver.peerDependencies[peer], expectedBridge);
  stableLock.packages[bridge].version = expectedBridge;
  stableLock.packages[driver].version = expectedDriver;
  stableLock.packages[driver].peerDependencies[peer] = expectedBridge;
  assert.deepEqual(f.json('package-lock.json'), stableLock);
});

test('rejects invalid suffixes and refuses to rewrite mismatched versions', (t) => {
  const f = fixture(t);
  const native = f.read('../gradle.properties');
  assert.throws(() => prepareAlpha('8.latest.1', f.root), /Expected <pr-number>/);
  assert.throws(() => prepareAlpha('08.1.1', f.root), /Expected <pr-number>/);
  writeFileSync(new URL(`${bridge}/android/build.gradle`, f.root), 'def spotflowGatewayVersion = "99.0.0"\n');
  assert.throws(() => prepareAlpha('8.1.1', f.root), /must match and be stable/);
  assert.equal(f.read('../gradle.properties'), native);
});

test('rejects a stale peer range in the lockfile', (t) => {
  const f = fixture(t);
  const lock = f.json('package-lock.json');
  lock.packages[driver].peerDependencies[peer] = '^0.0.1';
  writeFileSync(new URL('package-lock.json', f.root), JSON.stringify(lock));
  assert.throws(() => prepareAlpha('8.1.1', f.root), /must agree on the bridge peer dependency/);
  assert.equal(f.json(`${driver}/package.json`).version, '0.2.0');
});
