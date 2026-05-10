package com.example.eyeprotect.nav

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.eyeprotect.R
import com.example.eyeprotect.acupressure.ArGuideActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AcupressureFragment : Fragment() {
    private lateinit var selectedSymptomsContainer: LinearLayout

    private lateinit var btnFatigue: TextView
    private lateinit var btnEyeFatigue: TextView
    private lateinit var btnEyeCare: TextView
    private lateinit var btnHeadache: TextView
    private lateinit var btnTears: TextView
    private lateinit var btnBrowPain: TextView
    private lateinit var btnBlur: TextView
    private lateinit var nextButton: TextView

    private val selectedSymptoms = ArrayList<String>()
    private val selectedStates = mutableMapOf<String, Boolean>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.activity_acupressure_selection, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        bindViews(view)
        applyAllUnselectedStyles()
        setupSymptomButtons()
        setupNextButton()
        updateSelectedSymptomsPreview()
    }

    private fun bindViews(view: View) {
        selectedSymptomsContainer = view.findViewById(R.id.selectedSymptomsContainer)

        btnFatigue = view.findViewById(R.id.btnFatigue)
        btnEyeFatigue = view.findViewById(R.id.btnEyeFatigue)
        btnEyeCare = view.findViewById(R.id.btnEyeCare)
        btnHeadache = view.findViewById(R.id.btnHeadache)
        btnTears = view.findViewById(R.id.btnTears)
        btnBrowPain = view.findViewById(R.id.btnBrowPain)
        btnBlur = view.findViewById(R.id.btnBlur)
        nextButton = view.findViewById(R.id.nextButton)
    }

    private fun applyAllUnselectedStyles() {
        updateChipStyle(btnFatigue, false)
        updateChipStyle(btnEyeFatigue, false)
        updateChipStyle(btnEyeCare, false)
        updateChipStyle(btnHeadache, false)
        updateChipStyle(btnTears, false)
        updateChipStyle(btnBrowPain, false)
        updateChipStyle(btnBlur, false)
    }

    private fun setupSymptomButtons() {
        setupSymptomButton(btnFatigue, "疲勞")
        setupSymptomButton(btnEyeFatigue, "眼睛疲勞")
        setupSymptomButton(btnEyeCare, "眼睛澀澀")
        setupSymptomButton(btnHeadache, "頭痛")
        setupSymptomButton(btnTears, "流淚")
        setupSymptomButton(btnBrowPain, "眉骨疼痛")
        setupSymptomButton(btnBlur, "視力模糊")
    }

    private fun setupSymptomButton(chip: TextView, symptom: String) {
        chip.setOnClickListener {
            val isSelected = !(selectedStates[symptom] ?: false)
            selectedStates[symptom] = isSelected
            toggleSymptom(symptom, isSelected)
            updateChipStyle(chip, isSelected)
        }
    }

    private fun toggleSymptom(symptom: String, isSelected: Boolean) {
        if (isSelected) {
            if (!selectedSymptoms.contains(symptom)) {
                selectedSymptoms.add(symptom)
            }
        } else {
            selectedSymptoms.remove(symptom)
        }

        updateSelectedSymptomsPreview()
    }

    private fun updateSelectedSymptomsPreview() {
        selectedSymptomsContainer.removeAllViews()

        if (selectedSymptoms.isEmpty()) {
            selectedSymptomsContainer.addView(
                TextView(requireContext()).apply {
                    text = "尚未選擇症狀"
                    setTextColor(Color.parseColor("#666666"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
            )
            return
        }

        var row: LinearLayout? = null
        selectedSymptoms.forEachIndexed { index, symptom ->
            if (index % 3 == 0) {
                row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        if (index != 0) topMargin = dp(10)
                    }
                }
                selectedSymptomsContainer.addView(row)
            }

            row?.addView(
                TextView(requireContext()).apply {
                    text = symptom
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    gravity = Gravity.CENTER
                    setSingleLine(true)
                    setBackgroundResource(R.drawable.bg_acupressure_chip_blue)
                    setPadding(dp(6), 0, dp(6), 0)
                    layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                        setMargins(0, 0, dp(8), 0)
                    }
                }
            )
        }
    }

    private fun updateChipStyle(chip: TextView, isSelected: Boolean) {
        chip.setBackgroundResource(
            if (isSelected) R.drawable.bg_acupressure_chip_blue else R.drawable.bg_acupressure_chip_gray
        )
        chip.setTextColor(Color.WHITE)
    }

    private fun setupNextButton() {
        nextButton.setOnClickListener {
            if (selectedSymptoms.isEmpty()) {
                Toast.makeText(requireContext(), "請至少選擇一項症狀", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(requireContext(), ArGuideActivity::class.java)
                .putStringArrayListExtra("selected_symptoms", ArrayList(selectedSymptoms))
                .putStringArrayListExtra("selected_acupoints", mapSymptomsToSixPoints())
            startActivity(intent)
        }
    }

    private fun mapSymptomsToSixPoints(): ArrayList<String> {
        val points = linkedSetOf<String>()

        selectedSymptoms.forEach { symptom ->
            when (symptom) {
                "疲勞" -> points.addAll(listOf("承泣", "魚腰", "太陽"))
                "眼睛疲勞" -> points.addAll(listOf("承泣", "睛明", "魚腰", "太陽"))
                "眼睛澀澀" -> points.addAll(listOf("睛明", "攢竹", "絲竹空"))
                "頭痛" -> points.addAll(listOf("太陽", "絲竹空", "攢竹"))
                "流淚" -> points.addAll(listOf("睛明", "承泣"))
                "眉骨疼痛" -> points.addAll(listOf("攢竹", "魚腰"))
                "視力模糊" -> points.addAll(listOf("睛明", "承泣", "太陽"))
            }
        }

        return ArrayList(points)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
