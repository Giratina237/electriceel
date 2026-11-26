package com.liskovsoft.smartyoutubetv2.common.utils;

import android.content.Intent;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 4Z0TV6X2 #1, #2: the app's own sign-in QR code (https://youtube.com/qr/activate/NWF-WSH-NVF) opened on the TV, its code played as a
 * video ID: 11 characters, as one is. A sign-in page isn't a video.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class IntentExtractorSignInTest {
    @Test
    public void theSignInQrCodeIsntAVideo() {
        Intent intent = view("https://youtube.com/qr/activate/NWF-WSH-NVF");

        assertNull(IntentExtractor.extractVideoId(intent));
        assertTrue(IntentExtractor.isSignInUrl(intent));
    }

    @Test
    public void theActivationPagesAreSignInPages() {
        assertTrue(IntentExtractor.isSignInUrl(view("https://yt.be/activate")));
        assertTrue(IntentExtractor.isSignInUrl(view("https://www.youtube.com/tv/activate")));
        assertTrue(IntentExtractor.isSignInUrl(view("https://www.youtube.com/activate?user_code=NWF-WSH-NVF")));
    }

    /**
     * Shared as text (e.g. from a phone): the same
     */
    @Test
    public void theSignInQrCodeSharedAsTextIsntAVideo() {
        Intent intent = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "https://youtube.com/qr/activate/MDB-HVJ-BVW");

        assertNull(IntentExtractor.extractVideoId(intent));
        assertTrue(IntentExtractor.isSignInUrl(intent));
    }

    @Test
    public void aVideoLinkIsStillAVideo() {
        assertEquals("lBeMDqcWTG8", IntentExtractor.extractVideoId(view("https://youtu.be/lBeMDqcWTG8")));
        assertEquals("dQw4w9WgXcQ", IntentExtractor.extractVideoId(view("https://www.youtube.com/watch?v=dQw4w9WgXcQ")));
        assertFalse(IntentExtractor.isSignInUrl(view("https://youtu.be/lBeMDqcWTG8")));
        // A video ID with "activate" in it isn't the path segment
        assertFalse(IntentExtractor.isSignInUrl(view("https://youtu.be/activate123")));
    }

    private static Intent view(String url) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url));
    }
}
