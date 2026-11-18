package com.google.android.exoplayer2.audio;

import android.content.Context;
import android.media.AudioManager;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Player;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import static com.google.android.exoplayer2.audio.AudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY;
import static com.google.android.exoplayer2.audio.AudioFocusManager.PLAYER_COMMAND_WAIT_FOR_CALLBACK;
import static org.junit.Assert.assertEquals;

/**
 * The audio focus fixes of ExoPlayer 2.11.4 backported to the vendored 2.10.6. Here, not with the vendored ExoPlayer: that module's
 * tests don't compile. In ExoPlayer's package: the focus listener is package-private.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AudioFocusManagerBackportTest {
    private static final AudioAttributes MEDIA = new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build();

    private AudioManager mAudioManager;
    private AudioFocusManager mManager;
    private float mVolumeMultiplier = 1.0f;
    private int mPlayerCommand = Integer.MIN_VALUE;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mManager = new AudioFocusManager(context, new AudioFocusManager.PlayerControl() {
            @Override
            public void setVolumeMultiplier(float volumeMultiplier) {
                mVolumeMultiplier = volumeMultiplier;
            }

            @Override
            public void executePlayerCommand(int playerCommand) {
                mPlayerCommand = playerCommand;
            }
        });
        Shadows.shadowOf(mAudioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        assertEquals(PLAYER_COMMAND_PLAY_WHEN_READY, mManager.setAudioAttributes(MEDIA, true, Player.STATE_READY));
    }

    /**
     * Play pressed while another app holds transient focus: focus is asked for again, not waited for (#7182)
     */
    @Test
    public void aTransientLoss_isAskedForAgainOnPlay() {
        mManager.getFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        assertEquals(PLAYER_COMMAND_WAIT_FOR_CALLBACK, mPlayerCommand);

        Shadows.shadowOf(mAudioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);

        assertEquals(PLAYER_COMMAND_PLAY_WHEN_READY, mManager.handleSetPlayWhenReady(true, Player.STATE_READY));
    }

    /**
     * Stopped while ducked: the next video doesn't start at the ducked volume
     */
    @Test
    public void focusAbandonedWhileDucked_restoresTheVolume() {
        mManager.getFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
        assertEquals(0.2f, mVolumeMultiplier, 0);

        mManager.handleStop();

        assertEquals(1.0f, mVolumeMultiplier, 0);
        assertEquals(1.0f, mManager.getVolumeMultiplier(), 0);
    }

    /**
     * Ducked, then lost for good: paused, at full volume for when it's played again
     */
    @Test
    public void aLossWhileDucked_pausesAndRestoresTheVolume() {
        mManager.getFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);

        mManager.getFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);

        assertEquals(AudioFocusManager.PLAYER_COMMAND_DO_NOT_PLAY, mPlayerCommand);
        assertEquals(1.0f, mManager.getVolumeMultiplier(), 0);
    }
}
