package com.privateai.vault.vectorstore

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

/**
 * Desktop (JVM) implementation of VectorStore driver.
 */
actual fun createVectorStoreDriver(path: String): SqlDriver {
    val driver = JdbcSqliteDriver("jdbc:sqlite:$path")

    // Create tables
    com.privateai.vault.vectorstore.db.VectorDatabase.Schema.create(driver)

    return driver
}

/**
 * Desktop implementation of VectorStore factory.
 */
actual fun createVectorStore(databasePath: String): VectorStore {
    val driver = createVectorStoreDriver(databasePath)
    return SqliteVectorStore(driver)
}
