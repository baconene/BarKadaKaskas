package com.barkadakaskas.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.barkadakaskas.app.data.LaravelRepository
import com.barkadakaskas.app.data.TokenStore
import com.barkadakaskas.app.ui.TravelApp
import com.barkadakaskas.app.ui.TravelViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Laravel API with the token kept encrypted in the Android Keystore.
        val repository = LaravelRepository(TokenStore(applicationContext))
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TravelViewModel(repository) as T
        }
        setContent { TravelApp(viewModel(factory = factory)) }
    }
}
