package com.ahwaktv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AhwakTVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AhwakTV())
    }
}
