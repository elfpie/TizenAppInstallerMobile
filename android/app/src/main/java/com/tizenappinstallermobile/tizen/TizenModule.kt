package com.tizenappinstallermobile.tizen

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.provider.OpenableColumns
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.uimanager.ViewManager
import java.io.Closeable
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class TizenPackage : ReactPackage {
    override fun createNativeModules(context: ReactApplicationContext): List<NativeModule> = listOf(TizenModule(context))
    override fun createViewManagers(context: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}

class TizenModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val active = AtomicReference<Job?>(null)
    private val picker = AtomicReference<Promise?>(null)
    private val exportPicker = AtomicReference<java.util.concurrent.CompletableFuture<Uri?>?>(null)
    private val store = CertificateStore(context)
    private val preferences = context.getSharedPreferences("tizen-pocket", 0)
    private val diagnostics = Diagnostics(context)
    private var lastStage = ""
    private val http = OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).callTimeout(180, java.util.concurrent.TimeUnit.SECONDS).build()

    private class Job {
        val cancelled = AtomicBoolean(false)
        val resources = CopyOnWriteArrayList<Closeable>()
        fun check() { check(!cancelled.get()) { "Operation cancelled. The TV may finish an installation already started." } }
        fun register(resource: Closeable) {
            resources.add(resource)
            if (cancelled.get()) resource.close()
        }
        fun close() { resources.forEach { runCatching { it.close() } }; resources.clear() }
    }

    init {
        context.addActivityEventListener(object : BaseActivityEventListener() {
            override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
                if (requestCode == 8032) {
                    exportPicker.getAndSet(null)?.complete(if (resultCode == Activity.RESULT_OK) data?.data else null)
                    return
                }
                if (requestCode != 8031) return
                val promise = picker.getAndSet(null) ?: return
                val uri = data?.data
                if (resultCode != Activity.RESULT_OK || uri == null) { promise.resolve(null); return }
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else "package"
                } ?: "package"
                // Keep URI permission only for the current session. Import copies to encrypted/private storage.
                promise.resolve(Arguments.createMap().apply { putString("uri", uri.toString()); putString("name", name) })
            }
        })
    }

    override fun getName() = "TizenBridge"
    @ReactMethod fun addListener(eventName: String) = Unit
    @ReactMethod fun removeListeners(count: Int) = Unit

    private fun status(stage: String, message: String, percent: Double? = null) {
        if (stage != lastStage) { lastStage = stage; diagnostics.event("app.stage", mapOf("stage" to stage)) }
        if (!context.hasActiveReactInstance()) return
        val event = Arguments.createMap().apply {
            putString("stage", stage); putString("message", message)
            if (percent != null) putDouble("percent", percent.coerceIn(0.0, 100.0))
        }
        context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit("TizenProgress", event)
    }

    private fun job(promise: Promise, keepAlive: Boolean = false, action: (Job) -> Any?) {
        val job = Job()
        if (!active.compareAndSet(null, job)) { promise.reject("BUSY", "Another TV operation is running."); return }
        executor.execute {
            val power = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
            val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TizenPocket:operation")
            var foreground = false
            fun finish() {
                job.close()
                if (wake.isHeld) wake.release()
                if (foreground) { OperationService.stop(context); foreground = false }
                active.compareAndSet(job, null)
            }
            try {
                if (keepAlive) { OperationService.start(context); foreground = true }
                wake.acquire(10 * 60 * 1000L)
                val result = action(job)
                finish()
                promise.resolve(result)
            } catch (error: Exception) {
                diagnostics.event("app.operation.failed", mapOf("reason" to if (job.cancelled.get()) "cancelled" else "unexpected_error", "exception_class" to error.javaClass.name.replace('$', '_')))
                finish()
                promise.reject(if (job.cancelled.get()) "CANCELLED" else "TIZEN_ERROR", error.message ?: "TV operation failed.", error)
            } finally {
                finish()
            }
        }
    }

    private fun client(host: String, job: Job, timeout: Int = 5000): SdbClient {
        require(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}").matches(host) && host.split('.').all { it.toInt() in 0..255 }) {
            "Enter the TV's IPv4 address, for example 192.168.1.50."
        }
        // Respect Android's default routing, including the VPN's LAN policy. A VPN
        // may forbid bypassing it with Network.socketFactory (EPERM).
        return SdbClient(host, connectTimeout = timeout, register = job::register, checkCancelled = job::check)
    }

    private data class LocalAddress(val address: java.net.InetAddress, val networkPrefixLength: Short)
    private fun localAddresses(): List<LocalAddress> {
        val manager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val addresses = manager.allNetworks.flatMap { network ->
            val capabilities = manager.getNetworkCapabilities(network)
            if (capabilities == null || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)))
                emptyList()
            else manager.getLinkProperties(network)?.linkAddresses.orEmpty().filter {
                it.address is Inet4Address && it.address.isSiteLocalAddress && it.prefixLength in 1..30
            }.map { LocalAddress(it.address, it.prefixLength.toShort()) }
        }
        if (addresses.isNotEmpty()) return addresses.distinctBy { it.address.hostAddress }
        return NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual &&
                !Regex("^(tun|tap|ppp|wg|vpn|ipsec|tailscale)", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
            .flatMap { it.interfaceAddresses.asSequence() }
            .filter { it.address is Inet4Address && it.address.isSiteLocalAddress && it.networkPrefixLength in 1..30 }
            .map { LocalAddress(it.address, it.networkPrefixLength) }.toList()
    }
    private fun discoveryHttp(): OkHttpClient =
        http.newBuilder().connectTimeout(650, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(1200, java.util.concurrent.TimeUnit.MILLISECONDS).callTimeout(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build()

    @ReactMethod fun networkInfo(promise: Promise) {
        try {
            promise.resolve(Arguments.createArray().apply { localAddresses().forEach { address ->
                pushMap(Arguments.createMap().apply {
                    putString("ip", address.address.hostAddress); putInt("prefix", address.networkPrefixLength.toInt())
                })
            } })
        } catch (error: Exception) { promise.reject("NETWORK", error) }
    }

    @ReactMethod fun discover(promise: Promise) = job(promise) { job ->
        val addresses = localAddresses().filter { it.address.isSiteLocalAddress }
        val candidates = linkedSetOf<String>()
        for (address in addresses) {
            // Bound broad networks to the phone's /24. Smaller networks use their actual subnet.
            val prefix = address.networkPrefixLength.toInt().coerceIn(24, 30)
            val octets = address.address.address
            val value = octets.fold(0L) { sum, byte -> (sum shl 8) or (byte.toInt() and 255).toLong() }
            val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
            val start = value and mask
            val count = 1 shl (32 - prefix)
            for (offset in 1 until count - 1) {
                val ip = start + offset
                if (ip != value && candidates.size < 512) candidates.add((3 downTo 0).joinToString(".") { ((ip shr (it * 8)) and 255).toString() })
            }
        }
        check(candidates.isNotEmpty()) { "No local network found. Connect your phone to the same Wi-Fi as the TV, or enter its IP manually." }
        status("discover", "Scanning ${candidates.size} local addresses")
        val pool = Executors.newFixedThreadPool(24)
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        try {
            val tasks = candidates.map { host -> pool.submit<WritableMap?> {
                try {
                    job.check()
                    val tv = runCatching { SamsungTvInfo.probe(host, discoveryHttp(), job::register) }.getOrNull()
                    job.check()
                    if (tv != null) tvMap(host, "", emptyMap(), null, false, tv)
                    else client(host, job, 650).use { sdb -> tvMap(host, sdb.banner, sdb.capabilities(), null, false) }
                } catch (_: Exception) { null }
                finally {
                    val count = completed.incrementAndGet()
                    if (count % 16 == 0) status("discover", "Scanning local network", count.toDouble() / candidates.size * 100)
                }
            } }
            Arguments.createArray().apply { for (task in tasks) { job.check(); task.get()?.let { pushMap(it) } } }
        } finally { pool.shutdownNow() }
    }

    private fun tvMap(host: String, banner: String, capabilities: Map<String, String>, duid: String?, signed: Boolean, tv: SamsungTvInfo? = null): WritableMap =
        Arguments.createMap().apply {
            putString("host", host)
            putString("name", tv?.name ?: banner.substringAfter("::", banner).substringBefore(';').ifBlank { "Samsung TV" })
            putString("version", capabilities["platform_version"] ?: "Unknown")
            putString("duid", duid)
            putBoolean("hasCertificates", signed)
            putString("model", tv?.model)
            tv?.developerMode?.let { putBoolean("developerMode", it) }
            putString("developerHost", tv?.developerHost)
        }

    private fun duid(sdb: SdbClient): String = sdb.deviceUid()

    @ReactMethod fun connect(host: String, promise: Promise) = job(promise) { job ->
        status("connect", "Connecting to $host")
        val tv = runCatching { SamsungTvInfo.probe(host, discoveryHttp(), job::register) }.getOrNull()
        if (tv?.developerMode == false) error("Turn on Developer Mode on the TV, then restart it.")
        if (!tv?.developerHost.isNullOrBlank() && localAddresses().none { it.address.hostAddress == tv!!.developerHost })
            error("Set the TV's Developer Mode host IP to " + (localAddresses().firstOrNull()?.address?.hostAddress ?: "this phone's Wi-Fi IP") + ", then restart the TV.")
        client(host, job).use { sdb ->
            val capabilities = sdb.capabilities()
            val duid = duid(sdb)
            val profile = store.load(duid)
            val usable = profile != null && runCatching { profile.validate(duid) }.isSuccess
            preferences.edit().putString("lastHost", host).apply()
            tvMap(host, sdb.banner, capabilities, duid, usable, tv)
        }
    }

    @ReactMethod fun savedHost(promise: Promise) { promise.resolve(preferences.getString("lastHost", "")) }

    @ReactMethod fun pickFile(kind: String, promise: Promise) {
        val activity = context.currentActivity
        if (activity == null) { promise.reject("PICKER", "Open the app to select a file."); return }
        if (!picker.compareAndSet(null, promise)) { promise.reject("PICKER", "A file picker is already open."); return }
        activity.runOnUiThread {
            try {
                activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_TITLE, if (kind == "certificate") "Select a .p12 certificate" else "Select a .wgt or .tpk package")
                }, 8031)
            } catch (error: Exception) { picker.getAndSet(null)?.reject("PICKER", error) }
        }
    }

    private fun uriBytes(uri: String, limit: Int): ByteArray =
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { PackageArchive.bounded(it, limit) }
            ?: error("Cannot open the selected file. Select it again.")

    @ReactMethod fun importCertificates(host: String, authorUri: String, authorPassword: String,
        distributorUri: String, distributorPassword: String, promise: Promise) = job(promise) { job ->
        status("certificates", "Validating original signing certificates")
        client(host, job).use { sdb ->
            val duid = duid(sdb)
            val ap = authorPassword.toCharArray()
            val dp = distributorPassword.toCharArray()
            try {
                val profile = CertificateProfile(CertificateStore.readP12(uriBytes(authorUri, 2 * 1024 * 1024), ap),
                    CertificateStore.readP12(uriBytes(distributorUri, 2 * 1024 * 1024), dp))
                profile.validate(duid)
                store.save(duid, profile)
                profile.author.fingerprint
            } finally { ap.fill('\u0000'); dp.fill('\u0000') }
        }
    }

    @ReactMethod fun createCertificates(host: String, promise: Promise) = job(promise, keepAlive = true) { job ->
        val duid = client(host, job).use { duid(it) }
        val existing = store.load(duid)
        val profile = SamsungCertificates(context, job::register, job::check, { status("certificates", it) }, diagnostics::event).create(duid, existing)
        store.save(duid, profile)
        diagnostics.event("samsung.certificate.saved")
        profile.author.fingerprint
    }

    @ReactMethod fun exportCertificates(duid: String, password: String, promise: Promise) = job(promise, keepAlive = true) { job ->
        require(password.length >= 12) { "Use at least 12 characters for the certificate backup password." }
        val activity = context.currentActivity ?: error("Open the app to export certificates.")
        val profile = store.load(duid) ?: error("No certificate profile is saved for this TV.")
        val future = java.util.concurrent.CompletableFuture<Uri?>()
        check(exportPicker.compareAndSet(null, future)) { "An export dialog is already open." }
        job.register(Closeable { future.complete(null) })
        try {
            status("backup", "Choose where to save the password-protected certificate backup")
            activity.runOnUiThread {
                try {
                    activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
                        putExtra(Intent.EXTRA_TITLE, "tizen-pocket-certificates.zip")
                    }, 8032)
                } catch (error: Exception) { future.completeExceptionally(error) }
            }
            val uri = future.get(5, java.util.concurrent.TimeUnit.MINUTES) ?: return@job false
            job.check()
            val chars = password.toCharArray()
            try {
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot write to the selected location.")
                java.util.zip.ZipOutputStream(output).use { zip ->
                    for ((name, identity) in listOf("author.p12" to profile.author, "distributor.p12" to profile.distributor)) {
                        job.check()
                        zip.putNextEntry(java.util.zip.ZipEntry(name))
                        zip.write(CertificateStore.exportP12(identity, chars)); zip.closeEntry()
                    }
                    zip.putNextEntry(java.util.zip.ZipEntry("TV-DUID.txt")); zip.write(duid.toByteArray()); zip.closeEntry()
                }
            } finally { chars.fill('\u0000') }
            true
        } finally { exportPicker.compareAndSet(future, null) }
    }

    @ReactMethod fun installedApps(host: String, promise: Promise) = job(promise) { job ->
        client(host, job).use { it.shell("0 vd_applist") }
    }

    @ReactMethod fun inventory(host: String, promise: Promise) = job(promise) { job ->
        status("versions", "Reading installed versions from the TV")
        client(host, job).use { sdb ->
            val apps = TvInventory.parse(sdb.shell("0 vd_applist"))
            Arguments.createArray().apply { apps.forEach { app ->
                var fingerprint: String? = null
                if (Regex("jellyfin|tizenbrew|moonlight", RegexOption.IGNORE_CASE).containsMatchIn(app.name + app.appId)) {
                    val base = app.path?.trimEnd('/')
                    if (base != null && Regex("/[A-Za-z0-9_./-]+").matches(base)) {
                        for (suffix in listOf("/res/wgt/author-signature.xml", "/author-signature.xml", "/res/author-signature.xml")) {
                            job.check()
                            fingerprint = runCatching { PackageArchive.signatureContentFingerprint(sdb.pull(base + suffix)) }.getOrNull()
                            if (fingerprint != null) break
                        }
                    }
                }
                pushMap(Arguments.createMap().apply {
                    putString("appId", app.appId); putString("name", app.name); putString("version", app.version)
                    putString("installedAt", app.installedAt); putString("fingerprint", fingerprint)
                })
            } }
        }
    }

    @ReactMethod fun catalogRecords(host: String, promise: Promise) = job(promise) { job ->
        val duid = client(host, job).use { duid(it) }
        Arguments.createMap().apply { listOf("jellyfin", "tizenbrew", "moonlight").forEach { id ->
            val record = preferences.getString("install:$duid:$id", null)
            if (record != null) {
                val json = JSONObject(record)
                putMap(id, Arguments.makeNativeMap(json.keys().asSequence().associateWith { json.get(it) }))
            }
        } }
    }

    @ReactMethod fun packageMetadata(url: String, name: String, digest: String?, promise: Promise) = job(promise) { job ->
        val cacheKey = "metadata:" + sha256((url + digest).toByteArray())
        val cached = preferences.getString(cacheKey, null)?.let { JSONObject(it) }
        if (cached != null && System.currentTimeMillis() - cached.getLong("cachedAt") < 6 * 60 * 60 * 1000) {
            Arguments.makeNativeMap(cached.keys().asSequence().associateWith { cached.get(it) })
        } else {
            val file = File(context.cacheDir, "metadata-" + UUID.randomUUID() + ".zip")
            try {
                status("versions", "Checking upstream package " + name)
                download(url, file, digest, job)
                val info = PackageArchive.inspect(file)
                val json = JSONObject().put("appId", info.appId).put("packageId", info.packageId)
                    .put("version", info.version).put("name", info.name)
                    .put("fingerprint", PackageArchive.contentFingerprint(file, job::check))
                    .put("cachedAt", System.currentTimeMillis())
                preferences.edit().putString(cacheKey, json.toString()).apply()
                Arguments.makeNativeMap(json.keys().asSequence().associateWith { json.get(it) })
            } finally { file.delete() }
        }
    }

    private fun download(url: String, target: File, expectedDigest: String?, job: Job) {
        require(url.startsWith("https://")) { "Package downloads must use HTTPS." }
        val call = http.newCall(Request.Builder().url(url).build())
        job.register(Closeable { call.cancel() })
        call.execute().use { response ->
            check(response.isSuccessful) { "Download failed (HTTP ${response.code})." }
            require(response.request.url.isHttps) { "Download redirected to an insecure URL." }
            val body = response.body ?: error("Empty download response.")
            val length = body.contentLength()
            require(length <= PackageArchive.MAX_BYTES) { "Package download exceeds 512 MiB." }
            var total = 0L
            body.byteStream().use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var previous = -1
                while (true) {
                    job.check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= PackageArchive.MAX_BYTES) { "Package download exceeds 512 MiB." }
                    output.write(buffer, 0, count)
                    val progress = if (length > 0) (total * 100 / length).toInt() else 0
                    if (progress != previous) {
                        status("download", "Downloading package", if (length > 0) progress.toDouble() else null)
                        previous = progress
                    }
                }
            } }
            if (length >= 0) require(total == length) { "Incomplete package download." }
        }
        if (!expectedDigest.isNullOrBlank()) {
            require(expectedDigest.startsWith("sha256:")) { "Unsupported release digest." }
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            target.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
            require(hash.digest().joinToString("") { "%02x".format(it) } == expectedDigest.substringAfter(':').lowercase()) {
                "Package checksum does not match the release. Nothing was sent to the TV."
            }
        }
    }

    @ReactMethod fun preparePackage(source: String, name: String, digest: String?, promise: Promise) = job(promise, keepAlive = true) { job ->
        val extension = name.substringAfterLast('.').lowercase()
        require(extension == "wgt" || extension == "tpk") { "Choose a .wgt or .tpk package." }
        val file = File(context.cacheDir, "package-${UUID.randomUUID()}.$extension")
        try {
            if (source.startsWith("https://")) download(source, file, digest, job)
            else {
                require(source.startsWith("content://")) { "Select a package from your phone or download it over HTTPS." }
                status("read", "Reading selected package")
                context.contentResolver.openInputStream(Uri.parse(source))?.use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        job.check()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= PackageArchive.MAX_BYTES) { "Package exceeds 512 MiB." }
                        output.write(buffer, 0, count)
                    }
                } } ?: error("Cannot open selected package.")
            }
            val info = PackageArchive.inspect(file)
            require(info.kind == extension) { "File extension does not match the package manifest." }
            preferences.edit().putString("prepared:" + file.name, name).apply()
            Arguments.createMap().apply {
                putString("token", file.name); putString("name", info.name.ifBlank { name })
                putString("packageId", info.packageId); putString("appId", info.appId)
                putString("version", info.version); putString("kind", info.kind); putDouble("size", file.length().toDouble())
            }
        } catch (error: Exception) { file.delete(); throw error }
    }

    @ReactMethod fun discardPackage(token: String, promise: Promise) {
        if (Regex("package-[a-f0-9-]+\\.(wgt|tpk)").matches(token)) File(context.cacheDir, token).delete()
        preferences.edit().remove("prepared:" + token).apply()
        promise.resolve(null)
    }

    @ReactMethod fun install(host: String, token: String, catalogId: String?, resign: Boolean, promise: Promise) = job(promise, keepAlive = true) { job ->
        require(Regex("package-[a-f0-9-]+\\.(wgt|tpk)").matches(token)) { "Invalid package token." }
        val input = File(context.cacheDir, token)
        check(input.exists()) { "Package is no longer available. Select or download it again." }
        val info = PackageArchive.inspect(input)
        val signed = File(context.cacheDir, "signed-${UUID.randomUUID()}.${info.kind}")
        try {
            client(host, job).use { sdb ->
                val caps = sdb.capabilities()
                val duid = duid(sdb)
                val installed = sdb.shell("0 vd_applist")
                if (catalogId != null) {
                    val catalogApps = TvInventory.parse(installed).filter {
                        it.name.lowercase().replace(" ", "").startsWith(catalogId) ||
                            it.appId.lowercase().contains(catalogId)
                    }
                    require(catalogApps.isEmpty() || catalogApps.any { it.appId == info.appId }) {
                        "A different variant of this app is installed. Select the same app ID to preserve its settings and signed-in account."
                    }
                }
                val oldText = if (catalogId != null) preferences.getString("install:$duid:$catalogId", null) else null
                val previous = oldText?.let { JSONObject(it) }
                val previousInfo = previous?.let { PackageInfo(it.getString("packageId"), it.getString("appId"), "", "", info.kind) }
                val profile = if (resign) store.load(duid) else null
                val authorFingerprint = profile?.author?.fingerprint ?: PackageArchive.authorFingerprint(input)
                if (resign) {
                    check(profile != null) { "Set up Samsung certificates, or import the original author/distributor .p12 files, before installing." }
                    profile.validate(duid)
                }
                InstallPolicy.verifyIdentity(previousInfo, info, previous?.optString("author")?.ifBlank { null }, authorFingerprint)
                val ledger = preferences.getString("identity:$duid:${info.packageId}", null)?.let { JSONObject(it) }
                InstallPolicy.verifyIdentity(ledger?.let { PackageInfo(it.getString("packageId"), it.getString("appId"), "", "", info.kind) },
                    info, ledger?.optString("author")?.ifBlank { null }, authorFingerprint)
                val update = InstallPolicy.isInstalled(installed, info)
                status("sign", if (update) "Preparing in-place update; keeping the installed app" else "Preparing installation")
                val packageFile = if (profile != null) {
                    PackageArchive.sign(input, signed, profile.author, profile.distributor, job::check)
                    signed
                } else input
                val directory = caps["sdk_toolpath"]?.trimEnd('/') ?: "/home/owner/share/tmp/sdk_tools/tmp"
                require(Regex("/[A-Za-z0-9_./-]+").matches(directory) && directory.split('/').none { it == ".." }) { "TV returned an unsafe install directory." }
                val remote = directory + "/pocket-" + info.packageId + "." + info.kind
                var lastProgress = -1
                sdb.push(packageFile, remote) { percent ->
                    if (percent.toInt() != lastProgress) { status("upload", "Sending package to TV", percent); lastProgress = percent.toInt() }
                }
                job.check()
                status("install", if (update) "Updating app in place" else "Installing app")
                // No uninstall command exists in this module. Certificate failures are returned to the user.
                var installText = ""
                val result = sdb.shell("0 vd_appinstall ${info.appId} $remote", 180000) { chunk ->
                    installText = (installText + chunk).takeLast(2048)
                    Regex("\\[(\\d{1,3})\\]").findAll(installText).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull()?.let {
                        status("install", "TV is installing the package", it)
                    }
                }
                InstallPolicy.verifyResult(result)
                val after = sdb.shell("0 vd_applist")
                check(InstallPolicy.isInstalled(after, info)) { "TV reported success but the app is missing from its installed list." }
                val record = JSONObject().put("packageId", info.packageId).put("appId", info.appId)
                    .put("version", info.version).put("author", authorFingerprint ?: "")
                    .put("assetName", preferences.getString("prepared:" + token, ""))
                val editor = preferences.edit().putString("identity:$duid:${info.packageId}", record.toString())
                if (catalogId != null) editor.putString("install:$duid:$catalogId", record.toString())
                check(editor.commit()) { "App installed, but the phone could not save the update record." }
                status("complete", if (update) "Update complete" else "Installation complete", 100.0)
                Arguments.createMap().apply { putBoolean("updated", update); putString("name", info.name); putString("output", result) }
            }
        } finally { signed.delete() }
    }

    @ReactMethod fun cancel(promise: Promise) {
        active.get()?.let { it.cancelled.set(true); it.close() }
        promise.resolve(null)
    }

    override fun invalidate() {
        diagnostics.close()
        active.get()?.let { it.cancelled.set(true); it.close() }
        picker.getAndSet(null)?.reject("CLOSED", "App closed.")
        exportPicker.getAndSet(null)?.complete(null)
        executor.shutdownNow()
        super.invalidate()
    }

    @ReactMethod fun diagnosticsInfo(promise: Promise) {
        promise.resolve(Arguments.createMap().apply { putBoolean("configured", diagnostics.configured); putBoolean("enabled", diagnostics.enabled) })
    }
    @ReactMethod fun setDiagnosticsEnabled(enabled: Boolean, promise: Promise) {
        diagnostics.setEnabled(enabled); promise.resolve(null)
    }
}
