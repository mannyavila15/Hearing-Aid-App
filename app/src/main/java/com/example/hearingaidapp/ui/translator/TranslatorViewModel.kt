package com.example.hearingaidapp.ui.translator

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class TranslatorViewModel : ViewModel() {
    var originalText: String? = null // Add this to store the original text

    val translatedText = MutableLiveData<String>()

    private val _text = MutableLiveData<String>().apply {
        value = "This is home Fragment"
    }
    val text: LiveData<String> = _text
    var lastUsedLanguageCode: String? = "en"

    fun updateOriginalText(text: String) {
        originalText = text
    }


    fun updateTranslatedText(text: String) {
        translatedText.value = text
    }
}