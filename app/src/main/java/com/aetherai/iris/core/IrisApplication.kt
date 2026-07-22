package com.aetherai.iris.core

import android.app.Application

class IrisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MemoryStore.init(this)
    }
}
