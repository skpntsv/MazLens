package app.mazlens.core.protocol

import java.io.EOFException
import java.io.IOException
import java.io.Reader

/** Frames concatenated JSON objects without relying on TCP packet boundaries. */
class JsonObjectReader(private val reader: Reader, private val limit: Int) {
    @Throws(IOException::class)
    fun next(): String {
        val frame = StringBuilder()
        var depth = 0
        var quoted = false
        var escaped = false
        while (true) {
            val value = reader.read()
            if (value == -1) break
            val char = value.toChar()
            if (frame.isEmpty()) {
                if (Character.isWhitespace(char)) continue
                if (char != '{') throw IOException("Ответ начинается не с JSON-объекта.")
            }
            frame.append(char)
            if (frame.length > limit) throw IOException("Ответ превысил допустимый размер.")
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else if (char == '"') {
                quoted = true
            } else if (char == '{' || char == '[') {
                depth++
            } else if (char == '}' || char == ']') {
                if (--depth == 0) return frame.toString()
            }
        }
        throw EOFException(
            if (frame.isEmpty()) "Регистратор закрыл соединение без ответа."
            else "Соединение закрыто до конца JSON-ответа."
        )
    }
}
