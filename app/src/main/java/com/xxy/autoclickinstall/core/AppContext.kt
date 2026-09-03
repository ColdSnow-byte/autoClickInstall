package com.xxy.autoclickinstall.core

import android.content.Context

object AppContext {

    private var context: Context? = null

    fun init(app: Context) {
        context = app.applicationContext
    }

    fun get(): Context = context ?: throw IllegalStateException("AppContext 尚未初始化")
}
