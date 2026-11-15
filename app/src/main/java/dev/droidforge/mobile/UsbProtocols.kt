package dev.droidforge.mobile

import android.content.Context
import android.hardware.usb.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.min

/** ADB host transport over Android UsbManager bulk endpoints (ADB protocol 1.0). */
internal class AdbTransport(private val manager: UsbManager, private val device: UsbDevice, context: Context) {
    companion object {
        private const val CNXN = 0x4e584e43
        private const val AUTH = 0x48545541
        private const val OPEN = 0x4e45504f
        private const val OKAY = 0x59414b4f
        private const val CLSE = 0x45534c43
        private const val WRTE = 0x45545257
        private const val MAX_DATA = 256 * 1024
    }
    private val intf: UsbInterface
    private val input: UsbEndpoint
    private val output: UsbEndpoint
    private val connection: UsbDeviceConnection
    private var localId = 1
    private var remoteId = 0

    init {
        require(manager.hasPermission(device)) { "Сначала разрешите USB-доступ" }
        intf = (0 until device.interfaceCount).asSequence().map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == 255 && it.interfaceSubclass == 66 && it.interfaceProtocol == 1 }
            ?: error("ADB-интерфейс не найден")
        input = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
            ?: error("USB bulk IN endpoint не найден")
        output = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
            ?: error("USB bulk OUT endpoint не найден")
        connection = manager.openDevice(device) ?: error("Не удалось открыть USB-устройство")
        check(connection.claimInterface(intf, true)) { "Не удалось занять ADB-интерфейс" }
        send(CNXN, 0x01000001, MAX_DATA, "host::features=shell_v2,cmd,stat_v2,ls_v2\u0000".toByteArray())
        val authKey = AdbAuthKey(context.applicationContext)
        var hello = readFrame()
        if (hello.command == AUTH && hello.arg0 == 1) {
            send(AUTH, 2, 0, authKey.sign(hello.data))
            hello = readFrame()
            if (hello.command == AUTH && hello.arg0 == 1) {
                send(AUTH, 3, 0, authKey.adbPublicKey())
                hello = readFrame()
            }
        }
        check(hello.command == CNXN) { "ADB handshake не завершён. Разблокируйте экран телефона и подтвердите ключ DroidForge в системном окне USB-отладки." }
    }

    fun shell(command: String): ByteArray {
        val id = localId++
        val service = "shell:$command\u0000".toByteArray()
        send(OPEN, id, 0, service)
        var started = false
        val result = ByteArrayOutputStream()
        while (true) {
            val f = readFrame()
            when (f.command) {
                OKAY -> { remoteId = f.arg0; started = true; if (!started) error("ADB OPEN не подтверждён") }
                WRTE -> { result.write(f.data); send(OKAY, id, f.arg0, byteArrayOf()) }
                CLSE -> { send(CLSE, id, f.arg0, byteArrayOf()); break }
                else -> error("Неожиданный ADB кадр: ${tag(f.command)}")
            }
        }
        return result.toByteArray()
    }

    private data class Frame(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)
    private fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray) {
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(data.fold(0) { a, b -> a + (b.toInt() and 255) })
            .putInt(cmd.inv()).array()
        transferOut(h); if (data.isNotEmpty()) transferOut(data)
    }
    private fun readFrame(): Frame {
        val h = ByteArray(24); transferIn(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val n = b.int; val checksum = b.int; val magic = b.int
        check(magic == cmd.inv() && n in 0..MAX_DATA) { "Некорректный ADB кадр" }
        val data = ByteArray(n); if (n > 0) transferIn(data)
        check(data.fold(0) { a, v -> a + (v.toInt() and 255) } == checksum) { "ADB checksum mismatch" }
        return Frame(cmd, a0, a1, data)
    }
    private fun transferOut(bytes: ByteArray) {
        var off = 0
        while (off < bytes.size) {
            val n = connection.bulkTransfer(output, bytes, off, bytes.size - off, 15_000)
            check(n > 0) { "Таймаут отправки USB" }; off += n
        }
    }
    private fun transferIn(bytes: ByteArray) {
        var off = 0
        while (off < bytes.size) {
            val n = connection.bulkTransfer(input, bytes, off, bytes.size - off, 30_000)
            check(n > 0) { "Таймаут чтения USB" }; off += n
        }
    }
    fun sideload(path: String) {
        val id = localId++
        send(OPEN, id, 0, "sideload:\u0000".toByteArray())
        val ready = readFrame()
        check(ready.command == OKAY) { "Устройство не принимает sideload. Переведите его в режим ADB sideload." }
        remoteId = ready.arg0
        java.io.File(path).inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buffer); if (n < 0) break
                send(WRTE, id, remoteId, buffer.copyOf(n))
                val ack = readFrame()
                check(ack.command == OKAY) { "Передача sideload прервана" }
            }
        }
        send(CLSE, id, remoteId, byteArrayOf())
        val result = readFrame()
        check(result.command == CLSE || result.command == OKAY) { "Устройство не подтвердило sideload" }
    }
    fun push(path: String, remote: String, modified: Int = 0) {
        val id = localId++
        send(OPEN, id, 0, "sync:\u0000".toByteArray())
        val ready = readFrame()
        check(ready.command == OKAY) { "ADB sync service не запустился" }
        val rid = ready.arg0
        remoteId = rid
        val name = "$remote,33206".toByteArray()
        syncPacket("SEND", name)
        java.io.File(path).inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buffer); if (n < 0) break
                val data = ByteBuffer.allocate(4 + n).order(ByteOrder.LITTLE_ENDIAN).putInt(n).put(buffer, 0, n).array()
                syncPacket("DATA", data)
            }
        }
        syncPacket("DONE", ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(modified).array())
        val reply = readSyncPacket()
        check(reply.first == "OKAY") { "ADB push: " + String(reply.second, StandardCharsets.UTF_8) }
        send(CLSE, id, rid, byteArrayOf())
        readFrame()
    }
    fun pull(remote: String, destination: String) {
        val id = localId++
        send(OPEN, id, 0, "sync:\u0000".toByteArray())
        val ready = readFrame()
        check(ready.command == OKAY) { "ADB sync service не запустился" }
        val rid = ready.arg0
        remoteId = rid
        syncPacket("RECV", remote.toByteArray())
        java.io.File(destination).outputStream().use { out ->
            while (true) {
                val packet = readSyncPacket()
                when (packet.first) {
                    "DATA" -> out.write(packet.second)
                    "DONE" -> break
                    "FAIL" -> error(String(packet.second, StandardCharsets.UTF_8))
                    else -> error("Неожиданный ответ ADB sync: ${packet.first}")
                }
            }
        }
        send(CLSE, id, rid, byteArrayOf()); readFrame()
    }
    private fun syncPacket(name: String, data: ByteArray) {
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(name.toByteArray(StandardCharsets.US_ASCII)).putInt(data.size).array()
        send(WRTE, localId - 1, remoteId, header + data)
        val ack = readFrame()
        check(ack.command == OKAY) { "ADB sync запись не подтверждена" }
    }
    private fun readSyncPacket(): Pair<String, ByteArray> {
        val frame = readFrame()
        check(frame.command == WRTE) { "ADB sync кадр не получен" }
        send(OKAY, localId - 1, frame.arg0, byteArrayOf())
        val b = ByteBuffer.wrap(frame.data).order(ByteOrder.LITTLE_ENDIAN)
        val nameBytes = ByteArray(4); b.get(nameBytes); val name = String(nameBytes, StandardCharsets.US_ASCII)
        val size = b.int
        if (name == "DONE") return name to byteArrayOf()
        check(size in 0..MAX_DATA && size <= b.remaining()) { "Некорректный ADB sync пакет" }
        val payload = ByteArray(size); b.get(payload); return name to payload
    }
    fun close() { runCatching { connection.releaseInterface(intf); connection.close() } }
    private fun tag(v: Int) = String(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()), StandardCharsets.US_ASCII)
}

