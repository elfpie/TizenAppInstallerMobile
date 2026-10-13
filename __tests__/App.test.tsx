/**
 * @format
 */

import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import App from '../App';
import {bridge} from '../src/bridge';

jest.mock(
  'react-native-safe-area-context',
  () => require('react-native-safe-area-context/jest/mock').default,
);
jest.mock('../src/bridge', () => ({
  supported: true,
  progressEvents: {addListener: () => ({remove: jest.fn()})},
  bridge: {
    networkInfo: jest.fn(async () => [{ip: '192.168.1.10', prefix: 24}]),
    savedHost: jest.fn(async () => '192.168.1.50'),
    connect: jest.fn(async () => ({
      host: '192.168.1.50',
      name: 'Test TV',
      version: '7.0',
      duid: 'TEST-DUID',
      hasCertificates: true,
    })),
    inventory: jest.fn(async () => [
      {
        appId: 'brew.App',
        name: 'TizenBrew',
        version: '1.0.0',
        fingerprint: null,
      },
    ]),
    catalogRecords: jest.fn(async () => ({})),
    packageMetadata: jest.fn(async (url: string) => ({
      appId: url.includes('TizenBrew') ? 'brew.App' : 'other.App',
      packageId: 'brew',
      version: '2.0.5',
      fingerprint: 'latest',
      name: 'App',
    })),
  },
}));

test('connecting reads TV versions and displays available updates automatically', async () => {
  globalThis.fetch = jest.fn(async (url: string | URL | Request) => {
    const name = String(url).includes('TizenBrew')
      ? 'TizenBrew.wgt'
      : 'Jellyfin.wgt';
    return {
      ok: true,
      json: async () => ({
        tag_name: 'v2.0.5',
        html_url: 'https://github.com/release',
        assets: [
          {
            name,
            size: 1000,
            digest: null,
            browser_download_url: 'https://github.com/' + name,
          },
        ],
      }),
    } as Response;
  });
  let tree: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    tree = ReactTestRenderer.create(<App />);
  });
  await ReactTestRenderer.act(async () => {
    tree!.root
      .findByProps({accessibilityLabel: 'TV connection'})
      .props.onPress();
  });
  await ReactTestRenderer.act(async () => {
    await tree!.root.findByProps({label: 'Connect to TV'}).props.onPress();
  });
  expect(bridge.inventory).toHaveBeenCalledWith('192.168.1.50');
  expect(bridge.packageMetadata).toHaveBeenCalledTimes(3);
  const rendered = JSON.stringify(tree!.toJSON());
  expect(rendered).toContain('Update available');
  expect(rendered).toContain('1.0.0');
  expect(rendered).toContain('2.0.5');
  await ReactTestRenderer.act(async () => tree!.unmount());
});
