package com.example.lightledger

import android.content.ComponentName
import android.content.Intent
import androidx.room.MultiInstanceInvalidationService
import org.robolectric.DefaultTestLifecycle
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.TestLifecycle
import java.lang.reflect.Method

/** Supply Room's actual local Binder; Robolectric's default service binding returns null. */
class LedgerTestRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {
    override fun getTestLifecycleClass(): Class<out TestLifecycle> = RoomTestLifecycle::class.java
}

@OptIn(androidx.room.ExperimentalRoomApi::class)
class RoomTestLifecycle : DefaultTestLifecycle() {
    override fun beforeTest(method: Method) {
        super.beforeTest(method)
        val app = RuntimeEnvironment.getApplication()
        val service = Robolectric.buildService(MultiInstanceInvalidationService::class.java).create().get()
        val intent = Intent(app, MultiInstanceInvalidationService::class.java)
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(intent,
            ComponentName(app, MultiInstanceInvalidationService::class.java), service.onBind(intent))
    }
}
