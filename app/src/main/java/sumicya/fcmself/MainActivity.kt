package sumicya.fcmself

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import sumicya.fcmself.ui.navigation.FcmselfNavHost
import sumicya.fcmself.ui.theme.FcmselfTheme

/**
 * The single Activity — now fully Compose-based.
 *
 * All UI lives in composable screens; this Activity only sets up the theme,
 * edge-to-edge rendering, and the navigation host. The Xposed hooks and
 * data layer remain unchanged in their respective packages.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FcmselfTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FcmselfNavHost()
                }
            }
        }
    }
}