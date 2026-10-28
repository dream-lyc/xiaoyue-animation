package cn.childtv

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.childtv.core.SourceException
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors

/** Explicit manual opt-in only. Reads an existing source; never saves configuration or starts a scan. */
@RunWith(AndroidJUnit4::class)
class SourceConnectionDiagnosticTest {
    @Test fun diagnoseExistingSourceWithoutExposingCredentials() {
        val sourceId = InstrumentationRegistry.getArguments().getString("sourceId")
        assumeTrue("Manual sourceId argument is required", !sourceId.isNullOrBlank())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LocalStore(context)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val source = store.get("sources", requireNotNull(sourceId))
            val metadataOnly = InstrumentationRegistry.getArguments().getString("metadataOnly") == "true"
            val scope = if ((source?.optJSONArray("roots")?.length() ?: 0) > 0) "selected" else "root"
            val result = if (source == null) "result=not_found" else try {
                val vault = CredentialVault(context)
                val repository = SourceRepository(store, vault, worker = worker)
                val usernameConfigured = if (source.optBoolean("credentialConfigured")) vault.get("source:$sourceId")?.let { org.json.JSONObject(it).optString("username").isNotBlank() } == true else false
                val mode = repository.effectiveAuthMode(source)
                if (metadataOnly) "result=metadata effectiveAuthMode=$mode usernameConfigured=$usernameConfigured scope=$scope"
                else { repository.test(sourceId); "result=ok effectiveAuthMode=$mode usernameConfigured=$usernameConfigured scope=$scope" }
            } catch (error: SourceException) {
                "result=failed reason=${error.reason.name} status=${error.status ?: "none"} scope=$scope"
            } catch (error: Exception) {
                "result=failed category=${error.javaClass.simpleName} scope=$scope"
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "\nSOURCE_DIAGNOSTIC $result\n") })
        } finally { worker.shutdownNow(); store.database.close() }
    }
}
