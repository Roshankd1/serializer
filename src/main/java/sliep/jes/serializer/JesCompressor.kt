package sliep.jes.serializer

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

fun JSONObject.compress() = compressInternal().toByteArray()
fun JSONArray.compress() = compressInternal().toByteArray()

fun ByteArray.decompress(): Any {
    val buffer = ByteBuffer.wrap(this)
    if (buffer.get() == TYPE_ARRAY) {
        val elements = JSONArray()
        while (buffer.remaining() > 0) {
            val type = buffer.get()
            val value = when (type) {
                TYPE_OBJECT, TYPE_ARRAY -> buffer.data.decompress()
                TYPE_LONG -> buffer.long
                TYPE_DOUBLE -> buffer.double
                TYPE_FLOAT -> buffer.float
                TYPE_INT -> buffer.int
                TYPE_SHORT -> buffer.short
                TYPE_BYTE -> buffer.get()
                TYPE_BOOLEAN -> buffer.get() == 1.toByte()
                TYPE_STRING -> buffer.string
                else -> throw UnsupportedOperationException(type.toString())
            }
            elements.put(value)
        }
        return elements
    } else {
        val element = JSONObject()
        while (buffer.remaining() > 0) {
            val type = buffer.get()
            val name = buffer.string
            val value = when (type) {
                TYPE_OBJECT, TYPE_ARRAY -> buffer.data.decompress()
                TYPE_LONG -> buffer.long
                TYPE_DOUBLE -> buffer.double
                TYPE_FLOAT -> buffer.float
                TYPE_INT -> buffer.int
                TYPE_SHORT -> buffer.short
                TYPE_BYTE -> buffer.get()
                TYPE_BOOLEAN -> buffer.get() == 1.toByte()
                TYPE_STRING -> buffer.string
                else -> throw UnsupportedOperationException(type.toString())
            }
            element.put(name, value)
        }
        return element
    }
}

private fun JSONObject.compressInternal(): BinaryElement {
    val element = BinaryElement(TYPE_OBJECT)
    for (key in keys()) {
        val value = this[key]
        when (value) {
            is JSONObject -> element.put(key, value.compressInternal())
            is JSONArray -> element.put(key, value.compressInternal())
            else -> element.putPrimitive(key, value)
        }
    }
    return element
}

private fun JSONArray.compressInternal(): BinaryElement {
    val element = BinaryElement(TYPE_ARRAY)
    for (i in 0 until length()) {
        val value = this[i]
        when (value) {
            is JSONObject -> element.put(i.toString(), value.compressInternal())
            is JSONArray -> element.put(i.toString(), value.compressInternal())
            else -> element.putPrimitive(i.toString(), value)
        }
    }
    return element
}

private const val TYPE_OBJECT: Byte = 0
private const val TYPE_ARRAY: Byte = 1
private const val TYPE_LONG: Byte = 2
private const val TYPE_DOUBLE: Byte = 3
private const val TYPE_FLOAT: Byte = 4
private const val TYPE_INT: Byte = 5
private const val TYPE_SHORT: Byte = 6
private const val TYPE_BYTE: Byte = 7
private const val TYPE_BOOLEAN: Byte = 8
private const val TYPE_STRING: Byte = 9

private class BinaryElement(private val type: Byte) {
    private var length = 0
    private val queue = HashMap<String, Any>()

    fun put(name: String, value: BinaryElement) = putElement(name, value)

    fun putPrimitive(name: String, value: Any) {
        when (value) {
            is Number -> putElement(name, value.reduceSize)
            is Boolean, is String -> putElement(name, value)
            else -> throw UnsupportedOperationException(value.toString())
        }
    }

