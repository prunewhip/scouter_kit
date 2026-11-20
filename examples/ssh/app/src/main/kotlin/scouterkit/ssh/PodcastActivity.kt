package scouterkit.ssh

import android.app.Activity
import android.content.Intent
import android.os.Bundle

// Podcasts on a screen of its own: where texts shared from other apps arrive (Share: Vox Podcasts) and where "Episode
// ready" opens. The same page as the app's Podcasts tab (PodcastPage).
class PodcastActivity : Activity() {
    private lateinit var podcasts: PodcastPage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        podcasts = PodcastPage(this, page()) { finish() }
        if (savedInstanceState == null) podcasts.receive(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(Intent(this, PodcastActivity::class.java)) // handled once
        podcasts.receive(intent)
    }

    override fun onResume() {
        super.onResume()
        podcasts.visible(true)
    }

    override fun onPause() {
        super.onPause()
        podcasts.visible(false)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!podcasts.back()) super.onBackPressed()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        podcasts.result(requestCode, resultCode, data)
    }
}
