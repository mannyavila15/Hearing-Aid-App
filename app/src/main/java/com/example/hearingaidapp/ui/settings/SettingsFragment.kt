package com.example.hearingaidapp.ui.settings

import com.example.hearingaidapp.R
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.hearingaidapp.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val dashboardViewModel =
            ViewModelProvider(this).get(SettingsViewModel::class.java)

        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

//        val textView: TextView = binding.tvDropdownLabel
//        dashboardViewModel.text.observe(viewLifecycleOwner) {
//            textView.text = it
//        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val languages = resources.getStringArray(R.array.language_names)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, languages)
        binding.spinnerOptions.adapter = adapter

        // Save the selected language code when an item is selected
        binding.spinnerOptions.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val languageCodes = resources.getStringArray(R.array.language_codes)
                val selectedLanguageCode = languageCodes[position]
                // Save the selected language code for later use (e.g., in SharedPreferences)
                val sharedPref = activity?.getSharedPreferences("AppSettings", Context.MODE_PRIVATE) ?: return
                with (sharedPref.edit()) {
                    putString("selectedLanguageCode", selectedLanguageCode)
                    apply()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // Optional: Handle the case where nothing is selected
            }
        }

        binding.seekBarTextSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val textSize = progress * 5 + 10

                // Save the progress value to SharedPreferences whenever the user changes the slider
                val sharedPref = activity?.getSharedPreferences("AppSettings", Context.MODE_PRIVATE) ?: return
                with(sharedPref.edit()) {
                    putInt("textSize", textSize)
                    apply()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Not needed
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Not needed
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}