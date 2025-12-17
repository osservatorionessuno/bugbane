package org.osservatorionessuno.cadb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AdbPairingResultReceiver(
    private val onSuccess: () -> Unit,
    private val onFailure: (String?) -> Unit
) : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "AdbPairingResultReceiver"
    }
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == AdbPairingService.ACTION_PAIRING_RESULT) {
            val success = intent.getBooleanExtra(AdbPairingService.EXTRA_SUCCESS, false)
            val errorMessage = intent.getStringExtra(AdbPairingService.EXTRA_ERROR_MESSAGE)
            
            Log.d(TAG, "Received pairing result: success=$success, error=$errorMessage")
            
            if (success) {
                onSuccess()
            } else {
                onFailure(errorMessage)
            }
        }
    }
} 