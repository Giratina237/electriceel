package com.liskovsoft.smartyoutubetv2.tv.ui.common;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.leanback.app.BackgroundManager;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

public class UriBackgroundManager {
    private static final String TAG = UriBackgroundManager.class.getSimpleName();
    private static final int BACKGROUND_UPDATE_DELAY_MS = 300;
    private Uri mBackgroundURI;
    private Drawable mDefaultBackground;
    private DisplayMetrics mMetrics;
    private Runnable mBackgroundTask;
    private BackgroundManager mBackgroundManager;
    private final Activity mActivity;
    private final Handler mHandler;
    private int mBackgroundColor = -1;
    /**
     * The last background's load (see #showBackground), stopped when the activity goes (see #onDestroy)
     */
    private SimpleTarget<Bitmap> mBackgroundTarget;

    public UriBackgroundManager(Activity activity) {
        mActivity = activity;
        mHandler = new Handler();
        prepareBackgroundManager();
        setDefaultBackground();
    }

    private void prepareBackgroundManager() {
        mBackgroundManager = BackgroundManager.getInstance(mActivity);
        mBackgroundManager.attach(mActivity.getWindow());
        mDefaultBackground = ContextCompat.getDrawable(mActivity, Helpers.getThemeAttr(mActivity, R.attr.shelfBackground));
        mBackgroundTask = new UpdateBackgroundTask();
        mMetrics = new DisplayMetrics();
        mActivity.getWindowManager().getDefaultDisplay().getMetrics(mMetrics);
    }

    private void startBackgroundTimer(Uri backgroundURI) {
        mBackgroundURI = backgroundURI;
        mHandler.removeCallbacks(mBackgroundTask);
        mHandler.postDelayed(mBackgroundTask, BACKGROUND_UPDATE_DELAY_MS);
    }

    public void startBackgroundTimer(String bgImageUrl) {
        if (bgImageUrl != null) {
            startBackgroundTimer(Uri.parse(bgImageUrl));
        }
    }

    public void setBackgroundFrom(Video item) {
        // Selectively change background picture
    }

    public void onStart() {
        if (mBackgroundURI != null) {
            showBackground(mBackgroundURI.toString());
        } else if (mBackgroundColor != -1) {
            setBackgroundColor(mBackgroundColor);
        } else {
            setDefaultBackground();
        }
    }

    public void onStop() {
        mBackgroundManager.release();
    }

    public void onDestroy() {
        mHandler.removeCallbacks(mBackgroundTask);
        mBackgroundManager = null;

        if (mBackgroundTarget != null) {
            // Not the activity's Glide: it throws once the activity is destroyed. The application's finds the load wherever it is.
            Glide.with(mActivity.getApplicationContext()).clear(mBackgroundTarget);
            mBackgroundTarget = null;
        }
    }

    public void removeBackground() {
        mBackgroundManager.setDrawable(null);
    }

    public void setDefaultBackground() {
        mBackgroundManager.setDrawable(mDefaultBackground);
    }

    public void setBackgroundColor(int color) {
        mBackgroundColor = color;
        mBackgroundManager.setColor(color);
    }

    private class UpdateBackgroundTask implements Runnable {
        @Override
        public void run() {
            if (mBackgroundURI != null) {
                showBackground(mBackgroundURI.toString());
            }
        }
    }

    public BackgroundManager getBackgroundManager() {
        return mBackgroundManager;
    }

    public void showBackground(String uri) {
        // Destroyed (see #onDestroy): nothing to show it on, and the activity's Glide would throw
        if (mBackgroundManager == null) {
            return;
        }

        View videoView = mActivity.findViewById(R.id.video_surface);

        if (videoView != null) {
            videoView.setVisibility(uri == null ? View.VISIBLE : View.INVISIBLE);
        }

        if (uri == null) {
            removeBackground();
            return;
        }

        int width = mMetrics.widthPixels;
        int height = mMetrics.heightPixels;

        RequestOptions options = ViewUtil.glideOptions()
                .centerCrop()
                .error(mDefaultBackground);

        mBackgroundTarget = Glide.with(mActivity)
                .asBitmap()
                .load(uri)
                .apply(options)
                .into(new SimpleTarget<Bitmap>(width, height) {
                    @Override
                    public void onResourceReady(
                            @NonNull Bitmap resource,
                            Transition<? super Bitmap> transition) {
                        // The bitmap may come after the activity went: its callback was already on its way (CX17FXZW #20, 0.8 s after
                        // a video that couldn't play closed the player)
                        if (mBackgroundManager != null) {
                            mBackgroundManager.setBitmap(resource);
                        }
                    }
                });
    }

    public void showBackgroundColor(int colorResId) {
        try {
            showBackgroundColorInternal(colorResId);
        } catch (NullPointerException e) {
            // Likely caused by OOM in View.setVisibility
            Log.w(TAG, "showBackgroundColor: NPE swallowed", e);
        }
    }

    private void showBackgroundColorInternal(int colorResId) {
        View videoView = mActivity.findViewById(R.id.video_surface);

        if (videoView != null) {
            videoView.setVisibility(colorResId == -1 ? View.VISIBLE : View.INVISIBLE);
        }

        if (colorResId == -1) {
            removeBackground();
            return;
        }

        if (mBackgroundManager != null) {
            mBackgroundManager.setColor(ContextCompat.getColor(mActivity, colorResId));
        }
    }
}
