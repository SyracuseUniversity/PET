package com.atakmap.android.lingualinkledger.plugin;

import static com.atakmap.android.maps.MapView.getMapView;

import android.app.AlertDialog;
import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Builds every dialog the plugin shows, one way (D46):
 *  - an AlertDialog against ATAK's activity, getMapView().getContext(), so
 *    the dialog has a window token. A dialog or popup built from the plugin
 *    context has none, which is how the old Sign dialog's picker crashed
 *    ATAK (PL-35);
 *  - its content inside a ScrollView, and its buttons the AlertDialog's
 *    own, which sit outside the scrolling part, so they stay on screen in
 *    landscape (PL-34).
 * Content views are made from the plugin context, as before. Any picker in
 * them must be ATAK's PluginSpinner (D46).
 */
final class PetDialogs {

    private PetDialogs() {
    }

    /** A builder with a title and scrolling content; add buttons, then show(). */
    static AlertDialog.Builder builder(Context pluginContext, String title, View content) {
        ScrollView scroll = new ScrollView(pluginContext);
        scroll.setFillViewport(true);
        scroll.addView(content);
        return new AlertDialog.Builder(getMapView().getContext())
                .setTitle(title)
                .setView(scroll);
    }

    /** Plain text, padded, for a dialog's content. */
    static TextView text(Context pluginContext, CharSequence message) {
        TextView tv = new TextView(pluginContext);
        int pad = dp(pluginContext, 16);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setText(message);
        return tv;
    }

    /** A message with one Close button. */
    static AlertDialog showMessage(Context pluginContext, String title, CharSequence message) {
        return builder(pluginContext, title, text(pluginContext, message))
                .setPositiveButton("Close", null)
                .show();
    }

    static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
