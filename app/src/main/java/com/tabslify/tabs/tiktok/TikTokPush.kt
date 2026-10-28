package com.tabslify.tabs.tiktok

const val TIKTOK_PUSH_TOPIC = "tiktok"
const val TIKTOK_PUSH_CHANNEL = "tiktok_dm"
const val TIKTOK_PUSH_CHANNEL_ID = "tiktok_channel"
const val TIKTOK_PUSH_TAG = "tiktok"
const val TIKTOK_PUSH_TARGET = "tiktok"
const val TIKTOK_PUSH_TABLE = "tiktok_pushes"
const val TIKTOK_PUSH_FUNCTION = "tiktok-push"
const val TIKTOK_PUSH_MAX_ROWS = 40

fun tiktokPushNotificationId(convId: String): Int = convId.hashCode()