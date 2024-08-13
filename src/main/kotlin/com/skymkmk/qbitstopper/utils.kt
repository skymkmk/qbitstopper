package com.skymkmk.qbitstopper

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow

fun tickerFlow(millisecond: Long) = flow {
    while (true) {
        emit(Unit)
        delay(millisecond)
    }
}