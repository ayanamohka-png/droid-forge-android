package dev.droidforge.mobile

import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val ACTION_PERMISSION = "dev.droidforge.mobile.USB_PERMISSION"
        init { System.loadLibrary("droidforge_mobile_core") }
    }
    private external fun nativeDescribeUsb(vendorId: Int, productId: Int, adb: Boolean, fastboot: Boolean): String
    private lateinit var usb: UsbManager
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var devices: List<UsbDevice>
    private var chosen: UsbDevice? = null
    private val worker = Executors.newSingleThreadExecutor()
    private var pending: ((File) -> Unit)? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_PERMISSION) return
            @Suppress("DEPRECATION")
            val d = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            status.text = if (d != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                "USB-доступ разрешён: %04x:%04x".format(d.vendorId, d.productId)
            else "Доступ не выдан — подтвердите системный запрос"
            renderDevices()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(USB_SERVICE) as UsbManager
        val filter = IntentFilter(ACTION_PERMISSION)
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(20, 24, 20, 12); setBackgroundColor(0xFF101418.toInt())
        }
        root.addView(TextView(this).apply { text = "DroidForge"; textSize = 28f; setTextColor(0xFFEAF2F5.toInt()) })
        root.addView(TextView(this).apply { text = "USB OTG  •  ADB / Fastboot"; textSize = 14f; setTextColor(0xFF32D3EE.toInt()); setPadding(0, 3, 0, 8) })
        status = TextView(this).apply { textSize = 14f; setTextColor(0xFFB8C4CA.toInt()); setPadding(0, 4, 0, 8) }
        root.addView(status)
        root.addView(Button(this).apply { text = "Обновить USB-устройства"; setOnClickListener { renderDevices() } })
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 10, 0, 16) }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        renderDevices()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun renderDevices() {
        list.removeAllViews()
        devices = usb.deviceList.values.toList()
        if (devices.isEmpty()) {
            chosen = null
            status.text = "Нет USB-устройств. Проверьте OTG-адаптер и кабель."
            return
        }
        if (chosen !in devices) chosen = devices.first()
        status.text = "Найдено USB-устройств: ${devices.size}"
        devices.forEach { device ->
            val adb = (0 until device.interfaceCount).any { i ->
                val x = device.getInterface(i); x.interfaceClass == 255 && x.interfaceSubclass == 66 && x.interfaceProtocol == 1
            }
            val fastboot = (0 until device.interfaceCount).any { i ->
                val x = device.getInterface(i); x.interfaceClass == 255 && x.interfaceSubclass == 66 && x.interfaceProtocol == 3
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(14, 12, 14, 12); setBackgroundColor(0xFF1B2329.toInt())
            }
            card.addView(TextView(this).apply {
                text = "USB %04x:%04x%s".format(device.vendorId, device.productId, if (chosen == device) "  • выбрано" else "")
                textSize = 17f; setTextColor(0xFFEAF2F5.toInt())
            })
            card.addView(TextView(this).apply {
                text = nativeDescribeUsb(device.vendorId, device.productId, adb, fastboot)
                textSize = 13f; setTextColor(0xFFB8C4CA.toInt()); setPadding(0, 6, 0, 2)
            })
            val allowed = usb.hasPermission(device)
            card.addView(Button(this).apply {
                text = if (allowed) "Выбрать устройство" else "Запросить USB-доступ"
                setOnClickListener {
                    chosen = device
                    if (!usb.hasPermission(device)) {
                        val request = PendingIntent.getBroadcast(this@MainActivity, device.deviceId,
                            Intent(ACTION_PERMISSION).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
                        usb.requestPermission(device, request)
                        status.text = "Ожидается системное разрешение USB…"
                    } else renderDevices()
                }
            })
            if (chosen == device && allowed) addActions(card, device, adb, fastboot)
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 })
        }
    }

    private fun addActions(card: LinearLayout, device: UsbDevice, adb: Boolean, fastboot: Boolean) {
        val heading = { title: String ->
            card.addView(TextView(this).apply { text = title; textSize = 18f; setTextColor(0xFF32D3EE.toInt()); setPadding(0, 14, 0, 4) })
        }
        val button = { title: String, action: () -> Unit ->
            card.addView(Button(this).apply { text = title; isEnabled = if (title.startsWith("ADB")) adb else fastboot; setOnClickListener { action() } })
        }
        heading("ADB")
        button("ADB: Информация об устройстве") { runAdb(device) { it.shell("getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk").toString(Charsets.UTF_8) } }
        button("ADB: Сохранить logcat") {
            runAdb(device) { transport ->
                val log = transport.shell("logcat -d").toString(Charsets.UTF_8)
                File(filesDir, "droidforge-logcat.txt").writeText(log)
                "Лог сохранён: ${File(filesDir, "droidforge-logcat.txt").absolutePath} (${log.length} символов)"
            }
        }
        button("ADB: Скриншот") {
            runAdb(device) { transport ->
                val bytes = transport.shell("screencap -p")
                val out = File(filesDir, "droidforge-screen.png"); out.writeBytes(bytes)
                "Скриншот сохранён: ${out.absolutePath} (${bytes.size} байт)"
            }
        }
        button("ADB: Установить APK") { pickFile { file ->
            runAdb(device) { t ->
                val remote = "/data/local/tmp/${file.name}"
                t.push(file.absolutePath, remote)
                t.shell("pm install -r '$remote'").toString(Charsets.UTF_8)
            }
        } }
        button("ADB: Передать файл на телефон") { pickFile { file ->
            val dest = editText("Путь назначения", "/sdcard/${file.name}") { path ->
                runAdb(device) { it.push(file.absolutePath, path); "Файл передан в $path" }
            }
        } }
        button("ADB: Забрать файл с телефона") {
            val remote = editText("Путь на телефоне", "/sdcard/") { path ->
                val target = File(getExternalFilesDir(null), File(path).name.ifBlank { "adb-pull.bin" })
                runAdb(device) { it.pull(path, target.absolutePath); "Файл сохранён: ${target.absolutePath}" }
            }
        }
        button("ADB: Перезагрузить") { runAdb(device) { it.shell("reboot"); "Команда перезагрузки отправлена" } }
        button("ADB: Recovery / Bootloader") {
            choose("Режим загрузки", listOf("recovery", "bootloader", "sideload")) { mode ->
                runAdb(device) { it.shell("reboot $mode"); "Перезагрузка в режим $mode отправлена" }
            }
        }

        heading("Fastboot")
        button("Fastboot: Узнать product") { runFastboot(device) { it.command("getvar:product") } }
        button("Fastboot: Прошить раздел") {
            choose("Раздел", listOf("boot", "recovery", "system", "vendor", "radio")) { part ->
                pickFile { file -> confirm("Прошить раздел $part файлом ${file.name}?") {
                    runFastboot(device) { t -> t.flash(part, file.readBytes()); "Раздел $part прошит." }
                } }
            }
        }
        button("Fastboot: Загрузить образ без прошивки") { pickFile { file ->
            confirm("Загрузить образ ${file.name} без записи в раздел?") {
                runFastboot(device) { it.flash("boot", file.readBytes(), boot = true); "Команда загрузки образа отправлена." }
            }
        } }
        button("Fastboot: Стереть раздел") { choose("Раздел для стирания", listOf("userdata", "cache", "boot", "recovery")) { part ->
            confirm("Стереть раздел $part? Данные могут быть потеряны.") {
                runFastboot(device) { it.command("erase:$part") }
            }
        } }
        button("Fastboot: Разблокировать загрузчик") {
            confirm("Разблокировать загрузчик? Устройство может выполнить сброс данных.") {
                runFastboot(device) { it.command("flashing unlock") }
            }
        }
        button("Fastboot: Заблокировать загрузчик") {
            confirm("Заблокировать загрузчик? Убедитесь, что устройство загружается со штатной прошивкой.") {
                runFastboot(device) { it.command("flashing lock") }
            }
        }
        button("Fastboot: Перезагрузить систему") { runFastboot(device) { it.command("reboot") } }
        button("ADB: Sideload ZIP") { pickFile { file ->
            confirm("Устройство уже должно быть в режиме ADB sideload. Передать ${file.name}?") {
                runAdb(device) { it.sideload(file.absolutePath); "Sideload завершён." }
            }
        } }
    }

    private fun runAdb(device: UsbDevice, task: (AdbTransport) -> String) = runTask("ADB") {
        val t = AdbTransport(usb, device, this)
        try { task(t) } finally { t.close() }
    }
    private fun runFastboot(device: UsbDevice, task: (FastbootTransport) -> String) = runTask("Fastboot") {
        val t = FastbootTransport(usb, device)
        try { task(t) } finally { t.close() }
    }
    private fun runTask(label: String, task: () -> String) {
        status.text = "$label: выполнение…"
        worker.execute {
            val result = runCatching(task).fold({ it.ifBlank { "Готово" } }, { "Ошибка: ${it.message ?: it.javaClass.simpleName}" })
            runOnUiThread { status.text = result; Toast.makeText(this, result, Toast.LENGTH_LONG).show() }
        }
    }
    private fun pickFile(done: (File) -> Unit) {
        pending = done
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 101)
    }
    @Deprecated("Legacy result API retained for API 23")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 101 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        worker.execute {
            val file = runCatching { copyUri(uri) }.getOrElse { runOnUiThread { show("Не удалось прочитать файл: ${it.message}") }; return@execute }
            runOnUiThread { pending?.invoke(file); pending = null }
        }
    }
    private fun copyUri(uri: Uri): File {
        val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        } ?: "selected-file"
        val out = File(cacheDir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        contentResolver.openInputStream(uri).use { input -> requireNotNull(input).use { src -> out.outputStream().use(src::copyTo) } }
        return out
    }
    private fun editText(title: String, initial: String, done: (String) -> Unit) {
        val input = EditText(this).apply { setText(initial) }
        android.app.AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("Продолжить") { _, _ -> done(input.text.toString()) }.setNegativeButton("Отмена", null).show()
    }
    private fun choose(title: String, options: List<String>, done: (String) -> Unit) {
        android.app.AlertDialog.Builder(this).setTitle(title).setItems(options.toTypedArray()) { _, i -> done(options[i]) }.show()
    }
    private fun confirm(message: String, done: () -> Unit) {
        android.app.AlertDialog.Builder(this).setMessage(message).setPositiveButton("Продолжить") { _, _ -> done() }.setNegativeButton("Отмена", null).show()
    }
    private fun show(message: String) { android.app.AlertDialog.Builder(this).setMessage(message).setPositiveButton("ОК", null).show() }
}
