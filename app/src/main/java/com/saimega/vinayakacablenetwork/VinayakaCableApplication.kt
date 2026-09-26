package com.saimega.vinayakacablenetwork

import android.app.Application
import com.google.firebase.firestore.FirebaseFirestore

class VinayakaCableApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.USE_FIRESTORE_EMULATOR) {
            FirebaseFirestore.getInstance().useEmulator("10.0.2.2", 8080)
        }
    }
}