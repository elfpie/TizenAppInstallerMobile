# Connection fix validation — 2026-10-13

The first Android preview rejected valid Samsung close frames, so discovery silently omitted TVs and manual connection returned “Invalid SDB channel ID.”

Read-only packet capture on the user's Samsung QN55QNX1DAGXZD (192.168.15.5), after temporarily authorizing the development computer, confirmed:

- Tizen 9.0, SDB daemon 2.2.31.
- Service OPEN receives OKAY with an allocated remote ID, then WRTE using that ID.
- The TV ends capabilities, DUID and app-list services with `CLSE(0, localId)`.
- The client now accepts zero as the remote ID only for CLSE, while rejecting unrelated data/ack IDs.
- Live checks successfully read capabilities, validated the DUID, and parsed 22 installed apps over repeated requests. Jellyfin reported 0.1.0; TizenBrewNextGeneration reported 2.0.5.
- Installed Jellyfin signature files were not readable through the tested SDB paths. The update screen must retain its “Build version unknown” fallback for that TV.

Discovery now tries Samsung's port-8001 metadata endpoint before SDB. This can identify a TV even when Developer Mode is off or authorizes another device. That endpoint was initially reachable on this TV but unavailable after its reboot; the SDB fallback therefore remains necessary.

Android selects physical Wi-Fi/Ethernet network addresses, excludes VPN addresses, and binds LAN requests to the matching network without changing the app's global network. The TV screen prioritizes phone IP, scan and manual connection. Setup, certificate import help, backups and logs use expandable sections.

Validation: TypeScript, ESLint, 18 Jest tests, 16 native regression tests and a read-only live SDB check. Regression coverage includes zero-ID closes, delayed closes from a prior service, strict IDs for data frames, and HTTP TV identification. Live installation, Samsung sign-in and Android VPN routing require phone/end-to-end verification.

Restore the TV's Developer Mode host IP to the phone (192.168.15.3) and restart it before testing the new Android APK.
