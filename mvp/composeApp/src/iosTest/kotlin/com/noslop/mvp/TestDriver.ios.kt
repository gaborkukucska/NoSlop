package com.noslop.mvp

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.wrapConnection
import co.touchlab.sqliter.DatabaseConfiguration
import com.noslop.mvp.db.MeshDatabase

// SQLiter shares an in-memory database keyed by name, so a fixed name would leak rows between tests.
// A per-call unique name gives each test its own isolated in-memory DB.
private var dbSeq = 0

/** Kotlin/Native: a fresh in-memory SQLite per call (no file, so tests can't contaminate each other). */
actual fun inMemoryDriver(): SqlDriver {
    val schema = MeshDatabase.Schema
    return NativeSqliteDriver(
        DatabaseConfiguration(
            name = "test-${dbSeq++}.db",
            version = schema.version.toInt(),
            inMemory = true,
            create = { connection -> wrapConnection(connection) { schema.create(it) } },
        ),
    )
}
