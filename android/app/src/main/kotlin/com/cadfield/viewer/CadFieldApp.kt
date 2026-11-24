package com.cadfield.viewer

import android.app.Application
import android.content.Context
import com.cadfield.viewer.data.DocumentRepository
import com.cadfield.viewer.data.db.AppDatabase
import com.cadfield.viewer.sync.SyncScheduler

/** Contenedor de dependencias manual (sin framework DI para mantener el MVP simple). */
class AppContainer(context: Context) {
    val db = AppDatabase.build(context)
    val documents = DocumentRepository(context, db)
    /** Usuario actual. En Enterprise proviene del token OAuth2/JWT (claim "preferred_username"). */
    var currentUser: String = android.os.Build.MODEL.replace(' ', '_')
}

class CadFieldApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncScheduler.schedulePeriodic(this)
    }
}

val Context.container get() = (applicationContext as CadFieldApp).container
