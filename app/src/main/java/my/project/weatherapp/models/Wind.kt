package my.project.weatherapp.models

import java.nio.DoubleBuffer

data class Wind(
    val speed: Double,
    val deg: Int
): java.io.Serializable