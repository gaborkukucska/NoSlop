package com.noslop.mvp

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.noslop.mvp.db.MeshDatabase

/** JVM (Android unit tests run on the JVM): a fresh private in-memory SQLite per call, no device needed. */
actual fun inMemoryDriver(): SqlDriver =
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { MeshDatabase.Schema.create(it) }
