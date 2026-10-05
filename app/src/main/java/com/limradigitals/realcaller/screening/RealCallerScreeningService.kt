package com.limradigitals.realcaller.screening

import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * V1 entry point for Android's call-screening framework.
 *
 * Keep this service fast and local-first. Network/business enrichment must never
 * be required to make the initial screening decision.
 */
class RealCallerScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart.orEmpty()

        // V1 foundation: allow by default until the local reputation engine is wired in.
        // Future flow: normalize -> local lookup -> reputation -> business card -> decision.
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSilenceCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()

        respondToCall(callDetails, response)
    }
}
