import React, {useEffect, useRef, useState} from 'react';
import {
  ActivityIndicator,
  Linking,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';
import {
  bridge,
  PreparedPackage,
  Progress,
  progressEvents,
  SelectedFile,
  supported,
  Tv,
} from './bridge';
import {
  catalog,
  CatalogId,
  formatSize,
  installableAssets,
  latestRelease,
  CatalogApp,
  Release,
  ReleaseAsset,
} from './catalog';
import {evaluateUpdate, UpdateCheck} from './updates';

type Tab = 'apps' | 'tv' | 'settings';
type Review = PreparedPackage & {catalogId: CatalogId | null};
const c = {
  bg: '#090F18',
  panel: '#131D2B',
  line: '#253246',
  muted: '#93A4BD',
  text: '#F1F6FF',
  accent: '#58E1C2',
};
function Button({
  label,
  onPress,
  disabled = false,
  secondary = false,
}: {
  label: string;
  onPress: () => void;
  disabled?: boolean;
  secondary?: boolean;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{disabled}}
      disabled={disabled}
      onPress={onPress}
      style={({pressed}) => [
        s.button,
        secondary && s.secondary,
        disabled && s.disabled,
        pressed && s.pressed,
      ]}
    >
      <Text style={[s.buttonText, secondary && s.secondaryText]}>{label}</Text>
    </Pressable>
  );
}
function Field({
  label,
  value,
  onChange,
  placeholder,
  password = false,
  disabled = false,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  password?: boolean;
  disabled?: boolean;
}) {
  return (
    <View style={s.field}>
      <Text style={s.label}>{label}</Text>
      <TextInput
        accessibilityLabel={label}
        value={value}
        onChangeText={onChange}
        placeholder={placeholder}
        placeholderTextColor={c.muted}
        secureTextEntry={password}
        autoCapitalize="none"
        autoCorrect={false}
        editable={!disabled}
        style={s.input}
      />
    </View>
  );
}
function Details({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  const [expanded, setExpanded] = useState(false);
  return (
    <>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={title}
        accessibilityState={{expanded}}
        onPress={() => setExpanded(!expanded)}
        style={s.disclosure}
      >
        <Text style={s.cardTitle}>{title}</Text>
        <Text style={s.chevron}>{expanded ? '−' : '+'}</Text>
      </Pressable>
      {expanded && children}
    </>
  );
}
function Pocket() {
  const [tab, setTab] = useState<Tab>('apps');
  const [tv, setTv] = useState<Tv | null>(null);
  const [host, setHost] = useState('');
  const [ips, setIps] = useState<string[]>([]);
  const [devices, setDevices] = useState<Tv[]>([]);
  const [scanned, setScanned] = useState(false);
  const [busy, setBusy] = useState(false);
  const running = useRef(false);
  const [progress, setProgress] = useState<Progress | null>(null);
  const [log, setLog] = useState<string[]>([]);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [updates, setUpdates] = useState<
    Partial<Record<CatalogId, UpdateCheck>>
  >({});
  const [checkedAt, setCheckedAt] = useState('');
  const cancelled = useRef(false);
  const [details, setDetails] = useState<{
    app: CatalogApp;
    release: Release;
  } | null>(null);
  const [asset, setAsset] = useState<ReleaseAsset | null>(null);
  const [review, setReview] = useState<Review | null>(null);
  const [resign, setResign] = useState(true);
  const [packageUrl, setPackageUrl] = useState('');
  const [packageName, setPackageName] = useState('custom.wgt');
  const [showUrl, setShowUrl] = useState(false);
  const [author, setAuthor] = useState<SelectedFile | null>(null);
  const [distributor, setDistributor] = useState<SelectedFile | null>(null);
  const [authorPassword, setAuthorPassword] = useState('');
  const [distributorPassword, setDistributorPassword] = useState('');
  const [appList, setAppList] = useState('');
  const [backupPassword, setBackupPassword] = useState('');
  const stage = useRef('');
  useEffect(() => {
    if (!supported) return;
    bridge
      .networkInfo()
      .then(info => setIps(info.map(item => item.ip)))
      .catch(() => {});
    bridge
      .savedHost()
      .then(setHost)
      .catch(() => {});
    const listener = progressEvents?.addListener('TizenProgress', value => {
      const event = value as Progress;
      setProgress(event);
      if (stage.current !== event.stage) {
        stage.current = event.stage;
        setLog(old => [...old.slice(-19), event.message]);
      }
    });
    return () => listener?.remove();
  }, []);
  async function run(action: () => Promise<void>) {
    if (running.current) return;
    running.current = true;
    cancelled.current = false;
    setBusy(true);
    setError('');
    setNotice('');
    setProgress(null);
    stage.current = '';
    try {
      await action();
    } catch (failure) {
      const message =
        failure instanceof Error ? failure.message : String(failure);
      setError(message);
      setLog(old => [...old.slice(-19), message]);
    } finally {
      running.current = false;
      setBusy(false);
    }
  }
  const openUrl = (url: string) => {
    Linking.openURL(url).catch(() => setError('No browser is available.'));
  };
  async function connect(address: string) {
    await run(async () => {
      const result = await bridge.connect(address.trim());
      setTv(result);
      setHost(result.host);
      setAppList('');
      setTab('apps');
      setNotice('Connected to ' + result.name + '.');
      await refreshUpdates(result);
    });
  }
  async function refreshUpdates(target: Tv) {
    setUpdates({});
    setCheckedAt('');
    const inventory = await bridge.inventory(target.host);
    const records = await bridge.catalogRecords(target.host);
    const releases = await Promise.all(
      catalog.map(async app => {
        try {
          return {app, release: await latestRelease(app)};
        } catch (failure) {
          return {
            app,
            error: failure instanceof Error ? failure.message : String(failure),
          };
        }
      }),
    );
    for (const item of releases) {
      if (cancelled.current) throw new Error('Update check cancelled.');
      const {app} = item;
      const known =
        inventory.find(entry => entry.appId === records[app.id]?.appId) ||
        inventory.find(entry =>
          entry.name.toLowerCase().replace(/\s/g, '').startsWith(app.id),
        );
      try {
        if (!item.release) throw new Error(item.error);
        const assets = installableAssets(item.release, app);
        const selected =
          assets.find(entry => entry.name === records[app.id]?.assetName) ||
          assets[0];
        const metadata = await bridge.packageMetadata(
          selected.browser_download_url,
          selected.name,
          selected.digest ?? null,
        );
        const exact =
          inventory.find(entry => entry.appId === metadata.appId) || known;
        const result = {
          ...evaluateUpdate(app, exact, metadata),
          release: item.release,
          asset: selected,
        };
        setUpdates(old => ({...old, [app.id]: result}));
      } catch (failure) {
        if (cancelled.current) throw new Error('Update check cancelled.');
        const message =
          failure instanceof Error ? failure.message : String(failure);
        setUpdates(old => ({
          ...old,
          [app.id]: {
            state: 'error',
            label: 'Could not check updates',
            installed: known,
            message,
          },
        }));
      }
    }
    setCheckedAt(new Date().toLocaleTimeString());
  }
  async function prepare(
    source: string,
    name: string,
    digest: string | null,
    catalogId: CatalogId | null,
  ) {
    const prepared = await bridge.preparePackage(source, name, digest);
    if (review) await bridge.discardPackage(review.token);
    setReview({...prepared, catalogId});
    setResign(true);
    setDetails(null);
    setTab('apps');
  }
  async function pickCertificate(kind: 'author' | 'distributor') {
    await run(async () => {
      const file = await bridge.pickFile('certificate');
      if (!file) return;
      if (!/\.(p12|pfx)$/i.test(file.name))
        throw new Error('Select a .p12 or .pfx certificate.');
      (kind === 'author' ? setAuthor : setDistributor)(file);
    });
  }
  return (
    <SafeAreaView style={s.safe} edges={['top', 'left', 'right', 'bottom']}>
      <StatusBar barStyle="light-content" />
      <View style={s.header}>
        <View style={s.brandIcon}>
          <Text style={s.brandGlyph}>↗</Text>
        </View>
        <View style={s.grow}>
          <Text style={s.brand}>Tizen Pocket</Text>
        </View>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="TV connection"
          onPress={() => setTab('tv')}
          style={s.statusPill}
        >
          <View style={[s.dot, tv && s.dotOn]} />
          <Text style={s.statusText}>{tv ? 'Connected' : 'Connect TV'}</Text>
        </Pressable>
      </View>
      <ScrollView
        contentContainerStyle={s.content}
        keyboardShouldPersistTaps="handled"
      >
        {!supported && (
          <View style={s.error}>
            <Text style={s.body}>
              Install the Android APK to connect to a TV.
            </Text>
          </View>
        )}
        {!!error && (
          <View accessibilityRole="alert" style={s.error}>
            <Text style={s.errorTitle}>Couldn’t complete that</Text>
            <Text selectable style={s.body}>
              {error}
            </Text>
          </View>
        )}
        {!!notice && (
          <View accessibilityRole="alert" style={s.success}>
            <Text style={s.successText}>{notice}</Text>
          </View>
        )}
        {busy && (
          <View style={s.panel}>
            <View style={s.row}>
              <ActivityIndicator color={c.accent} />
              <Text style={[s.body, s.grow]}>
                {progress?.message || 'Working…'}
              </Text>
            </View>
            {progress?.percent !== undefined && (
              <>
                <View style={s.track}>
                  <View
                    style={[
                      s.fill,
                      {width: (progress.percent + '%') as '100%'},
                    ]}
                  />
                </View>
                <Text style={s.small}>{Math.round(progress.percent)}%</Text>
              </>
            )}
            {progress?.stage && (
              <Button
                label="Cancel operation"
                secondary
                onPress={() => {
                  cancelled.current = true;
                  bridge.cancel().catch(() => {});
                  setProgress({
                    stage: 'cancelling',
                    message:
                      'Cancelling… the TV may finish an installation already started.',
                  });
                }}
              />
            )}
          </View>
        )}
        {tab === 'apps' && (
          <>
            <Text style={s.title}>Your apps.</Text>
            <Text style={s.intro}>Install and update on your Samsung TV.</Text>
            <Pressable
              accessibilityRole="button"
              onPress={() => setTab('tv')}
              style={s.deviceCard}
            >
              <Text style={s.tvGlyph}>▣</Text>
              <View style={s.grow}>
                <Text style={s.cardTitle}>
                  {tv?.name || 'Start with your TV'}
                </Text>
                <Text style={s.small}>
                  {tv
                    ? tv.host + ' · Tizen ' + tv.version
                    : 'Same Wi-Fi. Developer Mode enabled.'}
                </Text>
              </View>
              <Text style={s.chevron}>›</Text>
            </Pressable>
            {tv && (
              <View style={s.panel}>
                <View style={s.sectionRow}>
                  <Text style={s.cardTitle}>Updates on your TV</Text>
                  <Text style={s.small}>
                    {checkedAt
                      ? Object.values(updates).filter(
                          item =>
                            item.state === 'update' ||
                            item.state === 'different-build',
                        ).length + ' available'
                      : 'Not checked'}
                  </Text>
                </View>
                <Text style={s.small}>
                  {checkedAt
                    ? 'Checked at ' + checkedAt
                    : 'Compare installed apps with the latest releases.'}
                </Text>
                <Button
                  label="Check for updates"
                  secondary
                  disabled={busy}
                  onPress={() => run(async () => refreshUpdates(tv))}
                />
              </View>
            )}
            {review ? (
              <View style={s.panel}>
                <Text style={s.eyebrow}>READY TO INSTALL</Text>
                <Text style={s.sectionTitle}>{review.name}</Text>
                <Text selectable style={s.small}>
                  {review.appId}
                  {'\n'}Version {review.version || 'not specified'} ·{' '}
                  {formatSize(review.size)} · {review.kind.toUpperCase()}
                </Text>
                <View style={s.divider} />
                <Text style={s.cardTitle}>Keep your TV settings</Text>
                <Text style={s.body}>
                  Use the same app variant and author certificate to keep your
                  settings. Updates never uninstall the app.
                </Text>
                <View style={s.row}>
                  <View style={s.grow}>
                    <Text style={s.cardTitle}>Samsung signing</Text>
                    <Text style={s.small}>
                      {resign
                        ? 'Reuse your saved profile'
                        : 'Use existing package signatures'}
                    </Text>
                  </View>
                  <Switch
                    accessibilityLabel="Use Samsung signing"
                    value={resign}
                    disabled={busy}
                    onValueChange={setResign}
                    trackColor={{false: c.line, true: '#226F61'}}
                    thumbColor={resign ? c.accent : c.muted}
                  />
                </View>
                {resign && tv && !tv.hasCertificates && (
                  <Button
                    label="Set up signing certificates"
                    secondary
                    disabled={busy}
                    onPress={() => setTab('settings')}
                  />
                )}
                <Button
                  label={
                    tv ? 'Install / update in place' : 'Connect a TV first'
                  }
                  disabled={
                    busy ||
                    !supported ||
                    (!!tv && resign && !tv.hasCertificates)
                  }
                  onPress={() => {
                    if (!tv) {
                      setTab('tv');
                      return;
                    }
                    run(async () => {
                      const result = await bridge.install(
                        tv.host,
                        review.token,
                        review.catalogId,
                        resign,
                      );
                      await bridge.discardPackage(review.token);
                      setReview(null);
                      setNotice(
                        (result.updated ? 'Updated ' : 'Installed ') +
                          review.name +
                          ' on your TV.',
                      );
                      try {
                        await refreshUpdates(tv);
                      } catch (failure) {
                        setError(
                          'Installation completed, but the update check could not finish: ' +
                            (failure instanceof Error
                              ? failure.message
                              : String(failure)),
                        );
                      }
                    });
                  }}
                />
                <Button
                  label="Choose another package"
                  secondary
                  disabled={busy}
                  onPress={() =>
                    run(async () => {
                      await bridge.discardPackage(review.token);
                      setReview(null);
                    })
                  }
                />
              </View>
            ) : details ? (
              <View style={s.panel}>
                <Text style={s.eyebrow}>LATEST UPSTREAM RELEASE</Text>
                <Text style={s.sectionTitle}>{details.app.name}</Text>
                <Text style={s.body}>{details.app.description}</Text>
                <Text style={s.small}>
                  {details.release.tag_name} · {details.app.repository}
                </Text>
                {updates[details.app.id] && (
                  <View style={s.ipBox}>
                    <Text style={s.ready}>
                      {updates[details.app.id]?.label}
                    </Text>
                    <Text style={s.small}>
                      On TV:{' '}
                      {updates[details.app.id]?.installed?.version ||
                        'not reported'}{' '}
                      · Available:{' '}
                      {updates[details.app.id]?.available?.version || 'unknown'}
                    </Text>
                    {!!updates[details.app.id]?.message && (
                      <Text style={s.small}>
                        {updates[details.app.id]?.message}
                      </Text>
                    )}
                  </View>
                )}
                <Text style={s.label}>Choose a package variant</Text>
                {installableAssets(details.release, details.app).map(item => (
                  <Pressable
                    key={item.name}
                    accessibilityRole="radio"
                    accessibilityState={{selected: asset?.name === item.name}}
                    disabled={busy}
                    onPress={() => setAsset(item)}
                    style={[
                      s.asset,
                      asset?.name === item.name && s.selectedAsset,
                    ]}
                  >
                    <Text style={s.radio}>
                      {asset?.name === item.name ? '●' : '○'}
                    </Text>
                    <View style={s.grow}>
                      <Text style={s.assetName}>{item.name}</Text>
                      <Text style={s.small}>{formatSize(item.size)}</Text>
                    </View>
                  </Pressable>
                ))}
                <Button
                  label="Download package"
                  disabled={busy || !asset || !supported}
                  onPress={() =>
                    run(async () => {
                      if (asset)
                        await prepare(
                          asset.browser_download_url,
                          asset.name,
                          asset.digest,
                          details.app.id,
                        );
                    })
                  }
                />
                <Button
                  label="View release on GitHub"
                  secondary
                  onPress={() => openUrl(details.release.html_url)}
                />
                <Button
                  label="Back to apps"
                  secondary
                  disabled={busy}
                  onPress={() => setDetails(null)}
                />
              </View>
            ) : (
              <>
                <View style={s.sectionRow}>
                  <Text style={s.sectionTitle}>The essentials</Text>
                  <Text style={s.small}>3 APPS</Text>
                </View>
                {catalog.map(app => (
                  <Pressable
                    key={app.id}
                    accessibilityRole="button"
                    accessibilityLabel={'Install or update ' + app.name}
                    disabled={busy || !supported}
                    onPress={() =>
                      run(async () => {
                        const release = await latestRelease(app);
                        setDetails({app, release});
                        setAsset(
                          installableAssets(release, app).find(
                            item => item.name === updates[app.id]?.asset?.name,
                          ) || installableAssets(release, app)[0],
                        );
                      })
                    }
                    style={({pressed}) => [
                      s.appCard,
                      pressed && s.pressed,
                      busy && s.disabled,
                    ]}
                  >
                    <View
                      style={[s.appIcon, {backgroundColor: app.color + '18'}]}
                    >
                      <Text style={[s.appGlyph, {color: app.color}]}>
                        {app.glyph}
                      </Text>
                    </View>
                    <View style={s.grow}>
                      <Text style={s.cardTitle}>{app.name}</Text>
                      <Text style={s.small}>{app.summary}</Text>
                      {tv && (
                        <View>
                          <Text
                            style={[
                              s.appAction,
                              {
                                color:
                                  updates[app.id]?.state === 'update' ||
                                  updates[app.id]?.state === 'different-build'
                                    ? c.accent
                                    : c.muted,
                              },
                            ]}
                          >
                            {updates[app.id]?.label || 'Not checked yet'}
                          </Text>
                          <Text style={s.small}>
                            On TV:{' '}
                            {updates[app.id]?.installed?.version ||
                              (updates[app.id]?.state === 'not-installed'
                                ? '—'
                                : 'unknown')}{' '}
                            · Latest:{' '}
                            {updates[app.id]?.available?.version || '—'}
                          </Text>
                        </View>
                      )}
                      <Text style={[s.appAction, {color: app.color}]}>
                        Install or update ↗
                      </Text>
                    </View>
                  </Pressable>
                ))}
                <View style={s.customCard}>
                  <Text style={s.eyebrow}>BEYOND THE CATALOG</Text>
                  <Text style={s.sectionTitle}>Got your own app?</Text>
                  <Text style={s.body}>
                    Choose a .wgt or .tpk from your phone, or use an HTTPS link.
                  </Text>
                  <Button
                    label="Choose a package file"
                    secondary
                    disabled={busy || !supported}
                    onPress={() =>
                      run(async () => {
                        const file = await bridge.pickFile('package');
                        if (file)
                          await prepare(file.uri, file.name, null, null);
                      })
                    }
                  />
                  <Button
                    label={
                      showUrl ? 'Hide download link' : 'Use a download link'
                    }
                    secondary
                    disabled={busy}
                    onPress={() => setShowUrl(!showUrl)}
                  />
                  {showUrl && (
                    <>
                      <Field
                        label="HTTPS package URL"
                        value={packageUrl}
                        onChange={setPackageUrl}
                        disabled={busy}
                        placeholder="https://example.com/app.wgt"
                      />
                      <Field
                        label="Package filename"
                        value={packageName}
                        onChange={setPackageName}
                        disabled={busy}
                      />
                      <Button
                        label="Download custom package"
                        disabled={busy || !supported || !packageUrl.trim()}
                        onPress={() =>
                          run(async () =>
                            prepare(
                              packageUrl.trim(),
                              packageName.trim(),
                              null,
                              null,
                            ),
                          )
                        }
                      />
                    </>
                  )}
                </View>
              </>
            )}
          </>
        )}
        {tab === 'tv' && (
          <>
            <Text style={s.title}>Your TV.</Text>
            <View style={s.panel}>
              <View style={s.ipBox}>
                <Text style={s.label}>Phone Wi-Fi IP</Text>
                <Text selectable style={s.ip}>
                  {ips[0] || 'Connect to Wi-Fi'}
                </Text>
              </View>
              <Button
                label="Scan for TVs"
                disabled={busy || !supported}
                onPress={() =>
                  run(async () => {
                    setDevices(await bridge.discover());
                    setScanned(true);
                  })
                }
              />
              {devices.map(device => (
                <View key={device.host} style={s.field}>
                  <Button
                    label={device.name + ' · ' + device.host}
                    secondary
                    disabled={busy}
                    onPress={() => connect(device.host)}
                  />
                  {device.developerMode === false && (
                    <Text style={s.small}>Developer Mode is off.</Text>
                  )}
                  {device.developerHost &&
                    !ips.includes(device.developerHost) && (
                      <Text style={s.small}>
                        TV host IP: {device.developerHost}. Set it to{' '}
                        {ips[0] || 'this phone’s Wi-Fi IP'}.
                      </Text>
                    )}
                </View>
              ))}
              {scanned && !devices.length && (
                <Text style={s.small}>
                  No TVs found. Enter the TV’s IP below.
                </Text>
              )}
              <View style={s.divider} />
              <Field
                label="TV IP address"
                value={host}
                onChange={setHost}
                disabled={busy}
                placeholder="192.168.1.50"
              />
              <Button
                label="Connect to TV"
                disabled={busy || !supported || !host.trim()}
                onPress={() => connect(host)}
              />
            </View>
            <View style={s.panel}>
              <Details title="TV setup">
                <Text style={s.body}>
                  1. TV Apps → enter 12345.{'\n'}2. Developer Mode on → host IP{' '}
                  {ips[0] || 'shown above'}.{'\n'}3. Restart the TV.
                </Text>
                <Button
                  label="Refresh phone IP"
                  secondary
                  disabled={busy || !supported}
                  onPress={() =>
                    run(async () =>
                      setIps((await bridge.networkInfo()).map(item => item.ip)),
                    )
                  }
                />
                <Button
                  label="Samsung setup guide ↗"
                  secondary
                  onPress={() =>
                    openUrl(
                      'https://developer.samsung.com/smarttv/develop/getting-started/using-sdk/tv-device.html',
                    )
                  }
                />
              </Details>
            </View>
            {tv && (
              <View style={s.panel}>
                <Text style={s.cardTitle}>{tv.name}</Text>
                <Text style={s.small}>
                  {tv.host} · Tizen {tv.version}
                </Text>
                <Button
                  label="Show installed apps"
                  secondary
                  disabled={busy}
                  onPress={() =>
                    run(async () =>
                      setAppList(await bridge.installedApps(tv.host)),
                    )
                  }
                />
                {!!appList && (
                  <Text selectable style={s.mono}>
                    {appList}
                  </Text>
                )}
                <Details title="Device details">
                  <Text selectable style={s.small}>
                    {tv.model}
                    {'\n'}DUID: {tv.duid}
                  </Text>
                </Details>
                <Button
                  label="Disconnect"
                  secondary
                  disabled={busy}
                  onPress={() => {
                    setTv(null);
                    setAppList('');
                  }}
                />
              </View>
            )}
          </>
        )}
        {tab === 'settings' && (
          <>
            <Text style={s.title}>Settings.</Text>
            <Text style={s.intro}>
              Keep the same signing keys for future updates.
            </Text>
            <View style={s.panel}>
              <View style={s.sectionRow}>
                <Text style={s.sectionTitle}>Samsung signing</Text>
                <Text style={tv?.hasCertificates ? s.ready : s.small}>
                  {tv?.hasCertificates ? 'READY' : 'SETUP NEEDED'}
                </Text>
              </View>
              <Text style={s.body}>
                Sign in for new installs. Import original keys for existing
                apps.
              </Text>
              <Button
                label={
                  tv?.hasCertificates
                    ? 'Renew distributor certificate'
                    : 'Sign in to Samsung'
                }
                disabled={busy || !tv}
                onPress={() =>
                  run(async () => {
                    if (!tv) return;
                    await bridge.createCertificates(tv.host);
                    setTv(await bridge.connect(tv.host));
                    setNotice('Signing profile saved securely.');
                  })
                }
              />
              {!tv && (
                <Button
                  label="Connect a TV first"
                  secondary
                  onPress={() => setTab('tv')}
                />
              )}
            </View>
            <View style={s.panel}>
              <Details title="Import existing certificates">
                <Text style={s.body}>
                  Choose the author.p12 and distributor.p12 used to install this
                  TV app.
                </Text>
                <Details title="Find CLI certificates">
                  <Text selectable style={s.small}>
                    ~/.local/share/TizenAppInstallerCli/CertificateCache/{'\n'}
                    Copy the matching TV’s .p12 files to your phone. Leave
                    passwords empty for CLI cached files.
                  </Text>
                </Details>
                <Button
                  label={
                    author ? 'Author: ' + author.name : 'Choose author.p12'
                  }
                  secondary
                  disabled={busy}
                  onPress={() => pickCertificate('author')}
                />
                <Field
                  label="Author certificate password"
                  value={authorPassword}
                  onChange={setAuthorPassword}
                  password
                  disabled={busy}
                  placeholder="Empty for CLI cached certificates"
                />
                <Button
                  label={
                    distributor
                      ? 'Distributor: ' + distributor.name
                      : 'Choose distributor.p12'
                  }
                  secondary
                  disabled={busy}
                  onPress={() => pickCertificate('distributor')}
                />
                <Field
                  label="Distributor certificate password"
                  value={distributorPassword}
                  onChange={setDistributorPassword}
                  password
                  disabled={busy}
                  placeholder="Empty for CLI cached certificates"
                />
                <Button
                  label="Import original certificates"
                  disabled={busy || !tv || !author || !distributor}
                  onPress={() =>
                    run(async () => {
                      if (!tv || !author || !distributor) return;
                      try {
                        await bridge.importCertificates(
                          tv.host,
                          author.uri,
                          authorPassword,
                          distributor.uri,
                          distributorPassword,
                        );
                        setTv(await bridge.connect(tv.host));
                        setAuthor(null);
                        setDistributor(null);
                        setNotice('Original signing profile imported.');
                      } finally {
                        setAuthorPassword('');
                        setDistributorPassword('');
                      }
                    })
                  }
                />
                <Text style={s.small}>Keys are encrypted on this phone.</Text>
              </Details>
            </View>
            <View style={s.panel}>
              <Details title="Back up certificates">
                <Text style={s.body}>
                  Save your keys before changing phones. Restore by importing
                  both .p12 files with this password.
                </Text>
                <Field
                  label="Backup password"
                  value={backupPassword}
                  onChange={setBackupPassword}
                  password
                  disabled={busy}
                  placeholder="At least 12 characters"
                />
                <Button
                  label="Export certificate backup"
                  secondary
                  disabled={
                    busy || !tv?.hasCertificates || backupPassword.length < 12
                  }
                  onPress={() =>
                    run(async () => {
                      if (!tv?.duid) return;
                      try {
                        const saved = await bridge.exportCertificates(
                          tv.duid,
                          backupPassword,
                        );
                        setNotice(
                          saved
                            ? 'Password-protected certificates exported. Keep the file and password somewhere safe.'
                            : 'Export cancelled.',
                        );
                      } finally {
                        setBackupPassword('');
                      }
                    })
                  }
                />
              </Details>
            </View>
            <View style={s.panel}>
              <Details title="Activity">
                <Text selectable style={s.mono}>
                  {log.length
                    ? log.join('\n\n')
                    : 'Installation activity will appear here.'}
                </Text>
                {!!log.length && (
                  <Button
                    label="Clear activity"
                    secondary
                    disabled={busy}
                    onPress={() => setLog([])}
                  />
                )}
              </Details>
            </View>
            <Text style={s.footer}>Tizen Pocket</Text>
          </>
        )}
      </ScrollView>
      <View style={s.tabs}>
        {(['apps', 'tv', 'settings'] as Tab[]).map(item => (
          <Pressable
            key={item}
            accessibilityRole="tab"
            accessibilityState={{selected: tab === item}}
            onPress={() => setTab(item)}
            style={s.tab}
          >
            <Text style={[s.tabIcon, tab === item && s.activeText]}>
              {item === 'apps' ? '▦' : item === 'tv' ? '▣' : '⚙'}
            </Text>
            <Text style={[s.tabLabel, tab === item && s.activeText]}>
              {item === 'apps'
                ? 'Apps'
                : item === 'tv'
                ? 'Your TV'
                : 'Settings'}
            </Text>
          </Pressable>
        ))}
      </View>
    </SafeAreaView>
  );
}
export default function App() {
  return (
    <SafeAreaProvider>
      <Pocket />
    </SafeAreaProvider>
  );
}
const s = StyleSheet.create({
  disclosure: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    minHeight: 44,
    gap: 12,
  },
  safe: {flex: 1, backgroundColor: c.bg},
  grow: {flex: 1},
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingHorizontal: 22,
    paddingVertical: 18,
    borderBottomWidth: 1,
    borderColor: c.line,
  },
  brandIcon: {
    width: 38,
    height: 38,
    borderRadius: 12,
    backgroundColor: c.accent,
    alignItems: 'center',
    justifyContent: 'center',
  },
  brandGlyph: {fontSize: 27, color: c.bg, fontWeight: '800'},
  brand: {
    fontSize: 20,
    color: c.text,
    fontWeight: '700',
    letterSpacing: -0.5,
  },
  eyebrow: {
    color: c.muted,
    fontSize: 10,
    fontWeight: '700',
    letterSpacing: 1.7,
    lineHeight: 18,
  },
  statusPill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    backgroundColor: c.panel,
    padding: 10,
    borderRadius: 20,
  },
  dot: {width: 6, height: 6, borderRadius: 3, backgroundColor: c.muted},
  dotOn: {backgroundColor: c.accent},
  statusText: {color: c.text, fontSize: 10, fontWeight: '600'},
  content: {padding: 22, paddingBottom: 36, gap: 18},
  title: {
    fontSize: 32,
    lineHeight: 38,
    color: c.text,
    fontWeight: '700',
    letterSpacing: -1.5,
  },
  intro: {fontSize: 16, lineHeight: 24, color: c.muted, marginBottom: 4},
  body: {fontSize: 14, lineHeight: 22, color: c.text},
  small: {fontSize: 12, lineHeight: 19, color: c.muted},
  panel: {
    backgroundColor: c.panel,
    padding: 20,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: c.line,
    gap: 15,
  },
  deviceCard: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#102A2A',
    borderWidth: 1,
    borderColor: '#23534C',
    padding: 16,
    borderRadius: 16,
    gap: 14,
  },
  tvGlyph: {fontSize: 31, color: c.accent},
  cardTitle: {fontSize: 17, lineHeight: 24, color: c.text, fontWeight: '600'},
  chevron: {fontSize: 27, color: c.accent},
  sectionRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
  },
  sectionTitle: {
    color: c.text,
    fontSize: 21,
    lineHeight: 28,
    fontWeight: '600',
    letterSpacing: -0.4,
  },
  appCard: {
    flexDirection: 'row',
    gap: 18,
    padding: 20,
    borderRadius: 20,
    backgroundColor: c.panel,
    borderColor: c.line,
    borderWidth: 1,
    alignItems: 'center',
  },
  appIcon: {
    width: 58,
    height: 64,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
  },
  appGlyph: {fontSize: 43, fontWeight: '500'},
  appAction: {fontSize: 12, fontWeight: '600', marginTop: 9},
  customCard: {
    padding: 21,
    borderRadius: 20,
    borderWidth: 1,
    borderStyle: 'dashed',
    borderColor: '#3A4D66',
    gap: 12,
    marginTop: 5,
  },
  button: {
    minHeight: 48,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 16,
    paddingVertical: 13,
    borderRadius: 12,
    backgroundColor: c.accent,
  },
  secondary: {
    backgroundColor: '#1D2A3C',
    borderWidth: 1,
    borderColor: '#30435A',
  },
  buttonText: {
    color: '#0C2722',
    fontSize: 14,
    fontWeight: '700',
    textAlign: 'center',
  },
  secondaryText: {color: c.text},
  disabled: {opacity: 0.45},
  pressed: {opacity: 0.75},
  field: {gap: 8},
  label: {fontSize: 12, color: c.muted, fontWeight: '600'},
  input: {
    color: c.text,
    backgroundColor: c.bg,
    borderColor: c.line,
    borderWidth: 1,
    borderRadius: 12,
    padding: 14,
    fontSize: 15,
    minHeight: 48,
  },
  ipBox: {padding: 16, borderRadius: 12, backgroundColor: c.bg, gap: 6},
  ip: {fontSize: 22, color: c.accent, fontWeight: '600'},
  divider: {height: 1, backgroundColor: c.line, marginVertical: 3},
  row: {flexDirection: 'row', alignItems: 'center', gap: 12},
  error: {
    padding: 16,
    borderRadius: 14,
    backgroundColor: '#351D26',
    borderWidth: 1,
    borderColor: '#734052',
    gap: 6,
  },
  errorTitle: {color: '#FFAFBA', fontSize: 15, fontWeight: '700'},
  success: {
    padding: 16,
    borderRadius: 14,
    backgroundColor: '#102C26',
    borderWidth: 1,
    borderColor: '#23594C',
  },
  successText: {color: c.accent, fontSize: 14, lineHeight: 21},
  track: {
    height: 5,
    backgroundColor: c.line,
    borderRadius: 3,
    overflow: 'hidden',
  },
  fill: {height: 5, backgroundColor: c.accent},
  asset: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    padding: 14,
    borderWidth: 1,
    borderColor: c.line,
    borderRadius: 12,
  },
  selectedAsset: {borderColor: c.accent, backgroundColor: '#122F2C'},
  assetName: {fontSize: 13, color: c.text},
  radio: {color: c.accent, fontSize: 20},
  ready: {color: c.accent, fontSize: 10, fontWeight: '700', letterSpacing: 1},
  mono: {
    fontFamily: 'monospace',
    fontSize: 12,
    lineHeight: 20,
    color: c.muted,
  },
  footer: {textAlign: 'center', color: c.muted, fontSize: 11, lineHeight: 20},
  tabs: {
    flexDirection: 'row',
    backgroundColor: c.bg,
    borderTopWidth: 1,
    borderColor: c.line,
    paddingTop: 9,
    paddingBottom: 6,
  },
  tab: {
    flex: 1,
    minHeight: 56,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 3,
  },
  tabIcon: {fontSize: 23, color: c.muted},
  tabLabel: {fontSize: 11, color: c.muted, fontWeight: '600'},
  activeText: {color: c.accent},
});
