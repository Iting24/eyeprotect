package com.example.eyeprotect.acupressure;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.eyeprotect.R;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

public class SelectionActivity extends AppCompatActivity {

    private LinearLayout selectedSymptomsContainer;

    private TextView btnFatigue;
    private TextView btnEyeFatigue;
    private TextView btnEyeCare;
    private TextView btnHeadache;
    private TextView btnTears;
    private TextView btnBrowPain;
    private TextView btnBlur;
    private TextView nextButton;

    private final ArrayList<String> selectedSymptoms = new ArrayList<>();

    private boolean isFatigueSelected = false;
    private boolean isEyeFatigueSelected = false;
    private boolean isEyeCareSelected = false;
    private boolean isHeadacheSelected = false;
    private boolean isTearsSelected = false;
    private boolean isBrowPainSelected = false;
    private boolean isBlurSelected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_acupressure_selection);

        bindViews();
        applyAllUnselectedStyles();
        setupSymptomButtons();
        setupNextButton();
        handleResetIfNeeded(getIntent());
        updateSelectedSymptomsPreview();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleResetIfNeeded(intent);
    }

    private void bindViews() {
        selectedSymptomsContainer = findViewById(R.id.selectedSymptomsContainer);

        btnFatigue = findViewById(R.id.btnFatigue);
        btnEyeFatigue = findViewById(R.id.btnEyeFatigue);
        btnEyeCare = findViewById(R.id.btnEyeCare);
        btnHeadache = findViewById(R.id.btnHeadache);
        btnTears = findViewById(R.id.btnTears);
        btnBrowPain = findViewById(R.id.btnBrowPain);
        btnBlur = findViewById(R.id.btnBlur);
        nextButton = findViewById(R.id.nextButton);
    }

    private void applyAllUnselectedStyles() {
        updateChipStyle(btnFatigue, false);
        updateChipStyle(btnEyeFatigue, false);
        updateChipStyle(btnEyeCare, false);
        updateChipStyle(btnHeadache, false);
        updateChipStyle(btnTears, false);
        updateChipStyle(btnBrowPain, false);
        updateChipStyle(btnBlur, false);
    }

    private void setupSymptomButtons() {
        btnFatigue.setOnClickListener(v -> {
            isFatigueSelected = !isFatigueSelected;
            toggleSymptom("疲勞", isFatigueSelected);
            updateChipStyle(btnFatigue, isFatigueSelected);
        });

        btnEyeFatigue.setOnClickListener(v -> {
            isEyeFatigueSelected = !isEyeFatigueSelected;
            toggleSymptom("眼睛疲勞", isEyeFatigueSelected);
            updateChipStyle(btnEyeFatigue, isEyeFatigueSelected);
        });

        btnEyeCare.setOnClickListener(v -> {
            isEyeCareSelected = !isEyeCareSelected;
            toggleSymptom("眼睛澀澀", isEyeCareSelected);
            updateChipStyle(btnEyeCare, isEyeCareSelected);
        });

        btnHeadache.setOnClickListener(v -> {
            isHeadacheSelected = !isHeadacheSelected;
            toggleSymptom("頭痛", isHeadacheSelected);
            updateChipStyle(btnHeadache, isHeadacheSelected);
        });

        btnTears.setOnClickListener(v -> {
            isTearsSelected = !isTearsSelected;
            toggleSymptom("流淚", isTearsSelected);
            updateChipStyle(btnTears, isTearsSelected);
        });

        btnBrowPain.setOnClickListener(v -> {
            isBrowPainSelected = !isBrowPainSelected;
            toggleSymptom("眉骨疼痛", isBrowPainSelected);
            updateChipStyle(btnBrowPain, isBrowPainSelected);
        });

        btnBlur.setOnClickListener(v -> {
            isBlurSelected = !isBlurSelected;
            toggleSymptom("視力模糊", isBlurSelected);
            updateChipStyle(btnBlur, isBlurSelected);
        });
    }

    private void toggleSymptom(String symptom, boolean isSelected) {
        if (isSelected) {
            if (!selectedSymptoms.contains(symptom)) {
                selectedSymptoms.add(symptom);
            }
        } else {
            selectedSymptoms.remove(symptom);
        }

        updateSelectedSymptomsPreview();
    }

    private void updateSelectedSymptomsPreview() {
        selectedSymptomsContainer.removeAllViews();

        if (selectedSymptoms.isEmpty()) {
            TextView placeholder = new TextView(this);
            placeholder.setText("尚未選擇症狀");
            placeholder.setTextColor(Color.parseColor("#666666"));
            placeholder.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            placeholder.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            placeholder.setLayoutParams(params);
            selectedSymptomsContainer.addView(placeholder);
            return;
        }

        LinearLayout row = null;

        for (int i = 0; i < selectedSymptoms.size(); i++) {

            // 每 3 個症狀開一排
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);

                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );

                if (i != 0) {
                    rowParams.topMargin = dp(10);
                }

                row.setLayoutParams(rowParams);
                selectedSymptomsContainer.addView(row);
            }

            TextView chip = new TextView(this);
            chip.setText(selectedSymptoms.get(i));
            chip.setTextColor(Color.WHITE);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setBackgroundResource(R.drawable.bg_acupressure_chip_blue);
            chip.setPadding(dp(6), 0, dp(6), 0);

            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                    0,
                    dp(34),
                    1
            );

            chipParams.setMargins(0, 0, dp(8), 0);
            chip.setLayoutParams(chipParams);

            if (row != null) {
                row.addView(chip);
            }
        }
    }

    private void updateChipStyle(TextView chip, boolean isSelected) {
        if (isSelected) {
            chip.setBackgroundResource(R.drawable.bg_acupressure_chip_blue);
            chip.setTextColor(Color.WHITE);
        } else {
            chip.setBackgroundResource(R.drawable.bg_acupressure_chip_gray);
            chip.setTextColor(Color.WHITE);
        }
    }

    private void setupNextButton() {
        nextButton.setOnClickListener(v -> {
            if (selectedSymptoms.isEmpty()) {
                Toast.makeText(this, "請至少選擇一項症狀", Toast.LENGTH_SHORT).show();
                return;
            }

            ArrayList<String> selectedAcupoints = mapSymptomsToSixPoints();

            Intent intent = new Intent(SelectionActivity.this, ArGuideActivity.class);
            intent.putStringArrayListExtra("selected_symptoms", new ArrayList<>(selectedSymptoms));
            intent.putStringArrayListExtra("selected_acupoints", selectedAcupoints);
            startActivity(intent);
        });
    }

    private ArrayList<String> mapSymptomsToSixPoints() {
        Set<String> points = new LinkedHashSet<>();

        for (String symptom : selectedSymptoms) {
            switch (symptom) {
                case "疲勞":
                    points.add("承泣");
                    points.add("魚腰");
                    points.add("太陽");
                    break;

                case "眼睛疲勞":
                    points.add("承泣");
                    points.add("睛明");
                    points.add("魚腰");
                    points.add("太陽");
                    break;

                case "眼睛澀澀":
                    points.add("睛明");
                    points.add("攢竹");
                    points.add("絲竹空");
                    break;

                case "頭痛":
                    points.add("太陽");
                    points.add("絲竹空");
                    points.add("攢竹");
                    break;

                case "流淚":
                    points.add("睛明");
                    points.add("承泣");
                    break;

                case "眉骨疼痛":
                    points.add("攢竹");
                    points.add("魚腰");
                    break;

                case "視力模糊":
                    points.add("睛明");
                    points.add("承泣");
                    points.add("太陽");
                    break;
            }
        }

        return new ArrayList<>(points);
    }

    private void handleResetIfNeeded(@Nullable Intent intent) {
        if (intent == null) {
            return;
        }

        boolean shouldReset = intent.getBooleanExtra("reset_selection", false);
        if (shouldReset) {
            resetAllSelections();
            intent.removeExtra("reset_selection");
        }
    }

    private void resetAllSelections() {
        selectedSymptoms.clear();

        isFatigueSelected = false;
        isEyeFatigueSelected = false;
        isEyeCareSelected = false;
        isHeadacheSelected = false;
        isTearsSelected = false;
        isBrowPainSelected = false;
        isBlurSelected = false;

        applyAllUnselectedStyles();
        updateSelectedSymptomsPreview();
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        );
    }
}
