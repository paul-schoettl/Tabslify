package com.tabslify.tabs.exploretab

import com.tabslify.tabs.ridetracking.data.Ride

fun segmentSuppressedByRide(segment: Segment, rides: List<Ride>): Boolean {
    if (!ExploreBikeClassifier.isBikeMode(segment.mode)) return false
    return rides.any { ride ->
        val rideEnd = ride.endTime ?: Long.MAX_VALUE
        segment.startTime < rideEnd && ride.startTime < segment.endTime
    }
}

fun visibleSegments(segments: List<Segment>, rides: List<Ride>): List<Segment> =
    segments.filterNot { segmentSuppressedByRide(it, rides) }
