package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import android.content.Context;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * An action for displaying chat/comments.
 */
public class ChatAction extends TwoStateAction {
    private final String mLabel;
    private String mCount;

    public ChatAction(Context context) {
        super(context, R.id.action_chat, R.drawable.action_chat);

        // Note, labels denote the action taken when clicked
        mLabel = context.getString(R.string.open_chat);
        setLabels(new String[] {mLabel, mLabel});
    }

    /**
     * The video's comment count after the label, e.g. "Chat/Comments • 1.1K"
     *
     * @param count null for none (e.g. a live stream)
     * @return the label changed, so the button is to be drawn again
     */
    public boolean setCount(String count) {
        if (Helpers.equals(count, mCount)) {
            return false;
        }

        mCount = count;
        String label = count != null ? mLabel + " " + Video.TERTIARY_TEXT_DELIM + " " + count : mLabel;

        // Setting the labels goes back to the first state
        int index = getIndex();
        setLabels(new String[] {label, label});
        setIndex(index);

        return true;
    }
}
