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
import androidx.core.util.Consumer;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.settingssearch.AiSettingsSearch;
import com.liskovsoft.smartyoutubetv2.common.settingssearch.SettingEntry;
import com.liskovsoft.smartyoutubetv2.common.settingssearch.SettingsCatalog;
import com.liskovsoft.smartyoutubetv2.common.settingssearch.SettingsSearch;
import com.liskovsoft.smartyoutubetv2.common.utils.TypingCorrection;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The search bar of the Settings section, at the end of the line of its title: the settings whose name has the text are listed
 * under it as it's typed in (see SettingsSearch). One opens as its card would, its row focused (see SettingsCatalog.open): Back
 * comes back here, the text and the list as they were.<br/>
 * The list shows while the bar or the list has the focus. Back in the bar empties it.<br/>
 * The settings are listed again after the focus was elsewhere (a setting opened, a card): they may have changed.<br/>
 * With the AI help on (see AiSettingsSearch), the keyboard's Search asks the AI too: its row first in the list (asking, then its
 * reply), the settings it picked under it, then the ones found on the device.
 */
public class SettingsSearchBar {
    private static final String TAG = SettingsSearchBar.class.getSimpleName();
    private static final int MAX_MATCHES = 50;
    /**
     * More rows than this: the list is as high as settings_search_results_height, and scrolls
     */
    private static final int MAX_ROWS_SHOWN = 5;
    /**
     * The row of the AI in the list
     */
    private static final Object AI_ROW = new Object();
    private final Context mContext;
    private final View mRoot;
    private final View mContainer;
    private final TextView mTitleView;
    private final EditText mField;
    private final TextView mEmptyView;
    private final ListView mResults;
    private final int mMinTop;
    private final ResultsAdapter mAdapter;
    private final Consumer<Runnable> mRunWithPassword;
    private final AiSettingsSearch mAi;
    /**
     * The focus was out of the bar and the list since the settings were listed
     */
    private boolean mIsStale = true;
    @Nullable
    private SettingsCatalog mCatalog;
    private final ViewTreeObserver.OnPreDrawListener mOnPreDraw = () -> {
        alignWithTitle();
        return true;
    };
    private final ViewTreeObserver.OnGlobalFocusChangeListener mOnFocusChange = this::onFocusChange;

    private SettingsSearchBar(View root, TextView titleView, Consumer<Runnable> runWithPassword) {
        mContext = root.getContext();
        mRunWithPassword = runWithPassword;
        mRoot = root;
        mContainer = root.findViewById(R.id.settings_search);
        mTitleView = titleView;
        mField = root.findViewById(R.id.settings_search_field);
        mEmptyView = root.findViewById(R.id.settings_search_empty);
        mResults = root.findViewById(R.id.settings_search_results);
        mAi = new AiSettingsSearch(mContext, this::onAiChange);
        mAdapter = new ResultsAdapter(mContext, mAi);
        mResults.setAdapter(mAdapter);
        mMinTop = mContext.getResources().getDimensionPixelSize(R.dimen.settings_search_min_top);
    }

