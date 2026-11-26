package com.liskovsoft.smartyoutubetv2.tv.ui.settingscards;

import android.content.Context;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.settingssearch.SettingEntry;
import com.liskovsoft.smartyoutubetv2.common.settingssearch.SettingsCatalog;
import com.liskovsoft.smartyoutubetv2.common.settingssearch.SettingsSearch;
import com.liskovsoft.smartyoutubetv2.common.utils.TypingCorrection;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * The search bar of the Settings section, at the end of the line of its title: the settings whose name has the text are listed
 * under it as it's typed in (see SettingsSearch). One opens as its card would, its row focused (see SettingsCatalog.open): Back
 * comes back here, the text and the list as they were.<br/>
 * The list shows while the bar or the list has the focus. Back in the bar empties it.
 */
public class SettingsSearchBar {
    private static final String TAG = SettingsSearchBar.class.getSimpleName();
    private static final int MAX_MATCHES = 50;
    /**
     * More rows than this: the list is as high as settings_search_results_height, and scrolls
     */
    private static final int MAX_ROWS_SHOWN = 5;
    private final Context mContext;
    private final View mRoot;
    private final View mContainer;
    private final TextView mTitleView;
    private final EditText mField;
    private final TextView mEmptyView;
    private final ListView mResults;
    private final int mMinTop;
    private final ResultsAdapter mAdapter;
    @Nullable
    private SettingsCatalog mCatalog;
    private final ViewTreeObserver.OnPreDrawListener mOnPreDraw = () -> {
        alignWithTitle();
        return true;
    };
    private final ViewTreeObserver.OnGlobalFocusChangeListener mOnFocusChange = (oldFocus, newFocus) -> updateResultsShown();

    private SettingsSearchBar(View root) {
        mContext = root.getContext();
        mRoot = root;
        mContainer = root.findViewById(R.id.settings_search);
        mTitleView = root.findViewById(R.id.settings_cards_title);
        mField = root.findViewById(R.id.settings_search_field);
        mEmptyView = root.findViewById(R.id.settings_search_empty);
        mResults = root.findViewById(R.id.settings_search_results);
        mAdapter = new ResultsAdapter(mContext);
        mResults.setAdapter(mAdapter);
        mMinTop = mContext.getResources().getDimensionPixelSize(R.dimen.settings_search_min_top);
    }

    /**
     * Shows the bar until {@link #detach()}
     *
     * @param root the view made by SettingsCardsLayout.wrap
     */
    public static SettingsSearchBar attach(View root) {
        SettingsSearchBar bar = new SettingsSearchBar(root);
        bar.setup();

        return bar;
    }

