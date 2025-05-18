package my.project.weatherapp.network

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.io.IOException
import java.io.InputStream

// Data class for the request body sent to the Cloud Function
data class SignedUrlRequest(
    val fileName: String,
    val contentType: String
)

// Data class for the response body received from the Cloud Function
data class SignedUrlResponse(
    val signedUrl: String? // Make it nullable as the function might return null in case of error
)

// Retrofit interface for your Cloud Run function API
interface CloudFunctionApiService {
    @POST("/") // Assuming your function is triggered at the root path "/"
    suspend fun getSignedUrl(
        @Body request: SignedUrlRequest,
        @Header("Authorization") authToken: String // Pass the authentication token here
    ): SignedUrlResponse
}

class GcsUploader(private val context: Context) {

    private val okHttpClient = OkHttpClient() // OkHttp client for the file upload

    // Retrofit instance for calling the Cloud Function API
    private lateinit var retrofit: Retrofit
    private lateinit var cloudFunctionApiService: CloudFunctionApiService

    // Initialize Retrofit with the base URL of your Cloud Run function
    fun initialize(cloudFunctionBaseUrl: String) {
        // Ensure the base URL ends with a '/' for Retrofit
        val baseUrl = if (cloudFunctionBaseUrl.endsWith("/")) cloudFunctionBaseUrl else "$cloudFunctionBaseUrl/"

        retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .client(okHttpClient) // Use the same OkHttp client
            .build()

        cloudFunctionApiService = retrofit.create(CloudFunctionApiService::class.java)
    }


    /**
     * Uploads a file from a given Uri to GCS using a pre-signed URL.
     *
     * @param signedUrl The pre-signed URL obtained from your backend/Cloud Function.
     * @param fileUri The Uri of the file to upload from the Android device.
     * @param contentType The content type of the file (e.g., "image/jpeg", "application/pdf").
     * @param callback A callback function to handle the upload result (success or failure).
     */
    suspend fun uploadFile(
        signedUrl: String,
        fileUri: Uri,
        contentType: String,
        callback: (Result<Unit>) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            // Get the InputStream from the file Uri
            val inputStream: InputStream? = context.contentResolver.openInputStream(fileUri)

            if (inputStream == null) {
                callback(Result.failure(IOException("Could not open input stream for Uri: $fileUri")))
                return@withContext
            }

            // Create the request body from the InputStream
            val requestBody = inputStream.readBytes().toRequestBody(contentType.toMediaTypeOrNull())

            // Build the PUT request to the signed URL
            val request = Request.Builder()
                .url(signedUrl)
                .put(requestBody)
                .header("Content-Type", contentType) // Ensure Content-Type header is set
                .build()

            // Execute the request using the OkHttp client
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d("GcsUploader", "Upload successful!")
                    callback(Result.success(Unit))
                } else {
                    val errorBody = response.body?.string()
                    Log.e("GcsUploader", "Upload failed: ${response.code} - $errorBody")
                    callback(Result.failure(IOException("Upload failed: ${response.code} - $errorBody")))
                }
            }
        } catch (e: Exception) {
            Log.e("GcsUploader", "Upload error: ${e.message}", e)
            callback(Result.failure(e))
        }
    }

    /**
     * Calls the Cloud Run function to get a signed URL for GCS upload using Retrofit.
     *
     * @param fileName The desired name of the file in the GCS bucket.
     * @param contentType The content type of the file.
     * @param authToken The authentication token for your Cloud Run function (e.g., Firebase ID token).
     * @return The signed URL string, or null if the request fails or signed URL is not in the response.
     */
    suspend fun getSignedUrlFromCloudFunction(
        fileName: String,
        contentType: String,
        authToken: String // Authentication token
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Create the request body object
            val requestBody = SignedUrlRequest(fileName, contentType)

            // Call the Retrofit API service
            val response = cloudFunctionApiService.getSignedUrl(requestBody, "Bearer $authToken")

            // Return the signed URL from the response
            response.signedUrl

        } catch (e: Exception) {
            Log.e("GcsUploader", "Error calling Cloud Function via Retrofit: ${e.message}", e)
            null
        }
    }
}