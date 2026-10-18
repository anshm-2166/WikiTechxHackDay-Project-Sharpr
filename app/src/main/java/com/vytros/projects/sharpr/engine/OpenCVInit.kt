package com.vytros.projects.sharpr.engine

import org.opencv.android.OpenCVLoader

object OpenCVInit {
    private val ok: Boolean by lazy { OpenCVLoader.initLocal() }

    fun ensure() {
        check(ok) { "OpenCV failed to initialise" }
    }
}
