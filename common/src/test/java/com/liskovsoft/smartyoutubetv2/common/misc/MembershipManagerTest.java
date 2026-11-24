package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.smartyoutubetv2.common.prefs.MembersData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The account's memberships, read before the first members' cards are shown (see MembershipManager, MembersData.onMembershipsRead)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MembershipManagerTest {
    private static final String ACCOUNT = "one@example.com";
    private static final String OTHER_ACCOUNT = "two@example.com";
    private static final String CHANNEL = "UCFLwN7vRu8M057qJF8TsBaA";
    private static final long MINUTE_MS = 60 * 1_000;
    private final Context mContext = RuntimeEnvironment.getApplication();
    // The channels each account is a member of
    private final Map<String, List<String>> mMemberships = new HashMap<>();
    private final List<String> mReads = new ArrayList<>(); // the account of each read
    private MembershipManager mManager;
    private String mAccount = ACCOUNT;
    private boolean mIsNetworkDown;
    private PublishSubject<List<String>> mPendingRead; // the next reads end when the test says

    @Before
    public void setUp() {
        GlobalPreferences.instance(mContext);
        AppPrefs.instance(mContext).onAccountChanged(null); // the anonymous account
        MembersData.clearForTesting(mContext);
        mMemberships.put(ACCOUNT, Collections.singletonList(CHANNEL));
        mMemberships.put(OTHER_ACCOUNT, Collections.emptyList());
        mManager = MembershipManager.instance(mContext);
        mManager.resetForTesting(this::read, () -> mAccount);
    }

    @After
    public void tearDown() {
        MembersData.clearForTesting(mContext);
        Utils.sHandler.removeCallbacksAndMessages(null);
    }

    @Test
    public void memberSeesThem() {
        assertTrue(resolve());

        assertEquals(MembersData.MODE_SHOW, getData().getMode());
        assertEquals(Collections.singletonList(ACCOUNT), mReads);
    }

    @Test
    public void notMemberDoesNot() {
        mAccount = OTHER_ACCOUNT;

        assertTrue(resolve());

        assertEquals(MembersData.MODE_HIDE, getData().getMode());
    }

    /**
     * Once in a run: a member of none isn't asked again at each group
     */
    @Test
    public void readOncePerAccount() {
        mAccount = OTHER_ACCOUNT;

        resolve();
        resolve();

        assertEquals(Collections.singletonList(OTHER_ACCOUNT), mReads);
    }

    @Test
    public void signedOutReadsNothing() {
        mAccount = null;

        assertTrue(resolve());

        assertTrue(mReads.isEmpty());
        assertEquals(MembersData.MODE_HIDE, getData().getMode());
    }

    /**
     * Shown or marked, they're on the cards already
     */
    @Test
    public void readOnlyWhileHidden() {
        getData().setMode(MembersData.MODE_MARK);

        assertTrue(resolve());

        assertTrue(mReads.isEmpty());
        assertEquals(MembersData.MODE_MARK, getData().getMode());
    }

    @Test
    public void runningReadIsShared() {
        PublishSubject<List<String>> read = PublishSubject.create();
        mPendingRead = read;

        List<Boolean> done = new ArrayList<>();
        mManager.resolve().subscribe(done::add);
        mManager.resolve().subscribe(done::add);

        // Held while they're read
        assertTrue(done.isEmpty());

        read.onNext(mMemberships.get(ACCOUNT));
        read.onComplete();

        assertEquals(Arrays.asList(true, true), done);
        assertEquals(1, mReads.size());
        assertEquals(MembersData.MODE_SHOW, getData().getMode());
    }

    /**
     * A failed read changes nothing, and isn't repeated at once: the cards are shown as the setting is
     */
    @Test
    public void failedReadChangesNothing() {
        mIsNetworkDown = true;

        assertTrue(resolve());
        assertTrue(resolve());

        assertEquals(1, mReads.size());
        assertEquals(MembersData.MODE_HIDE, getData().getMode());
    }

    /**
     * Another read turns it on (e.g. of the next group)
     */
    @Test
    public void failedReadIsRetriedLater() {
        mIsNetworkDown = true;
        resolve();
        mIsNetworkDown = false;
        mManager.setFailedTimeForTesting(2 * MINUTE_MS);

        assertTrue(resolve());

        assertEquals(2, mReads.size());
        assertEquals(MembersData.MODE_SHOW, getData().getMode());
    }

    /**
     * A slow read holds the cards 5 s at most. It goes on, and applies to the cards after.
     */
    @Test
    public void slowReadHoldsTheCardsForAWhileOnly() {
        PublishSubject<List<String>> read = PublishSubject.create();
        mPendingRead = read;

        List<Boolean> done = new ArrayList<>();
        mManager.resolve().subscribe(done::add);
        ShadowLooper.idleMainLooper(5, TimeUnit.SECONDS);

        assertEquals(Collections.singletonList(true), done);
        assertEquals(MembersData.MODE_HIDE, getData().getMode());

        read.onNext(mMemberships.get(ACCOUNT));
        read.onComplete();

        assertEquals(MembersData.MODE_SHOW, getData().getMode());
    }

    /**
     * The setting is the account's picked now: the read of the one left doesn't change it
     */
    @Test
    public void readOfAnAccountLeftIsDropped() {
        PublishSubject<List<String>> read = PublishSubject.create();
        mPendingRead = read;
        mManager.resolve().subscribe();

        mAccount = OTHER_ACCOUNT;
        read.onNext(mMemberships.get(ACCOUNT));
        read.onComplete();

        assertEquals(MembersData.MODE_HIDE, getData().getMode());

        // The one left is read again when picked again
        mAccount = ACCOUNT;
        mPendingRead = null;

        assertTrue(resolve());
        assertEquals(MembersData.MODE_SHOW, getData().getMode());
    }

    @Test
    public void neededForMembersCardsWhileHidden() {
        List<MediaGroup> members = Collections.singletonList(createGroup(createMembersVideo("members1"), createVideo("video1")));
        List<MediaGroup> others = Collections.singletonList(createGroup(createVideo("video1")));

        assertTrue(mManager.isNeededFor(members));
        assertFalse(mManager.isNeededFor(others));

        getData().setMode(MembersData.MODE_SHOW);

        assertFalse(mManager.isNeededFor(members));
    }

    /**
     * "Members first" or no label at all: still a members' video
     */
    @Test
    public void neededForAnyMembersLabel() {
        Video unlabelled = createMembersVideo("members1");
        unlabelled.membersLabel = "";

        assertTrue(mManager.isNeededFor(Collections.singletonList(createGroup(unlabelled))));
    }

    private boolean resolve() {
        List<Boolean> done = new ArrayList<>();
        mManager.resolve().subscribe(done::add);

        return done.size() == 1 && done.get(0);
    }

    private Observable<List<String>> read() {
        mReads.add(mAccount);

        if (mPendingRead != null) {
            return mPendingRead;
        }

        if (mIsNetworkDown) {
            return Observable.error(new IllegalStateException("No network"));
        }

        return Observable.just(mMemberships.get(mAccount));
    }

    private MembersData getData() {
        return MembersData.instance(mContext);
    }

    private static MediaGroup createGroup(Video... videos) {
        return HiddenVideoResolverTest.createGroup(MediaGroup.TYPE_HOME, null, videos);
    }

    private static Video createMembersVideo(String videoId) {
        Video video = createVideo(videoId);
        video.membersLabel = "Members only";
        return video;
    }

    private static Video createVideo(String videoId) {
        Video video = new Video();
        video.videoId = videoId;
        video.title = "Video " + videoId;
        video.author = "Channel";
        return video;
    }
}
