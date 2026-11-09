package com.tabslify.core.logging

object Log {

    fun v(tag: String, message: String) = AppLog.v(tag, message)

    fun d(tag: String, message: String) = AppLog.d(tag, message)

    fun i(tag: String, message: String) = AppLog.i(tag, message)

    fun w(tag: String, message: String) = AppLog.w(tag, message)

    fun w(tag: String, message: String, throwable: Throwable?) =
        AppLog.w(tag, message, throwable)

    fun e(tag: String, message: String) = AppLog.e(tag, message)

    fun e(tag: String, message: String, throwable: Throwable?) =
        AppLog.e(tag, message, throwable)
}
