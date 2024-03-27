package com.example.hearingaidapp.ui.translator

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.hearingaidapp.R
import com.example.hearingaidapp.databinding.FragmentTranslatorBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import kotlin.math.pow


class TranslatorFragment : Fragment() {

    private var _binding: FragmentTranslatorBinding? = null
    private var gainFactor: Float = 1.0f // Default gain factor
    // Audio recording and playback
    private lateinit var audioRecord: AudioRecord
    private lateinit var audioTrack: AudioTrack
    private lateinit var audioManager: AudioManager

    private var isAudioProcessing = false // Flag to control audio processing
    private var minBufferSize: Int = 0

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!
    private val translatorViewModel: TranslatorViewModel by activityViewModels()
    private val RQ_SPEECH_REC = 102

    private var audioFocusRequest: AudioFocusRequest? = null
    @RequiresApi(Build.VERSION_CODES.O)
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {

            AudioManager.AUDIOFOCUS_LOSS -> {
                // Stop audio processing
                stopAudioProcessing()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Pause audio processing
                pauseAudioProcessing()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Lower the playback volume
                // Not directly applicable here, but you can adjust your logic accordingly
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Resume audio processing with original volume
                resumeAudioProcessing()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTranslatorBinding.inflate(inflater, container, false)

        binding.btnButton.setOnClickListener {
            startSpeechToText()
        }

        return binding.root
    }

    @SuppressLint("MissingPermission")
    private fun initAudioComponents() {
        val sampleRate = 44100 // Common audio sampling rate
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        Log.d("AudioStatus", "AudioRecord initialized, state: ${audioRecord.state}, recording state: ${audioRecord.recordingState}")
        Log.d("AudioStatus", "AudioTrack initialized, state: ${audioTrack.state}, play state: ${audioTrack.playState}")


        Log.d("AudioInit", "AudioRecord and AudioTrack initialized with buffer size: $minBufferSize")

        // Route audio to the earpiece
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        // Ensure the volume is set appropriately for the earpiece
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume, AudioManager.FLAG_SHOW_UI)

    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1
    }
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_CODE)
        } else {
            initAudioComponents()
            startAudioProcessing()
        }

        val seekBar: SeekBar = view.findViewById(R.id.seekBarAudioAmp)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Update gain factor based on slider position
                gainFactor = calculateGainFactor(progress)
                Log.d("SeekBar", "User is adjusting the seekBar. Current progress: $progress, Gain: $gainFactor")

            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // You could add code here for when the user starts dragging the slider
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // You could add code here for when the user stops dragging the slider
            }
        })

        // Observe the LiveData in the ViewModel to update the UI
        translatorViewModel.translatedText.observe(viewLifecycleOwner) { translatedText ->
            // Update the text in the TextView
            binding.textTranslator.setText(translatedText)
        }
    }


    @RequiresApi(Build.VERSION_CODES.O)
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initAudioComponents()
            startAudioProcessing()
        } else {
            Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestAudioFocus(): Boolean {
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()

        val result = audioManager.requestAudioFocus(audioFocusRequest!!)
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startAudioProcessing() {
        if (requestAudioFocus()) {
            CoroutineScope(Dispatchers.Default).launch {
                isAudioProcessing = true
                audioRecord.startRecording()
                Log.d("AudioStatus", "AudioRecord started, recording state: ${audioRecord.recordingState}")
                audioTrack.play()
                Log.d("AudioStatus", "AudioTrack playing, play state: ${audioTrack.playState}")

                val buffer = ShortArray(minBufferSize)
                Log.d("AudioLoop", "Starting audio processing loop")
                while (isAudioProcessing) {
                    if (audioRecord.state == AudioRecord.STATE_INITIALIZED && audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val readResult = audioRecord.read(buffer, 0, buffer.size)
                        if (readResult > 0) {
                            // Apply gain to the buffer before playback
                            Log.d("AudioProcessing", "Read result: $readResult")
                            Log.d("AudioProcessing", "Buffer before gain adjustment: ${buffer.take(5)}")
                            val adjustedBuffer = adjustGain(buffer, gainFactor)
                            Log.d("AudioProcessing", "Buffer after gain adjustment: ${adjustedBuffer.take(5)}")
                            audioTrack.write(adjustedBuffer, 0, readResult)

                            Log.d("AudioLoop", "AudioRecord read successful, recording state: ${audioRecord.recordingState}")
                            Log.d("AudioLoop", "AudioTrack write successful, play state: ${audioTrack.playState}")

                        }
                    } else {
                        Log.e("AudioRecordError", "AudioRecord is not initialized or not recording")
                    }
                }
            }
        }
    }

    private fun pauseAudioProcessing() {
        // Check if audio processing is currently active before trying to pause
        if (isAudioProcessing) {
            // Stop the audio recording and playback without releasing resources
            audioRecord.stop()
            Log.d("AudioStatus", "AudioRecord stopped, recording state: ${audioRecord.recordingState}")
            audioTrack.pause() // Pause playback. You could also use stop() depending on your requirement
            Log.d("AudioStatus", "AudioTrack paused/stopped, play state: ${audioTrack.playState}")
            isAudioProcessing = false // Indicate that audio processing has been paused
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun resumeAudioProcessing() {
        // Ensure that we have audio focus before resuming
        if (requestAudioFocus()) {
            // Restart the audio recording and playback
            audioRecord.startRecording()
            Log.d("AudioStatus", "AudioRecord resumed, recording state: ${audioRecord.recordingState}")
            audioTrack.play()
            Log.d("AudioStatus", "AudioTrack resumed, play state: ${audioTrack.playState}")
            isAudioProcessing = true // Indicate that audio processing has resumed

            // Resume audio data processing in a background thread
            CoroutineScope(Dispatchers.Default).launch {
                val buffer = ShortArray(minBufferSize)
                while (isAudioProcessing) {
                    if (audioRecord.state == AudioRecord.STATE_INITIALIZED && audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val readResult = audioRecord.read(buffer, 0, buffer.size)
                        if (readResult > 0) {
                            // Apply gain to the buffer before playback
                            Log.d("AudioProcessing", "Read result: $readResult")
                            Log.d("AudioProcessing", "Buffer before gain adjustment: ${buffer.take(5)}")
                            val adjustedBuffer = adjustGain(buffer, gainFactor)
                            Log.d("AudioProcessing", "Buffer after gain adjustment: ${adjustedBuffer.take(5)}")
                            audioTrack.write(adjustedBuffer, 0, readResult)

                            Log.d("AudioLoop", "AudioRecord read successful, recording state: ${audioRecord.recordingState}")
                            Log.d("AudioLoop", "AudioTrack write successful, play state: ${audioTrack.playState}")

                        }
                    } else {
                        Log.e("AudioRecordError", "AudioRecord is not initialized or not recording")
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun stopAudioProcessing() {
        isAudioProcessing = false
        audioRecord.stop()
        Log.d("AudioStatus", "AudioRecord stopped, recording state: ${audioRecord.recordingState}")
        audioTrack.pause() // or stop, depending on your needs
        Log.d("AudioStatus", "AudioTrack paused/stopped, play state: ${audioTrack.playState}")
        releaseAudioFocus()
    }

    private fun adjustGain(audioBuffer: ShortArray, gainFactor: Float): ShortArray {
        return audioBuffer.map { sample ->
            // Apply gain factor and ensure the value does not exceed Short.MAX_VALUE or Short.MIN_VALUE
            (sample * gainFactor).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                .toInt()
                .toShort()
        }.toShortArray()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun releaseAudioFocus() {
        if (audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest!!)
        }
    }

    private fun calculateGainFactor(sliderProgress: Int): Float {
        // Map slider range (0-100) to dB range (40dB-80dB)
        val maxSliderValue = 1000
        val dB = (sliderProgress / maxSliderValue.toFloat()) * (80f - 40f) + 40f
        // Convert dB gain to linear scale
        return 10.0.pow(dB / 20.0).toFloat()
        Log.d("GainCalculation", "Calculated gain factor: $gainFactor")

    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RQ_SPEECH_REC && resultCode == Activity.RESULT_OK) {
            val result = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val recognizedText = result?.get(0).toString() // Updated
            binding.textTranslator.setText(recognizedText)

            translatorViewModel.updateTranslatedText(recognizedText)

            // Retrieve the selected language code
            val sharedPref = activity?.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
            val defaultTextSize = resources.getDimension(R.dimen.default_text_size) // Define a default size in your dimens.xml
            val textSize = sharedPref?.getInt("textSize", defaultTextSize.toInt()) ?: defaultTextSize.toInt()
            binding.textTranslator.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize.toFloat())
            val selectedLanguageCode = sharedPref?.getString("selectedLanguageCode", "en") // Default to English

            translateText(recognizedText, selectedLanguageCode!!)
        }
    }

    private fun loadAndApplySettings() {
        val sharedPref = activity?.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        val textSize = sharedPref?.getInt("textSize", resources.getDimension(R.dimen.default_text_size).toInt()) ?: return
        val selectedLanguageCode = sharedPref.getString("selectedLanguageCode", "en") ?: return

        // Apply the text size to your TextView
        binding.textTranslator.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize.toFloat())

        if (translatorViewModel.originalText != null && selectedLanguageCode != translatorViewModel.lastUsedLanguageCode) {
            translateText(translatorViewModel.originalText!!, selectedLanguageCode)
        }
    }

    private fun startSpeechToText() {
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            Toast.makeText(requireContext(), "Speech recognition is not available", Toast.LENGTH_SHORT).show()
        } else {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say something!")
            startActivityForResult(intent, RQ_SPEECH_REC)
        }
    }

    private fun translateText(originalText: String, targetLanguage: String) {
        CoroutineScope(Dispatchers.IO).launch {
            translatorViewModel.updateOriginalText(originalText)
            translatorViewModel.lastUsedLanguageCode = targetLanguage
            val client = OkHttpClient()
            val url = "https://translation.googleapis.com/language/translate/v2"
            val apiKey = "AIzaSyDflada85_3r_rMutCZN4bKMYQNBCw3iak" // Caution: Storing API key in the app is not recommended for production
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

            // Parse the JSON response to extract the translated text
            val translatedText = JSONObject(responseBody ?: "").getJSONObject("data")
                .getJSONArray("translations").getJSONObject(0)
                .getString("translatedText")

            withContext(Dispatchers.Main) {
                // Update UI with translated text
                translatorViewModel.updateTranslatedText(translatedText)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadAndApplySettings()
    }

    private fun restoreAudioManagerMode() {
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onDestroyView() {
        super.onDestroyView()
        isAudioProcessing = false
        stopAudioProcessing()
        audioRecord.stop()
        audioRecord.release()
        Log.d("AudioCleanup", "AudioRecord released")
        audioTrack.stop()
        audioTrack.release()
        Log.d("AudioCleanup", "AudioTrack released")
        _binding = null
        restoreAudioManagerMode()
    }
}