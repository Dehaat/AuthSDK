package com.example.dehaatauthsdk

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.Exception
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.experimental.and

class SNTPClient
internal constructor(
    private val listener: Listener
) {
    interface Listener {
        fun onTimeResponse(rawDate: String?, date: Date?, ex: Exception?)
    }

    var ntpTime: Long = 0
        private set

    var ntpTimeReference: Long = 0
        private set

    var roundTripTime: Long = 0
        private set

    fun requestTime(host: String?, timeout: Int): Boolean {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = timeout
            val address = InetAddress.getByName(host)
            val buffer = ByteArray(NTP_PACKET_SIZE)
            val request = DatagramPacket(buffer, buffer.size, address, NTP_PORT)
            buffer[0] = (NTP_MODE_CLIENT or (NTP_VERSION shl 3)).toByte()

            // get current time and write it to the request packet
            val requestTime = System.currentTimeMillis()
            val requestTicks = SystemClock.elapsedRealtime()
            writeTimeStamp(buffer, TRANSMIT_TIME_OFFSET, requestTime)
            socket.send(request)

            // read the response
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            val responseTicks = SystemClock.elapsedRealtime()
            val responseTime = requestTime + (responseTicks - requestTicks)

            // extract the results
            val originateTime = readTimeStamp(buffer, ORIGINATE_TIME_OFFSET)
            val receiveTime = readTimeStamp(buffer, RECEIVE_TIME_OFFSET)
            val transmitTime = readTimeStamp(buffer, TRANSMIT_TIME_OFFSET)
            val roundTripTime = responseTicks - requestTicks - (transmitTime - receiveTime)
            val clockOffset = (receiveTime - originateTime + (transmitTime - responseTime)) / 2
            ntpTime = responseTime + clockOffset
            ntpTimeReference = responseTicks
            this.roundTripTime = roundTripTime
        } catch (e: Exception) {
            Handler(Looper.getMainLooper()).post { listener.onTimeResponse(null, null, e) }
            return false
        } finally {
            socket?.close()
        }
        return true
    }

    private fun read32(buffer: ByteArray, offset: Int): Long {
        val b0 = buffer[offset]
        val b1 = buffer[offset + 1]
        val b2 = buffer[offset + 2]
        val b3 = buffer[offset + 3]

        val i0 = if ((b0.and(0x80.toByte())) == 0x80.toByte()) (b0.and(0x7F)) + 0x80 else b0.toInt()
        val i1 = if ((b1.and(0x80.toByte())) == 0x80.toByte()) (b1.and(0x7F)) + 0x80 else b1.toInt()
        val i2 = if ((b2.and(0x80.toByte())) == 0x80.toByte()) (b2.and(0x7F)) + 0x80 else b2.toInt()
        val i3 = if ((b3.and(0x80.toByte())) == 0x80.toByte()) (b3.and(0x7F)) + 0x80 else b3.toInt()
        return (i0.toLong() shl 24) + (i1.toLong() shl 16) + (i2.toLong() shl 8) + i3.toLong()
    }

    private fun readTimeStamp(buffer: ByteArray, offset: Int): Long {
        val seconds = read32(buffer, offset)
        val fraction = read32(buffer, offset + 4)
        return (seconds - OFFSET_1900_TO_1970) * 1000 + fraction * 1000L / 0x100000000L
    }

    private fun writeTimeStamp(buffer: ByteArray, offset: Int, time: Long) {
        var offset = offset
        var seconds = time / 1000L
        val milliseconds = time - seconds * 1000L
        seconds += OFFSET_1900_TO_1970

        // write seconds in big endian format
        buffer[offset++] = (seconds shr 24).toByte()
        buffer[offset++] = (seconds shr 16).toByte()
        buffer[offset++] = (seconds shr 8).toByte()
        buffer[offset++] = (seconds shr 0).toByte()
        val fraction = milliseconds * 0x100000000L / 1000L
        // write fraction in big endian format
        buffer[offset++] = (fraction shr 24).toByte()
        buffer[offset++] = (fraction shr 16).toByte()
        buffer[offset++] = (fraction shr 8).toByte()
        // low order bits should be random data
        buffer[offset++] = (Math.random() * 255.0).toInt().toByte()
    }

    companion object {
        const val DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ssZ"
        val SIMPLE_DATE_FORMAT = SimpleDateFormat(DATE_FORMAT, Locale.US)
        private const val REFERENCE_TIME_OFFSET = 16
        private const val ORIGINATE_TIME_OFFSET = 24
        private const val RECEIVE_TIME_OFFSET = 32
        private const val TRANSMIT_TIME_OFFSET = 40
        private const val NTP_PACKET_SIZE = 48
        private const val NTP_PORT = 123
        private const val NTP_MODE_CLIENT = 3
        private const val NTP_VERSION = 3

        private const val OFFSET_1900_TO_1970 = (365L * 70L + 17L) * 24L * 60L * 60L
        fun getDate(_timeZone: TimeZone, _listener: Listener) {
            Thread {
                val sntpClient = SNTPClient(_listener)
                if (sntpClient.requestTime("time.google.com", 5000)) {
                    val nowAsPerDeviceTimeZone = sntpClient.ntpTime
                    SIMPLE_DATE_FORMAT.timeZone = _timeZone
                    val rawDate = SIMPLE_DATE_FORMAT.format(nowAsPerDeviceTimeZone)
                    try {
                        val date = SIMPLE_DATE_FORMAT.parse(rawDate)
                        Handler(Looper.getMainLooper()).post {
                            _listener.onTimeResponse(
                                rawDate,
                                date,
                                null
                            )
                        }
                    } catch (e: ParseException) {
                        Handler(Looper.getMainLooper()).post {
                            _listener.onTimeResponse(
                                null,
                                null,
                                e
                            )
                        }
                    }
                }
            }.start()
        }
    }
}