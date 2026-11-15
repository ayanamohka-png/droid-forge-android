package dev.droidforge.mobile

import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    companion object {
        private const val ACTION_PERMISSION = "dev.droidforge.mobile.USB_PERMISSION"
        init { System.loadLibrary("droidforge_mobile_core") }
    }
    private external fun nativeDescribeUsb(vendorId: Int, productId: Int, adb: Boolean, fastboot: Boolean): String
    private lateinit var usb: UsbManager
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_PERMISSION) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            status.text = if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                "Доступ выдан: %04x:%04x".format(device.vendorId, device.productId)
            else "Доступ не выдан — подтвердите запрос Android"
            showDevices()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(USB_SERVICE) as UsbManager
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, IntentFilter(ACTION_PERMISSION), RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, IntentFilter(ACTION_PERMISSION))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(22, 28, 22, 16); setBackgroundColor(0xFF101418.toInt())
        }
        root.addView(TextView(this).apply { text = "DroidForge"; textSize = 28f; setTextColor(0xFFEAF2F5.toInt()) })
        root.addView(TextView(this).apply { text = "USB OTG  •  ADB / Fastboot"; textSize = 14f; setTextColor(0xFF32D3EE.toInt()); setPadding(0, 4, 0, 16) })
        status = TextView(this).apply { textSize = 14f; setTextColor(0xFFB8C4CA.toInt()); setPadding(0, 0, 0, 10) }
        root.addView(status)
        root.addView(Button(this).apply { text = "Обновить устройства"; setOnClickListener { showDevices() } })
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 12, 0, 0) }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root); showDevices()
    }
    override fun onDestroy() { unregisterReceiver(receiver); super.onDestroy() }
    private fun showDevices() {
        list.removeAllViews()
        val devices = usb.deviceList.values
        if (devices.isEmpty()) { status.text = "Нет устройств. Проверьте OTG-переходник и кабель."; return }
        status.text = "Найдено USB-устройств: ${devices.size}"
        devices.forEach { device ->
            val descriptors = (0 until device.interfaceCount).joinToString(",") { i ->
                val intf = device.getInterface(i); "${intf.interfaceClass}:${intf.interfaceSubclass}:${intf.interfaceProtocol}"
            }
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14, 12, 14, 12); setBackgroundColor(0xFF1B2329.toInt()) }
            card.addView(TextView(this).apply { text = "USB %04x:%04x".format(device.vendorId, device.productId); textSize = 17f; setTextColor(0xFFEAF2F5.toInt()) })
            val adb = descriptors.split(',').any { it == "255:66:1" }
            val fastboot = descriptors.split(',').any { it == "255:66:3" }
            card.addView(TextView(this).apply { text = nativeDescribeUsb(device.vendorId, device.productId, adb, fastboot); textSize = 13f; setTextColor(0xFFB8C4CA.toInt()); setPadding(0, 6, 0, 6) })
            val permitted = usb.hasPermission(device)
            card.addView(Button(this).apply {
                text = if (permitted) "Доступ разрешён" else "Запросить доступ"; isEnabled = !permitted
                setOnClickListener {
                    val request = PendingIntent.getBroadcast(this@MainActivity, 0, Intent(ACTION_PERMISSION).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
                    usb.requestPermission(device, request); status.text = "Ожидание системного подтверждения USB-доступа…"
                }
            })
            list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10 })
        }
    }
}
