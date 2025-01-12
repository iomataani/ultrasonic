/*
 * Artist.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.domain

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "artists", primaryKeys = ["id", "serverId"])
data class Artist(
    override var id: String,
    @ColumnInfo(defaultValue = "-1")
    override var serverId: Int = -1,
    override var name: String? = null,
    override var index: String? = null,
    override var coverArt: String? = null,
    override var albumCount: Long? = null,
    override var closeness: Int = 0
) : ArtistOrIndex(id, serverId)
