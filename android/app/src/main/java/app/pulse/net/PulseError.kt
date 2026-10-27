package app.pulse.net

/**
 * Пользовательские ошибки репозитория. Тексты подставляет UI по локали
 * устройства — сетевой слой не знает про переводы.
 */
sealed class PulseError(message: String) : Exception(message) {
    /** Нет соединения с релеем. */
    object Offline : PulseError("offline")

    /** Релей не ответил за отведенное время. */
    object Timeout : PulseError("timeout")

    /** Релей ответил ошибкой — текст пробрасываем как есть (данные сервера). */
    data class Relay(val reason: String) : PulseError(reason)
}
