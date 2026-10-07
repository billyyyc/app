package com.example.studentlookup

import android.app.Application
import com.example.studentlookup.data.db.AppDatabase

class App : Application() {
    val database by lazy { AppDatabase.get(this) }
}
