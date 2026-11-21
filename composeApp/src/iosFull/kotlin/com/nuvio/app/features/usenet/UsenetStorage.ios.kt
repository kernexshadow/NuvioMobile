@file:OptIn(ExperimentalForeignApi::class)

package com.nuvio.app.features.usenet

import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.serialization.json.Json
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.NSUserDefaults
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * Profile-local sources in the Keychain, readable on this device only after its first unlock; never
 * part of profile or cloud sync, nor of device backups.
 */
internal class KeychainUsenetSourceStorage {
    private val lock = SynchronizedObject()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    // Screens read the sources on the main thread: each profile is read once, every later read is this copy.
    private val decoded = HashMap<Int, UsenetSourceConfiguration>()

    fun read(profileId: Int): UsenetSourceConfiguration = synchronized(lock) {
        decoded[profileId]?.let { return@synchronized it }
        // Do not silently overwrite credentials if the Keychain cannot be read.
        val stored = Keychain.read(key(profileId)) ?: return@synchronized UsenetSourceConfiguration()
        json.decodeFromString(UsenetSourceConfiguration.serializer(), stored).also { decoded[profileId] = it }
    }

    fun update(profileId: Int, value: UsenetSourceConfiguration): Unit = synchronized(lock) {
        value.validate()
        Keychain.write(key(profileId), json.encodeToString(UsenetSourceConfiguration.serializer(), value))
        decoded[profileId] = value
    }

    fun removeProfile(profileId: Int): Unit = synchronized(lock) {
        // Dropped first: a failed delete must not leave removed credentials readable.
        decoded.remove(profileId)
        Keychain.write(key(profileId), null)
    }

    fun clear(): Unit = synchronized(lock) {
        decoded.clear()
        Keychain.clear()
    }

    private fun key(profileId: Int) = "profile_$profileId"
}

/** Generic-password items of the Usenet service. */
private object Keychain {
    private const val SERVICE = "com.nuvio.media.usenet"

    fun read(key: String): String? = query(key) { query ->
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            if (status == errSecItemNotFound) return@memScoped null
            check(status == errSecSuccess) { "Unable to read Usenet sources (OSStatus $status)" }
            val data: CFDataRef = result.value?.reinterpret() ?: return@memScoped null
            try {
                val bytes = CFDataGetBytePtr(data) ?: return@memScoped null
                ByteArray(CFDataGetLength(data).toInt()) { bytes[it].toByte() }.decodeToString()
            } finally {
                CFRelease(data)
            }
        }
    }

    fun write(key: String, value: String?) = query(key) { query ->
        if (value == null) {
            checkDelete(SecItemDelete(query))
        } else {
            val bytes = value.encodeToByteArray().toUByteArray()
            require(bytes.isNotEmpty())
            val data = CFDataCreate(null, bytes.refTo(0), bytes.size.toLong()) ?: error("Unable to encode Usenet sources")
            val attributes = dictionary()
            try {
                CFDictionarySetValue(attributes, kSecValueData, data)
                val update = SecItemUpdate(query, attributes)
                if (update == errSecItemNotFound) {
                    CFDictionarySetValue(query, kSecValueData, data)
                    CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                    val added = SecItemAdd(query, null)
                    check(added == errSecSuccess) { "Could not save Usenet sources (OSStatus $added)" }
                } else {
                    check(update == errSecSuccess) { "Could not save Usenet sources (OSStatus $update)" }
                }
            } finally {
                CFRelease(attributes)
                CFRelease(data)
            }
        }
    }

    fun clear() = query(null) { checkDelete(SecItemDelete(it)) }

    private fun checkDelete(status: Int) {
        check(status == errSecSuccess || status == errSecItemNotFound) { "Unable to remove Usenet sources (OSStatus $status)" }
    }

    private fun dictionary(): CFMutableDictionaryRef =
        CFDictionaryCreateMutable(null, 0L, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
            ?: error("Unable to create a Keychain query")

    private inline fun <T> query(key: String?, block: (CFMutableDictionaryRef) -> T): T {
        val service = CFStringCreateWithCString(null, SERVICE, kCFStringEncodingUTF8) ?: error("Unable to encode the Keychain service")
        val account = key?.let { CFStringCreateWithCString(null, it, kCFStringEncodingUTF8) }
        val query = dictionary()
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, service)
            account?.let { CFDictionarySetValue(query, kSecAttrAccount, it) }
            return block(query)
        } finally {
            CFRelease(query)
            account?.let { CFRelease(it) }
            CFRelease(service)
        }
    }
}

/** One group of keys in the app's user defaults, named "[prefix].key". */
internal class DefaultsUsenetPreferences(private val prefix: String) : UsenetPreferences {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun getString(key: String): String? = defaults.stringForKey("$prefix.$key")
    override fun putString(key: String, value: String) = defaults.setObject(value, forKey = "$prefix.$key")
    override fun remove(key: String) = defaults.removeObjectForKey("$prefix.$key")
}

/** Holds no credentials and is shared by profiles; only an account reset clears it. */
internal class DefaultsNewznabStateStorage : NewznabStateStorage {
    private val preferences = DefaultsUsenetPreferences("usenet_indexer_state")

    override fun load(): String? = preferences.getString(KEY)
    override fun save(value: String) = preferences.putString(KEY, value)
    @Volatile override var generation = 0
        private set

    fun clear() {
        preferences.remove(KEY)
        generation += 1
    }

    private companion object { const val KEY = "indexers" }
}

/** Holds no credentials and is shared by profiles; only an account reset clears it. */
internal class DefaultsUnplayableReleaseStorage : UnplayableReleaseStorage {
    private val preferences = DefaultsUsenetPreferences("usenet_unplayable_releases")

    override fun load(): String? = preferences.getString(KEY)
    override fun save(value: String) = preferences.putString(KEY, value)
    @Volatile override var generation = 0
        private set

    fun clear() {
        preferences.remove(KEY)
        generation += 1
    }

    private companion object { const val KEY = "releases" }
}

internal class DefaultsUsenetStatsStorage : UsenetStatsStorage {
    private val preferences = DefaultsUsenetPreferences("usenet_source_stats")

    override fun load(): String? = preferences.getString(KEY)
    override fun save(value: String) = preferences.putString(KEY, value)
    override fun clear() = preferences.remove(KEY)
    override fun loadPreference(name: String): String? = preferences.getString(name)
    override fun savePreference(name: String, value: String) = preferences.putString(name, value)
    @Volatile override var generation = 0
        private set

    /** An account reset: the counts and the chosen filters go. They hold no credential and are shared by profiles. */
    fun clearAll() {
        listOf(KEY, "period", "content").forEach(preferences::remove)
        generation += 1
    }

    private companion object { const val KEY = "stats" }
}
