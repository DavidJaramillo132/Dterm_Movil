package com.example.dterm

import android.content.Context

/** Where to connect and as whom, as last used. */
data class Connection(
    val host: String = "",
    val port: String = "4242",
    val secret: String = "",
    val session: String = "default",
    val rememberSecret: Boolean = false,
)

/**
 * Remembers the last connection so coming back costs one tap instead of four
 * fields, one of them 64 hexadecimal characters typed on a phone keyboard.
 *
 * The secret is stored only when explicitly asked for. It is the one value here
 * that opens a shell, and app-private storage keeps it from other apps but not
 * from someone holding an unlocked phone — so the choice belongs to the person,
 * not to this class.
 */
class Recall(context: Context) {

    private val store = context.getSharedPreferences("connection", Context.MODE_PRIVATE)

    fun load(): Connection {
        val remember = store.getBoolean(REMEMBER_SECRET, false)

        return Connection(
            host = store.getString(HOST, "").orEmpty(),
            port = store.getString(PORT, "4242").orEmpty(),
            secret = if (remember) store.getString(SECRET, "").orEmpty() else "",
            session = store.getString(SESSION, "default").orEmpty(),
            rememberSecret = remember,
        )
    }

    fun save(connection: Connection) {
        store.edit().apply {
            putString(HOST, connection.host)
            putString(PORT, connection.port)
            putString(SESSION, connection.session)
            putBoolean(REMEMBER_SECRET, connection.rememberSecret)

            // Turning the switch off has to erase what is already on disk, or
            // the secret would outlive the permission to keep it.
            if (connection.rememberSecret) {
                putString(SECRET, connection.secret)
            } else {
                remove(SECRET)
            }
        }.apply()
    }

    private companion object {
        const val HOST = "host"
        const val PORT = "port"
        const val SECRET = "secret"
        const val SESSION = "session"
        const val REMEMBER_SECRET = "remember_secret"
    }
}
