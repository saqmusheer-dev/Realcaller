package com.limradigitals.realcaller.screening

import android.telecom.Call
import android.telecom.CallScreeningService
import com.limradigitals.realcaller.data.CallerRepository
import com.limradigitals.realcaller.data.ReputationEngine

/** Local-first V1 call screening. No network request is required for the decision. */
class RealCallerScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart.orEmpty()
        val repository = CallerRepository(applicationContext)
        val record = repository.lookup(number)
        repository.recordIncomingCall(number)

        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSilenceCall(ReputationEngine.shouldSilence(record))
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()

        respondToCall(callDetails, response)
    }
}
