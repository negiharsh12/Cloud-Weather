package my.project.weatherapp.models

data class Weather(
    val id: Int,
    val main: String,
    val description: String,
    val icon: String
): java.io.Serializable