    /**
     * Shows the bar until {@link #detach()}
     *
     * @param root a view with the bar in it (settings_search_bar): the classic cards (settings_cards), the modern settings page
     * @param titleView the title the bar sits on the line of, in the same parent as the bar
     * @param runWithPassword runs a setting opened once the settings password is typed in, as a card (see SettingsGridFragment)
     */
    public static SettingsSearchBar attach(View root, TextView titleView, Consumer<Runnable> runWithPassword) {
        SettingsSearchBar bar = new SettingsSearchBar(root, titleView, runWithPassword);
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
        mAi.cancel();
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
                String text = s.toString().trim();

                // Another question: the AI's answer was to the one before
                if (mAi.isActive() && !text.equals(mAi.getQuestion())) {
                    mAi.cancel();
                }

                search(text);
            }
        });

        // The keyboard's Search or Enter: down to the settings found, the keyboard closed. The AI asked too when its help is on.
        mField.setOnEditorActionListener((v, actionId, event) -> {
            boolean isEnterKey = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;

            if (actionId != EditorInfo.IME_ACTION_SEARCH && actionId != EditorInfo.IME_ACTION_DONE && !isEnterKey) {
                return false;
            }

            if (event == null || event.getAction() == KeyEvent.ACTION_DOWN) {
                hideKeyboard();
                String text = mField.getText().toString().trim();

                if (!text.isEmpty() && !text.equals(mAi.getQuestion()) && mAi.isReady() && mAi.ask(text)) {
                    search(text);
                }

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

            // Up from the first setting found: to the bar. The list keeps the key otherwise, and its next focus up isn't
            // followed in the Browse screen
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && mResults.getSelectedItemPosition() <= 0) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    mField.requestFocus();
                }

                return true;
            }

            return false;
        });

        mResults.setOnItemClickListener((parent, view, position, id) -> {
            Object item = mAdapter.getItem(position);

            if (item == AI_ROW) {
                // It failed with the model: another one picked (a setting: the settings password first), the question asked again
                if (mAi.canPickModel()) {
                    mRunWithPassword.accept(mAi::pickModel);
                }
            } else if (item instanceof SettingEntry) {
                open((SettingEntry) item);
            }
        });
        // The row is marked only while the list has the focus: not while typing in the bar
        mResults.setSelector(android.R.color.transparent);
        mResults.setOnFocusChangeListener((v, hasFocus) -> mResults.setSelector(hasFocus ?
                R.drawable.settings_search_row_focused : android.R.color.transparent));

        mRoot.getViewTreeObserver().addOnPreDrawListener(mOnPreDraw);
        mRoot.getViewTreeObserver().addOnGlobalFocusChangeListener(mOnFocusChange);
    }

    private void search(String text) {
        List<Object> rows = new ArrayList<>();

        if (!text.isEmpty()) {
            Set<String> shown = new HashSet<>();

            // The AI's row and picks first: the picks once, not again among the ones found
            if (mAi.isActive()) {
                rows.add(AI_ROW);

                for (SettingEntry pick : mAi.getPicks()) {
                    if (shown.add(pick.toString())) {
                        rows.add(pick);
                    }
                }
            }

            SettingsCatalog catalog = getCatalog();

            if (catalog != null) {
                for (SettingEntry entry : SettingsSearch.find(catalog.getEntries(), text, MAX_MATCHES)) {
                    if (shown.add(entry.toString())) {
                        rows.add(entry);
                    }
                }
            }
        }

        mAdapter.setRows(rows);
        mEmptyView.setText(mContext.getString(com.liskovsoft.smartyoutubetv2.common.R.string.find_setting_none, text));

        // Scrolls past a few rows, as high as its rows otherwise
        ViewGroup.LayoutParams params = mResults.getLayoutParams();
        // The AI's reply takes a few lines: fewer rows then
        int maxRows = mAi.isActive() ? MAX_ROWS_SHOWN - 2 : MAX_ROWS_SHOWN;
        params.height = rows.size() > maxRows ?
                mContext.getResources().getDimensionPixelSize(R.dimen.settings_search_results_height) : ViewGroup.LayoutParams.WRAP_CONTENT;
        mResults.setLayoutParams(params);

        updateResultsShown();
    }

    /**
     * Listed on the first search, then kept while the focus stays in the bar and the list (see SettingsCatalog.get)
     */
    @Nullable
    private SettingsCatalog getCatalog() {
        if (mIsStale) {
            SettingsCatalog.clearCache();
            mIsStale = false;
        }

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

        mRunWithPassword.accept(() -> {
            if (!SettingsCatalog.open(mContext, entry)) {
                // The settings changed since they were listed
                mIsStale = true;
                MessageHelpers.showMessage(mContext, com.liskovsoft.smartyoutubetv2.common.R.string.find_setting_gone);
                search(mField.getText().toString().trim());
            }
        });
    }

    /**
     * The AI answered, or failed: its row and picks shown, the row focused kept
     */
    private void onAiChange() {
        boolean hadFocus = mResults.hasFocus();
        int selected = mResults.getSelectedItemPosition();
        search(mField.getText().toString().trim());

        if (hadFocus && mAdapter.getCount() > 0) {
            mResults.setSelection(Math.max(0, Math.min(selected, mAdapter.getCount() - 1)));
        }
    }

    private void onFocusChange(View oldFocus, View newFocus) {
        if (newFocus != mField && newFocus != mResults) {
            mIsStale = true;
        }

        updateResultsShown();
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
     * The settings found: the name, and where it is. The AI's row: who answers, and its reply.
     */
    private static final class ResultsAdapter extends ArrayAdapter<Object> {
        private final LayoutInflater mInflater;
        private final AiSettingsSearch mAi;

        private ResultsAdapter(Context context, AiSettingsSearch ai) {
            super(context, 0, new ArrayList<>());
            mInflater = LayoutInflater.from(context);
            mAi = ai;
        }

        private void setRows(List<Object> rows) {
            setNotifyOnChange(false);
            clear();
            addAll(rows);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            View view = convertView != null ? convertView : mInflater.inflate(R.layout.settings_search_row, parent, false);
            Object item = getItem(position);
            TextView title = view.findViewById(R.id.settings_search_row_title);
            TextView text = view.findViewById(R.id.settings_search_row_where);

            if (item == AI_ROW) {
                // Who answers, and its reply in full (asking, or why it didn't answer)
                title.setText(mAi.getTitle());
                text.setText(mAi.getText());
                text.setMaxLines(Integer.MAX_VALUE);
                text.setVisibility(View.VISIBLE);
            } else if (item instanceof SettingEntry) {
                SettingEntry entry = (SettingEntry) item;
                title.setText(entry.title);
                text.setText(TextUtils.join(" › ", entry.path));
                text.setMaxLines(1);
                text.setVisibility(entry.path.isEmpty() ? View.GONE : View.VISIBLE);
            }

            return view;
        }
    }
}
