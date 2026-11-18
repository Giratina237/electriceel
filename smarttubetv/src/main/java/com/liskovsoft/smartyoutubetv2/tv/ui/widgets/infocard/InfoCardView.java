package com.liskovsoft.smartyoutubetv2.tv.ui.widgets.infocard;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.liskovsoft.mediaserviceinterfaces.data.InfoCardItem;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * The teaser of an info card over the video (see InfoCardController), at the top end of the video's frame, below the corner clock when
 * it shows.<br/>
 * It takes no focus until {@link #enter()} (D-pad up while it shows): then OK opens its card and back (or down) leaves it, while the
 * video plays on. Picked, it stays till it's left, past the time of its teaser.<br/>
 * Its image (round for a channel) and the teaser's text, with what the card opens under it when the teaser says something else, or else
 * the video's author, the channel's subscribers or the link's description. A video's length is on its image.
 */
public class InfoCardView extends FrameLayout {
    private static final int BACKGROUND_COLOR = 0xCC000000;
    private static final int BADGE_COLOR = 0xCC000000;
    private static final int PLACEHOLDER_COLOR = 0x80000000;
    private static final int SUBTITLE_COLOR = 0xB3FFFFFF;
    private final Rect mFrame = new Rect();
    private final int mMargin;
    private final int mCornerRadius;
    private final int mFocusStroke;
    private final int mBorderWidth;
    private final int mPadding;
    private final int mImageWidth;
    private final int mImageHeight;
    private final int mTextGap;
    private final int mTextMaxWidth;
    // Laid out again after the frame and the corner, not inside their layout
    private final OnLayoutChangeListener mRelayout = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> post(this::requestLayout);
    @Nullable
    private View mFrameView;
    @Nullable
    private ViewGroup mCornerView;
    @Nullable
    private Listener mListener;
    @Nullable
    private View mFocusBefore;
    // The one shown, and the one to show once the shown one is left (see setItem)
    @Nullable
    private InfoCardItem mItem;
    @Nullable
    private InfoCardItem mRequestedItem;
    @Nullable
    private View mCard;
    @Nullable
    private ImageView mImage;

    public interface Listener {
        void onCardClicked(InfoCardItem item);
    }

    public InfoCardView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mMargin = getResources().getDimensionPixelSize(R.dimen.info_card_margin);
        mCornerRadius = getResources().getDimensionPixelSize(R.dimen.info_card_corner_radius);
        mFocusStroke = getResources().getDimensionPixelSize(R.dimen.info_card_focus_stroke);
        mBorderWidth = getResources().getDimensionPixelSize(R.dimen.info_card_border_width);
        mPadding = getResources().getDimensionPixelSize(R.dimen.info_card_padding);
        mImageWidth = getResources().getDimensionPixelSize(R.dimen.info_card_image_width);
        mImageHeight = getResources().getDimensionPixelSize(R.dimen.info_card_image_height);
        mTextGap = getResources().getDimensionPixelSize(R.dimen.info_card_text_gap);
        mTextMaxWidth = getResources().getDimensionPixelSize(R.dimen.info_card_text_max_width);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
    }

    /**
     * The view the video is drawn in: the teaser goes at its top end
     */
    public void setFrameView(@Nullable View frameView) {
        if (mFrameView != null) {
            mFrameView.removeOnLayoutChangeListener(mRelayout);
        }

        mFrameView = frameView;

        if (mFrameView != null) {
            mFrameView.addOnLayoutChangeListener(mRelayout);
        }

        requestLayout();
    }

    /**
     * The corner clock (global clock and ending time) at the top end of the screen: the teaser goes below it when it shows
     */
    public void setCornerView(@Nullable ViewGroup cornerView) {
        if (mCornerView != null) {
            mCornerView.removeOnLayoutChangeListener(mRelayout);
        }

        mCornerView = cornerView;

        if (mCornerView != null) {
            mCornerView.addOnLayoutChangeListener(mRelayout);
        }

        requestLayout();
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    /**
     * The card whose teaser shows now, null for none. One that has the focus stays till it's left (see {@link #exit()}): then this one
     * shows.
     */
    public void setItem(@Nullable InfoCardItem item) {
        mRequestedItem = item;

        if (!isActive()) {
            show(item);
        }
    }

    public boolean hasCard() {
        return mCard != null && getVisibility() == VISIBLE;
    }

    /**
     * The teaser has the focus (see {@link #enter()})
     */
    public boolean isActive() {
        return getDescendantFocusability() != FOCUS_BLOCK_DESCENDANTS;
    }

    /**
     * The focus moves to the teaser
     *
     * @return whether it got it
     */
    public boolean enter() {
        if (mCard == null) {
            return false;
        }

        mFocusBefore = getRootView().findFocus();
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);

        if (!mCard.requestFocus()) {
            setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
            mFocusBefore = null;
            return false;
        }

        return true;
    }

    /**
     * The focus goes back to where it was before {@link #enter()}, and the teaser asked for meanwhile shows (see {@link #setItem})
     */
    public void exit() {
        if (!isActive()) {
            return;
        }

        View before = mFocusBefore;
        mFocusBefore = null;
        boolean hadFocus = hasFocus();
        boolean isRestored = hadFocus && before != null && before.isAttachedToWindow() && before.requestFocus();

        // Drops the teaser's focus when it wasn't moved back
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);

        if (hadFocus && !isRestored) {
            // The teaser can't take it now: the first view of the window outside it does
            getRootView().requestFocus();
        }

        show(mRequestedItem);
    }

    /**
     * A key pressed while the teaser has the focus: OK opens its card, back or down leaves it, the other arrows go nowhere
     *
     * @return true if handled, false for a key the player handles (e.g. a media key)
     */
    public boolean onActiveKeyDown(KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (event.getRepeatCount() == 0) {
                    InfoCardItem item = mItem;
                    exit();

                    if (item != null && mListener != null) {
                        mListener.onCardClicked(item);
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                // Up went in: down goes out
                exit();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            default:
                return false;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                getDefaultSize(getSuggestedMinimumHeight(), heightMeasureSpec));

        if (mCard != null) {
            // The same height whatever the text: the image's
            mCard.measure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(mImageHeight + mPadding * 2, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (mCard == null) {
            return;
        }

        updateFrame();

        int cardRight = mFrame.right - mMargin;
        int cardTop = Math.max(mFrame.top + mMargin, getCornerBottom());

        mCard.layout(cardRight - mCard.getMeasuredWidth(), cardTop, cardRight, cardTop + mCard.getMeasuredHeight());
    }

    /**
     * The video's frame as it's shown (scaled, moved next to a dialog), in this view's coordinates. The whole view before the video
     * has a size.
     */
    private void updateFrame() {
        if (mFrameView != null && mFrameView.getWidth() > 0 && mFrameView.getHeight() > 0 && mFrameView.getParent() == getParent()) {
            mFrameView.getHitRect(mFrame);
            mFrame.offset(-getLeft(), -getTop());
        } else {
            mFrame.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
        }
    }

    /**
     * The bottom of the corner clock in this view's coordinates, 0 when nothing of it shows
     */
    private int getCornerBottom() {
        if (mCornerView == null || mCornerView.getVisibility() != VISIBLE || mCornerView.getParent() != getParent()) {
            return 0;
        }

        for (int i = 0; i < mCornerView.getChildCount(); i++) {
            if (mCornerView.getChildAt(i).getVisibility() == VISIBLE) {
                return mCornerView.getBottom() - getTop();
            }
        }

        return 0;
    }

    private void show(@Nullable InfoCardItem item) {
        if (item == mItem) {
            return;
        }

        if (mCard != null) {
            if (mImage != null) {
                // Not the activity's Glide: it throws once the activity is destroyed, where the player released in
                // PlaybackFragment#onDestroy takes the card away (as EndScreenView's did, J9T6DZWN)
                Glide.with(getContext().getApplicationContext()).clear(mImage);
            }

            removeView(mCard);
            mCard = null;
            mImage = null;
        }

        mItem = item;

        if (item != null) {
            mCard = createCard(item);
            addView(mCard);
        }

        requestLayout();
    }

    private View createCard(InfoCardItem item) {
        Context context = getContext();
        boolean isRound = item.getType() == InfoCardItem.TYPE_CHANNEL;

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(mPadding, mPadding, mPadding, mPadding);
        card.setFocusable(true);
        card.setContentDescription(item.getTeaser());
        GradientDrawable background = new GradientDrawable();
        background.setColor(BACKGROUND_COLOR);
        // The card is dark: a white edge keeps it apart from a dark video
        background.setStroke(mBorderWidth, Color.WHITE);
        background.setCornerRadius(mCornerRadius);
        card.setBackground(background);
        card.setForeground(createFocusRing());
        card.setOnClickListener(v -> {
            exit();

            if (mListener != null) {
                mListener.onCardClicked(item);
            }
        });

        card.addView(createImage(item, isRound), new LinearLayout.LayoutParams(isRound ? mImageHeight : mImageWidth, mImageHeight));

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        textParams.leftMargin = mTextGap;
        card.addView(text, textParams);

        TextView teaser = createText(R.dimen.info_card_teaser_text_size);
        teaser.setText(item.getTeaser());
        teaser.setTypeface(Typeface.DEFAULT_BOLD);
        teaser.setMaxLines(2);
        text.addView(teaser);

        String subtitleText = Helpers.equals(item.getTeaser(), item.getTitle()) ? item.getMetadata() : item.getTitle();

        if (!TextUtils.isEmpty(subtitleText)) {
            TextView subtitle = createText(R.dimen.info_card_subtitle_text_size);
            subtitle.setText(subtitleText);
            subtitle.setTextColor(SUBTITLE_COLOR);
            text.addView(subtitle);
        }

        return card;
    }

    private View createImage(InfoCardItem item, boolean isRound) {
        Context context = getContext();

        FrameLayout frame = new FrameLayout(context);
        frame.setBackground(new ColorDrawable(PLACEHOLDER_COLOR));
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                if (isRound) {
                    outline.setOval(0, 0, v.getWidth(), v.getHeight());
                } else {
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), mCornerRadius);
                }
            }
        });
        frame.setClipToOutline(true);

        mImage = new ImageView(context);
        mImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(mImage, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        Glide.with(context).load(item.getImageUrl()).into(mImage);

        if (!TextUtils.isEmpty(item.getBadge())) {
            int badgePadding = getResources().getDimensionPixelSize(R.dimen.info_card_badge_padding);
            TextView badge = createText(R.dimen.info_card_badge_text_size);
            badge.setText(item.getBadge());
            badge.setPadding(badgePadding * 2, badgePadding, badgePadding * 2, badgePadding);
            GradientDrawable background = new GradientDrawable();
            background.setColor(BADGE_COLOR);
            background.setCornerRadius(mCornerRadius);
            badge.setBackground(background);
            LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
            params.setMargins(badgePadding, badgePadding, badgePadding, badgePadding);
            frame.addView(badge, params);
        }

        return frame;
    }

    private TextView createText(int textSizeResId) {
        TextView text = new TextView(getContext());
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(textSizeResId));
        text.setMaxLines(1);
        text.setMaxWidth(mTextMaxWidth);
        text.setEllipsize(TextUtils.TruncateAt.END);
        return text;
    }

    private Drawable createFocusRing() {
        GradientDrawable ring = new GradientDrawable();
        ring.setStroke(mFocusStroke, Color.WHITE);
        ring.setColor(Color.TRANSPARENT);
        ring.setCornerRadius(mCornerRadius);

        StateListDrawable result = new StateListDrawable();
        result.addState(new int[] {android.R.attr.state_focused}, ring);
        result.addState(new int[] {}, new ColorDrawable(Color.TRANSPARENT));
        return result;
    }
}
