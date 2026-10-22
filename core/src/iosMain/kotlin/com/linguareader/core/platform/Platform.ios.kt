package com.linguareader.core.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import okio.Path
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import platform.CoreFoundation.CFStringConvertEncodingToNSStringEncoding
import platform.CoreFoundation.CFStringConvertIANACharSetNameToEncoding
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFStringEncodingInvalidId
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.NSCharacterSet
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSMakeRange
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSStringEnumerationBySentences
import platform.Foundation.NSStringEnumerationSubstringNotRequired
import platform.Foundation.create
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.decomposedStringWithCanonicalMapping
import platform.Foundation.decomposedStringWithCompatibilityMapping
import platform.Foundation.enumerateSubstringsInRange
import platform.Foundation.precomposedStringWithCanonicalMapping
import platform.Foundation.precomposedStringWithCompatibilityMapping
import platform.Foundation.timeIntervalSince1970
import kotlinx.coroutines.IO

/*
 * Implementación iOS del puerto de plataforma. Pendiente de validar en un Mac (tests de `core` en
 * iosSimulatorArm64): en Windows solo se comprueba que compila.
 */

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ns(text: String): NSString = NSString.create(string = text)

@OptIn(ExperimentalForeignApi::class)
actual fun normalizeUnicode(text: String, form: UnicodeForm): String = when (form) {
    UnicodeForm.NFC -> ns(text).precomposedStringWithCanonicalMapping
    UnicodeForm.NFD -> ns(text).decomposedStringWithCanonicalMapping
    UnicodeForm.NFKC -> ns(text).precomposedStringWithCompatibilityMapping
    UnicodeForm.NFKD -> ns(text).decomposedStringWithCompatibilityMapping
}

@OptIn(ExperimentalForeignApi::class)
actual fun sentenceBreaks(text: String, languageTag: String): List<Int> {
    val string = ns(text)
    val out = mutableListOf<Int>()
    string.enumerateSubstringsInRange(
        NSMakeRange(0u, string.length),
        NSStringEnumerationBySentences or NSStringEnumerationSubstringNotRequired,
    ) { _, range, _, _ ->
        range.useContents { out += (location + length).toInt() }
    }
    if (out.lastOrNull() != text.length) out += text.length
    return out
}

private val MARKS = setOf(CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK)

@OptIn(ExperimentalForeignApi::class)
actual fun isLetterOrDigitCodePoint(codePoint: Int): Boolean =
    if (codePoint < 0x10000) codePoint.toChar().isLetterOrDigit()
    else NSCharacterSet.alphanumericCharacterSet().longCharacterIsMember(codePoint.toUInt())

@OptIn(ExperimentalForeignApi::class)
actual fun isMarkCodePoint(codePoint: Int): Boolean =
    if (codePoint < 0x10000) codePoint.toChar().category in MARKS
    else NSCharacterSet.nonBaseCharacterSet().longCharacterIsMember(codePoint.toUInt())

actual fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

@OptIn(ExperimentalForeignApi::class)
actual fun setFileLastModified(path: Path, epochMillis: Long): Boolean =
    NSFileManager.defaultManager.setAttributes(
        mapOf<Any?, Any?>(NSFileModificationDate to NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)),
        ofItemAtPath = path.toString(),
        error = null,
    )

@OptIn(ExperimentalForeignApi::class)
actual class Sha256 {
    private var ctx = nativeHeap.alloc<CC_SHA256_CTX>().also { CC_SHA256_Init(it.ptr) }

    actual fun update(bytes: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        bytes.usePinned { CC_SHA256_Update(ctx.ptr, it.addressOf(offset), length.toUInt()) }
    }

    actual fun digest(): ByteArray {
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
        out.usePinned { CC_SHA256_Final(it.addressOf(0).reinterpret(), ctx.ptr) }
        nativeHeap.free(ctx.ptr)
        ctx = nativeHeap.alloc<CC_SHA256_CTX>().also { CC_SHA256_Init(it.ptr) }
        return out
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun decodeBytes(bytes: ByteArray, offset: Int, length: Int, charset: String): String? {
    val cfName = CFStringCreateWithCString(null, charset, kCFStringEncodingUTF8) ?: return null
    val cfEncoding = CFStringConvertIANACharSetNameToEncoding(cfName)
    if (cfEncoding == kCFStringEncodingInvalidId) return null
    val encoding = CFStringConvertEncodingToNSStringEncoding(cfEncoding)
    if (length == 0) return ""
    return bytes.usePinned { pinned ->
        NSString.create(bytes = pinned.addressOf(offset), length = length.toULong(), encoding = encoding)?.toString()
    }
}

actual fun availableProcessors(): Int = NSProcessInfo.processInfo.activeProcessorCount.toInt()

actual val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

@OptIn(ExperimentalForeignApi::class)
actual fun freeBytes(path: Path): Long? = runCatching {
    val attributes = NSFileManager.defaultManager.attributesOfFileSystemForPath(path.toString(), error = null)
    (attributes?.get(platform.Foundation.NSFileSystemFreeSize) as? platform.Foundation.NSNumber)?.longLongValue
}.getOrNull()
