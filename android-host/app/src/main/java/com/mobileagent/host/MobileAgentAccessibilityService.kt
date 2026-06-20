package com.mobileagent.host

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class MobileAgentAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        AccessibilityState.service = this
        HostBridgeServer.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        if (AccessibilityState.service == this) {
            AccessibilityState.service = null
        }
        super.onDestroy()
    }
}
