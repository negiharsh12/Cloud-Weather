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
import android.view.WindowManager
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
import my.project.weatherapp.databinding.ActivityMainBinding
import my.project.weatherapp.models.WeatherResponse
import my.project.weatherapp.network.WeatherService
import retrofit2.*
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.*
import kotlin.properties.Delegates

class MainActivity : AppCompatActivity() {

    private var binding: ActivityMainBinding? = null

    private lateinit var mFusedLocationProviderClient: FusedLocationProviderClient

    private var mProgressDialog: Dialog? = null

    private lateinit var mSharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding?.root)

        mFusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        mSharedPreferences = getSharedPreferences(Constants.PREFERENCE_NAME, Context.MODE_PRIVATE)
        setUpUI()
        isLocationPermissionGranted()

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
    private fun  requestLocationData(){
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
            /*Log.i("bhai","$latitude")
            Log.i("behen","$longitude")*/
            weatherDetail(latitude!!, longitude!!)
        }
    }

    private fun weatherDetail(latitude: Double, longitude: Double){
        if(Constants.isNetworkAvailable(this@MainActivity)){
            /*Toast.makeText(this@MainActivity,"Ok!!",Toast.LENGTH_SHORT).show()*/
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



    override fun onDestroy() {
        super.onDestroy()
        binding = null
    }
}