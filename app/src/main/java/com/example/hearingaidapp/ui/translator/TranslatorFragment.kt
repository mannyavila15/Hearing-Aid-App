package com.example.hearingaidapp.ui.translator

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.coroutineScope
import com.example.hearingaidapp.databinding.FragmentTranslatorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.pow
import okhttp3.Callback
import org.json.JSONException
import java.io.IOException
import android.util.Base64
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay


class TranslatorFragment : Fragment() {

    private var _binding: FragmentTranslatorBinding? = null
    private val binding get() = _binding!!
    private val translatorViewModel: TranslatorViewModel by activityViewModels()
    private val audioAmplifier = AudioAmplifier()
    private val RQ_SPEECH_REC = 102
    private var lastTranscribedText: String? = null
    private var transcriptionCount = 0
    private var transcript = StringBuilder()
    private var resetTranscriptJob: Job? = null


    companion object {
        private const val PERMISSION_REQUEST_CODE = 1
        private const val SAMPLE_RATE = 16000 // Sample rate in Hz
        private const val CHUNK_DURATION_MS = 2000 // Chunk duration in milliseconds
        const val BUFFER_SIZE = SAMPLE_RATE * CHUNK_DURATION_MS / 1000
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTranslatorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.seekBarAudioAmp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val dbGain = progress  // This will be in the range 0 to 20
                val linearGain = 10.0.pow(dbGain / 20.0)
                audioAmplifier.setGain(linearGain)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) { }

