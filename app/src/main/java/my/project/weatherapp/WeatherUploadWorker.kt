package my.project.weatherapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import my.project.weatherapp.models.WeatherResponse
import my.project.weatherapp.network.WeatherService
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.create
import java.text.SimpleDateFormat
import java.util.*
import java.util.Properties
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WeatherUploadWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(appContext)
    }

    companion object {
        private const val TAG = "LocationWeatherWorker"
    }

    /**
     * This is the main method that WorkManager calls on a background thread.
     * It should be a suspending function when using CoroutineWorker.
     */
    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork: Starting location and weather fetch.")

        // Check for location permissions
        if (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "doWork: Location permission not granted.")
            // You might want to notify the user or handle this case differently
            return Result.failure()
        }

        return try {
            // Fetch current location
            val currentLocation = getCurrentLocation()

            if (currentLocation != null) {
                Log.i(TAG, "doWork: Location fetched: Lat: ${currentLocation.latitude}, Lon: ${currentLocation.longitude}")

                // Now, call your getWeather function with the fetched location
                // This is where you integrate your existing function.
                // We'll assume your getWeather function is defined elsewhere and handles its own logic.
                // For demonstration, we'll simulate a call.
                val weatherData = weatherDetail(currentLocation.latitude, currentLocation.longitude)

                Log.i(TAG, "doWork: Weather data processed: $weatherData") // Or however you handle the result
                Result.success() // Indicate that the work finished successfully
            } else {
                Log.w(TAG, "doWork: Failed to get current location.")
                Result.retry() // Or Result.failure() if you don't want to retry
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "doWork: SecurityException while getting location. Is permission granted?", e)
            Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "doWork: An error occurred.", e)
            Result.failure() // Or Result.retry() if it's a transient error
        }
    }

    /**
     * Suspends the coroutine until the current location is fetched.
     * This function encapsulates the asynchronous location fetching logic.
     */
    @SuppressLint("MissingPermission") // We check permission in doWork()
    private suspend fun getCurrentLocation(): Location? {
        // Ensure this is called from a context with a Looper if not using coroutines,
        // but with CoroutineWorker and suspendCancellableCoroutine, it's handled.
        return withContext(Dispatchers.IO) { // Perform location fetching on an IO thread
            // Check permission again, just in case, though primarily handled in doWork
            if (ContextCompat.checkSelfPermission(
                    appContext, Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
                    appContext, Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "getCurrentLocation: Permission check failed again inside suspend function.")
                return@withContext null
            }

            val cancellationTokenSource = CancellationTokenSource()

            // Using suspendCancellableCoroutine to bridge the Task API with coroutines
            suspendCancellableCoroutine { continuation ->
                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY, // Or PRIORITY_BALANCED_POWER_ACCURACY
                    cancellationTokenSource.token
                ).addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        Log.d(TAG, "Location success: $location")
                        continuation.resume(location)
                    } else {
                        Log.d(TAG, "Location success but location is null")
                        continuation.resume(null)
                    }
                }.addOnFailureListener { exception ->
                    Log.e(TAG, "Location failure", exception)
                    continuation.resumeWithException(exception)
                }

                // Handle coroutine cancellation
                continuation.invokeOnCancellation {
                    cancellationTokenSource.cancel()
                    Log.d(TAG, "Location fetch cancelled")
                }
            }
        }
    }

    private fun weatherDetail(latitude: Double, longitude: Double) {
        Log.e(TAG, "Here 3")
        if (Constants.isNetworkAvailable(appContext)) {
            val retrofit: Retrofit = Retrofit.Builder()
                .baseUrl(Constants.BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            val service: WeatherService = retrofit
                .create<WeatherService>(WeatherService::class.java)

            val listCall: Call<WeatherResponse> = service.getWeather(
                latitude, longitude, Constants.METRIC_UNIT, Constants.APP_ID
            )

            listCall.enqueue(object : Callback<WeatherResponse> {
                override fun onResponse(
                    call: Call<WeatherResponse>,
                    response: Response<WeatherResponse>
                ) {
                    if (response.isSuccessful) {
                        val weatherList: WeatherResponse? = response.body()
                        Log.e("Checking-Data", "Here 4 - Response successful")
                        val weatherResponseJsonString = Gson().toJson(weatherList)

                        // --- MODIFICATION START ---
                        // Launch a coroutine on the IO dispatcher for network operations (sending email)
                        GlobalScope.launch(Dispatchers.IO) {
                            try {
                                Log.d("EmailSend", "Attempting to send email in background...")
                                sendEmail(weatherResponseJsonString) // This now runs on a background thread
                                Log.d("EmailSend", "Email sending process initiated from background.")
                                // If you need to update UI after email is sent (e.g., show a success message)
                                // you must switch back to the Main dispatcher
                                // withContext(Dispatchers.Main) {
                                //    Toast.makeText(appContext, "Email sent successfully!", Toast.LENGTH_SHORT).show()
                                //    Log.d("EmailSendUI", "Success UI update on Main thread.")
                                // }
                            } catch (e: Exception) {
                                Log.e("EmailSendError", "Error sending email: ${e.message}", e)
                                // If you need to update UI about the error
                                // withContext(Dispatchers.Main) {
                                //    Toast.makeText(appContext, "Failed to send email.", Toast.LENGTH_SHORT).show()
                                //    Log.e("EmailSendUIError", "Error UI update on Main thread.")
                                // }
                            }
                        }
                        // --- MODIFICATION END ---

                    } else {
                        val code = response.code()
                        when (code) {
                            400 -> {
                                Log.e("RetrofitError-400", "Bad Request or Connection Issue")
                            }
                            404 -> {
                                Log.e("RetrofitError-404", "Not Found")
                            }
                            else -> {
                                Log.e("RetrofitError-$code", "Generic Error: ${response.message()}")
                            }
                        }
                    }
                }

                override fun onFailure(call: Call<WeatherResponse>, t: Throwable) {
                    Log.e("RetrofitFailure", "API call failed: ${t.message}", t)
                }
            })
        } else {
            Log.e("NetworkError", "No network available to fetch weather details.")
        }
    }
    private fun sendEmail(weatherData: String) {
        // IMPORTANT: Storing credentials directly in code is a security risk.
        // Consider using a backend service to handle email sending or more secure credential storage.
        val username = "assasin.blood1@gmail.com" // Your Gmail address
        val password = "qpnf sqoo tpzg ahgs"      // Your App Password for Gmail

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
                setText("Latest weather update:\n\n$weatherData") // Email body
            }

            Log.d(TAG, "Attempting to send email...")
            Transport.send(message) // This is a blocking network call
            Log.i(TAG, "Email sent successfully to recipients.")

        } catch (e: MessagingException) {
            Log.e(TAG, "Failed to send email due to MessagingException: ${e.message}", e)
            // Propagate the exception so doWork can handle it (e.g., return Result.failure())
            throw e // Re-throw to be caught by doWork's try-catch
        } catch (e: Exception) {
            Log.e(TAG, "An unexpected error occurred during sendEmail: ${e.message}", e)
            throw e // Re-throw
        }
    }
    private fun getCurrentTimeStamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    }

}
