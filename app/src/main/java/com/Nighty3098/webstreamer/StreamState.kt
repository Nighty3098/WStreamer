package com.Nighty3098.webstreamer

object StreamState {
    @Volatile
    var latestFrame: ByteArray? = null
        private set

    val lock = java.lang.Object()

    fun pushFrame(frame: ByteArray) {
        synchronized(lock) {
            latestFrame = frame
            (lock as java.lang.Object).notifyAll()
        }
    }
}
