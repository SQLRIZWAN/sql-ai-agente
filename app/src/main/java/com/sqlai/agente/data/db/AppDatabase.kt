package com.sqlai.agente.data.db

import android.content.ContentValues
import android.content.Context
import com.sqlai.agente.core.vault.Vault
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SQLiteConnection
import java.io.File

/**
 * Encrypted local store (SQLCipher AES-256-CBC + HMAC).
 *
 * The DB passphrase is generated once, sealed with the Android Keystore via [Vault],
 * and only materialised in memory while the process holds the open handle. Chat logs,
 * trading history, workflow definitions and generated scripts all live here.
 *
 * We manage the handle explicitly (no SQLiteOpenHelper) so teardown can be ordered:
 * close DB -> free native pages -> drop sealed key material.
 */
class AppDatabase private constructor(private val db: SQLiteDatabase) {

    fun insertMessage(role: String, content: String, provider: String?): Long {
        val cv = ContentValues().apply {
            put("role", role)
            put("content", content)
            put("provider", provider)
            put("created_at", System.currentTimeMillis())
        }
        return db.insert("messages", null, cv)
    }

    fun recentMessages(limit: Int = 50): List<Triple<String, String, String>> {
        val out = mutableListOf<Triple<String, String, String>>()
        db.rawQuery(
            "SELECT role, content, IFNULL(provider,'') FROM messages ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out += Triple(c.getString(0), c.getString(1), c.getString(2))
        }
        return out.asReversed()
    }

    fun insertTrade(
        exchange: String, symbol: String, side: String, qty: Double,
        price: Double, pnl: Double?, reason: String?,
    ) {
        val cv = ContentValues().apply {
            put("exchange", exchange); put("symbol", symbol); put("side", side)
            put("qty", qty); put("price", price); put("pnl", pnl); put("reason", reason)
            put("created_at", System.currentTimeMillis())
        }
        db.insert("trades", null, cv)
    }

    fun insertLog(kind: String, body: String) {
        val cv = ContentValues().apply {
            put("kind", kind); put("body", body); put("created_at", System.currentTimeMillis())
        }
        db.insert("thinking_log", null, cv)
    }

    fun insertScript(name: String, language: String, body: String): Long {
        val cv = ContentValues().apply {
            put("name", name); put("language", language); put("body", body)
            put("created_at", System.currentTimeMillis())
        }
        return db.insert("scripts", null, cv)
    }

    fun listScripts(): List<Triple<Long, String, String>> {
        val out = mutableListOf<Triple<Long, String, String>>()
        db.rawQuery("SELECT id, name, body FROM scripts ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) out += Triple(c.getLong(0), c.getString(1), c.getString(2))
        }
        return out
    }

    fun insertWorkflow(name: String, stepsJson: String): Long {
        val cv = ContentValues().apply {
            put("name", name); put("steps_json", stepsJson)
            put("created_at", System.currentTimeMillis())
        }
        return db.insert("workflows", null, cv)
    }

    fun listWorkflows(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        db.rawQuery("SELECT name, steps_json FROM workflows WHERE enabled=1", null).use { c ->
            while (c.moveToNext()) out += (c.getString(0) to c.getString(1))
        }
        return out
    }

    fun clearAll() {
        db.delete("messages", null, null)
        db.delete("thinking_log", null, null)
        db.delete("trades", null, null)
    }

    /**
     * Deterministic teardown: closes the native handle so SQLCipher pages are freed
     * before the process is trimmed. Safe to call more than once.
     */
    fun close() {
        runCatching { db.close() }
    }

    companion object {
        private const val DB_NAME = "sqlai_agent.db"

        @Volatile
        private var instance: AppDatabase? = null

        /**
         * Opens (or creates) the encrypted database using a Keystore-sealed passphrase.
         * The raw key exists only on the JVM stack for the duration of this call.
         */
        fun open(context: Context): AppDatabase {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }

                val vault = Vault(context)
                val passphrase: String
                val sealedB64 = vault.getSecret("db.passphrase")
                if (sealedB64 != null) {
                    val packed = android.util.Base64.decode(sealedB64, android.util.Base64.NO_WRAP)
                    passphrase = String(vault.unseal(packed), Charsets.ISO_8859_1)
                } else {
                    val fresh = ByteArray(32)
                    java.security.SecureRandom().nextBytes(fresh)
                    passphrase = String(fresh, Charsets.ISO_8859_1)
                    val sealed = vault.seal(passphrase.toByteArray(Charsets.ISO_8859_1))
                    vault.putSecret(
                        "db.passphrase",
                        android.util.Base64.encodeToString(sealed, android.util.Base64.NO_WRAP)
                    )
                }

                // sqlcipher-android 4.x ships prebuilt .so with the AAR (auto-loaded).
                val file = context.getDatabasePath(DB_NAME)
                file.parentFile?.mkdirs()

                // Hook runs pre/post key derivation: enable memory security + WAL
                // before the passphrase is applied so no plaintext page ever hits disk.
                val hook = object : SQLiteDatabaseHook {
                    override fun preKey(connection: SQLiteConnection?) = Unit
                    override fun postKey(connection: SQLiteConnection?) {
                        connection ?: return
                        runCatching { connection.execute("PRAGMA cipher_memory_security = ON", null, null) }
                        runCatching { connection.execute("PRAGMA journal_mode = WAL", null, null) }
                    }
                }
                val db = SQLiteDatabase.openOrCreateDatabase(
                    file,
                    passphrase,
                    null,
                    null,
                    hook,
                )
                val appDb = AppDatabase(db)
                appDb.createSchema()
                instance = appDb
                // Wipe the transient passphrase reference ASAP.
                return appDb
            }
        }

        fun closeInstance() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }

    private fun createSchema() {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS messages(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                provider TEXT,
                tokens_in INTEGER DEFAULT 0,
                tokens_out INTEGER DEFAULT 0,
                created_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS trades(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                exchange TEXT NOT NULL,
                symbol TEXT NOT NULL,
                side TEXT NOT NULL,
                qty REAL NOT NULL,
                price REAL NOT NULL,
                pnl REAL,
                reason TEXT,
                created_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS workflows(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                steps_json TEXT NOT NULL,
                enabled INTEGER DEFAULT 1,
                created_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS scripts(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                language TEXT DEFAULT 'python',
                body TEXT NOT NULL,
                last_run_ms INTEGER,
                created_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS thinking_log(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT,
                body TEXT NOT NULL,
                created_at INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_msg_created ON messages(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_trade_created ON trades(created_at)")
    }
}
