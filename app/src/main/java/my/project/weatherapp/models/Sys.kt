package my.project.weatherapp.models

data class Sys(
    val type: Int,
    val id: Double,
    val country: String,
    val sunrise: Long,
    val sunset: Long
): java.io.Serializable
