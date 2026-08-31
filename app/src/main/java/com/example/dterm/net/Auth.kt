package com.example.dterm.net

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

//
// Shared-secret authentication.
//
// The secret never crosses the wire. The server sends 32 random bytes, this
// client answers HMAC-SHA256(secret, challenge), and the server recomputes the
// same MAC. An eavesdropper sees a challenge and a MAC, and neither one can be
// replayed: the next challenge is different.
//

object Auth {
    const val CHALLENGE_SIZE = 32
    const val MAC_SIZE = 32

    /**
     * The server reads its secret from a file and trims surrounding whitespace,
     * so a secret pasted into the app with a stray newline must be trimmed the
     * same way or every MAC will differ.
     */
    fun normalize(secret: String): String = secret.trim()

    fun hmacSha256(secret: String, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(normalize(secret).toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data)
    }
}
