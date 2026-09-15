package com.example.stealthsos

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import com.google.android.gms.common.api.ResolvableApiException
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.SmsManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {

    private var fakeScreenView: View? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var mediaRecorder: MediaRecorder? = null
    private var audioFilePath: String = ""
    private var isRecording = false
    private var isSmsSent = false
    private var isSecondSmsSent = false
    private var isStealthModeActive = false

    private var latestCapturedPhotoBytes: ByteArray? = null
    private var latestAudioUri: Uri? = null
    private var latestAudioFile: File? = null

    private var lastSentLat: Double = 0.0
    private var lastSentLon: Double = 0.0
    private var locationCallback: LocationCallback? = null

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        setContentView(R.layout.activity_main)

        requestNecessaryPermissions()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val btnManualTrigger = findViewById<Button>(R.id.btnManualTrigger)
        val btnVoiceTrigger = findViewById<Button>(R.id.btnVoiceTrigger)
        val btnStopListening = findViewById<Button>(R.id.btnStopListening)
        val btnOpenSettings = findViewById<Button>(R.id.btnOpenSettings)
        statusText = findViewById(R.id.tvStatus)

        // Modern Back Press handling
        if (this is androidx.activity.ComponentActivity) {
            this.onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isStealthModeActive) return
                    isEnabled = false
                    onBackPressed()
                    isEnabled = true
                }
            })
        }

        btnOpenSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnManualTrigger.setOnClickListener {
            checkGpsAndExecute {
                triggerStealthMode()
            }
        }

        btnVoiceTrigger.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA), 102)
            } else {
                checkGpsAndExecute {
                    val serviceIntent = Intent(this, VoiceListeningService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    statusText.text = "Optimized Shout Detection Active"
                    statusText.setTextColor(Color.parseColor("#1976D2"))
                    Toast.makeText(this, "Listening for Danger Shouts", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnStopListening.setOnClickListener {
            val stopIntent = Intent(this, VoiceListeningService::class.java).apply {
                action = "STOP_SERVICE"
            }
            startService(stopIntent)

            statusText.text = "System Standby"
            statusText.setTextColor(Color.parseColor("#4CAF50"))
            Toast.makeText(this, "Listening Stopped Successfully", Toast.LENGTH_SHORT).show()
        }

        if (intent.getBooleanExtra("TRIGGER_SOS", false)) {
            triggerStealthMode()
        }
    }

    private fun isGpsEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
    }

    private fun checkGpsAndExecute(onGpsEnabled: () -> Unit) {
        if (isGpsEnabled()) {
            onGpsEnabled()
            return
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000).build()
        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
        val client = LocationServices.getSettingsClient(this)
        val task = client.checkLocationSettings(builder.build())

        task.addOnSuccessListener {
            onGpsEnabled()
        }

        task.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                try {
                    exception.startResolutionForResult(this, 1001)
                } catch (sendEx: IntentSender.SendIntentException) {
                    promptOpenGpsSettings()
                }
            } else {
                promptOpenGpsSettings()
            }
        }
    }

    private fun promptOpenGpsSettings() {
        Toast.makeText(this, "Please turn ON GPS/Location for Emergency SOS!", Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            Toast.makeText(this, "GPS Enabled Successfully", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNecessaryPermissions() {
        val permissionsList = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsList.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissionsList.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions.toTypedArray(), 101)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("TRIGGER_SOS", false) == true) {
            triggerStealthMode()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isStealthModeActive) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun triggerStealthMode() {
        if (isStealthModeActive) return
        isStealthModeActive = true

        stopService(Intent(this, VoiceListeningService::class.java))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Allow 'Appear on top' to completely block Status Bar!", Toast.LENGTH_LONG).show()
            return
        }

        isSmsSent = false
        lastSentLat = 0.0
        lastSentLon = 0.0
        latestCapturedPhotoBytes = null
        latestAudioUri = null
        latestAudioFile = null
        showFakePowerOffScreen()
        startDistanceBasedLocationSMS()
        captureFrontCameraIntruder()

        Handler(Looper.getMainLooper()).postDelayed({
            startEvidenceAudioRecording()
        }, 2000)
    }

    // 5 Selfies with 2 seconds delay
    @SuppressLint("MissingPermission")
    private fun captureFrontCameraIntruder() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var frontCameraId: String? = null

            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontCameraId = cameraId
                    break
                }
            }

            frontCameraId?.let { cameraId ->
                val mainHandler = Handler(Looper.getMainLooper())
                cameraManager.openCamera(cameraId, object : android.hardware.camera2.CameraDevice.StateCallback() {
                    override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
                        try {
                            // Buffer capacity set to 7 to prevent buffer starvation during 5 photo captures
                            val reader = android.media.ImageReader.newInstance(640, 480, android.graphics.ImageFormat.JPEG, 7)
                            var photosSaved = 0

                            reader.setOnImageAvailableListener({ imgReader ->
                                var image: android.media.Image? = null
                                try {
                                    image = imgReader.acquireNextImage()
                                    if (image != null) {
                                        val buffer = image.planes[0].buffer
                                        val bytes = ByteArray(buffer.remaining())
                                        buffer.get(bytes)
                                        latestCapturedPhotoBytes = bytes

                                        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
                                        val imageFileName = "Intruder_Selfie_$timeStamp.jpg"

                                        // 1. Internal cache file for OkHttp upload (bypasses Scoped Storage restrictions)
                                        val tempPhotoFile = File(cacheDir, imageFileName)
                                        try {
                                            tempPhotoFile.outputStream().use { os ->
                                                os.write(bytes)
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }

                                        // 2. Public gallery MediaStore backup
                                        val contentValues = ContentValues().apply {
                                            put(MediaStore.MediaColumns.DISPLAY_NAME, imageFileName)
                                            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/StealthSOS")
                                        }

                                        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                                        uri?.let {
                                            contentResolver.openOutputStream(it)?.use { outputStream ->
                                                outputStream.write(bytes)
                                            }
                                        }

                                        // 3. Upload cache file to Cloudinary & dispatch SMS
                                        uploadToCloudinaryAndSendSMS(tempPhotoFile)
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                } finally {
                                    image?.close()
                                    photosSaved++
                                    // Strictly close camera and reader only after 5 photos are completely saved
                                    if (photosSaved >= 5) {
                                        try {
                                            imgReader.close()
                                            camera.close()
                                        } catch (e: Exception) {}
                                    }
                                }
                            }, mainHandler)

                            val dummySurfaceTexture = android.graphics.SurfaceTexture(1).apply { setDefaultBufferSize(640, 480) }
                            val dummySurface = android.view.Surface(dummySurfaceTexture)

                            val captureBuilder = camera.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE)
                            captureBuilder.addTarget(reader.surface)
                            captureBuilder.set(android.hardware.camera2.CaptureRequest.JPEG_ORIENTATION, 270)

                            camera.createCaptureSession(listOf(dummySurface, reader.surface), object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                                    try {
                                        val previewReq = camera.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW)
                                        previewReq.addTarget(dummySurface)
                                        session.setRepeatingRequest(previewReq.build(), null, null)

                                        var captureCount = 0
                                        val captureRunnable = object : Runnable {
                                            override fun run() {
                                                if (captureCount < 5 && isStealthModeActive) {
                                                    try {
                                                        session.capture(captureBuilder.build(), null, null)
                                                        captureCount++
                                                        mainHandler.postDelayed(this, 2000)
                                                    } catch (e: Exception) {
                                                        e.printStackTrace()
                                                    }
                                                }
                                            }
                                        }
                                        mainHandler.post(captureRunnable)

                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        camera.close()
                                    }
                                }
                                override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                                    camera.close()
                                }
                            }, mainHandler)
                        } catch (e: Exception) {
                            camera.close()
                        }
                    }

                    override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) {
                        camera.close()
                    }

                    override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) {
                        camera.close()
                    }
                }, mainHandler)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val evidenceAudioStopHandler = Handler(Looper.getMainLooper())
    private var evidenceAudioRunnable: Runnable? = null

    private fun startEvidenceAudioRecording() {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "SOS_Audio_Evidence_$timeStamp.3gp"

        // Save 15s evidence clip into internal cacheDir for OkHttp upload
        val audioFile = File(cacheDir, fileName)
        latestAudioFile = audioFile

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            setOutputFile(audioFile.absolutePath)
            try { prepare(); start(); isRecording = true } catch (e: Exception) { e.printStackTrace() }
        }

        // Schedule auto-stop after 15 seconds
        evidenceAudioRunnable = Runnable {
            if (isRecording && isStealthModeActive) {
                try {
                    mediaRecorder?.stop()
                    mediaRecorder?.release()
                    mediaRecorder = null
                    isRecording = false
                } catch (e: Exception) {
                    mediaRecorder = null
                    isRecording = false
                }

                // Upload 15s audio clip from internal cacheDir to Cloudinary and dispatch SMS
                latestAudioFile?.let { file ->
                    if (file.exists() && file.length() > 0L) {
                        uploadToCloudinaryAndSendSMS(file)
                    }
                }

                // Immediately start continuous recording if still in stealth mode
                if (isStealthModeActive) {
                    startContinuousAudioRecording()
                }
            }
        }
        evidenceAudioStopHandler.postDelayed(evidenceAudioRunnable!!, 15000)
    }

    private fun startContinuousAudioRecording() {
        if (!isStealthModeActive) return

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "SOS_Audio_Continuous_$timeStamp.3gp"

        val storageDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val stealthDir = File(storageDir, "StealthSOS")
        if (!stealthDir.exists()) stealthDir.mkdirs()
        val audioFile = File(stealthDir, fileName)

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/3gpp")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/StealthSOS")
                }
                val audioUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                val pfd = audioUri?.let { resolver.openFileDescriptor(it, "w") }
                if (pfd != null) {
                    setOutputFile(pfd.fileDescriptor)
                } else {
                    setOutputFile(audioFile.absolutePath)
                }
            } else {
                setOutputFile(audioFile.absolutePath)
            }
            try { prepare(); start(); isRecording = true } catch (e: Exception) {}
        }
    }

    private fun stopAudioRecording() {
        evidenceAudioRunnable?.let { evidenceAudioStopHandler.removeCallbacks(it) }
        evidenceAudioRunnable = null

        if (isRecording) {
            try {
                mediaRecorder?.stop()
                mediaRecorder?.release()
                mediaRecorder = null
                isRecording = false
                statusText.text = "System Standby"
                statusText.setTextColor(Color.parseColor("#4CAF50"))
            } catch (e: Exception) {
                mediaRecorder = null
                isRecording = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDistanceBasedLocationSMS() {
        if (isSmsSent) return

        // 1. Get initial location and send initial SMS immediately
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null && !isSmsSent) {
                lastSentLat = location.latitude
                lastSentLon = location.longitude
                sendGoogleMapsSMS(lastSentLat, lastSentLon, isInitial = true)
                isSmsSent = true
            }
        }.addOnFailureListener {
            if (!isSmsSent) {
                sendGoogleMapsSMS(0.0, 0.0, isInitial = true)
                isSmsSent = true
            }
        }

        // Fallback: If lastLocation returned null or took time, fetch fresh location
        val cancellationTokenSource = CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
            .addOnSuccessListener { location ->
                if (location != null && !isSmsSent) {
                    lastSentLat = location.latitude
                    lastSentLon = location.longitude
                    sendGoogleMapsSMS(lastSentLat, lastSentLon, isInitial = true)
                    isSmsSent = true
                }
            }

        // 2. Request location updates triggered ONLY when device moves >= 50 meters
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L)
            .setMinUpdateIntervalMillis(5000L)
            .setMinUpdateDistanceMeters(50f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val newLocation = locationResult.lastLocation ?: return

                if (lastSentLat == 0.0 && lastSentLon == 0.0) {
                    lastSentLat = newLocation.latitude
                    lastSentLon = newLocation.longitude
                    sendGoogleMapsSMS(lastSentLat, lastSentLon, isInitial = !isSmsSent)
                    isSmsSent = true
                    return
                }

                // Calculate displacement distance from last sent location
                val results = FloatArray(1)
                Location.distanceBetween(
                    lastSentLat, lastSentLon,
                    newLocation.latitude, newLocation.longitude,
                    results
                )
                val distanceMovedMeters = results[0]

                // Send update SMS ONLY if device moved >= 50 meters
                if (distanceMovedMeters >= 50f) {
                    lastSentLat = newLocation.latitude
                    lastSentLon = newLocation.longitude
                    sendGoogleMapsSMS(lastSentLat, lastSentLon, isInitial = false)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendGoogleMapsSMS(lat: Double, lon: Double, isInitial: Boolean) {
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val prefs = getSharedPreferences("StealthPrefs", Context.MODE_PRIVATE)
            val rawContacts = prefs.getString("EMERGENCY_CONTACT", "8667285995, 9087434571") ?: "8667285995"
            val emergencyNumbers = rawContacts.split(",").map { it.trim() }.filter { it.isNotEmpty() }

            val mapsUrl = if (lat != 0.0 || lon != 0.0) {
                "https://www.google.com/maps?q=$lat,$lon"
            } else {
                "Location pending (GPS searching...)"
            }

            val secretMessage = if (isInitial) {
                "Emergency SOS! I need help. My live location: $mapsUrl"
            } else {
                "StealthSOS Movement Alert: Device moved. Updated location: $mapsUrl"
            }

            val parts = smsManager.divideMessage(secretMessage)

            for (number in emergencyNumbers) {
                if (number.length >= 10) {
                    smsManager.sendMultipartTextMessage(number, null, parts, null, null)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun uploadToCloudinaryAndSendSMS(mediaFile: File) {
        if (!mediaFile.exists() || mediaFile.length() == 0L) return

        val prefs = getSharedPreferences("StealthPrefs", Context.MODE_PRIVATE)
        val cloudName = "dtsspotm3" // Unga Cloudinary Cloud Name
        val uploadPreset = "stealth_preset" // Unga Upload Preset
        val rawContacts = prefs.getString("EMERGENCY_CONTACT", "8667285995") ?: "8667285995"
        val emergencyNumbers = rawContacts.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val mimeType = if (mediaFile.name.endsWith(".3gp")) "video/3gpp" else "image/jpeg"
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", mediaFile.name, mediaFile.asRequestBody(mimeType.toMediaTypeOrNull()))
                    .addFormDataPart("upload_preset", uploadPreset)
                    .build()

                val resourceType = if (mediaFile.name.endsWith(".3gp")) "video" else "image"
                val request = Request.Builder()
                    .url("https://api.cloudinary.com/v1_1/$cloudName/$resourceType/upload")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val jsonObject = JSONObject(responseBody)
                    val secureUrl = jsonObject.getString("secure_url")
                    for (number in emergencyNumbers) {
                        sendEvidenceSMS(secureUrl, number)
                    }
                }
            } catch (e: Exception) {
                Log.e("StealthSOS", "Upload Error: ${e.message}")
            }
        }
    }

    private suspend fun sendEvidenceSMS(link: String, phoneNumber: String) {
        withContext(Dispatchers.Main) {
            try {
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                val message = "StealthSOS Evidence: $link"
                val parts = smsManager.divideMessage(message)
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showFakePowerOffScreen() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val prefs = getSharedPreferences("StealthPrefs", Context.MODE_PRIVATE)
        val holdSeconds = prefs.getInt("HOLD_TIME", 5)
        val holdMillis = (holdSeconds * 1000).toLong()

        fakeScreenView = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                return true
            }
        }.apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true

            val handler = Handler(Looper.getMainLooper())
            val exitRunnable = Runnable { exitStealthMode() }

            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        handler.postDelayed(exitRunnable, holdMillis)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(exitRunnable)
                        true
                    }
                    else -> true
                }
            }

            systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_LOW_PROFILE
                    )
        }

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            windowManager.addView(fakeScreenView, params)
            fakeScreenView?.requestFocus()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun exitStealthMode() {
        if (!isStealthModeActive) return
        isStealthModeActive = false

        // Stop continuous distance-based location updates
        locationCallback?.let {
            try {
                fusedLocationClient.removeLocationUpdates(it)
            } catch (e: Exception) {}
        }
        locationCallback = null

        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            fakeScreenView?.let { windowManager.removeView(it) }
            fakeScreenView = null
        } catch (e: Exception) {}

        stopAudioRecording()
        Toast.makeText(this, "Stealth Mode Deactivated", Toast.LENGTH_SHORT).show()
    }
}

