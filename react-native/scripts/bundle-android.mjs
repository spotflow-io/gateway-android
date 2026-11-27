// Builds the Spotflow gateway AAR from this repository and copies it, with its POM, into the React Native
// package as a local Maven repository (packages/ble-gateway/android/maven), which the package's
// build.gradle resolves it from. Needs a JDK 17 and the Android SDK, as for the gateway itself.
import { execFileSync } from 'node:child_process';
import { cpSync, existsSync, readFileSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const reactNativeDir = join(dirname(fileURLToPath(import.meta.url)), '..');
const repoDir = join(reactNativeDir, '..');
const packageDir = join(reactNativeDir, 'packages', 'ble-gateway');

const version = /^spotflowVersion=(.+)$/m.exec(readFileSync(join(repoDir, 'gradle.properties'), 'utf8'))?.[1]?.trim();
const expected = /spotflowGatewayVersion = "(.+)"/.exec(readFileSync(join(packageDir, 'android', 'build.gradle'), 'utf8'))?.[1];
if (!version || version !== expected) {
  console.error(`android/build.gradle wants gateway ${expected}, but this repository builds ${version}: update spotflowGatewayVersion.`);
  process.exit(1);
}

const gradlew = join(repoDir, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
execFileSync(gradlew, ['--console=plain', '-q', ':spotflow-ble-gateway:publishReleasePublicationToDistRepository'], {
  cwd: repoDir,
  stdio: 'inherit',
});

const artifact = join('io', 'spotflow', 'spotflow-ble-gateway', version);
const source = join(repoDir, 'build', 'maven-repo', artifact);
if (!existsSync(source)) {
  console.error(`Gradle did not produce ${source}`);
  process.exit(1);
}
const target = join(packageDir, 'android', 'maven');
rmSync(target, { recursive: true, force: true });
cpSync(source, join(target, artifact), { recursive: true });
console.log(`Bundled spotflow-ble-gateway ${version} into ${target}`);
