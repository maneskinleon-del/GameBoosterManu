package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val icon: String, // Emoji or Icon Name
    val governor: String,
    val refreshRate: String,
    val isActive: Boolean,
    val isCustom: Boolean
)
