package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.google.android.exoplayer2.upstream.Loader;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import okhttp3.Call;
import okhttp3.Request;

/**
 * The player's OkHttp calls, each canceled with its load (see Loader#setCancelListener). A seek cancels the loads going on, but
 * OkHttp's socket read doesn't end on the loader thread's interrupt, as Cronet's wait does: a request the server held (a live
 * stream's next segment) kept the seek waiting until it was answered or timed out. On 2026-11-15, the first rewind at a live stream's
 * head played again 1.6-5 s after the key over OkHttp, the default engine on SHIELD, and 0.3-0.6 s over Cronet.
 */
public final class CancelableCalls implements Call.Factory {
    /**
     * The newest call of each loader's thread, kept until the thread is gone
     */
    private static final Map<Thread, Call> sCalls = Collections.synchronizedMap(new WeakHashMap<>());
    private final Call.Factory mDelegate;

    public CancelableCalls(Call.Factory delegate) {
        mDelegate = delegate;
    }

    /**
     * Before any player's calls: the loads canceled from then on abort their call
     */
    public static void install() {
        Loader.setCancelListener(CancelableCalls::onLoadCanceled);
    }

    @NonNull
    @Override
    public Call newCall(@NonNull Request request) {
        Call call = mDelegate.newCall(request);

        // A load's: made on its loader's thread. Not a prefetch's (see SabrLivePrefetcher), which no load waits for.
        if (Loader.getCurrentLoadable() != null) {
            sCalls.put(Thread.currentThread(), call);
        }

        return call;
    }

    /**
     * The canceled load's call, if it made one: cancel() ends a read blocked on its socket at once. A call that's over already isn't
     * affected. One the load makes after the cancel isn't canceled: it runs its course, as every call did before.
     */
    @VisibleForTesting
    static void onLoadCanceled(Thread loadThread) {
        Call call = sCalls.remove(loadThread);

        if (call != null) {
            call.cancel();
        }
    }

    @Nullable
    @VisibleForTesting
    static Call getCall(Thread thread) {
        return sCalls.get(thread);
    }
}
