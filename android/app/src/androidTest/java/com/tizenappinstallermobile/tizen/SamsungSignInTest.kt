package com.tizenappinstallermobile.tizen

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tizenappinstallermobile.MainActivity
import java.io.Closeable
import java.net.InetAddress
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SamsungSignInTest {
    @Test fun productionSignInRespondsOnIpv6WithAnotherAppForeground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val browserUrl = CompletableFuture<String>()
        val resources = CopyOnWriteArrayList<Closeable>()
        val worker = Executors.newSingleThreadExecutor()
        try {
            OperationService.start(context)
            val auth = worker.submit<org.json.JSONObject> {
                SamsungCertificates(context, resources::add, {}, {}, launchBrowser = { url ->
                    instrumentation.context.startActivity(Intent(instrumentation.context, TestBrowserActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    browserUrl.complete(url)
                }).login()
            }
            val uri = Uri.parse(browserUrl.get(10, TimeUnit.SECONDS))
            assertEquals("account.samsung.com", uri.host)
            val callback = Uri.parse(uri.getQueryParameter("redirect_uri"))
            assertEquals("localhost", callback.host)
            // Android 14+ can freeze a cached app after ten seconds. Keep the browser visible longer.
            Thread.sleep(15000)
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val process = manager.runningAppProcesses.first { it.pid == android.os.Process.myPid() }
            assertTrue("Sign-in must not become cached while the browser is visible",
                process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE)
            val form = "state=${uri.getQueryParameter("state")}&code=" + URLEncoder.encode(
                "{\"access_token\":\"instrumentation-token\",\"userId\":\"instrumentation-user\"}", "UTF-8")
            Socket(InetAddress.getByName("::1"), callback.port).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write(("POST ${callback.path} HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: ${form.length}\r\n\r\n$form").toByteArray())
                assertTrue(socket.getInputStream().bufferedReader().readText().startsWith("HTTP/1.1 200"))
            }
            assertEquals("instrumentation-user", auth.get(5, TimeUnit.SECONDS).getString("userId"))
        } finally {
            resources.forEach { it.close() }
            OperationService.stop(context)
            worker.shutdownNow()
        }
    }
}
