export type CatalogId = 'jellyfin' | 'tizenbrew' | 'moonlight';
export type CatalogApp = {
  id: CatalogId;
  name: string;
  repository: string;
  summary: string;
  description: string;
  color: string;
  glyph: string;
  preferredAssets: string[];
};
export const catalog: CatalogApp[] = [
  {
    id: 'jellyfin',
    name: 'Jellyfin',
    repository: 'jeppevinkel/jellyfin-tizen-builds',
    summary: 'Your media, on the big screen.',
    description:
      'Keep the same variant and signing keys to retain your server and login.',
    color: '#AA89FF',
    glyph: '◇',
    preferredAssets: ['Jellyfin-12.z.wgt', 'Jellyfin.wgt'],
  },
  {
    id: 'tizenbrew',
    name: 'TizenBrew',
    repository: 'reisxd/TizenBrew',
    summary: 'A home for community TV apps.',
    description: 'Choose the launcher build supported by your TV.',
    color: '#FFBE83',
    glyph: '⌘',
    preferredAssets: [
      'TizenBrewStandalone.wgt',
      'TizenBrewStandalone-Old.wgt',
      'TizenBrew.wgt',
    ],
  },
  {
    id: 'moonlight',
    name: 'Moonlight',
    repository: 'brightcraft/moonlight-tizen',
    summary: 'Stream your PC games to your TV.',
    description:
      'Pair with Sunshine after installing. ForceGM enables game mode.',
    color: '#78BDFF',
    glyph: '☾',
    preferredAssets: ['Moonlight.wgt'],
  },
];
export type ReleaseAsset = {
  name: string;
  browser_download_url: string;
  size: number;
  digest: string | null;
};
export type Release = {
  tag_name: string;
  html_url: string;
  published_at: string;
  assets: ReleaseAsset[];
};

export function installableAssets(
  release: Release,
  app: CatalogApp,
): ReleaseAsset[] {
  const rank = (name: string) => {
    const index = app.preferredAssets.findIndex(
      preferred => preferred.toLowerCase() === name.toLowerCase(),
    );
    return index < 0 ? 1000 : index;
  };
  return release.assets
    .filter(
      asset =>
        /\.(wgt|tpk)$/i.test(asset.name) &&
        asset.size > 0 &&
        asset.browser_download_url.startsWith('https://'),
    )
    .sort(
      (a, b) => rank(a.name) - rank(b.name) || a.name.localeCompare(b.name),
    );
}
export async function latestRelease(app: CatalogApp): Promise<Release> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 20000);
  try {
    const response = await fetch(
      'https://api.github.com/repos/' + app.repository + '/releases/latest',
      {
        headers: {Accept: 'application/vnd.github+json'},
        signal: controller.signal,
      },
    );
    if (!response.ok) {
      if (response.status === 403 || response.status === 429) {
        throw new Error(
          'GitHub is limiting requests. Try later or select a downloaded package.',
        );
      }
      throw new Error(
        'Could not load releases (HTTP ' + response.status + ').',
      );
    }
    const release: Release = await response.json();
    if (
      !Array.isArray(release.assets) ||
      !release.tag_name ||
      !release.html_url
    ) {
      throw new Error('GitHub returned an invalid release.');
    }
    if (!installableAssets(release, app).length)
      throw new Error(
        'No TV package in this release. Select a local package instead.',
      );
    return release;
  } finally {
    clearTimeout(timer);
  }
}
export const formatSize = (bytes: number) =>
  (bytes / 1024 / 1024).toFixed(1) + ' MB';
