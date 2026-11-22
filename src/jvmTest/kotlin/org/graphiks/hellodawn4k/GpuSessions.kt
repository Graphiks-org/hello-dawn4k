package org.graphiks.hellodawn4k

import kotlinx.coroutines.withContext
import org.graphiks.dawn4k.webgpu.toolkit.Dawn4kWebGpuToolkitExtension
import org.graphiks.dawn4k.webgpu.toolkit.openDawnSession
import org.graphiks.webgpu.toolkit.Session
import org.graphiks.webgpu.toolkit.SessionConfig
import kotlin.time.Duration.Companion.seconds

/**
 * Opens a dawn4k session for tests: [openDawnSession] binds the session to the Dawn
 * instance it opened, so the extension's dawn-typed handles resolve against it.
 *
 * The block runs admitted as the session's GPU work and dispatched onto the session's
 * execution owner: the extension creates the Dawn context on a dedicated thread, and
 * off-owner native GPU calls crash in the Metal driver (AGXMetal).
 */
internal suspend fun <T> withDawnSession(
    config: SessionConfig = SessionConfig(startupTimeout = 60.seconds),
    block: suspend (Session) -> T,
): T {
    val session = openDawnSession(Dawn4kWebGpuToolkitExtension(), config)
    try {
        return session.run {
            withContext(checkNotNull(session.executionOwner) { "the session pins no execution owner" }) {
                block(session)
            }
        }
    } finally {
        // Stop manual progress before shutdown so the shutdown stays admissible.
        session.manualProgress?.stop()
        session.shutdown()
    }
}
