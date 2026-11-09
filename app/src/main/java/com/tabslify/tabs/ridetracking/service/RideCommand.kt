package com.tabslify.tabs.ridetracking.service

object RideCommand {
    const val ACTION_START = "com.tabslify.ridetracking.START"
    const val ACTION_PAUSE = "com.tabslify.ridetracking.PAUSE"
    const val ACTION_RESUME = "com.tabslify.ridetracking.RESUME"
    const val ACTION_STOP = "com.tabslify.ridetracking.STOP"

    const val EXTRA_RIDE_ID = "ride_id"
    const val EXTRA_MODE = "ride_mode"

    const val NOTIFICATION_ID = 80001
    const val CHANNEL_ID = "ride_tracking"
}
