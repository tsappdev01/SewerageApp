@file:OptIn(ExperimentalForeignApi::class)

package com.meterreading.reader.platform

import com.meterreading.reader.data.AppGraph
import com.meterreading.reader.data.ReadingState
import com.meterreading.reader.data.Sealer
import com.meterreading.reader.settings.AppServices
import com.meterreading.reader.settings.Connectivity
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIDevice
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_get_main_queue
import kotlin.experimental.ExperimentalNativeApi

/** Starts the shared [AppServices] with iOS's storage, Keychain and build values. Safe to call often. */
object IosPlatform {
    private var started = false

    fun start() {
        if (started) return
        started = true
        AppServices.init(IosServices())
        AppServices.inForeground = true
        AppServices.onForeground(currentTimeMillis())
        // FR-001.5: after 15 minutes away the phone's lock is asked for again.
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            AppServices.inForeground = false
            AppServices.onBackground(currentTimeMillis())
            remindIfWaiting()
        }
        center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            AppServices.inForeground = true
            AppServices.onForeground(currentTimeMillis())
            UNUserNotificationCenter.currentNotificationCenter().removeAllPendingNotificationRequests()
        }
        UNUserNotificationCenter.currentNotificationCenter()
            .requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { _, _ -> }
    }

    /**
     * FR-020.4 on iOS: iOS does not let an app wait for signal in the background, so when the reader
     * leaves the app with work waiting, a reminder is set for 30 minutes later. Opening the app asks
     * "Send now / Later" as soon as there is signal.
     */
    private fun remindIfWaiting() {
        if (!AppServices.isStarted || !AppGraph.isReady || !AppGraph.hasWaiting()) return
        val repo = AppGraph.repository
        val readings = repo.readings.value.count { it.state == ReadingState.QUEUED } + (AppGraph.inspections?.waitingVisits?.value ?: 0)
        val photos = repo.photosWaiting.value + (AppGraph.inspections?.waitingPhotos?.value ?: 0)
        val content = UNMutableNotificationContent().apply {
            title = "Waiting to send"
            body = "On the phone: $readings readings, $photos photos. Open DIP Field Service when you have signal."
        }
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(30.0 * 60, repeats = false)
        val request = UNNotificationRequest.requestWithIdentifier("waiting", content, trigger)
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, null)
    }
}

private class IosServices : PlatformServices {
    override val prefs: KeyValueStore = UserDefaultsStore(NSUserDefaults.standardUserDefaults)

    /** Application Support/data: not backed up, and encrypted by iOS until the phone is first unlocked. */
    override val dataDir: File = run {
        val support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
        val dir = File(support, "data").also { it.mkdirs() }
        NSFileManager.defaultManager.setAttributes(
            mapOf(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication), ofItemAtPath = dir.path, error = null,
        )
        NSURL.fileURLWithPath(dir.path).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
        dir
    }

    override val sealer: Sealer = KeychainSealer("com.meterreading.reader.data")

    @OptIn(ExperimentalNativeApi::class)
    override val build = BuildValues(
        apiBaseUrl = info("MRApiBaseUrl") ?: "https://zApps.dipark.com/",
        useFakeData = info("MRUseFakeData").isYes(),
        readerLogin = info("MRReaderLogin").orEmpty(),
        deviceLock = info("MRDeviceLock")?.isYes() ?: true,
        settingsPin = info("MRSettingsPin").orEmpty(),
        versionName = info("CFBundleShortVersionString") ?: "",
        isDebug = kotlin.native.Platform.isDebugBinary,
        deviceModel = "Apple ${UIDevice.currentDevice.model}",
        osVersion = "${UIDevice.currentDevice.systemName} ${UIDevice.currentDevice.systemVersion}",
    )

    override fun startConnectivity() {
        val monitor = nw_path_monitor_create()
        nw_path_monitor_set_update_handler(monitor) { path -> Connectivity.update(nw_path_get_status(path) == nw_path_status_satisfied) }
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_start(monitor)
    }

    // iOS has no WorkManager: the app asks when it is open, and a reminder is set when it is left (IosPlatform).
    override fun scheduleUploadCheck() {}

    private fun info(key: String): String? =
        (NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("$(") }

    private fun String?.isYes(): Boolean = this?.lowercase() in setOf("yes", "true", "1")
}

private class UserDefaultsStore(private val defaults: NSUserDefaults) : KeyValueStore {
    override fun getString(key: String): String? = defaults.stringForKey(key)
    override fun getLong(key: String, default: Long): Long =
        if (defaults.objectForKey(key) == null) default else defaults.integerForKey(key)
    override fun getInt(key: String, default: Int): Int = getLong(key, default.toLong()).toInt()
    override fun getBoolean(key: String, default: Boolean): Boolean =
        if (defaults.objectForKey(key) == null) default else defaults.boolForKey(key)
    override fun putString(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, forKey = key)
    }
    override fun putLong(key: String, value: Long) = defaults.setInteger(value, forKey = key)
    override fun putInt(key: String, value: Int) = defaults.setInteger(value.toLong(), forKey = key)
    override fun putBoolean(key: String, value: Boolean) = defaults.setBool(value, forKey = key)
}
