package com.secomes.netswitch

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EventLog {
    val lines = MutableStateFlow<List<String>>(emptyList())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun add(msg: String) {
        Log.i("NetSwitch", msg)
        lines.value = (lines.value + "${fmt.format(Date())} $msg").takeLast(200)
    }
}
