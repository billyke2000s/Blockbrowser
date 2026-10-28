package com.example.blockbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

public class VideoPlayerActivity extends Activity {
    private static final String[] ASPECT_LABELS = {
        "Fit (original)", "Fill screen (stretch)", "Zoom (crop)", "16:9", "4:3"
    };
    private AspectVideoView video;
    private Button aspectButton;
    private int aspectMode;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) aspectMode = state.getInt("aspectMode", 0);
        if (aspectMode < 0 || aspectMode >= ASPECT_LABELS.length) aspectMode = 0;

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        frame.setClipChildren(true);
        video = new AspectVideoView();
        frame.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        MediaController controller = new MediaController(this);
        controller.setAnchorView(frame);
        video.setMediaController(controller);

        aspectButton = new Button(this);
        updateAspectLabel();
        aspectButton.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("Aspect ratio")
            .setSingleChoiceItems(ASPECT_LABELS, aspectMode, (dialog, which) -> {
                aspectMode = which;
                video.requestLayout();
                updateAspectLabel();
                dialog.dismiss();
            })
            .setNegativeButton("Cancel", null)
            .show());
        FrameLayout.LayoutParams buttonLayout = new FrameLayout.LayoutParams(
            -2, -2, Gravity.TOP | Gravity.END);
        int margin = Math.round(12 * getResources().getDisplayMetrics().density);
        buttonLayout.setMargins(margin, margin, margin, margin);
        frame.addView(aspectButton, buttonLayout);
        // Keep controls inside the system bars, including Android 15's edge-to-edge layout.
        frame.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(frame);

        video.setOnPreparedListener(mp -> {
            video.setVideoDimensions(mp.getVideoWidth(), mp.getVideoHeight());
            mp.setOnVideoSizeChangedListener((player, width, height) ->
                video.setVideoDimensions(width, height));
            if (state != null) video.seekTo(state.getInt("position", 0));
            if (state == null || state.getBoolean("playing", true)) video.start();
        });
        video.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "This video format is not supported by the device",
                Toast.LENGTH_LONG).show();
            return false;
        });
        video.setVideoURI(getIntent().getData());
    }

    private void updateAspectLabel() {
        aspectButton.setText("Aspect ratio: " + ASPECT_LABELS[aspectMode]);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("aspectMode", aspectMode);
        state.putInt("position", video.getCurrentPosition());
        state.putBoolean("playing", video.isPlaying());
        super.onSaveInstanceState(state);
    }

    private class AspectVideoView extends VideoView {
        private int videoWidth;
        private int videoHeight;

        AspectVideoView() { super(VideoPlayerActivity.this); }

        void setVideoDimensions(int width, int height) {
            videoWidth = width;
            videoHeight = height;
            requestLayout();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, heightSpec);
            int width = MeasureSpec.getSize(widthSpec);
            int height = MeasureSpec.getSize(heightSpec);
            if (width <= 0 || height <= 0) return;
            if (aspectMode == 1) {
                setMeasuredDimension(width, height);
                return;
            }
            double ratio;
            if (aspectMode == 3) ratio = 16.0 / 9.0;
            else if (aspectMode == 4) ratio = 4.0 / 3.0;
            else if (videoWidth > 0 && videoHeight > 0) ratio = (double) videoWidth / videoHeight;
            else return;

            // Fit and forced ratios stay inside the viewport; zoom covers it and crops the edges.
            boolean wider = (double) width / height > ratio;
            if (wider != (aspectMode == 2)) width = Math.max(1, (int) Math.round(height * ratio));
            else height = Math.max(1, (int) Math.round(width / ratio));
            setMeasuredDimension(width, height);
        }
    }
}