            override fun onStopTrackingTouch(seekBar: SeekBar?) { }
        })

        if (transcript.isEmpty()) {
            binding.textTranslator.setText("Text will appear here")
        }

        checkAndRequestPermissions()
    }

    // Code for streaming audio data to Google Speech-to-Text API

    fun streamAudioToGoogleAPI(audioData: ByteArray) {
        val apiKey = "AIzaSyDflada85_3r_rMutCZN4bKMYQNBCw3iak" // Make sure to replace with your actual API key
        val url = "https://speech.googleapis.com/v1/speech:recognize?key=$apiKey"
        val client = OkHttpClient()

        // Encode the audio data to base64
        val audioBase64 = Base64.encodeToString(audioData, Base64.NO_WRAP)

        // Create JSON payload
        val jsonPayload = JSONObject().apply {
            put("config", JSONObject().apply {
                put("encoding", "LINEAR16")
                put("sampleRateHertz", 16000)
                put("languageCode", "en-US")
            })
            put("audio", JSONObject().apply {
                put("content", audioBase64)
            })
        }.toString()

        val requestBody = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())

        // Build the request
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        // Send the request
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
                println("Request failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!isAdded) { // Check if fragment is still added to its activity
                    return
                }
                val responseData = response.body?.string()
                println("Response received: $responseData")
                val transcribedText = parseTranscribedText(responseData)
                // Update UI with transcribed text
                if (transcribedText != null && transcribedText.isNotEmpty()) {
                    transcript.append(transcribedText + " ")
                    activity?.runOnUiThread {
                        translateText(transcript.toString())
                        transcriptionCount ++
                        if (transcriptionCount >= 8) { // Check if it's time to clear the text
                            transcript.clear()
                            transcriptionCount = 0 // Reset the count
                            binding.textTranslator.setText("") // Clear the text on UI
                        } else {
                            startOrRestartTranscriptResetJob()
                        }
                    }
                }
            }
        })
    }

    private fun startOrRestartTranscriptResetJob() {
        resetTranscriptJob?.cancel() // Cancel any existing job
        resetTranscriptJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(7000) // Wait for 7 seconds
            transcript.clear() // Clear the transcript
            activity?.runOnUiThread {
                binding.textTranslator.setText("") // Clear the text on UI
            }
        }
    }


    private fun refreshTranscriptionDisplay() {
        val currentText = transcript.toString() // Get the current transcript text
        translateText(currentText) // Re-translate the entire transcript
        applyTextSize() // Ensure the text size is also refreshed
    }


    private fun parseTranscribedText(responseData: String?): String? {
        responseData?.let {
            try {
                val jsonObject = JSONObject(it)
                val resultsArray = jsonObject.getJSONArray("results")
                if (resultsArray.length() > 0) {
                    val resultObject = resultsArray.getJSONObject(0)
                    val alternativesArray = resultObject.getJSONArray("alternatives")
                    if (alternativesArray.length() > 0) {
                        val alternativeObject = alternativesArray.getJSONObject(0)
                        return alternativeObject.getString("transcript")
                    }
                }
            } catch (e: JSONException) {
                e.printStackTrace()
            }
        }
        return null // Return null to indicate no valid transcription was obtained
    }


    private fun checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_CODE)
        } else {
            audioAmplifier.startCapturingAndAmplifying()
            // Start streaming to Google's API here after implementing it
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, "Permission granted", Toast.LENGTH_SHORT).show()
            audioAmplifier.startCapturingAndAmplifying()
        } else {
            Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun translateText(originalText: String) {
        val sharedPref = requireActivity().getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        val targetLanguage = sharedPref.getString("selectedLanguageCode", "en") ?: "en"

        // Switch to viewLifecycleOwner.lifecycleScope to scope the coroutine to the fragment's view lifecycle
        viewLifecycleOwner.lifecycleScope.launch {
            val translatedText = withContext(Dispatchers.IO) {
                // Network request logic remains the same
                val client = OkHttpClient()
                val url = "https://translation.googleapis.com/language/translate/v2"
                val apiKey = "AIzaSyDflada85_3r_rMutCZN4bKMYQNBCw3iak"
                val requestBody = JSONObject().apply {
                    put("q", originalText)
                    put("target", targetLanguage)
                }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url("$url?key=$apiKey")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                JSONObject(responseBody ?: "").getJSONObject("data")
                    .getJSONArray("translations").getJSONObject(0)
                    .getString("translatedText")
            }

            // Ensure that fragment is still added to its activity before attempting to update UI
            if(isAdded) {
                binding.textTranslator.setText(translatedText) // Safely update the UI with translated text
                applyTextSize() // Safely ensure the correct text size is applied to the translated text
            }
        }
    }



    private fun applyTextSize() {
        val sharedPref = requireActivity().getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        val textSize = sharedPref.getInt("textSize", 18) // Default to 18 if not set
        binding.textTranslator.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize.toFloat())
    }

    override fun onResume() {
        super.onResume()
        refreshTranscriptionDisplay()
        startOrRestartTranscriptResetJob()

        if (transcript.isEmpty()) {
            binding.textTranslator.setText("Text will appear here")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Speech recognition result handling
        if (requestCode == RQ_SPEECH_REC && resultCode == Activity.RESULT_OK) {
            val result = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val recognizedText = result?.get(0) ?: ""
            translateText(recognizedText)
        }
        // Ensure the audio amplification is stopped after speech recognition completes or fails
        audioAmplifier.stopCapturingAndAmplifying()
    }


    override fun onDestroyView() {
        super.onDestroyView()
        resetTranscriptJob?.cancel()
        _binding = null
        audioAmplifier.stopCapturingAndAmplifying()
    }

    // Inner class for amplifying audio
    inner class AudioAmplifier {
        private var isCapturing = false

        @Volatile
        private var gainFactor = 1.0  // Default no gain

        fun setGain(gain: Double) {
            gainFactor = gain
        }

        fun startCapturingAndAmplifying() {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(requireContext(), "Permission for recording audio not granted", Toast.LENGTH_SHORT).show()
                return
            }

            isCapturing = true
            thread {
                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    BUFFER_SIZE * 2 // Adjust as necessary
                )

                val audioTrack = AudioTrack.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(BUFFER_SIZE * 2)
                    .build()

                audioRecord.startRecording()
                audioTrack.play()

                val buffer = ShortArray(BUFFER_SIZE)
                while (isCapturing) {
                    val readResult = audioRecord.read(buffer, 0, buffer.size)
                    if (readResult > 0) {
                        for (i in buffer.indices) {
                            buffer[i] = (buffer[i] * gainFactor).coerceIn(Short.MIN_VALUE.toFloat().toDouble(),
                                Short.MAX_VALUE.toFloat().toInt().toShort().toDouble()).toInt().toShort()
                        }
                        // Write the captured audio data to the AudioTrack for playback
                        audioTrack.write(buffer, 0, readResult)

                        // Convert and send this chunk for transcription
                        val audioBytes = shortArrayToByteArray(buffer.copyOfRange(0, readResult))
                        streamAudioToGoogleAPI(audioBytes)
                    }
                }

                audioRecord.stop()
                audioRecord.release()
                audioTrack.stop()
                audioTrack.release()
            }
        }


        private fun shortArrayToByteArray(shorts: ShortArray): ByteArray {
            val bytes = ByteArray(shorts.size * 2) // Each short is 2 bytes
            for ((index, value) in shorts.withIndex()) {
                // Little-endian
                bytes[index * 2] = (value.toInt() and 0xFF).toByte() // LSB
                bytes[index * 2 + 1] = ((value.toInt() shr 8) and 0xFF).toByte() // MSB
            }
            return bytes
        }


        fun stopCapturingAndAmplifying() {
            isCapturing = false
        }
    }
}