/** Fastboot USB transport. Commands are exposed only when a Fastboot interface exists. */
internal class FastbootTransport(private val manager: UsbManager, device: UsbDevice) {
    private val intf: UsbInterface
    private val input: UsbEndpoint
    private val output: UsbEndpoint
    private val connection: UsbDeviceConnection
    init {
        require(manager.hasPermission(device)) { "Сначала разрешите USB-доступ" }
        intf = (0 until device.interfaceCount).asSequence().map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == 255 && it.interfaceSubclass == 66 && it.interfaceProtocol == 3 }
            ?: error("Fastboot-интерфейс не найден")
        input = (0 until intf.endpointCount).map { intf.getEndpoint(it) }.firstOrNull { it.type == 2 && it.direction == 128 }
            ?: error("Fastboot bulk IN endpoint не найден")
        output = (0 until intf.endpointCount).map { intf.getEndpoint(it) }.firstOrNull { it.type == 2 && it.direction == 0 }
            ?: error("Fastboot bulk OUT endpoint не найден")
        connection = manager.openDevice(device) ?: error("Не удалось открыть USB-устройство")
        check(connection.claimInterface(intf, true)) { "Не удалось занять Fastboot-интерфейс" }
    }
    fun command(command: String): String {
        val bytes = command.toByteArray(StandardCharsets.US_ASCII)
        check(bytes.size <= 64) { "Команда Fastboot длиннее 64 байт" }
        check(connection.bulkTransfer(output, bytes, bytes.size, 15_000) == bytes.size) { "Не удалось отправить Fastboot-команду" }
        return readStatus()
    }
    fun flash(partition: String, image: ByteArray, boot: Boolean = false): String {
        require(partition.matches(Regex("[A-Za-z0-9_.-]{1,32}"))) { "Недопустимое имя раздела" }
        if (boot) {
            download(image)
            return command("boot")
        }
        download(image)
        return command("flash:$partition")
    }
    private fun download(image: ByteArray) {
        require(image.isNotEmpty()) { "Файл образа пуст" }
        val request = "download:%08x".format(image.size).toByteArray(StandardCharsets.US_ASCII)
        check(connection.bulkTransfer(output, request, request.size, 15_000) == request.size) { "Не удалось запросить загрузку образа" }
        val reply = ByteArray(64)
        val replySize = connection.bulkTransfer(input, reply, reply.size, 30_000)
        check(replySize >= 12 && String(reply, 0, 4, StandardCharsets.US_ASCII) == "DATA") { "Fastboot не принял запрос загрузки" }
        val allowed = String(reply, 4, replySize - 4, StandardCharsets.US_ASCII).trim().toLongOrNull(16)
            ?: error("Некорректный ответ Fastboot DATA")
        require(image.size.toLong() <= allowed) { "Образ превышает допустимый размер Fastboot" }
        var off = 0
        while (off < image.size) {
            val count = min(64 * 1024, image.size - off)
            val n = connection.bulkTransfer(output, image, off, count, 30_000)
            check(n > 0) { "Ошибка передачи образа Fastboot" }
            off += n
        }
        readStatus()
    }
    private fun readStatus(): String {
        val response = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (true) {
            val n = connection.bulkTransfer(input, chunk, chunk.size, 30_000)
            check(n >= 4) { "Таймаут Fastboot" }
            val code = String(chunk, 0, 4, StandardCharsets.US_ASCII)
            response.write(chunk, 4, n - 4)
            when (code) {
                "INFO" -> continue
                "DATA" -> error("Fastboot ожидает файл: " + response.toString("UTF-8"))
                "OKAY" -> return response.toString("UTF-8")
                "FAIL" -> error(response.toString("UTF-8").ifBlank { "Fastboot отказал в выполнении команды" })
                else -> error("Неизвестный ответ Fastboot: $code")
            }
        }
    }
    fun close() { runCatching { connection.releaseInterface(intf); connection.close() } }
}
