package com.liskovsoft.smartyoutubetv2.tv.ui.dialogs.other;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * A button's row: a description of several lines (e.g. a line per server checked) shows each of them, the row as tall as they need.
 * Any other shows in two lines at most, as the layout's.<br/>
 * It may start with an avatar (e.g. a channel's, see setAvatarUrl).
 */
public class ButtonPreference extends Preference {
    /**
     * modern_preference.xml's
     */
    private static final int SUMMARY_MAX_LINES = 2;
    private boolean mHasAvatar;

    public ButtonPreference(Context context) {
        super(context);
    }

    /**
     * The row starts with the image, round and bigger than the layout's icons. A circle shows until it's loaded, so the row
     * never moves when it comes.
     */
    public void setAvatarUrl(String url) {
        Context context = getContext();
        int size = context.getResources().getDimensionPixelSize(R.dimen.preference_avatar_size);

        mHasAvatar = true;
        setIcon(createPlaceholder(context, size));

        Glide.with(context)
                .load(url)
                .circleCrop()
                .into(new CustomTarget<Drawable>(size, size) {
                    @Override
                    public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                        setIcon(resource);
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {
                        // NOP: the row keeps the avatar while it's shown
                    }
                });
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

        ImageView icon = (ImageView) holder.findViewById(android.R.id.icon);

        if (icon != null) {
            applyIconSize(icon);
        }
    }

    /**
     * An avatar's size, or the layout's. Each bind: the row's view may have been another button's.
     */
    private void applyIconSize(ImageView icon) {
        ViewGroup.LayoutParams params = icon.getLayoutParams();
        Object layoutSize = icon.getTag(R.id.preference_icon_layout_size);

        if (layoutSize == null) {
            layoutSize = params.width;
            icon.setTag(R.id.preference_icon_layout_size, layoutSize);
        }

        int size = mHasAvatar ? getContext().getResources().getDimensionPixelSize(R.dimen.preference_avatar_size) : (int) layoutSize;

        if (params.width != size || params.height != size) {
            params.width = size;
            params.height = size;
            icon.setLayoutParams(params);
        }
    }

    private static Drawable createPlaceholder(Context context, int size) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(ContextCompat.getColor(context, R.color.preference_avatar_placeholder));
        circle.setSize(size, size);
        return circle;
    }
}
