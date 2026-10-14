# Private diagnostics

The collector accepts structured operation stages, HTTP status codes and exception class names. It rejects account data, credentials, URLs, free-form messages and unknown fields. Records expire after seven days and storage is capped at 20,000 events. Delivery from Android is best effort and never blocks an operation.

A private deployment can use:

- `/opt/tizen-pocket-diagnostics/collector.py`
- `/etc/systemd/system/tizen-pocket-diagnostics.service`
- `/etc/tizen-pocket-diagnostics/keys.env` (root-only, distinct `DIAGNOSTIC_WRITE_KEY` and `DIAGNOSTIC_READ_KEY`)
- `/var/lib/tizen-pocket-diagnostics/events.sqlite3` (systemd private state directory)

The unprivileged systemd service listens on `127.0.0.1:8794`. Tailscale Serve can expose HTTPS at `https://YOUR-HOST.YOUR-TAILNET.ts.net:8443` only inside your tailnet. Keep Funnel disabled. `GET /health` checks availability; `POST /v1/events` requires the write bearer key, and `GET /v1/events?limit=100` requires the separate read bearer key. Do not put the read key in the APK or GitHub.

Public GitHub builds explicitly leave diagnostics unconfigured. To make a private diagnostic APK locally, provide `ANDROID_DIAGNOSTICS_URL` and `ANDROID_DIAGNOSTICS_WRITE_KEY` through the build environment and run `npm run apk`. Keep that APK private. The configured app has a **Settings → Send diagnostics** switch. The write key is included in the configured APK and can be extracted, so it must remain write-only and the endpoint must remain restricted to the private tailnet. All APKs using it can submit records. Never upload a configured APK, its generated BuildConfig, or its build logs to public releases or artifacts.

To query records over SSH without exposing keys in command arguments:

```python
from pathlib import Path
import urllib.request

keys = dict(line.split('=', 1) for line in
            Path('/etc/tizen-pocket-diagnostics/keys.env').read_text().splitlines())
request = urllib.request.Request('http://127.0.0.1:8794/v1/events?limit=100',
    headers={'Authorization': 'Bearer ' + keys['DIAGNOSTIC_READ_KEY']})
print(urllib.request.urlopen(request).read().decode())
```

Run that script as root on your collector host. Check service health with `systemctl status tizen-pocket-diagnostics`. Stop collection with `systemctl disable --now tizen-pocket-diagnostics`; remove this dedicated HTTPS endpoint with `tailscale serve --https=8443 off`. Other Tailscale Serve endpoints are independent.

Run collector tests locally with `python3 -m unittest discover -s diagnostics -v`.
