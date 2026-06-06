package com.raidenrabit.irisledsync;

import android.content.Intent;
import android.content.res.Configuration;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

public class LedCarSession extends Session {

    private static final String TAG = "LedCarSession";

    // Register lifecycle callbacks in the constructor so they fire as early as
    // possible — specifically before onCreateScreen, which only runs when the user
    // explicitly opens our car app. The host creates a Session when it first binds
    // our CarAppService, and delivers onCarConfigurationChanged to that session
    // regardless of whether the user has ever opened the app screen.
    public LedCarSession() {
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onCreate(@NonNull LifecycleOwner owner) {
                // Fires when the car host binds our service — read the initial
                // car night mode so onCarConfigurationChanged can update it later.
                Log.d(TAG, "onCreate: attachCarContext");
                DayNightManager.getInstance(getCarContext()).attachCarContext(getCarContext());
            }
            @Override public void onDestroy(@NonNull LifecycleOwner owner) {
                Log.d(TAG, "onDestroy: detachCarContext");
                DayNightManager.getInstance(getCarContext()).detachCarContext();
            }
        });
    }

    @NonNull @Override
    public Screen onCreateScreen(@NonNull Intent intent) {
        return new ZoneListScreen(getCarContext());
    }

    @Override
    public void onCarConfigurationChanged(@NonNull Configuration newConfig) {
        boolean isCarNight = (newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        Log.d(TAG, "onCarConfigurationChanged: isCarNight=" + isCarNight);
        DayNightManager.getInstance(getCarContext()).onCarNightModeChanged(isCarNight);
    }
}
