import {NativeEventEmitter, NativeModules, Platform} from 'react-native';

export type Tv = {
  host: string;
  name: string;
  version: string;
  duid: string | null;
  hasCertificates: boolean;
};
export type SelectedFile = {uri: string; name: string};
export type PreparedPackage = {
  token: string;
  name: string;
  packageId: string;
  appId: string;
  version: string;
  kind: 'wgt' | 'tpk';
  size: number;
};
export type Progress = {stage: string; message: string; percent?: number};
interface TizenBridge {
  exportCertificates(duid: string, password: string): Promise<boolean>;
  inventory(host: string): Promise<InstalledApp[]>;
  catalogRecords(host: string): Promise<Record<string, CatalogRecord>>;
  packageMetadata(
    url: string,
    name: string,
    digest: string | null,
  ): Promise<PackageMetadata>;
  networkInfo(): Promise<{ip: string; prefix: number}[]>;
  discover(): Promise<Tv[]>;
  connect(host: string): Promise<Tv>;
  savedHost(): Promise<string>;
  pickFile(kind: 'package' | 'certificate'): Promise<SelectedFile | null>;
  preparePackage(
    source: string,
    name: string,
    digest: string | null,
  ): Promise<PreparedPackage>;
  discardPackage(token: string): Promise<void>;
  createCertificates(host: string): Promise<string>;
  importCertificates(
    host: string,
    authorUri: string,
    authorPassword: string,
    distributorUri: string,
    distributorPassword: string,
  ): Promise<string>;
  installedApps(host: string): Promise<string>;
  install(
    host: string,
    token: string,
    catalogId: string | null,
    resign: boolean,
  ): Promise<{updated: boolean; name: string; output: string}>;
  cancel(): Promise<void>;
}
export const bridge: TizenBridge = NativeModules.TizenBridge;
export type InstalledApp = {
  appId: string;
  name: string;
  version: string | null;
  installedAt: string | null;
  fingerprint: string | null;
};
export type PackageMetadata = {
  appId: string;
  packageId: string;
  name: string;
  version: string;
  fingerprint: string;
};
export type CatalogRecord = {
  appId: string;
  packageId: string;
  version: string;
  assetName: string;
};
export const supported = Platform.OS === 'android' && !!bridge;
export const progressEvents = supported
  ? new NativeEventEmitter(NativeModules.TizenBridge)
  : null;
