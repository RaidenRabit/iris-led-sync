package com.raidenrabit.irisledsync;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

/**
 * Entry point for the Android Auto applet.
 * Appears in Android Auto's launcher under the app name.
 */
public class LedCarAppService extends CarAppService {

    @NonNull @Override
    public Session onCreateSession() {
        return new LedCarSession();
    }

    @NonNull @Override
    public HostValidator createHostValidator() {
        // Allow all hosts during development; lock to allowlist before publishing
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }
}
