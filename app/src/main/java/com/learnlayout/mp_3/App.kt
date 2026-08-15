package com.learnlayout.mp_3

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
    }
}