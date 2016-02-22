/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*Reference: http://developer.android.com/training/wearables/data-layer/assets.html */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class NewSunshineWatchFace extends CanvasWatchFaceService{

    private static final String TAG = "WatchFace";

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }


    private static class EngineHandler extends Handler {
        private final WeakReference<NewSunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(NewSunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            NewSunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements  DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint mLowPaint;
        boolean mAmbient;
        Time mTime;
        private GoogleApiClient mGoogleApiClient;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        int mTapCount;
        Date date;

        private static final String OWM_MAX = "max";
        private static final String OWM_MIN = "min";
        private static final String WEATHER_IMAGE = "weatherImage";

        private int maxTemp;
        private int minTemp;
        private Bitmap weatherBitmap;

        int width;
        int height;

        private static final int TIMEOUT_MS = 500;

        float mXOffset;
        float mYOffset;

        SimpleDateFormat simpleDateFormat;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(NewSunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            mGoogleApiClient = new GoogleApiClient.Builder(NewSunshineWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks( this)
                    .addOnConnectionFailedListener(this)
                    .build();
            if (ConnectionResult.SUCCESS == GooglePlayServicesUtil.isGooglePlayServicesAvailable(getApplicationContext())) {
                System.out.println("GPS AVAILABLE");
                mGoogleApiClient.connect();
            }

            Resources resources = NewSunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.primary));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.white));

            mLowPaint = new Paint();
            mLowPaint = createTextPaint(resources.getColor(R.color.white));

            mTime = new Time();

            simpleDateFormat = new SimpleDateFormat("EEE, MMM d yyyy");
            date = new Date();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                System.out.println("PASA POR ACA 1");
                mGoogleApiClient.connect();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
                Wearable.DataApi.removeListener(mGoogleApiClient, this);
                mGoogleApiClient.disconnect();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            NewSunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            NewSunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = NewSunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            float subtextSize = resources.getDimension(isRound
                    ? R.dimen.digital_subtext_size_round : R.dimen.digital_subtext_size);

            mTextPaint.setTextSize(textSize);
            mLowPaint.setTextSize(subtextSize);
            mLowPaint.setStrokeWidth(1);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                    mLowPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = NewSunshineWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(R.color.primary));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            float spacing = 10;
            int bitmapSize = 50;

            width = bounds.width();
            height = bounds.height();

            date.setTime(System.currentTimeMillis());

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();

            float yCoordenate = mYOffset;

            // Draw Hour and minute
            String text = String.format("%d:%02d", mTime.hour, mTime.minute);
            canvas.drawText(text, bounds.centerX() - mTextPaint.measureText(text)/2, yCoordenate, mTextPaint);
            yCoordenate += mLowPaint.getTextSize() + spacing;

            // Draw the date
            text = simpleDateFormat.format(date);
            canvas.drawText(text, bounds.centerX() - mLowPaint.measureText(text)/2, yCoordenate, mLowPaint);
            yCoordenate += spacing * 3/2;

            if (maxTemp > minTemp) {
                // Draw the Separating line
                float halfLine = (bounds.centerX() - mXOffset) / 2;
                canvas.drawLine(bounds.centerX() - halfLine, yCoordenate, bounds.centerX() + halfLine, yCoordenate, mLowPaint);
                yCoordenate += mLowPaint.getTextSize() + spacing * 2;

                // Draw the temperature range
                text = String.format("%d°   %d°", maxTemp, minTemp);
                if (!isInAmbientMode() && weatherBitmap != null) {
                    canvas.drawText(text, bounds.centerX() - (mLowPaint.measureText(text) + bitmapSize + spacing) / 2 + bitmapSize + spacing,
                            yCoordenate, mLowPaint);
                }
                else {
                    canvas.drawText(text, bounds.centerX() - mLowPaint.measureText(text) / 2, yCoordenate, mLowPaint);
                }
            }

            // Scale the bitmap to fit the device
            if (weatherBitmap != null && !isInAmbientMode()) {
                weatherBitmap = Bitmap.createScaledBitmap(weatherBitmap, bitmapSize, bitmapSize, true);
                canvas.drawBitmap(weatherBitmap, bounds.centerX() - (mLowPaint.measureText(text) + bitmapSize + spacing) / 2,
                        yCoordenate - (bitmapSize/2 + mLowPaint.getTextSize()/2), null);
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(Bundle bundle) {
            System.out.println("PASA POR ACA 2");
            Wearable.DataApi.addListener(mGoogleApiClient, this);

        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            System.out.println("PASA POR ACA 0");
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    System.out.println("PASA POR ACA 00 "+ event.getDataItem().getUri().getPath());
                    if ( event.getDataItem().getUri().getPath().equals("/temperatures"))
                    {
                        DataItem item = event.getDataItem();
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        maxTemp = (int) dataMap.getDouble(OWM_MAX);
                        minTemp = (int) dataMap.getDouble(OWM_MIN);
                        Asset profileAsset = dataMap.getAsset(WEATHER_IMAGE);
                        new loadBitmapFromAsset().execute(profileAsset);
                    }

                }
            }
        }

        // Ref: https://developers.google.com/android/guides/api-client#Sync
        private class loadBitmapFromAsset extends AsyncTask {

            @Override
            protected Bitmap doInBackground(Object[] param) {
                Asset asset = (Asset)param[0];
                ConnectionResult result = mGoogleApiClient.blockingConnect(500, TimeUnit.MILLISECONDS);
                if (!result.isSuccess()) {
                    return null;
                }
                InputStream assetInputStream = Wearable.DataApi.getFdForAsset(mGoogleApiClient, asset).await().getInputStream();
                mGoogleApiClient.disconnect();

                if (assetInputStream == null) {
                    return null;
                }
                // decode the stream into a bitmap
                weatherBitmap = BitmapFactory.decodeStream(assetInputStream);
                return weatherBitmap;
            }
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            Log.e(TAG, "onConnectionFailed(): Failed to connect, with result: " + connectionResult);
        }
    }
}
