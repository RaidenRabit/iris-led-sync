package com.example.aimp_plugin;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.car.app.Screen;
import androidx.car.app.Session;

public class LedCarSession extends Session {

    @NonNull @Override
    public Screen onCreateScreen(@NonNull Intent intent) {
        return new ZoneListScreen(getCarContext());
    }
}
