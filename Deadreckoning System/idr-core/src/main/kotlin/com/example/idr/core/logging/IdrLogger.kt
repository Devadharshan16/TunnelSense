package com.example.idr.core.logging

/**
 * Pluggable logging interface so core IDR estimation logic can run
 * seamlessly on Android (via Logcat), desktop JVM / Raspberry Pi (stdout),
 * or headless embedded runtimes without platform dependencies.
 */
interface IdrLogger {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

object ConsoleIdrLogger : IdrLogger {
    override fun d(tag: String, message: String) = println("DEBUG [$tag]: $message")
    override fun w(tag: String, message: String) = println("WARN  [$tag]: $message")
    override fun e(tag: String, message: String, throwable: Throwable?) {
        System.err.println("ERROR [$tag]: $message")
        throwable?.printStackTrace()
    }
}

object NoOpIdrLogger : IdrLogger {
    override fun d(tag: String, message: String) {}
    override fun w(tag: String, message: String) {}
    override fun e(tag: String, message: String, throwable: Throwable?) {}
}
