package my.project.weatherapp

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.gson.Gson
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.project.weatherapp.Constants.TAG
import my.project.weatherapp.Constants.getCurrentTimeStamp
import my.project.weatherapp.databinding.ActivityMainBinding
import my.project.weatherapp.models.WeatherResponse
import my.project.weatherapp.network.Firebase
import my.project.weatherapp.network.GcsUploader
import my.project.weatherapp.network.WeatherService
import retrofit2.*
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import kotlin.math.log

class MainActivity : AppCompatActivity() {

    private var binding: ActivityMainBinding? = null

    private lateinit var mFusedLocationProviderClient: FusedLocationProviderClient

    private var mProgressDialog: Dialog? = null

    private lateinit var mSharedPreferences: SharedPreferences

    private lateinit var gcsUploader: GcsUploader
    private val cloudFunctionBaseUrl = "https://generate-signed-url-for-data-upload-611260698276.asia-south2.run.app"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding?.root)

        val currentUser = FirebaseAuth.getInstance().currentUser
        if(currentUser != null) { // no user signed in
            startActivity(Intent(this@MainActivity, SignInScreen::class.java))
        }
        else { // user available
            mFusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
            mSharedPreferences = getSharedPreferences(Constants.PREFERENCE_NAME, Context.MODE_PRIVATE)
            setUpUI()
            isLocationPermissionGranted()
            Toast.makeText(this, "${currentUser}", Toast.LENGTH_SHORT).show()
        }
        /*Log.e("Client_ID", "${R.string.default_web_client_id}")*/

        binding?.btnUploadToCloud?.setOnClickListener {
            val weatherData = mSharedPreferences.getString(Constants.WEATHER_RESPONSE_DATA, null)
            if (!weatherData.isNullOrBlank()) {
                gcsUploader = GcsUploader(this@MainActivity)
                gcsUploader.initialize(cloudFunctionBaseUrl)
                sendMail()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "Please refresh the data and wait for a few seconds before uploading.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    }

    private fun simulateGettingWeatherDataAndUpload() {
        // todo: 1st we will refresh the data and then proceed further

        uploadWeatherData("")
    }

    private fun getAuthenticationToken(): String {
        return ""
    }

    private fun uploadWeatherData(jsonData: String) {
        lifecycleScope.launch {
            val fileUri: Uri? = saveJsonToTempFile(jsonData)
            if (fileUri == null) {
                Log.e("UploadFlow", "Failed to save JSON data to temporary file")
                // TODO: Show error message to user
                return@launch
            }

            val fileName = "weather_data_${System.currentTimeMillis()}.json" // Choose a suitable file name
            val contentType = "application/json"

            // Step 2: Get your authentication token
            // TODO: Replace with your actual logic to get the auth token
            val authToken: String? = getAuthenticationToken() // !!! IMPLEMENT THIS FUNCTION !!!

            if (authToken == null) {
                Log.e("UploadFlow", "Failed to get authentication token")
                // TODO: Show error message to user
                // TODO: Clean up the temporary file if you created one
                return@launch
            }

            // Step 3: Call the Cloud Run function to get the signed URL
            val signedUrl = gcsUploader.getSignedUrlFromCloudFunction(
                fileName,
                contentType,
                authToken
            )

            if (signedUrl != null) {
                // Step 4: Use the signed URL to upload the file directly to GCS
                gcsUploader.uploadFile(signedUrl, fileUri, contentType) { result ->
                    result.onSuccess {
                        // Handle successful upload
                        Log.d("UploadFlow", "JSON data uploaded successfully to GCS!")
                        // TODO: Clean up the temporary file if you created one
                        deleteTempFile(fileUri)
                        // TODO: Notify your backend or update UI as needed
                    }.onFailure { error ->
                        // Handle upload failure
                        Log.e("UploadFlow", "JSON data upload failed: ${error.message}")
                        // TODO: Show error message to user
                        // TODO: Clean up the temporary file if you created one
                        deleteTempFile(fileUri)
                    }
                }
            } else {
                // Handle the case where getting the signed URL failed
                Log.e("UploadFlow", "Failed to get signed URL for JSON data")
                // TODO: Show error message to user
                // TODO: Clean up the temporary file if you created one
                deleteTempFile(fileUri)
            }
        }
    }

    // Helper function to save JSON string to a temporary file and return its Uri
    private fun saveJsonToTempFile(jsonData: String): Uri? {
        return try {
            val tempFile = File.createTempFile(
                "weather_data_", // Prefix
                ".json",         // Suffix
                cacheDir         // Directory (using cacheDir is good for temporary files)
            )
            FileOutputStream(tempFile).use { fos ->
                fos.write(jsonData.toByteArray())
            }
            Uri.fromFile(tempFile)
        } catch (e: IOException) {
            Log.e("UploadFlow", "Error saving JSON to temp file: ${e.message}", e)
            null
        }
    }

    // Helper function to delete the temporary file
    private fun deleteTempFile(fileUri: Uri) {
        try {
            val file = File(fileUri.path ?: return)
            if (file.exists()) {
                file.delete()
                Log.d("UploadFlow", "Temporary file deleted: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e("UploadFlow", "Error deleting temporary file: ${e.message}", e)
        }
    }

    private fun isLocationEnabled(): Boolean{
        val locationManager: LocationManager =  getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun isLocationPermissionGranted(){
        if(!isLocationEnabled()){
            Toast.makeText(this@MainActivity,
                "Your Location Provider is turned off!!", Toast.LENGTH_SHORT).show()
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            startActivity(intent)
        }
        else{
            Dexter.withActivity(this).withPermissions(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ).withListener(object: MultiplePermissionsListener {
                override fun onPermissionsChecked(report: MultiplePermissionsReport?) {
                    if(report!!.areAllPermissionsGranted()){
                        requestLocationData()
                    }
                }
                override fun onPermissionRationaleShouldBeShown(
                    p0: MutableList<PermissionRequest>?,
                    p1: PermissionToken?
                ) {
                    showRationalDialogForPermissions()
                }
            }).onSameThread().check()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationData(){
        val mLocationRequest = com.google.android.gms.location.LocationRequest()
        mLocationRequest.priority = com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY
        mLocationRequest.interval = 0
        mLocationRequest.fastestInterval = 0
        mLocationRequest.numUpdates = 1

        mFusedLocationProviderClient.requestLocationUpdates(
            mLocationRequest, mLocationCallBack,
            Looper.myLooper()
        )
    }
    private val mLocationCallBack = object : LocationCallback(){
        override fun onLocationResult(locationResult: LocationResult) {
            val mLastLocation: Location? = locationResult.lastLocation
            val latitude = mLastLocation?.latitude
            val longitude = mLastLocation?.longitude
            Log.i("DATA-LOCATION","$latitude")
            Log.i("DATA-LOCATION","$longitude")
            weatherDetail(latitude!!, longitude!!)
        }
    }

    /*private fun weatherDetail(latitude: Double, longitude: Double){
        if(Constants.isNetworkAvailable(this@MainActivity)){
            *//*Toast.makeText(this@MainActivity,"Ok!!",Toast.LENGTH_SHORT).show()*//*
            val retrofit: Retrofit = Retrofit.Builder()
                .baseUrl(Constants.BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            val service: WeatherService = retrofit
                .create<WeatherService>(WeatherService::class.java)

            val listCall: Call<WeatherResponse> = service.getWeather(
                latitude, longitude, Constants.METRIC_UNIT, Constants.APP_ID
            )

            showCustomProgressDialog()

            listCall.enqueue(object: Callback<WeatherResponse>{
                override fun onResponse(
                    call: Call<WeatherResponse>,
                    response: Response<WeatherResponse>
                ) {
                    if(response.isSuccessful){
                        hideProgressDialog()
                        val weatherList: WeatherResponse? = response.body()

                        val weatherResponseJsonString = Gson().toJson(weatherList)
                        val editor = mSharedPreferences.edit()
                        editor.putString(Constants.WEATHER_RESPONSE_DATA, weatherResponseJsonString)
                        editor.apply()
                        setUpUI()
                        //Log.e("bhai", "$weatherList")
                    }
                    else{
                        val code = response.code()
                        when(code){
                            400 -> {
                                Log.e("lke 400","Bad Connection")
                            }
                            404 -> {
                                Log.e("lke 404","Not Found")
                            }
                            else -> {
                                Log.e("lke","Generic Error")
                            }
                        }
                    }
                }

                override fun onFailure(call: Call<WeatherResponse>, t: Throwable) {
                    Log.e("lkeeeee", t.message.toString())
                    hideProgressDialog()
                }

            })
        }
        else{
            Toast.makeText(this@MainActivity,"No!!",Toast.LENGTH_SHORT).show()
        }
    }*/

    private fun weatherDetail(latitude: Double, longitude: Double) {
        if (!Constants.isNetworkAvailable(this@MainActivity)) {
            Toast.makeText(this, "No internet connection", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                showCustomProgressDialog()

                val retrofit = Retrofit.Builder()
                    .baseUrl(Constants.BASE_URL)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()

                val service = retrofit.create(WeatherService::class.java)
                val response = service.getWeather(latitude, longitude, Constants.METRIC_UNIT, Constants.APP_ID)

                hideProgressDialog()

                if (response.isSuccessful) {
                    response.body()?.let { weather ->
                        val weatherResponseJson = Gson().toJson(weather)
                        mSharedPreferences.edit().putString(Constants.WEATHER_RESPONSE_DATA, weatherResponseJson).apply()
                        setUpUI()
                    }
                } else {
                    Log.e("Weather", "Error ${response.code()}")
                }
            } catch (e: Exception) {
                hideProgressDialog()
                Log.e("Weather", "Exception: ${e.message}")
            }
        }
    }



    private fun showRationalDialogForPermissions(){
        AlertDialog.Builder(this).setMessage(
            "It looks you have turned off permissions required by the app")
            .setPositiveButton("Go To Settings") {
                    _,_ ->
                try{
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    val uri = Uri.fromParts("Package", packageName, null)
                    intent.data = uri
                    startActivity(intent)
                }   catch (e: ActivityNotFoundException){
                    e.printStackTrace()
                }
            }.setNegativeButton("Cancel"){dialog, _ ->
                dialog.dismiss()
            }.show()
    }

    private fun showCustomProgressDialog() {
        mProgressDialog = Dialog(this)
        mProgressDialog!!.setContentView(R.layout.dialogcustomprogress)
        mProgressDialog!!.show()
    }
    private fun hideProgressDialog() {
        if(mProgressDialog != null){
            mProgressDialog!!.dismiss()
        }
    }

    private fun setUpUI(){
        val weatherResponseJsonString = mSharedPreferences
            .getString(Constants.WEATHER_RESPONSE_DATA,"")

        if(!weatherResponseJsonString.isNullOrEmpty()){
            val weatherList = Gson()
                .fromJson(weatherResponseJsonString, WeatherResponse::class.java)

            for(i in weatherList.weather.indices){
                Log.i("bhai",weatherList.weather.toString())
                binding?.tvMain?.text = weatherList.weather[i].main
                binding?.tvMainDesc?.text = weatherList.weather[i].description
                binding?.tvTemp?.text = weatherList.main.temp.toString() +
                        getUnit(application.resources.configuration.locales.toString())

                when(weatherList.weather[i].icon){
                    "01d" -> binding?.ivMain?.setImageResource(R.drawable.sunny)
                    "01n" -> binding?.ivMain?.setImageResource(R.drawable.night1)
                    "03d","03n" -> binding?.ivMain?.setImageResource(R.drawable.cloud)
                    "50d","50n" -> binding?.ivMain?.setImageResource(R.drawable.mist)
                    "11d","11n" -> binding?.ivMain?.setImageResource(R.drawable.storm)
                    "10d","10n" -> binding?.ivMain?.setImageResource(R.drawable.rain)
                    "13d","13n" -> binding?.ivMain?.setImageResource(R.drawable.snowflake)
                    "09d","09n" -> binding?.ivMain?.setImageResource(R.drawable.shower_rain)
                    "04d","04n" -> binding?.ivMain?.setImageResource(R.drawable.broken_cloud)
                    "02n" -> binding?.ivMain?.setImageResource(R.drawable.few_cloud_night)
                    "02d" -> binding?.ivMain?.setImageResource(R.drawable.few_cloud_day)
                }
            }
            binding?.tvHumid?.text = weatherList.main.humidity.toString() + " per cent"
            binding?.tvWindSpeed?.text = weatherList.wind.speed.toString()
            binding?.tvName?.text = weatherList.name
            binding?.tvCountry?.text = weatherList.sys.country
            binding?.tvSunriseTime?.text = unixTime(weatherList.sys.sunrise)
            binding?.tvSunsetTime?.text = unixTime(weatherList.sys.sunset)
            binding?.tvMax?.text = weatherList.main.temp_max.toString() + " max"
            binding?.tvMin?.text = weatherList.main.temp_min.toString() + " min"
            binding?.tvCurrentTime?.text = Constants.getCurrentTimeStamp()
        }

    }

    private fun getUnit(value: String): String {
        var type = "°C"
        if("US"==value || "LR"==value || "MM"==value){
            type = "°F"
        }
        return type
    }

    private fun unixTime(timex: Long): String?{
        val date = Date(timex*1000L)
        val sdf = SimpleDateFormat("HH:mm", Locale.UK)
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(date)
    }

    private fun dayOrNight(s1: String): String {
        if(s1[2]=='d'){
            return "Day"
        }
        return "Night"
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when(item.itemId){
            R.id.action_refresh -> {
                requestLocationData()
                true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    private fun uploadDataToCloud() {
        lifecycleScope.launch { // Make sure you are in a CoroutineScope (like lifecycleScope in an Activity/Fragment)
            withContext(Dispatchers.IO) {
                try {
                    sendMail()
                } catch (e: MessagingException) {
                    Log.e(TAG, "Error sending email (MessagingException): ${e.message}", e)
                    // Handle MessagingException (e.g., show a user-friendly message)
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error sending email: ${e.message}", e)
                    // Handle other exceptions
                }
            }
            // This block will execute after the email sending is complete (on the main thread)
            Log.d(TAG, "Email sending process initiated in the background.")
            // Optionally, update UI to indicate that email sending is in progress
        }
    }

    private fun sendMail() {
        // IMPORTANT: Storing credentials directly in code is a security risk.
        // Consider using a backend service to handle email sending or more secure credential storage.
        val username = "assasin.blood1@gmail.com" // Your Gmail address
        val password = "qpnf sqoo tpzg ahgs"  // Your App Password for Gmail

        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true") // TLS is required
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", "587") // SMTP port for TLS
        }

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(username, password)
            }
        })

        try {
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(username))
                // You can add multiple recipients
                setRecipients(
                    Message.RecipientType.TO,
                    InternetAddress.parse("negiharsh12@gmail.com, assasin.blood1@gmail.com") // Recipient email addresses
                )
                subject = "Weather Details: ${getCurrentTimeStamp()}" // Dynamic subject
                val weatherResponseJsonString = mSharedPreferences
                    .getString(Constants.WEATHER_RESPONSE_DATA,"")
                setText(weatherResponseJsonString) // Email body
            }

            Log.d(TAG, "Attempting to send email...")
            Transport.send(message) // This is a blocking network call
            Log.i(TAG, "Email sent successfully to recipients.")

        } catch (e: MessagingException) {
            Log.e(TAG, "Failed to send email due to MessagingException: ${e.message}", e)
            throw e // Re-throw to be caught by doWork's try-catch
        } catch (e: Exception) {
            Log.e(TAG, "An unexpected error occurred during sendEmail: ${e.message}", e)
            throw e // Re-throw
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding = null
    }
}