package com.mobilepreview.overlay;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings shared by the activity (writes) and the overlay service (reads). */
final class Prefs {
    static final String PROFILE = "profile";
    static final String DANGER = "danger";
    static final String CORNERS = "corners";
    static final String PNG_URI = "pngUri";
    static final String PNG_NAME = "pngName";
    static final String PNG_ON = "pngOn";
    static final String PNG_ALPHA = "pngAlpha";
    static final String PNG_STRETCH = "pngStretch";

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("overlay", Context.MODE_PRIVATE);
    }

    private Prefs() { }
}