// Voice Listening Service with Optimized Threshold (30000)
class VoiceListeningService : Service() {
    private var audioRecord: AudioRecord? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var secretWord = "help"

    // UPDATED THRESHOLD: 30,000 (Catches moderate/danger shouts easily without triggering on normal room noise)
    private val AMPLITUDE_THRESHOLD = 10000



    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("StealthPrefs", Context.MODE_PRIVATE)
        secretWord = prefs.getString("SECRET_WORD", "help")?.lowercase() ?: "help"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("SOS_CHANNEL", "Stealth SOS Background", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            val notification = Notification.Builder(this, "SOS_CHANNEL")
                .setContentTitle("StealthSOS Active")
                .setContentText("Optimized Voice Protection Running...")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .build()
            startForeground(1, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            isListening = false
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                unmuteBeep(audioManager)
            } catch (e: Exception) {}
            try {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                speechRecognizer?.destroy()
            } catch (e: Exception) {}
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isListening) {
            startSilentMonitoring()
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startSilentMonitoring() {
        if (isListening) return
        isListening = true

        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val sampleRate = 8000
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            try {
                audioRecord?.startRecording()
                val buffer = ShortArray(bufferSize)

                while (isListening) {
                    val readSize = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (readSize > 0) {
                        var maxAmplitude = 0
                        for (i in 0 until readSize) {
                            val absVal = Math.abs(buffer[i].toInt())
                            if (absVal > maxAmplitude) {
                                maxAmplitude = absVal
                            }
                        }

                        if (maxAmplitude > AMPLITUDE_THRESHOLD && !audioManager.isMusicActive) {
                            isListening = false
                            try {
                                audioRecord?.stop()
                                audioRecord?.release()
                                audioRecord = null
                            } catch (e: Exception) {}

                            Handler(Looper.getMainLooper()).post {
                                verifySecretWordViaSpeech(audioManager)
                            }
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                isListening = false
            }
        }.start()
    }

    private val mutedStreams = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_ALARM
    )

    @SuppressLint("QueryPermissionsNeeded")
    private fun verifySecretWordViaSpeech(audioManager: AudioManager) {
        try {
            muteBeep(audioManager)

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            val mainHandler = Handler(Looper.getMainLooper())
            var isHandled = false

            val safetyTimeoutRunnable = Runnable {
                if (!isHandled) {
                    isHandled = true
                    try {
                        speechRecognizer?.destroy()
                        speechRecognizer = null
                    } catch (e: Exception) {}
                    mainHandler.postDelayed({
                        unmuteBeep(audioManager)
                        startSilentMonitoring()
                    }, 500)
                }
            }

            mainHandler.postDelayed(safetyTimeoutRunnable, 6000)

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    if (isHandled) return
                    isHandled = true
                    mainHandler.removeCallbacks(safetyTimeoutRunnable)

                    try {
                        speechRecognizer?.destroy()
                        speechRecognizer = null
                    } catch (e: Exception) {}

                    mainHandler.postDelayed({
                        unmuteBeep(audioManager)
                        startSilentMonitoring()
                    }, 500)
                }

                override fun onResults(results: Bundle?) {
                    if (isHandled) return
                    isHandled = true
                    mainHandler.removeCallbacks(safetyTimeoutRunnable)

                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    var matched = false
                    matches?.let {
                        for (phrase in it) {
                            if (phrase.lowercase().contains(secretWord)) {
                                matched = true
                                break
                            }
                        }
                    }

                    try {
                        speechRecognizer?.destroy()
                        speechRecognizer = null
                    } catch (e: Exception) {}

                    if (matched) {
                        mainHandler.postDelayed({
                            unmuteBeep(audioManager)
                        }, 500)
                        val triggerIntent = Intent(this@VoiceListeningService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra("TRIGGER_SOS", true)
                        }
                        startActivity(triggerIntent)
                        stopSelf()
                    } else {
                        mainHandler.postDelayed({
                            unmuteBeep(audioManager)
                            startSilentMonitoring()
                        }, 500)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            speechRecognizer?.startListening(speechIntent)

        } catch (e: Exception) {
            unmuteBeep(audioManager)
            startSilentMonitoring()
        }
    }

    private fun muteBeep(audioManager: AudioManager) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                for (stream in mutedStreams) {
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                }
            } else {
                @Suppress("DEPRECATION")
                for (stream in mutedStreams) {
                    audioManager.setStreamMute(stream, true)
                }
            }
        } catch (e: Exception) {}
    }

    private fun unmuteBeep(audioManager: AudioManager) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                for (stream in mutedStreams) {
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                }
            } else {
                @Suppress("DEPRECATION")
                for (stream in mutedStreams) {
                    audioManager.setStreamMute(stream, false)
                }
            }
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        isListening = false
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            unmuteBeep(audioManager)
        } catch (e: Exception) {}
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            speechRecognizer?.destroy()
        } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
