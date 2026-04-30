package com.familyguardian.app

import android.app.Application
import com.familyguardian.app.cloud.CloudBaseClient

class FamilyGuardianApp : Application() {
    
    companion object {
        lateinit var instance: FamilyGuardianApp
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        CloudBaseClient.init(this)
    }
}