    /**
     * A key of the grid of cards under the bar: Up from the first row comes to the bar
     *
     * @return taken
     */
    public boolean onGridKey(KeyEvent event, boolean isFirstRow) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_DPAD_UP || !isFirstRow) {
            return false;
        }

        // The key comes down and up: move once, swallow both
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            mField.requestFocus();
        }

        return true;
    }

    public void detach() {
        mRoot.getViewTreeObserver().removeOnPreDrawListener(mOnPreDraw);
        mRoot.getViewTreeObserver().removeOnGlobalFocusChangeListener(mOnFocusChange);
    }

    private void setup() {
        mContainer.setVisibility(View.VISIBLE);
        TypingCorrection.applySearchSetting(mField);
        mResults.setNextFocusUpId(R.id.settings_search_field);

        mField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                search(s.toString().trim());
            }
        });

        // The keyboard's Search or Enter: down to the settings found, the keyboard closed
        mField.setOnEditorActionListener((v, actionId, event) -> {
            boolean isEnterKey = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;

            if (actionId != EditorInfo.IME_ACTION_SEARCH && actionId != EditorInfo.IME_ACTION_DONE && !isEnterKey) {
                return false;
            }

            if (event == null || event.getAction() == KeyEvent.ACTION_DOWN) {
                hideKeyboard();

                if (mAdapter.getCount() > 0) {
                    mResults.requestFocus();
                    mResults.setSelection(0);
                }
            }

            return true;
        });

        mField.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }

            // Down to the settings found, when there are any (the cards otherwise)
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && mResults.getVisibility() == View.VISIBLE) {
                mResults.requestFocus();
                mResults.setSelection(0);
                return true;
            }

            // Back empties the bar first
            if (keyCode == KeyEvent.KEYCODE_BACK && mField.length() > 0) {
                mField.setText("");
                return true;
            }

            return false;
        });

        mResults.setOnKeyListener((v, keyCode, event) -> {
            // Back to the bar, the text kept
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
                mField.requestFocus();
                return true;
            }

            return false;
        });

        mResults.setOnItemClickListener((parent, view, position, id) -> open(mAdapter.getItem(position)));
        // The row is marked only while the list has the focus: not while typing in the bar
        mResults.setSelector(android.R.color.transparent);
        mResults.setOnFocusChangeListener((v, hasFocus) -> mResults.setSelector(hasFocus ?
                R.drawable.settings_search_row_focused : android.R.color.transparent));

        mRoot.getViewTreeObserver().addOnPreDrawListener(mOnPreDraw);
        mRoot.getViewTreeObserver().addOnGlobalFocusChangeListener(mOnFocusChange);
    }

    private void search(String text) {
        List<SettingEntry> found = new ArrayList<>();

        if (!text.isEmpty()) {
            SettingsCatalog catalog = getCatalog();

            if (catalog != null) {
                found = SettingsSearch.find(catalog.getEntries(), text, MAX_MATCHES);
            }
        }

        mAdapter.setEntries(found);
        mEmptyView.setText(mContext.getString(com.liskovsoft.smartyoutubetv2.common.R.string.find_setting_none, text));

        // Scrolls past a few rows, as high as its rows otherwise
        ViewGroup.LayoutParams params = mResults.getLayoutParams();
        params.height = found.size() > MAX_ROWS_SHOWN ?
                mContext.getResources().getDimensionPixelSize(R.dimen.settings_search_results_height) : ViewGroup.LayoutParams.WRAP_CONTENT;
        mResults.setLayoutParams(params);

        updateResultsShown();
    }

    /**
     * Listed on the first search, then kept (see SettingsCatalog.get)
     */
    @Nullable
    private SettingsCatalog getCatalog() {
        try {
            mCatalog = SettingsCatalog.get(mContext);
        } catch (RuntimeException e) {
            Log.e(TAG, "Can't list the settings: " + e);
            mCatalog = null;
        }

        return mCatalog;
    }

    private void open(@Nullable SettingEntry entry) {
        if (entry == null) {
            return;
        }

        if (!SettingsCatalog.open(mContext, entry, () -> { })) {
            // The settings changed since they were listed
            SettingsCatalog.clearCache();
            MessageHelpers.showMessage(mContext, com.liskovsoft.smartyoutubetv2.common.R.string.find_setting_gone);
            search(mField.getText().toString().trim());
        }
    }

    /**
     * The settings found, or that there are none, while the bar or the list has the focus and there's text
     */
    private void updateResultsShown() {
        View focused = mRoot.findFocus();
        boolean isSearching = mField.length() > 0 && (focused == mField || focused == mResults);
        boolean hasResults = mAdapter.getCount() > 0;

        mResults.setVisibility(isSearching && hasResults ? View.VISIBLE : View.GONE);
        mEmptyView.setVisibility(isSearching && !hasResults ? View.VISIBLE : View.GONE);
    }

    /**
     * The bar's middle on the middle of the title's line: the title moves (see SettingsCardsLayout)
     */
    private void alignWithTitle() {
        int titleTextTop = mTitleView.getTop() + mTitleView.getPaddingTop();
        int titleTextHeight = mTitleView.getHeight() - mTitleView.getPaddingTop() - mTitleView.getPaddingBottom();
        // Not against the top of the screen
        float barTop = Math.max(mMinTop, titleTextTop + (titleTextHeight - mField.getHeight()) / 2f);
        float translation = barTop - mContainer.getTop();

        if (mContainer.getTranslationY() != translation) {
            mContainer.setTranslationY(translation);
        }
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) mContext.getSystemService(Context.INPUT_METHOD_SERVICE);

        if (manager != null) {
            manager.hideSoftInputFromWindow(mField.getWindowToken(), 0);
        }
    }

    /**
     * The settings found: the name, and where it is
     */
    private static final class ResultsAdapter extends ArrayAdapter<SettingEntry> {
        private final LayoutInflater mInflater;

        private ResultsAdapter(Context context) {
            super(context, 0, new ArrayList<>());
            mInflater = LayoutInflater.from(context);
        }

        private void setEntries(List<SettingEntry> entries) {
            setNotifyOnChange(false);
            clear();
            addAll(entries);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            View view = convertView != null ? convertView : mInflater.inflate(R.layout.settings_search_row, parent, false);
            SettingEntry entry = getItem(position);

            if (entry != null) {
                ((TextView) view.findViewById(R.id.settings_search_row_title)).setText(entry.title);
                TextView where = view.findViewById(R.id.settings_search_row_where);
                where.setText(TextUtils.join(" › ", entry.path));
                where.setVisibility(entry.path.isEmpty() ? View.GONE : View.VISIBLE);
            }

            return view;
        }
    }
}
