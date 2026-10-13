package com.tizenappinstallermobile.tizen

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.react.bridge.Promise
import com.tizenappinstallermobile.MainApplication
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.*
import org.junit.runner.RunWith

/** Runs the production React Native module on Android, with a real restrictive VPN. */
@RunWith(AndroidJUnit4::class)
class TizenNetworkingTest {
    private lateinit var module: TizenModule
    private val host = "10.0.2.2" // Dedicated fake TV on the emulator host, never a real TV.

    @Before fun setUp() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MainApplication
        module = requireNotNull(application.reactHost.currentReactContext?.getNativeModule("TizenBridge") as? TizenModule)
        metadata(true)
    }

    private fun metadata(enabled: Boolean) {
        OkHttpClient().newCall(Request.Builder().url("http://$host:8002/metadata/" + if (enabled) "on" else "off")
            .post(byteArrayOf().toRequestBody()).build()).execute().use { assertTrue(it.isSuccessful) }
    }
    private fun invoke(action: (Promise) -> Unit): Any? {
        val result = CompletableFuture<Any?>()
        val promise = Proxy.newProxyInstance(Promise::class.java.classLoader, arrayOf(Promise::class.java)) { _, method, args ->
            when (method.name) {
                "resolve" -> result.complete(args?.firstOrNull())
                "reject" -> result.completeExceptionally(AssertionError("Native module rejected: " + args?.joinToString()))
            }
            null
        } as Promise
        action(promise)
        return result.get(90, TimeUnit.SECONDS)
    }
    private fun connectAndReadVersions() {
        val tv = invoke { module.connect(host, it) } as ReadableMap
        assertEquals(host, tv.getString("host"))
        assertEquals("9.0", tv.getString("version"))
        assertEquals("CI-FAKE-TV-DUID-12345", tv.getString("duid"))
        val apps = invoke { module.inventory(host, it) } as ReadableArray
        assertEquals(2, apps.size())
        assertEquals("0.1.0", apps.getMap(0)!!.getString("version"))
        assertEquals("2.0.5", apps.getMap(1)!!.getString("version"))
        assertEquals(host, invoke { module.savedHost(it) })
    }
    private fun scan() {
        val found = invoke { module.discover(it) } as ReadableArray
        assertTrue("Automatic scan must find the fake TV", (0 until found.size()).any { found.getMap(it)!!.getString("host") == host })
    }
    @Test fun manualConnectionAndVersionReadsWithVpn() { connectAndReadVersions() }
    @Test fun manualConnectionWhenHttpUnavailableWithVpn() { metadata(false); connectAndReadVersions() }
    @Test fun automaticHttpDiscoveryWithVpn() { scan() }
    @Test fun automaticSdbDiscoveryWhenHttpUnavailableWithVpn() { metadata(false); scan() }

    companion object {
        @BeforeClass @JvmStatic fun enableVpnAndReproduceOldBindingFailure() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val application = instrumentation.targetContext.applicationContext as MainApplication
            val start = application.reactHost.start()
            assertTrue("Standalone React Native bundle must start", start.waitForCompletion(30, TimeUnit.SECONDS))
            assertNull("React Native startup failed", start.getError())
            val testContext = instrumentation.context
            instrumentation.uiAutomation.executeShellCommand("appops set ${testContext.packageName} ACTIVATE_VPN allow").close()
            testContext.startActivity(Intent(testContext, TestVpnActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val manager = instrumentation.targetContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (manager.allNetworks.none { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }) {
                check(System.nanoTime() < deadline) { "Restrictive test VPN did not start" }
                Thread.sleep(100)
            }
            val physical = manager.allNetworks.first { manager.getNetworkCapabilities(it)?.let { caps ->
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            } == true }
            try {
                physical.socketFactory.createSocket().use { it.connect(InetSocketAddress("10.0.2.2", 26101), 2000) }
                fail("Regression setup invalid: VPN must deny the old explicit physical-network binding")
            } catch (error: java.io.IOException) {
                assertTrue("Expected EPERM from the old binding, got: $error", error.message.orEmpty().contains("EPERM"))
            }
        }
        @AfterClass @JvmStatic fun disableVpn() {
            val context = InstrumentationRegistry.getInstrumentation().context
            context.stopService(Intent(context, TestVpnService::class.java))
            val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MainApplication
            application.reactHost.destroy("Instrumentation finished", null).waitForCompletion(10, TimeUnit.SECONDS)
        }
    }
}
