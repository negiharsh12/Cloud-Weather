package my.project.weatherapp

import android.content.Context
import android.icu.util.Calendar
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Constants {

    const val APP_ID: String = "c05d133efffd334913261048f783189c"
    const val BASE_URL: String = "https://api.openweathermap.org/data/"
    const val METRIC_UNIT: String = "metric"
    const val PREFERENCE_NAME = "WeatherAppPreference"
    const val WEATHER_RESPONSE_DATA = "weather_response_data"


    fun isNetworkAvailable(context: Context): Boolean{
        val connectivityManager = context.
                getSystemService(Context.CONNECTIVITY_SERVICE) as
                ConnectivityManager
        // Old Way
        /*val networkInfo = connectivityManager.activeNetworkInfo
        return networkInfo != null && networkInfo.isConnectedOrConnecting*/

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
            val network = connectivityManager.activeNetwork ?: return false
            val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false

            return when{
                activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
                activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
                activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
                else -> false
            }
        }   else{
            val networkInfo = connectivityManager.activeNetworkInfo
            return networkInfo != null && networkInfo.isConnectedOrConnecting
        }
    }

    /**
     * Calculates the delay in milliseconds until the next occurrence of the target hour and minute.
     * If the target time today has already passed, it schedules for the same time tomorrow.
     */
    fun getInitialDelayMillis(targetHour: Int, targetMinute: Int = 0, targetSecond: Int = 0): Long {
        val now = Calendar.getInstance()
        val targetTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, targetSecond)
            set(Calendar.MILLISECOND, 0)
        }

        // If target time on the current day is in the past, schedule for the next day
        if (now.after(targetTime)) {
            targetTime.add(Calendar.DAY_OF_MONTH, 1)
        }
        // Current time for logging: Saturday, May 17, 2025, 7:34 PM
        // Example: If targetHour is 6 (6 AM):
        //   - now is 7:34 PM on Sat.
        //   - targetTime initially set to 6:00 AM on Sat.
        //   - now.after(targetTime) is true.
        //   - targetTime.add(Calendar.DAY_OF_MONTH, 1) changes targetTime to 6:00 AM on Sun.
        //   - Delay will be calculated until Sun 6:00 AM. (Correct)
        // Example: If targetHour is 18 (6 PM):
        //   - now is 7:34 PM on Sat.
        //   - targetTime initially set to 6:00 PM on Sat.
        //   - now.after(targetTime) is true.
        //   - targetTime.add(Calendar.DAY_OF_MONTH, 1) changes targetTime to 6:00 PM on Sun.
        //   - Delay will be calculated until Sun 6:00 PM. (Correct for next occurrence)

        return targetTime.timeInMillis - now.timeInMillis
    }

    fun getCurrentTimeStamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    }
}