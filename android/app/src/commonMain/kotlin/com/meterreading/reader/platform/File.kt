package com.meterreading.reader.platform

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * A file on the phone, with the few java.io.File operations the app uses, for Android and iOS alike
 * (backed by okio). Paths are plain strings, so they can be kept in the encrypted queue.
 */
class File(val path: String) {
    constructor(parent: File, child: String) : this(parent.path.trimEnd('/') + "/" + child)
    constructor(parent: String, child: String) : this(parent.trimEnd('/') + "/" + child)

    private val okioPath: Path get() = path.toPath()

    val name: String get() = okioPath.name
    val parentFile: File? get() = okioPath.parent?.let { File(it.toString()) }

    fun exists(): Boolean = FileSystem.SYSTEM.exists(okioPath)
    fun length(): Long = FileSystem.SYSTEM.metadataOrNull(okioPath)?.size ?: 0L
    fun readBytes(): ByteArray = FileSystem.SYSTEM.read(okioPath) { readByteArray() }
    fun readText(): String = readBytes().decodeToString()
    fun writeBytes(bytes: ByteArray) {
        FileSystem.SYSTEM.write(okioPath) { write(bytes) }
    }
    fun writeText(text: String) = writeBytes(text.encodeToByteArray())

    /** True when the file is gone afterwards. */
    fun delete(): Boolean = runCatching { FileSystem.SYSTEM.delete(okioPath, mustExist = false) }.isSuccess
    fun mkdirs(): Boolean = runCatching { FileSystem.SYSTEM.createDirectories(okioPath) }.isSuccess

    /** Replaces [target] if it exists. */
    fun renameTo(target: File): Boolean = runCatching { FileSystem.SYSTEM.atomicMove(okioPath, target.okioPath) }.isSuccess

    fun listFiles(): List<File> = runCatching { FileSystem.SYSTEM.list(okioPath).map { File(it.toString()) } }.getOrDefault(emptyList())

    override fun equals(other: Any?): Boolean = other is File && other.path == path
    override fun hashCode(): Int = path.hashCode()
    override fun toString(): String = path
}