    /**
     * **** OBJECT
     * **** TYPE NS NAME SIZE DATA
     *
     * **** ARRAY
     * **** TYPE NS NAME SIZE DATA
     *
     * **** PRIMITIVE
     * **** TYPE NS NAME DATA
     *
     * TYPE LENGTH [SIZE DATA,SIZE DATA,SIZE DATA]
     */
    private fun putElement(name: String, value: Any) {
        queue[name] = value
        /**TYPE**/
        length += Byte.SIZE_BYTES
        /**NAME**/
        if (type != TYPE_ARRAY) length += Int.SIZE_BYTES + name.bytes.size
        /**DATA**/
        length += when (value) {
            is Number -> value.size
            is Boolean -> Byte.SIZE_BYTES
            is String -> Int.SIZE_BYTES + value.bytes.size
            is BinaryElement -> Byte.SIZE_BYTES + Int.SIZE_BYTES + value.length
            else -> throw UnsupportedOperationException(value.toString())
        }
    }

    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(Byte.SIZE_BYTES + length)
        /**TYPE**/
        buffer.put(type)
        for (key in queue.keys) {
            val value = queue[key]
            when (value) {
                is Number -> {
                    /**TYPE**/
                    buffer.put(value.type)
                    /**NAME**/
                    if (type != TYPE_ARRAY)
                        buffer.putString(key)
                    /**DATA**/
                    when (value) {
                        is Long -> buffer.putLong(value)
                        is Double -> buffer.putDouble(value)
                        is Float -> buffer.putFloat(value)
                        is Int -> buffer.putInt(value)
                        is Short -> buffer.putShort(value)
                        is Byte -> buffer.put(value)
                    }
                }
                is Boolean -> {
                    /**TYPE**/
                    buffer.put(TYPE_BOOLEAN)
                    /**NAME**/
                    if (type != TYPE_ARRAY)
                        buffer.putString(key)
                    /**DATA**/
                    buffer.put(if (value) 1.toByte() else 0.toByte())
                }
                is String -> {
                    /**TYPE**/
                    buffer.put(TYPE_STRING)
                    /**NAME**/
                    if (type != TYPE_ARRAY)
                        buffer.putString(key)
                    /**SIZE**/
                    buffer.putInt(value.bytes.size)
                    /**DATA**/
                    buffer.put(value.bytes)
                }
                is BinaryElement -> {
                    /**TYPE**/
                    buffer.put(value.type)
                    /**NAME**/
                    if (type != TYPE_ARRAY)
                        buffer.putString(key)
                    /**SIZE/LENGTH**/
                    buffer.putInt(Byte.SIZE_BYTES + value.length)
                    /**DATA**/
                    buffer.put(value.toByteArray())
                }
            }
        }
        return buffer.array()
    }
}

private val String.bytes: ByteArray
    get() = toByteArray(StandardCharsets.UTF_8)
val ByteBuffer.string: String
    get() = String(data, StandardCharsets.UTF_8)
val ByteBuffer.data: ByteArray
    get() {
        val bytes = ByteArray(int)
        get(bytes)
        return bytes
    }

fun ByteBuffer.putData(data: ByteArray) {
    putInt(data.size)
    put(data)
}

fun ByteBuffer.putString(data: String) = putData(data.bytes)

val Number.size: Int
    get() = when (this) {
        is Long -> Long.SIZE_BYTES
        is Double -> java.lang.Double.BYTES
        is Float -> java.lang.Float.BYTES
        is Int -> Int.SIZE_BYTES
        is Short -> Short.SIZE_BYTES
        is Byte -> Byte.SIZE_BYTES
        else -> throw UnsupportedOperationException("not a number")
    }
val Number.type: Byte
    get() = when (this) {
        is Long -> TYPE_LONG
        is Double -> TYPE_DOUBLE
        is Float -> TYPE_FLOAT
        is Int -> TYPE_INT
        is Short -> TYPE_SHORT
        is Byte -> TYPE_BYTE
        else -> throw UnsupportedOperationException("not a number")
    }
val Number.reduceSize: Number
    get() = when (this) {
        is Double -> kotlin.runCatching { toBigDecimal().longValueExact().reduceSize }
                .getOrElse { if (toString() == toFloat().toString()) toFloat() else this }
        is Float -> kotlin.runCatching { toBigDecimal().longValueExact().reduceSize }
                .getOrDefault(this)
        is Long -> when {
            this > Byte.MIN_VALUE && this < Byte.MAX_VALUE -> toByte()
            this > Short.MIN_VALUE && this < Short.MAX_VALUE -> toShort()
            this > Int.MIN_VALUE && this < Int.MAX_VALUE -> toInt()
            else -> this
        }
        is Int -> when {
            this > Byte.MIN_VALUE && this < Byte.MAX_VALUE -> toByte()
            this > Short.MIN_VALUE && this < Short.MAX_VALUE -> toShort()
            else -> this
        }
        is Short -> if (this > Byte.MIN_VALUE && this < Byte.MAX_VALUE) toByte() else this
        is Byte -> this
        else -> throw UnsupportedOperationException("not a number")
    }
