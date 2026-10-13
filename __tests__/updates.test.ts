import {catalog, installableAssets, Release} from '../src/catalog';
import {compareVersions, evaluateUpdate} from '../src/updates';
import type {InstalledApp, PackageMetadata} from '../src/bridge';
const installed: InstalledApp = {
  appId: 'abc.Jellyfin',
  name: 'Jellyfin',
  version: '0.1.0',
  installedAt: null,
  fingerprint: null,
};
const available: PackageMetadata = {
  appId: installed.appId,
  name: 'Jellyfin',
  packageId: 'abc',
  version: '0.1.0',
  fingerprint: 'latest',
};
test.each([
  ['1.9.0', '1.10.0', -1],
  ['2.0', '2.0.0', 0],
  ['1.0.1', '1.0.0', 1],
  ['1.0.0-beta.2', '1.0.0-beta.11', -1],
  ['1.0.0-beta', '1.0.0', -1],
  ['2026-10-12-1041', '0.1.0', null],
  ['unknown', '1.0.0', null],
])('compare %s against %s', (a, b, expected) =>
  expect(compareVersions(a, b)).toBe(expected),
);
test('Jellyfin static package version does not imply current build', () => {
  expect(evaluateUpdate(catalog[0], installed, available).state).toBe(
    'unknown',
  );
});
test('same version with different signed content offers build review', () => {
  expect(
    evaluateUpdate(catalog[0], {...installed, fingerprint: 'older'}, available)
      .state,
  ).toBe('different-build');
});
test('matching TV content confirms current build', () => {
  expect(
    evaluateUpdate(
      catalog[0],
      {...installed, fingerprint: available.fingerprint},
      available,
    ).state,
  ).toBe('current');
});
test('newer TV version is not an upgrade candidate', () => {
  expect(
    evaluateUpdate(
      catalog[2],
      {...installed, version: '2.0.0'},
      {...available, version: '1.18.0'},
    ).state,
  ).toBe('newer');
});
test('lower TV version offers an update', () => {
  expect(
    evaluateUpdate(
      catalog[2],
      {...installed, version: '1.17.0'},
      {...available, version: '1.18.0'},
    ).state,
  ).toBe('update');
});
test('different app identity cannot be an in-place update', () => {
  expect(
    evaluateUpdate(catalog[0], installed, {
      ...available,
      appId: 'secondary.Jellyfin',
    }).state,
  ).toBe('different-variant');
});
test('not installed is distinct from unknown', () => {
  expect(evaluateUpdate(catalog[0], undefined, available).state).toBe(
    'not-installed',
  );
});
test('catalog prefers primary stable build and filters source archives', () => {
  const release = {
    assets: [
      {
        name: 'Jellyfin-master.wgt',
        size: 10,
        browser_download_url: 'https://github.com/master',
      },
      {
        name: 'source.zip',
        size: 10,
        browser_download_url: 'https://github.com/source',
      },
      {
        name: 'Jellyfin.wgt',
        size: 10,
        browser_download_url: 'https://github.com/stable',
      },
    ],
  } as Release;
  expect(
    installableAssets(release, catalog[0]).map(asset => asset.name),
  ).toEqual(['Jellyfin.wgt', 'Jellyfin-master.wgt']);
});
