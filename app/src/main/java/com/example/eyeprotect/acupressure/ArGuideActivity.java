package com.example.eyeprotect.acupressure;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.Image;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.OptIn;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.example.eyeprotect.R;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ArGuideActivity extends ComponentActivity implements TextToSpeech.OnInitListener {

    private PreviewView previewView;
    private ImageView guideImageView;
    private ImageView leftHandGuide;
    private ImageView rightHandGuide;
    private AcupointOverlayView acupointOverlayView;

    private TextView tvCountdown;
    private TextView tvGuideText;
    private TextView tvAcupointName;
    private TextView btnPauseResume;
    private TextView btnSkip;

    private ProgressBar progressAcupoint;
    private TextView tvProgressCount;

    private FrameLayout fullBlueOverlay;
    private ImageView overlayAcupointImage;
    private TextView overlayTitle;
    private TextView overlayMessage;
    private TextView overlayCountdown;
    private TextView btnFinishOk;
    private TextView btnOverlayPauseResume;
    private TextView btnOverlaySkip;

    private TextToSpeech tts;
    private CountDownTimer timer;

    private FaceDetector faceDetector;
    private ExecutorService cameraExecutor;

    private ArrayList<String> selectedAcupoints = new ArrayList<>();
    private int currentIndex = 0;

    private final int prepareSeconds = 5;
    private final int locateSeconds = 5;
    private final int pressSeconds = 25;

    private boolean hasFaceNow = false;
    private boolean isPaused = false;

    private int remainingSeconds = 0;
    private Runnable currentFinishRunnable;
    private String currentPhase = "PREPARE";

    private long lastNoFaceHintTime = 0L;

    private final ActivityResultLauncher<String> requestCameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "需要相機權限才能使用 AR 示範", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_acupressure_ar_guide);

        bindViews();
        setupHandImages();
        setupFaceDetector();
        setupButtons();

        ArrayList<String> received = getIntent().getStringArrayListExtra("selected_acupoints");

        if (received != null && !received.isEmpty()) {
            selectedAcupoints = received;
        } else {
            selectedAcupoints.add("太陽");
        }

        tts = new TextToSpeech(this, this);

        showCurrentAcupoint();
        checkCameraPermission();

        tvGuideText.postDelayed(this::startGuideFlow, 800);
    }

    private void bindViews() {
        previewView = findViewById(R.id.previewView);
        guideImageView = findViewById(R.id.guideImageView);
        leftHandGuide = findViewById(R.id.leftHandGuide);
        rightHandGuide = findViewById(R.id.rightHandGuide);
        acupointOverlayView = findViewById(R.id.acupointOverlayView);

        tvCountdown = findViewById(R.id.tvCountdown);
        tvGuideText = findViewById(R.id.tvGuideText);
        tvAcupointName = findViewById(R.id.tvAcupointName);

        btnPauseResume = findViewById(R.id.btnPauseResume);
        btnSkip = findViewById(R.id.btnSkip);

        progressAcupoint = findViewById(R.id.progressAcupoint);
        tvProgressCount = findViewById(R.id.tvProgressCount);

        fullBlueOverlay = findViewById(R.id.fullBlueOverlay);
        overlayAcupointImage = findViewById(R.id.overlayAcupointImage);
        overlayTitle = findViewById(R.id.overlayTitle);
        overlayMessage = findViewById(R.id.overlayMessage);
        overlayCountdown = findViewById(R.id.overlayCountdown);

        btnFinishOk = findViewById(R.id.btnFinishOk);
        btnOverlayPauseResume = findViewById(R.id.btnOverlayPauseResume);
        btnOverlaySkip = findViewById(R.id.btnOverlaySkip);

        acupointOverlayView.setHandViews(leftHandGuide, rightHandGuide);
    }

    private void setupButtons() {
        btnPauseResume.setOnClickListener(v -> togglePause());
        btnOverlayPauseResume.setOnClickListener(v -> togglePause());

        btnSkip.setOnClickListener(v -> skipToNextOrFinish());
        btnOverlaySkip.setOnClickListener(v -> skipToNextOrFinish());

        btnFinishOk.setOnClickListener(v -> {
            Intent intent = new Intent(ArGuideActivity.this, SelectionActivity.class);
            intent.putExtra("reset_selection", true);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });
    }

    private void setupHandImages() {
        int leftId = getResources().getIdentifier(
                "hand_left_overlay",
                "drawable",
                getPackageName()
        );

        int rightId = getResources().getIdentifier(
                "hand_right_overlay",
                "drawable",
                getPackageName()
        );

        if (leftId != 0) {
            leftHandGuide.setImageResource(leftId);
        }

        if (rightId != 0) {
            rightHandGuide.setImageResource(rightId);
        }

        leftHandGuide.setAlpha(0.62f);
        rightHandGuide.setAlpha(0.62f);
    }

    private void setupFaceDetector() {
        FaceDetectorOptions options =
                new FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                        .enableTracking()
                        .build();

        faceDetector = FaceDetection.getClient(options);
        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    private void showCurrentAcupoint() {
        String acupoint = getCurrentAcupoint();

        tvAcupointName.setText(acupoint + "穴");
        guideImageView.setImageResource(getGuideImage(acupoint));
        overlayAcupointImage.setImageResource(getGuideImage(acupoint));
        acupointOverlayView.setAcupointType(acupoint);

        updateProgressUI();
    }

    private void updateProgressUI() {
        int total = Math.max(1, selectedAcupoints.size());
        int current = Math.min(currentIndex + 1, total);

        progressAcupoint.setMax(total);
        progressAcupoint.setProgress(current);
        tvProgressCount.setText(current + "/" + total);
    }

    private String getCurrentAcupoint() {
        if (currentIndex >= 0 && currentIndex < selectedAcupoints.size()) {
            return selectedAcupoints.get(currentIndex);
        }

        return "太陽";
    }

    private String getNextAcupoint() {
        int nextIndex = currentIndex + 1;

        if (nextIndex >= 0 && nextIndex < selectedAcupoints.size()) {
            return selectedAcupoints.get(nextIndex);
        }

        return null;
    }

    private int getGuideImage(String acupoint) {
        switch (acupoint) {
            case "太陽":
                return R.drawable.taiyang_guide;
            case "睛明":
                return R.drawable.jingming_guide;
            case "絲竹空":
                return R.drawable.sizhukong_guide;
            case "承泣":
                return R.drawable.chengqi_guide;
            case "魚腰":
                return R.drawable.yuyao_guide;
            case "攢竹":
                return R.drawable.cuanzhu_guide;
            default:
                return R.drawable.taiyang_guide;
        }
    }

    private void checkCameraPermission() {
        boolean granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED;

        if (granted) {
            startCamera();
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis =
                        new ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeFace);

                CameraSelector frontCameraSelector =
                        new CameraSelector.Builder()
                                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                                .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        frontCameraSelector,
                        preview,
                        imageAnalysis
                );

            } catch (Exception e) {
                Toast.makeText(this, "相機啟動錯誤：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeFace(ImageProxy imageProxy) {
        Image mediaImage = imageProxy.getImage();

        if (mediaImage == null) {
            imageProxy.close();
            return;
        }

        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        InputImage inputImage = InputImage.fromMediaImage(mediaImage, rotation);

        boolean swapped = rotation == 90 || rotation == 270;
        int imageWidth = swapped ? imageProxy.getHeight() : imageProxy.getWidth();
        int imageHeight = swapped ? imageProxy.getWidth() : imageProxy.getHeight();

        faceDetector.process(inputImage)
                .addOnSuccessListener(faces -> {
                    if (faces == null || faces.isEmpty()) {
                        runOnUiThread(() -> {
                            hasFaceNow = false;
                            acupointOverlayView.clearFace();
                            showNoFaceHintSometimes();
                        });
                        return;
                    }

                    Face largestFace = faces.get(0);

                    for (Face face : faces) {
                        int area = face.getBoundingBox().width() * face.getBoundingBox().height();
                        int largestArea = largestFace.getBoundingBox().width()
                                * largestFace.getBoundingBox().height();

                        if (area > largestArea) {
                            largestFace = face;
                        }
                    }

                    Face finalFace = largestFace;

                    runOnUiThread(() -> {
                        hasFaceNow = true;
                        acupointOverlayView.setSourceInfo(imageWidth, imageHeight, true);
                        acupointOverlayView.updateFace(finalFace, getCurrentAcupoint());
                    });
                })
                .addOnFailureListener(e -> runOnUiThread(() -> {
                    hasFaceNow = false;
                    acupointOverlayView.clearFace();
                }))
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void showNoFaceHintSometimes() {
        long now = System.currentTimeMillis();

        if (now - lastNoFaceHintTime > 2500) {
            lastNoFaceHintTime = now;
            tvGuideText.setText("請將臉部置中，讓系統偵測五官");
        }
    }

    private void startGuideFlow() {
        showPrepareOverlay();
    }

    private void showPrepareOverlay() {
        currentPhase = "PREPARE";
        acupointOverlayView.setMassageActive(false);

        String acupoint = getCurrentAcupoint();

        showBlueCountdown(
                "準備按摩 " + acupoint + "穴",
                "請先看示意圖，等等跟著手指引導按摩",
                prepareSeconds,
                this::stepLocate
        );
    }

    private void stepLocate() {
        currentPhase = "LOCATE";
        fullBlueOverlay.setVisibility(View.GONE);
        btnPauseResume.setVisibility(View.VISIBLE);
        btnSkip.setVisibility(View.VISIBLE);
        acupointOverlayView.setMassageActive(false);

        String acupoint = getCurrentAcupoint();
        String text;

        if (!hasFaceNow) {
            text = "請先將臉部置中，讓系統偵測五官";
        } else {
            text = getLocateText(acupoint);
        }

        tvGuideText.setText(text);
        speak(text);

        startManagedCountdown(locateSeconds, this::stepPress);
    }

    private void stepPress() {
        currentPhase = "PRESS";
        fullBlueOverlay.setVisibility(View.GONE);
        btnPauseResume.setVisibility(View.VISIBLE);
        btnSkip.setVisibility(View.VISIBLE);
        acupointOverlayView.setMassageActive(true);

        String acupoint = getCurrentAcupoint();
        String text = getPressText(acupoint);

        tvGuideText.setText(text);
        speak(text);

        startManagedCountdown(pressSeconds, this::goNextAcupointOrFinish);
    }

    private void goNextAcupointOrFinish() {
        acupointOverlayView.setMassageActive(false);

        String currentAcupoint = getCurrentAcupoint();
        String nextAcupoint = getNextAcupoint();

        if (nextAcupoint == null) {
            showFinishScreen();
            return;
        }

        boolean sameAction = getMassageActionKey(currentAcupoint)
                .equals(getMassageActionKey(nextAcupoint));

        currentIndex++;
        showCurrentAcupoint();

        if (sameAction) {
            String text = "接續按摩" + getCurrentAcupoint() + "穴，動作相同，請繼續";
            tvGuideText.setText(text);
            speak(text);

            stepPress();
        } else {
            String text = "接下來示範" + getCurrentAcupoint() + "穴";
            tvGuideText.setText(text);
            speak(text);

            showPrepareOverlay();
        }
    }

    private void skipToNextOrFinish() {
        if ("FINISH".equals(currentPhase)) {
            return;
        }

        if (timer != null) {
            timer.cancel();
        }

        isPaused = false;
        btnPauseResume.setText("暫停");
        btnOverlayPauseResume.setText("暫停");

        remainingSeconds = 0;
        currentFinishRunnable = null;

        acupointOverlayView.setMassageActive(false);

        if (currentIndex < selectedAcupoints.size() - 1) {
            currentIndex++;
            showCurrentAcupoint();

            String text = "已跳過，接下來是" + getCurrentAcupoint() + "穴";
            tvGuideText.setText(text);
            speak(text);

            showPrepareOverlay();
        } else {
            showFinishScreen();
        }
    }

    private String getMassageActionKey(String acupoint) {
        switch (acupoint) {
            case "太陽":
                return "CIRCLE";
            case "睛明":
                return "UP_DOWN_PRESS";
            case "承泣":
                return "POINT_PRESS";
            case "攢竹":
                return "INWARD_UP_PRESS";
            case "魚腰":
                return "VERTICAL_PRESS";
            case "絲竹空":
                return "OUTWARD_RUB";
            default:
                return "DEFAULT";
        }
    }

    private void showBlueCountdown(String title, String message, int seconds, Runnable onFinish) {
        fullBlueOverlay.setVisibility(View.VISIBLE);
        overlayAcupointImage.setVisibility(View.VISIBLE);
        overlayCountdown.setVisibility(View.VISIBLE);

        btnFinishOk.setVisibility(View.GONE);
        btnOverlayPauseResume.setVisibility(View.VISIBLE);
        btnOverlaySkip.setVisibility(View.VISIBLE);

        overlayAcupointImage.setImageResource(getGuideImage(getCurrentAcupoint()));
        overlayTitle.setText(title);
        overlayMessage.setText(message);

        speak(title);
        startManagedCountdown(seconds, onFinish);
    }

    private void showFinishScreen() {
        currentPhase = "FINISH";
        acupointOverlayView.setMassageActive(false);

        if (timer != null) {
            timer.cancel();
        }

        fullBlueOverlay.setVisibility(View.VISIBLE);

        overlayAcupointImage.setVisibility(View.GONE);
        overlayCountdown.setVisibility(View.GONE);

        btnPauseResume.setVisibility(View.GONE);
        btnSkip.setVisibility(View.GONE);
        btnOverlayPauseResume.setVisibility(View.GONE);
        btnOverlaySkip.setVisibility(View.GONE);

        btnFinishOk.setVisibility(View.VISIBLE);

        overlayTitle.setText("結束本次按摩");
        overlayMessage.setText("本次穴位按摩已完成，請按確定回到選擇介面");

        speak("結束本次按摩");
    }

    private void startManagedCountdown(int seconds, Runnable onFinish) {
        if (timer != null) {
            timer.cancel();
        }

        remainingSeconds = seconds;
        currentFinishRunnable = onFinish;
        updateCountdownText(seconds);

        if (isPaused) {
            return;
        }

        timer = new CountDownTimer((seconds + 1) * 1000L, 1000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                int value = (int) (millisUntilFinished / 1000L) - 1;

                if (value < 0) {
                    value = 0;
                }

                if (value > seconds) {
                    value = seconds;
                }

                remainingSeconds = value;
                updateCountdownText(value);
            }

            @Override
            public void onFinish() {
                remainingSeconds = 0;
                updateCountdownText(0);

                Runnable finish = currentFinishRunnable;
                currentFinishRunnable = null;

                if (finish != null) {
                    finish.run();
                }
            }
        };

        timer.start();
    }

    private void updateCountdownText(int value) {
        String text = String.valueOf(value);
        tvCountdown.setText(text);
        overlayCountdown.setText(text);
    }

    private void togglePause() {
        if ("FINISH".equals(currentPhase)) {
            return;
        }

        if (!isPaused) {
            isPaused = true;
            btnPauseResume.setText("繼續");
            btnOverlayPauseResume.setText("繼續");

            if (timer != null) {
                timer.cancel();
            }

            acupointOverlayView.setMassageActive(false);
            tvGuideText.setText("已暫停，按「繼續」恢復");
            speak("已暫停");

        } else {
            isPaused = false;
            btnPauseResume.setText("暫停");
            btnOverlayPauseResume.setText("暫停");

            if ("PRESS".equals(currentPhase)) {
                acupointOverlayView.setMassageActive(true);
            }

            if (remainingSeconds > 0 && currentFinishRunnable != null) {
                startManagedCountdown(remainingSeconds, currentFinishRunnable);
            }

            speak("繼續");
        }
    }

    private String getLocateText(String acupoint) {
        switch (acupoint) {
            case "太陽":
                return "請將雙手食指放到兩側太陽穴紅點位置";
            case "睛明":
                return "請將雙手食指放到兩側內眼角旁紅點位置";
            case "承泣":
                return "請將雙手食指放到兩側眼睛下方紅點位置";
            case "絲竹空":
                return "請將雙手食指放到兩側眉尾旁紅點位置";
            case "魚腰":
                return "請將雙手食指放到兩側眉毛中央紅點位置";
            case "攢竹":
                return "請將雙手食指放到兩側眉頭旁紅點位置";
            default:
                return "請將雙手食指放到兩側紅點位置";
        }
    }

    private String getPressText(String acupoint) {
        switch (acupoint) {
            case "太陽":
                return "請用雙手食指在太陽穴順時針輕輕畫圓按摩";
            case "睛明":
                return "請用雙手食指在內眼角旁輕輕上下按壓";
            case "承泣":
                return "請用雙手食指在眼睛下方輕輕定點按壓";
            case "攢竹":
                return "請用雙手食指在眉頭位置往內上方輕輕按壓";
            case "魚腰":
                return "請用雙手食指在眉毛中央輕輕垂直按壓";
            case "絲竹空":
                return "請用雙手食指在眉尾旁往外側小幅揉動";
            default:
                return "請用雙手食指輕輕按摩穴位";
        }
    }

    private void speak(String text) {
        if (tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "guide_tts");
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && tts != null) {
            tts.setLanguage(Locale.TAIWAN);
            tts.setSpeechRate(0.95f);
            tts.setPitch(1.0f);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (timer != null) {
            timer.cancel();
        }

        if (faceDetector != null) {
            faceDetector.close();
        }

        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }

        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}
