import type {InstalledApp, PackageMetadata} from './bridge';
import type {CatalogApp, Release, ReleaseAsset} from './catalog';
export type UpdateState =
  | 'not-installed'
  | 'update'
  | 'current'
  | 'newer'
  | 'different-build'
  | 'unknown'
  | 'different-variant'
  | 'error';
export type UpdateCheck = {
  state: UpdateState;
  label: string;
  installed?: InstalledApp;
  available?: PackageMetadata;
  release?: Release;
  asset?: ReleaseAsset;
  message?: string;
};
export function compareVersions(
  installed: string,
  available: string,
): number | null {
  const parse = (version: string) => {
    const match = /^v?(\d+(?:\.\d+){1,3})(?:-([0-9A-Za-z.-]+))?$/.exec(
      version.trim(),
    );
    if (!match) return null;
    return {
      numbers: match[1].split('.').map(BigInt),
      pre: match[2]?.split('.'),
    };
  };
  const a = parse(installed),
    b = parse(available);
  if (!a || !b) return null;
  for (let i = 0; i < Math.max(a.numbers.length, b.numbers.length); i++) {
    const x = a.numbers[i] || 0n,
      y = b.numbers[i] || 0n;
    if (x !== y) return x < y ? -1 : 1;
  }
  if (!a.pre && !b.pre) return 0;
  if (!a.pre) return 1;
  if (!b.pre) return -1;
  for (let i = 0; i < Math.max(a.pre.length, b.pre.length); i++) {
    const x = a.pre[i],
      y = b.pre[i];
    if (x === y) continue;
    if (x === undefined) return -1;
    if (y === undefined) return 1;
    const xn = /^\d+$/.test(x),
      yn = /^\d+$/.test(y);
    if (xn && yn) return BigInt(x) < BigInt(y) ? -1 : 1;
    if (xn !== yn) return xn ? -1 : 1;
    return x < y ? -1 : 1;
  }
  return 0;
}
export function evaluateUpdate(
  app: CatalogApp,
  installed: InstalledApp | undefined,
  available: PackageMetadata,
): UpdateCheck {
  const base = {installed, available};
  if (!installed)
    return {...base, state: 'not-installed', label: 'Not installed'};
  if (installed.appId !== available.appId)
    return {
      ...base,
      state: 'different-variant',
      label: 'Different variant installed',
      message: 'Choose the package with the same app ID before updating.',
    };
  if (installed.fingerprint && installed.fingerprint === available.fingerprint)
    return {...base, state: 'current', label: 'Up to date'};
  const comparison = installed.version
    ? compareVersions(installed.version, available.version)
    : null;
  if (comparison !== null && comparison < 0)
    return {...base, state: 'update', label: 'Update available'};
  if (comparison !== null && comparison > 0)
    return {...base, state: 'newer', label: 'Installed version is newer'};
  if (comparison === 0 && installed.fingerprint)
    return {
      ...base,
      state: 'different-build',
      label: 'Different build available',
      message:
        'The latest release has different content. Equal version numbers do not prove which build is newer; review the build before updating.',
    };
  if (comparison === 0 && app.id !== 'jellyfin')
    return {...base, state: 'current', label: 'Up to date'};
  return {
    ...base,
    state: 'unknown',
    label: 'Build version unknown',
    message:
      app.id === 'jellyfin'
        ? 'Jellyfin reuses package version 0.1.0. The TV did not expose signed file hashes, so this build cannot be compared reliably.'
        : 'The TV did not report a comparable version.',
  };
}
