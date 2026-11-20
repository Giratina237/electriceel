package com.liskovsoft.smartyoutubetv2.tv.ui.dialogs.other;

import android.content.Context;
import android.text.TextUtils;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

/**
 * A button's row: a description of several lines (e.g. a line per server checked) shows each of them, the row as tall as they need.
 * Any other shows in two lines at most, as the layout's.
 */
public class ButtonPreference extends Preference {
    /**
     * modern_preference.xml's
     */
    private static final int SUMMARY_MAX_LINES = 2;

    public ButtonPreference(Context context) {
        super(context);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        TextView summary = (TextView) holder.findViewById(android.R.id.summary);

        if (summary != null) {
            CharSequence text = getSummary();
            // Each bind: the row's view may have been another button's
            summary.setMaxLines(text != null && TextUtils.indexOf(text, '\n') >= 0 ? Integer.MAX_VALUE : SUMMARY_MAX_LINES);
        }
    }
}
