package my.project.weatherapp.models

data class WeatherResponse(
    val coordinates: Coordinates,
    val weather: List<Weather>,
    val base: String,
    val main: Main,
    val visibility: Long,
    val wind: Wind,
    val clouds: Cloud,
    val dt: Long,
    val sys: Sys,
    val timezone: Int,
    val id: Int,
    val name: String,
    val cod: Int
) : java.io.Serializable




