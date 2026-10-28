package cn.childtv

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** Exercise Room's real main-thread guard rather than disabling it for tests. */
class WorkerThreadRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val worker = Executors.newSingleThreadExecutor()
            try {
                worker.submit { base.evaluate() }.get()
            } catch (failure: ExecutionException) {
                throw failure.cause ?: failure
            } finally {
                worker.shutdownNow()
            }
        }
    }
}
