package com.noslop.mvp

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.noslop.mvp.db.MeshDatabase

/** JVM (desktop) test driver: a fresh private in-memory SQLite per call. */
actual fun inMemoryDriver(): SqlDriver =
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { MeshDatabase.Schema.create(it) }
