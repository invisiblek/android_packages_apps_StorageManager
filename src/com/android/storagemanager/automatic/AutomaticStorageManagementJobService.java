/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.storagemanager.automatic;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.os.BatteryManager;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.util.Log;
import com.android.settingslib.deviceinfo.PrivateStorageInfo;
import com.android.settingslib.deviceinfo.StorageManagerVolumeProvider;
import com.android.settingslib.deviceinfo.StorageVolumeProvider;
import com.android.storagemanager.overlay.FeatureFactory;
import com.android.storagemanager.overlay.StorageManagementJobProvider;

/**
 * {@link JobService} class to start automatic storage clearing jobs to free up space. The job only
 * starts if the device is under a certain percent of free storage.
 */
public class AutomaticStorageManagementJobService extends JobService {
    private static final String TAG = "AsmJobService";

    private static final long DEFAULT_LOW_FREE_PERCENT = 15;

    private StorageManagementJobProvider mProvider;
    private StorageVolumeProvider mVolumeProvider;

    @Override
    public boolean onStartJob(JobParameters args) {
        // We need to double-check the precondition shere because they are not enforced for a
        // periodic job.
        if (!preconditionsFulfilled()) {
            // By telling the system to re-schedule the job, it will attempt to execute again at a
            // later idle window -- possibly one where we are charging.
            jobFinished(args, true);
            return false;
        }

        if (!volumeNeedsManagement()) {
            Log.i(TAG, "Skipping automatic storage management.");
            Settings.Secure.putLong(getContentResolver(),
                    Settings.Secure.AUTOMATIC_STORAGE_MANAGER_LAST_RUN,
                    System.currentTimeMillis());
            jobFinished(args, false);
            return false;
        }

        boolean isEnabled =
                Settings.Secure.getInt(getContentResolver(),
                        Settings.Secure.AUTOMATIC_STORAGE_MANAGER_ENABLED, 0) != 0;
        if (!isEnabled) {
            NotificationController.maybeShowNotification(getApplicationContext());
            jobFinished(args, false);
            return false;
        }

        mProvider = FeatureFactory.getFactory(this).getStorageManagementJobProvider();
        if (mProvider != null) {
            return mProvider.onStartJob(this, args, getDaysToRetain());
        }

        jobFinished(args, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters args) {
        if (mProvider != null) {
            return mProvider.onStopJob(this, args);
        }

        return false;
    }

    void setStorageVolumeProvider(StorageVolumeProvider storageProvider) {
        mVolumeProvider = storageProvider;
    }

    private int getDaysToRetain() {
        return Settings.Secure.getInt(getContentResolver(),
                Settings.Secure.AUTOMATIC_STORAGE_MANAGER_DAYS_TO_RETAIN,
                Settings.Secure.AUTOMATIC_STORAGE_MANAGER_DAYS_TO_RETAIN_DEFAULT);
    }

    private boolean volumeNeedsManagement() {
        if (mVolumeProvider == null) {
            mVolumeProvider = new StorageManagerVolumeProvider(
                    getSystemService(StorageManager.class));
        }

        PrivateStorageInfo info = PrivateStorageInfo.getPrivateStorageInfo(mVolumeProvider);

        long lowStorageThreshold = (info.totalBytes * DEFAULT_LOW_FREE_PERCENT) / 100;
        return info.freeBytes < lowStorageThreshold;
    }

    private boolean preconditionsFulfilled() {
        // NOTE: We don't check the idle state here because this job should be running in idle
        // maintenance windows. During the idle maintenance window, the device is -technically- not
        // idle. For more information, see PowerManager.isDeviceIdleMode().

        boolean isCharging = false;
        BatteryManager batteryManager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        if (batteryManager != null) {
            isCharging = batteryManager.isCharging();
        }

        return isCharging;
    }
}