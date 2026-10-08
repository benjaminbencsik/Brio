package com.bencsik.brio.core

/** Search once around the available servers, excluding every already attempted index. */
fun nextUntriedServer(count: Int, current: Int, attempted: Set<Int>): Int? =
    if (count <= 0) null else (1..count).map { (current + it) % count }.firstOrNull { it !in attempted }
