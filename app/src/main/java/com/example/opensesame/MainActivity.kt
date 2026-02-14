package com.example.opensesame

import android.Manifest
import android.app.ActivityManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var langSpinner: Spinner

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val isMonitoring = intent.getBooleanExtra("IS_MONITORING", false)
            updateButtonStates(isMonitoring)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStartService)
        btnStop = findViewById(R.id.btnStopService)
        langSpinner = findViewById(R.id.langSpinner)

        val languages = arrayOf("English", "Finnish", "Swedish")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages)
        langSpinner.adapter = adapter

        val prefs = getSharedPreferences("GaragePrefs", Context.MODE_PRIVATE)
        langSpinner.setSelection(prefs.getInt("selected_lang", 0))
        updateLanguageUI(langSpinner.selectedItemPosition)

        langSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                prefs.edit().putInt("selected_lang", pos).apply()
                updateLanguageUI(pos)
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, // Added this to match Manifest
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.BLUETOOTH_CONNECT
        ).apply {
            // Add Post Notifications for Android 13 (API 33) and above
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

        ActivityCompat.requestPermissions(this, perms, 1)

        btnStart.setOnClickListener {
            val hasFineLocation = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarseLocation = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

            // 1. Check if we have "Overlay" (Display over other apps) for the Dialer
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
            }
            // 2. Check if we have Basic Location (Fine/Coarse)
            else if (!hasFineLocation || !hasCoarseLocation) {
                // Re-request basic permissions if they were denied
                ActivityCompat.requestPermissions(this, perms, 1)
            }
            // 3. Check for Background Location (The "Daemon" requirement)
            else if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestBackgroundLocation() // This calls the function we just placed!
            }
            // 4. Everything is good -> Start Monitoring
            else {
                startMonitoring()
            }
        }

        btnStop.setOnClickListener {
            val intent = Intent(this, GarageAutomationService::class.java).apply { putExtra("ACTION", "STOP_SERVICE") }
            startForegroundService(intent)
            updateButtonStates(false)
        }
    }

    private fun startMonitoring() {
        val intent = Intent(this, GarageAutomationService::class.java).apply { putExtra("ACTION", "START_GPS") }
        startForegroundService(intent)
        updateButtonStates(true)
        moveTaskToBack(true)
    }


    private fun updateButtonStates(isMonitoring: Boolean) {
        btnStart.isEnabled = !isMonitoring
        btnStop.isEnabled = isMonitoring
        langSpinner.isEnabled = !isMonitoring
    }

    private fun updateLanguageUI(langIndex: Int) {
        when(langIndex) {
            1 -> { btnStart.text = "Käynnistä"; btnStop.text = "Sammuta" }
            2 -> { btnStart.text = "Starta"; btnStop.text = "Stoppa" }
            else -> { btnStart.text = "Start Monitoring"; btnStop.text = "Stop Monitoring" }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("GATEKEEPER_STATUS_UPDATE")
        registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        updateButtonStates(isServiceRunning(GarageAutomationService::class.java))
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }



    private fun requestOverlayPermission() {
        AlertDialog.Builder(this).setTitle("Permission Required")
            .setMessage("Allow 'Display over other apps' to see the dialer.")
            .setPositiveButton("Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }.show()
    }

    private fun requestBackgroundLocation() {
        val (title, msg) = when(langSpinner.selectedItemPosition) {
            1 -> "Taustasijainti tarvitaan" to "Valitse asetus: 'Salli aina', jotta Gatekeeper toimii taustalla."
            2 -> "Bakgrundsplats krävs" to "Välj inställningen: 'Tillåt alltid' för att Gatekeeper ska fungera i bakgrunden."
            else -> "Background Location Required" to "Please set location permission to 'Allow all the time' in Settings."
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null) // Direct link to Gatekeeper settings
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == serviceClass.name }
    }
}