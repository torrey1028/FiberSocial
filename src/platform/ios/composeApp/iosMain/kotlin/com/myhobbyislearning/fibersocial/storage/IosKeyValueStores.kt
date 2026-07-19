package com.myhobbyislearning.fibersocial.storage

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.COpaquePointerVar
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

// Store names mirror the Android prefs files so the two platforms' storage layouts
// stay recognizably parallel (see :app storage/AndroidKeyValueStore.kt).
const val AUTH_STORE_NAME = "fibersocial_auth"
const val NOTIFICATION_STATE_STORE_NAME = "notification_state"
const val NOTIFICATION_SETTINGS_STORE_NAME = "notification_settings"
const val THEME_SETTINGS_STORE_NAME = "theme_settings"
const val GROUP_ORDER_STORE_NAME = "group_order"
const val GROUP_LAST_VIEWED_STORE_NAME = "group_last_viewed"

/**
 * `NSUserDefaults`-backed [KeyValueStore] for non-secret state and settings — the iOS
 * analog of Android's plain `SharedPreferences` store. Keys are namespaced with
 * [name] rather than using `NSUserDefaults(suiteName:)`: suites are app-group
 * infrastructure, and an unentitled suite name can silently fall back to a shared
 * domain — a plain prefix has no such failure mode.
 */
class NsUserDefaultsKeyValueStore(private val name: String) : KeyValueStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    private fun namespaced(key: String) = "$name.$key"

    override suspend fun getString(key: String): String? = defaults.stringForKey(namespaced(key))

    override suspend fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = namespaced(key))
    }

    override suspend fun remove(key: String) {
        defaults.removeObjectForKey(namespaced(key))
    }
}

/**
 * Keychain-backed [KeyValueStore] for the OAuth token — the iOS analog of Android's
 * `EncryptedSharedPreferences` store. One generic-password item per key, namespaced by
 * [service]. `AfterFirstUnlock` accessibility so background refresh (#118) can read the
 * token while the device is locked (post-first-unlock), matching what the Android
 * WorkManager sync can do.
 */
@OptIn(ExperimentalForeignApi::class)
class KeychainKeyValueStore(private val service: String) : KeyValueStore {

    override suspend fun getString(key: String): String? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = withQuery(key, extra = {
            CFDictionaryAddValue(it, kSecReturnData, kCFBooleanTrue)
            CFDictionaryAddValue(it, kSecMatchLimit, kSecMatchLimitOne)
        }) { query -> SecItemCopyMatching(query, result.ptr) }
        if (status != errSecSuccess) {
            if (status != errSecItemNotFound) {
                println("FiberSocial: Keychain read for $service/$key failed: $status")
            }
            return null
        }
        val data = CFBridgingRelease(result.value) as? NSData ?: return null
        NSString.create(data = data, encoding = NSUTF8StringEncoding) as String?
    }

    override suspend fun putString(key: String, value: String) {
        @Suppress("CAST_NEVER_SUCCEEDS")
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding)!!
        val dataRef = CFBridgingRetain(data)
        try {
            val addStatus = withQuery(key, extra = {
                CFDictionaryAddValue(it, kSecValueData, dataRef)
                CFDictionaryAddValue(it, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
            }) { query -> SecItemAdd(query, null) }
            if (addStatus == errSecDuplicateItem) {
                val updateStatus = withQuery(key) { query ->
                    withDictionary({
                        CFDictionaryAddValue(it, kSecValueData, dataRef)
                    }) { update -> SecItemUpdate(query, update) }
                }
                if (updateStatus != errSecSuccess) {
                    println("FiberSocial: Keychain update for $service/$key failed: $updateStatus")
                }
            } else if (addStatus != errSecSuccess) {
                println("FiberSocial: Keychain add for $service/$key failed: $addStatus")
            }
        } finally {
            CFRelease(dataRef)
        }
    }

    override suspend fun remove(key: String) {
        val status = withQuery(key) { query -> SecItemDelete(query) }
        if (status != errSecSuccess && status != errSecItemNotFound) {
            println("FiberSocial: Keychain delete for $service/$key failed: $status")
        }
    }

    /** Builds the base query (class/service/account), runs [block], releases bridged refs. */
    private inline fun <T> withQuery(
        key: String,
        extra: (CFDictionaryRef?) -> Unit = {},
        block: (CFDictionaryRef?) -> T,
    ): T {
        val serviceRef = CFBridgingRetain(service as NSString)
        val accountRef = CFBridgingRetain(key as NSString)
        try {
            return withDictionary({
                CFDictionaryAddValue(it, kSecClass, kSecClassGenericPassword)
                CFDictionaryAddValue(it, kSecAttrService, serviceRef)
                CFDictionaryAddValue(it, kSecAttrAccount, accountRef)
                extra(it)
            }, block)
        } finally {
            CFRelease(accountRef)
            CFRelease(serviceRef)
        }
    }

    private inline fun <T> withDictionary(
        fill: (CFDictionaryRef?) -> Unit,
        block: (CFDictionaryRef?) -> T,
    ): T {
        val dict = CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
        try {
            fill(dict)
            return block(dict)
        } finally {
            CFRelease(dict)
        }
    }
}
