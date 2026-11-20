package com.nebulamusic.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val username: String,
    /** AES-GCM (Android Keystore) encrypted password, Base64. */
    val secret: String = "",
)

/** Android Keystore backed AES-GCM, so saved passwords are never stored in clear text. */
private object Vault {
    private const val ALIAS = "nebula_server_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(secret: String): String? = runCatching {
        val raw = Base64.decode(secret, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12))
    }.getOrNull()
}

/** The list of saved servers and which one is active. */
class ServerStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("nebula_servers", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _servers = MutableStateFlow(
        runCatching { json.decodeFromString<List<ServerConfig>>(sp.getString("servers", "[]")!!) }.getOrDefault(emptyList())
    )
    val servers: StateFlow<List<ServerConfig>> = _servers.asStateFlow()

    private val _activeId = MutableStateFlow(sp.getString("active", null))
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    val active: ServerConfig? get() = _servers.value.firstOrNull { it.id == _activeId.value } ?: _servers.value.firstOrNull()

    fun password(s: ServerConfig): String = Vault.decrypt(s.secret).orEmpty()

    fun save(s: ServerConfig, password: String?): ServerConfig {
        val secret = if (password != null) Vault.encrypt(password) else s.secret
        val saved = s.copy(secret = secret)
        val list = _servers.value.filter { it.id != s.id } + saved
        persist(list)
        setActive(saved.id)
        return saved
    }

    fun remove(id: String) {
        val list = _servers.value.filter { it.id != id }
        persist(list)
        if (_activeId.value == id) setActive(list.firstOrNull()?.id)
    }

    fun setActive(id: String?) {
        sp.edit().putString("active", id).apply()
        _activeId.value = id
    }

    private fun persist(list: List<ServerConfig>) {
        sp.edit().putString("servers", json.encodeToString(list)).apply()
        _servers.value = list
    }
}

/** Normalises what the user typed: adds a scheme when missing, drops trailing slashes and a pasted /rest suffix. */
fun normalizeServerUrl(raw: String): String {
    var u = raw.trim().trimEnd('/')
    if (u.isEmpty()) return u
    if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
    return u.removeSuffix("/rest").trimEnd('/')
}
