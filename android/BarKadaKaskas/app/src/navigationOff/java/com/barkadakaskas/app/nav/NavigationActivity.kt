package com.barkadakaskas.app.nav

import android.app.Activity
import android.os.Bundle

/** Built without the Navigation SDK: hand the destination to Google Maps. (InAppNavigation.enabled is false, so this is rarely reached.) */
class NavigationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(InAppNavigation.EXTRA_FALLBACK)?.let { InAppNavigation.openGoogleMaps(this, it) }
        finish()
    }
}
