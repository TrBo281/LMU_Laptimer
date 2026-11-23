package com.lmulaptimer

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.lmulaptimer.data.AutoSync
import com.lmulaptimer.data.Repo
import com.lmulaptimer.ui.App

class MainActivity : ComponentActivity() {

    private lateinit var repo: Repo

    override fun onResume() {
        super.onResume()
        TrainingStreakWidget.update(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        repo = Repo(applicationContext)
        if (repo.autoSync) {
            AutoSync.schedule(this)
            AutoSync.runNow(this)
        }
        setContent {
            App(repo)
        }
    }
}
