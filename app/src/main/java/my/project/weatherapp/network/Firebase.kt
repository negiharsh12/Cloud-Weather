package my.project.weatherapp.network

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import my.project.weatherapp.MainActivity

class Firebase: AppCompatActivity() {
    private val auth = FirebaseAuth.getInstance()
    fun getCurrentUserId(): String {
        val currentUser = auth.currentUser
        var currentUserId = ""
        if(currentUser != null) {
            currentUserId = currentUser.uid
        }
        return currentUserId
    }

    fun firebaseAuthWithGoogle(idToken: String, context: Context) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if(task.isSuccessful) {
                    startActivity(Intent(context, MainActivity::class.java))
                }
                else {
                    Toast.makeText(this, "Authentication Failed. Try Again !!!", Toast.LENGTH_SHORT).show()
                }
            }
    }
}