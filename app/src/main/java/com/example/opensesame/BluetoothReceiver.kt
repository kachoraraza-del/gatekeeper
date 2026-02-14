package com.example.opensesame

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val deviceName = device?.name ?: "Unknown"

        if (deviceName.contains("LEXUS", ignoreCase = true)) {
            val serviceIntent = Intent(context, GarageAutomationService::class.java)
            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    Log.d("Gatekeeper", "Lexus Connected - Wake up Monitoring")
                    serviceIntent.putExtra("ACTION", "START_GPS")
                    context.startForegroundService(serviceIntent)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    Log.d("Gatekeeper", "Lexus Disconnected - Standby Mode")
                    serviceIntent.putExtra("ACTION", "STOP_GPS")
                    context.startForegroundService(serviceIntent)
                }
            }
        }
    }
}