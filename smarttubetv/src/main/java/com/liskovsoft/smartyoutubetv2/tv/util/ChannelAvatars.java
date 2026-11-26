package com.liskovsoft.smartyoutubetv2.tv.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

import java.util.List;

/**
 * The avatar of a collaboration: its first two channels' as a group, as YouTube shows them. The first at the top start, the second
 * at the bottom end over it, with a gap cut around it. As big as the avatar of one channel, so the button it's on keeps its size.
 */
public final class ChannelAvatars {
    // Of the group's size: each avatar's, and the gap around the second one
    private static final float AVATAR_FRACTION = 0.68f;
    private static final float GAP_FRACTION = 0.06f;

    public interface Callback {
        /**
         * On the main thread. Null when neither avatar loaded.
         */
        void onReady(@Nullable Drawable avatar);
    }

    private ChannelAvatars() {
    }

    /**
     * @param urls the channels' avatars, the first two are used
     * @param sizePx the group's width and height
     */
    public static void loadGroup(Context context, List<String> urls, int sizePx, Callback callback) {
        int avatarSize = Math.round(sizePx * AVATAR_FRACTION);
        Bitmap[] avatars = new Bitmap[2];
        int[] pending = {2};

        for (int i = 0; i < 2; i++) {
            int index = i;

            Glide.with(context)
                    .asBitmap()
                    .load(urls.get(i))
                    .circleCrop()
                    .into(new CustomTarget<Bitmap>(avatarSize, avatarSize) {
                        @Override
                        public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                            // Copied: Glide may reuse the bitmap of a target once it's cleared
                            avatars[index] = resource.copy(Bitmap.Config.ARGB_8888, false);
                            onLoaded();
                        }

                        @Override
                        public void onLoadFailed(@Nullable Drawable errorDrawable) {
                            onLoaded();
                        }

                        @Override
                        public void onLoadCleared(@Nullable Drawable placeholder) {
                            // NOP: the avatar was copied
                        }

                        private void onLoaded() {
                            if (--pending[0] == 0) {
                                callback.onReady(compose(context, avatars, sizePx, avatarSize));
                            }
                        }
                    });
        }
    }

    /**
     * Without one of the two: the other as one channel's avatar
     */
    @Nullable
    private static Drawable compose(Context context, Bitmap[] avatars, int sizePx, int avatarSize) {
        if (avatars[0] == null && avatars[1] == null) {
            return null;
        }

        Bitmap group = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(group);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        if (avatars[0] == null || avatars[1] == null) {
            canvas.drawBitmap(avatars[0] != null ? avatars[0] : avatars[1], null, new Rect(0, 0, sizePx, sizePx), paint);
            return new BitmapDrawable(context.getResources(), group);
        }

        canvas.drawBitmap(avatars[0], null, new Rect(0, 0, avatarSize, avatarSize), paint);

        // The gap: cut out of the first avatar around the second
        Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        float center = sizePx - avatarSize / 2f;
        canvas.drawCircle(center, center, avatarSize / 2f + Math.max(1, sizePx * GAP_FRACTION), clear);

        canvas.drawBitmap(avatars[1], null, new Rect(sizePx - avatarSize, sizePx - avatarSize, sizePx, sizePx), paint);

        return new BitmapDrawable(context.getResources(), group);
    }
}
