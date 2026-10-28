package cn.childtv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CredentialVaultDeviceTest {
    @Test fun concurrentFirstUseCannotReplaceAnotherWritersEncryptionKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val namespace = "vault-fixture-${UUID.randomUUID()}"
        val alias = "$namespace.key"
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = (1..8).map { index -> pool.submit {
                val vault = CredentialVault(context, namespace, alias)
                assertTrue(start.await(5, TimeUnit.SECONDS))
                repeat(3) { sequence -> val id = "fixture-$index-$sequence"; val secret = "secret-$index-$sequence"; vault.put(id, secret); assertEquals(secret, vault.get(id)) }
            } }
            start.countDown(); futures.forEach { it.get(30, TimeUnit.SECONDS) }
            val reopened = CredentialVault(context, namespace, alias)
            (1..8).forEach { index -> repeat(3) { sequence -> assertEquals("secret-$index-$sequence", reopened.get("fixture-$index-$sequence")) } }
        } finally {
            pool.shutdownNow(); context.deleteSharedPreferences(namespace)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
