package com.learnlayout.mp_3

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val uri: Uri,
    val dateAdded: Long = 0L
) : Parcelable {

    constructor(parcel: Parcel) : this(
        id = parcel.readLong(),
        title = parcel.readString() ?: "",
        artist = parcel.readString() ?: "",
        duration = parcel.readLong(),
        uri = Uri.parse(parcel.readString()),
        dateAdded = parcel.readLong()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(id)
        parcel.writeString(title)
        parcel.writeString(artist)
        parcel.writeLong(duration)
        parcel.writeString(uri.toString())
        parcel.writeLong(dateAdded)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<Song> {
        override fun createFromParcel(parcel: Parcel): Song = Song(parcel)
        override fun newArray(size: Int): Array<Song?> = arrayOfNulls(size)
    }
}