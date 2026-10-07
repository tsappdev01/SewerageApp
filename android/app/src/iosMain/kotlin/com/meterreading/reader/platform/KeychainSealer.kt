@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.meterreading.reader.platform

import com.meterreading.reader.data.Sealer
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemCopyMatching
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateDecryptedData
import platform.Security.SecKeyCreateEncryptedData
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyRef
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrApplicationTag
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecAttrTokenID
import platform.Security.kSecAttrTokenIDSecureEnclave
import platform.Security.kSecClass
import platform.Security.kSecClassKey
import platform.Security.kSecKeyAlgorithmECIESEncryptionCofactorVariableIVX963SHA256AESGCM
import platform.Security.kSecPrivateKeyAttrs
import platform.Security.kSecReturnRef

/**
 * Encrypts data kept on the phone with a key the iOS Keychain holds (the iOS counterpart of the Android
 * Keystore sealer): ECIES with AES-GCM to an elliptic-curve key that never leaves the Keychain, in the
 * Secure Enclave where the device has one. The key is "this device only": it is not in backups and does
 * not move to another phone. Sealed data starts with "MRQ2".
 */
class KeychainSealer(private val tag: String) : Sealer {
    private val algorithm = kSecKeyAlgorithmECIESEncryptionCofactorVariableIVX963SHA256AESGCM

    override fun seal(plain: ByteArray): ByteArray {
        val publicKey = SecKeyCopyPublicKey(privateKey()) ?: error("No public key")
        try {
            val input: CFDataRef? = CFBridgingRetain(plain.toNSData())?.reinterpret()
            val sealed = SecKeyCreateEncryptedData(publicKey, algorithm, input, null) ?: error("Could not encrypt")
            input?.let { CFRelease(it) }
            return MAGIC + (CFBridgingRelease(sealed) as NSData).toByteArray()
        } finally {
            CFRelease(publicKey)
        }
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(isSealed(sealed)) { "Not sealed data" }
        val input: CFDataRef? = CFBridgingRetain(sealed.copyOfRange(MAGIC.size, sealed.size).toNSData())?.reinterpret()
        val plain = SecKeyCreateDecryptedData(privateKey(), algorithm, input, null) ?: error("Could not decrypt")
        input?.let { CFRelease(it) }
        return (CFBridgingRelease(plain) as NSData).toByteArray()
    }

    override fun isSealed(bytes: ByteArray): Boolean =
        bytes.size > MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    private var cached: SecKeyRef? = null

    private fun privateKey(): SecKeyRef = cached ?: (find() ?: create()).also { cached = it }

    private fun tagData(): NSData = (tag as NSString).dataUsingEncoding(NSUTF8StringEncoding)!!

    private fun find(): SecKeyRef? = memScoped {
        val query = dictionary()
        CFDictionaryAddValue(query, kSecClass, kSecClassKey)
        CFDictionaryAddValue(query, kSecAttrApplicationTag, CFBridgingRetain(tagData()))
        CFDictionaryAddValue(query, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
        CFDictionaryAddValue(query, kSecReturnRef, kCFBooleanTrue)
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        if (status == errSecSuccess) result.value?.reinterpret() else null
    }

    /** A new key: in the Secure Enclave when there is one (not in the simulator), else in the Keychain. */
    private fun create(): SecKeyRef = create(secureEnclave = true) ?: create(secureEnclave = false) ?: error("Could not create a Keychain key")

    private fun create(secureEnclave: Boolean): SecKeyRef? {
        val privateAttrs = dictionary()
        CFDictionaryAddValue(privateAttrs, kSecAttrIsPermanent, kCFBooleanTrue)
        CFDictionaryAddValue(privateAttrs, kSecAttrApplicationTag, CFBridgingRetain(tagData()))
        CFDictionaryAddValue(privateAttrs, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        val attrs = dictionary()
        CFDictionaryAddValue(attrs, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
        CFDictionaryAddValue(attrs, kSecAttrKeySizeInBits, CFBridgingRetain(NSNumber(int = 256)))
        if (secureEnclave) CFDictionaryAddValue(attrs, kSecAttrTokenID, kSecAttrTokenIDSecureEnclave)
        CFDictionaryAddValue(attrs, kSecPrivateKeyAttrs, privateAttrs)
        val key = SecKeyCreateRandomKey(attrs, null)
        CFRelease(attrs)
        CFRelease(privateAttrs)
        return key
    }

    private fun dictionary(): CFMutableDictionaryRef? =
        CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)

    private companion object {
        val MAGIC = "MRQ2".encodeToByteArray()
    }
}
