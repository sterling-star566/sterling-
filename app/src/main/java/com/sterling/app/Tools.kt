package com.sterling.app

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

/** 0..1 similarity. Forgiving of speech-recognition mistakes. */
fun sim(a0: String, b0: String): Double {
    val a = norm(a0); val b = norm(b0)
    if (a.isEmpty() || b.isEmpty()) return 0.0
    if (a == b) return 1.0
    if (minOf(a.length, b.length) >= 4 && (a in b || b in a)) return 0.85
    val d = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) d[i][0] = i
    for (j in 0..b.length) d[0][j] = j
    for (i in 1..a.length) for (j in 1..b.length)
        d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
    return 1.0 - d[a.length][b.length].toDouble() / maxOf(a.length, b.length)
}

class Tools(private val c: Context) {
    private val DOCS = "com.android.externalstorage.documents"

    private fun go(i: Intent): Boolean = try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }

    fun run(call: Call): String {
        val a = call.args
        return when (call.name) {
            "battery" -> "Battery is at ${(c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} percent"
            "get_time" -> "It's " + SimpleDateFormat("h:mm a, EEEE d MMMM", Locale.getDefault()).format(Date())
            "set_timer" -> { go(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, a["seconds"]?.toIntOrNull() ?: 60).putExtra(AlarmClock.EXTRA_SKIP_UI, true)); "Timer started" }
            "set_alarm" -> { go(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, a["hour"]?.toIntOrNull() ?: 7).putExtra(AlarmClock.EXTRA_MINUTES, a["minute"]?.toIntOrNull() ?: 0).putExtra(AlarmClock.EXTRA_SKIP_UI, true)); "Alarm set" }
            "web_search" -> { go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(a["query"] ?: "")))); "Searching for ${a["query"]}" }
            "navigate" -> { if (!go(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(a["place"] ?: ""))))) go(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(a["place"] ?: "")))); "Navigating to ${a["place"]}" }
            "camera" -> { go(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)); "Opening camera" }
            "volume", "set_volume" -> volume(a)
            "brightness", "set_brightness" -> brightness(a)
            "rotation" -> { needWrite()?.let { return it }
                Settings.System.putInt(c.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (a["state"] == "off") 0 else 1); "Auto rotate ${a["state"]}" }
            "flashlight" -> {
                val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: return "I can't find a flashlight"
                val on = a["state"] != "off"; cm.setTorchMode(id, on); if (on) "Flashlight on" else "Flashlight off"
            }
            "media" -> {
                val k = when (a["key"]) { "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE }
                val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, k)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, k)); "Okay"
            }
            "call_contact" -> contact(a["name"] ?: "")
            "dial" -> { go(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + a["number"]))); "Dialing" }
            "open_app" -> openApp(a["app"] ?: "", a["soft"] == "1")
            "open_files" -> { go(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://$DOCS/root/primary"), "vnd.android.document/root")); "Opening files" }
            "open_folder" -> openFolder(a["name"] ?: "")
            "open_file" -> openMedia(a["name"] ?: "", null)
            "play_music" -> openMedia(a["name"] ?: "", "audio")
            "play_video" -> openMedia(a["name"] ?: "", "video")
            "open_music" -> { go(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)); "Opening music" }
            "send_file" -> sendFile(a["name"] ?: "")
            "receive_files" -> { go(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)); "Bluetooth is visible for five minutes. Send the file from the other phone, then say open received files" }
            "bluetooth_on" -> { go(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); "Tap allow to turn Bluetooth on" }
            "settings" -> settings(a["page"] ?: "main")
            "activity_report" -> activity()
            else -> "I can't do that yet"
        }
    }

    // ---- device ----
    private fun needWrite(): String? =
        if (Settings.System.canWrite(c)) null else { go(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${c.packageName}"))); "I need permission to change settings first" }

    private fun volume(a: Map<String, String>): String {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val lvl = a["level"]?.toIntOrNull()
        if (lvl != null) { am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * lvl.coerceIn(0, 100) / 100, AudioManager.FLAG_SHOW_UI); return "Volume $lvl percent" }
        val d = when (a["direction"]) { "down" -> AudioManager.ADJUST_LOWER; "mute" -> AudioManager.ADJUST_MUTE; else -> AudioManager.ADJUST_RAISE }
        repeat(if (d == AudioManager.ADJUST_MUTE) 1 else 3) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, AudioManager.FLAG_SHOW_UI) }
        return "Done"
    }

    private fun brightness(a: Map<String, String>): String {
        needWrite()?.let { return it }
        val cur = Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val next = (a["level"]?.toIntOrNull()?.let { it.coerceIn(0, 100) * 255 / 100 } ?: (cur + if (a["direction"] == "down") -50 else 50)).coerceIn(10, 255)
        Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS, next)
        return "Brightness ${next * 100 / 255} percent"
    }

    private fun settings(page: String): String {
        val sdk = Build.VERSION.SDK_INT
        val i = when (page) {
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "hotspot" -> Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
            "wifi" -> if (sdk >= 30) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
            "data" -> if (sdk >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)
            "airplane" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "nfc" -> Intent(Settings.ACTION_NFC_SETTINGS)
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            "storage" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            "apps" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "language" -> Intent(Settings.ACTION_LOCALE_SETTINGS)
            "date" -> Intent(Settings.ACTION_DATE_SETTINGS)
            "vpn" -> Intent(Settings.ACTION_VPN_SETTINGS)
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        if (!go(i)) go(Intent(Settings.ACTION_SETTINGS))
        return if (page == "main") "Opening settings" else "Opening $page settings"
    }

    // ---- contacts ----
    private fun contact(name: String): String {
        if (c.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != 0) return "I need contacts permission"
        var best: Pair<String, String>? = null; var bestScore = 0.0
        c.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use {
            while (it.moveToNext()) {
                val dn = it.getString(0) ?: continue
                val s = maxOf(sim(name, dn), dn.split(" ").maxOfOrNull { w -> sim(name, w) } ?: 0.0)
                if (s > bestScore) { bestScore = s; best = dn to it.getString(1) }
            }
        }
        val b = best
        if (b == null || bestScore < 0.6) return "I couldn't find $name in your contacts"
        go(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + b.second))); return "Calling ${b.first}"
    }

    // ---- apps ----
    private val alias = mapOf("snap" to "snapchat", "insta" to "instagram", "yt" to "youtube", "fb" to "facebook", "ig" to "instagram")

    private fun openApp(q0: String, soft: Boolean): String {
        val q = alias[q0.trim()] ?: q0
        val pm = c.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val best = apps.maxByOrNull { sim(q, it.first) }
        val s = best?.let { sim(q, it.first) } ?: 0.0
        if (best != null && s >= (if (soft) 0.85 else 0.55)) {
            pm.getLaunchIntentForPackage(best.second)?.let { go(it); return "Opening ${best.first}" }
        }
        if (soft) return ""
        return openMedia(q, null, quiet = true) ?: "I couldn't find $q as an app or a file"
    }

    // ---- files ----
    private fun find(name: String, kind: String?): Pair<Uri, String>? {
        val col = when (kind) { "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI; else -> MediaStore.Files.getContentUri("external") }
        val like = "%" + name.trim().replace(Regex("\\s+"), "%") + "%"
        try {
            c.contentResolver.query(col, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.TITLE} LIKE ?", arrayOf(like, like),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use {
                if (it.moveToFirst()) return ContentUris.withAppendedId(col, it.getLong(0)) to (it.getString(1) ?: "*/*")
            }
        } catch (e: SecurityException) { }
        return null
    }

    private fun openMedia(name: String, kind: String?, quiet: Boolean = false): String? {
        val f = find(name, kind) ?: return if (quiet) null else "I couldn't find $name. Check that file access is allowed"
        go(Intent(Intent.ACTION_VIEW).setDataAndType(f.first, f.second).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return "Opening $name"
    }

    private fun sendFile(name: String): String {
        val f = find(name, null) ?: return "I couldn't find $name"
        val send = Intent(Intent.ACTION_SEND).setType(f.second).putExtra(Intent.EXTRA_STREAM, f.first).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        go(Intent.createChooser(send, "Send $name"))
        return "Pick where to send $name"
    }

    private val folders = mapOf("downloads" to "Download", "download" to "Download", "music" to "Music", "movies" to "Movies", "videos" to "Movies",
        "pictures" to "Pictures", "photos" to "DCIM", "camera roll" to "DCIM/Camera", "dcim" to "DCIM", "documents" to "Documents",
        "screenshots" to "Pictures/Screenshots", "recordings" to "Recordings", "received files" to "Download/Bluetooth", "bluetooth" to "Download/Bluetooth")

    private fun findDir(name: String): String? {
        val root = Environment.getExternalStorageDirectory()
        var level = listOf(root)
        repeat(3) {
            val next = mutableListOf<File>()
            for (d in level) d.listFiles()?.forEach { f ->
                if (f.isDirectory && !f.name.startsWith(".")) { if (sim(f.name, name) >= 0.75) return f.relativeTo(root).path; next += f }
            }
            level = next
        }
        return null
    }

    private fun openFolder(name: String): String {
        val rel = folders.entries.sortedByDescending { it.key.length }.firstOrNull { name.contains(it.key) }?.value ?: findDir(name)
            ?: return "I couldn't find a folder called $name"
        val uri = DocumentsContract.buildDocumentUri(DOCS, "primary:$rel")
        if (!go(Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR))) return "I couldn't open that folder"
        return "Opening $name"
    }

    // ---- usage ----
    private fun activity(): String {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
                   else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        if (mode != AppOpsManager.MODE_ALLOWED) { go(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); return "I need usage access first" }
        val now = System.currentTimeMillis()
        val top = (c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)
            .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 24 * 3600_000L, now)
            .filter { it.totalTimeInForeground > 60_000 && it.packageName != c.packageName }
            .sortedByDescending { it.totalTimeInForeground }.take(3)
        if (top.isEmpty()) return "Not much activity yet"
        val pm = c.packageManager
        return "Today you've mostly used " + top.joinToString(", ") {
            val n = try { pm.getApplicationLabel(pm.getApplicationInfo(it.packageName, 0)).toString() } catch (e: Exception) { it.packageName }
            "$n for ${it.totalTimeInForeground / 60_000} minutes"
        }
    }
}
