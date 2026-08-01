package com.localnotes.app

import android.app.Application
import com.localnotes.app.data.prefs.LibraryPreferences
import com.localnotes.app.data.repository.NotesRepository
import com.localnotes.app.data.storage.SafLibraryStore

class NotesApplication : Application() {
    lateinit var repository: NotesRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val prefs = LibraryPreferences(this)
        val store = SafLibraryStore(this)
        repository = NotesRepository(this, prefs, store)
    }
}
