package com.google.android.exoplayer2.source.sabr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException;
import com.google.android.exoplayer2.upstream.Loader.UnexpectedLoaderException;
import java.util.Collections;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/**
 * A 401 or 403 says what it refused, as a SabrProtocolError does (WVE7ZKDD #1, #2: a 403 on a rewind, with neither the request nor its
 * session's age)
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 28)
public class SabrRefusedRequestTest {
    @After
    public void tearDown() {
        SabrRefusedRequest.resetForTesting();
    }

    @Test
    public void aRefusalIsFoundByTheErrorItCameAs() {
        InvalidResponseCodeException refusal = refusal(403);

        SabrRefusedRequest.onLoadError(refusal, "media, video 313, audio 251, selected 313, session 0 (first)", 87_000);
        SabrRefusedRequest refused = SabrRefusedRequest.of(new UnexpectedLoaderException(refusal));

        assertEquals("media, video 313, audio 251, selected 313, session 0 (first)", refused.request);
        assertEquals(87_000, refused.sessionAgeMs);
        assertSame(refused, SabrRefusedRequest.of(refusal));
    }

    /**
     * Another error, or another refusal than the last one kept: none
     */
    @Test
    public void anotherErrorHasNone() {
        SabrRefusedRequest.onLoadError(refusal(401), "init, video 136, audio 251, selected 251, session 1 (refetched)", 40);

        assertNull(SabrRefusedRequest.of(refusal(401)));
        assertNull(SabrRefusedRequest.of(new IllegalStateException("offline")));
        assertNull(SabrRefusedRequest.of(null));
    }

    /**
     * Not a refusal (e.g. a 503 of the server): not kept
     */
    @Test
    public void anotherResponseCodeIsntKept() {
        InvalidResponseCodeException serverError = refusal(503);

        SabrRefusedRequest.onLoadError(serverError, "media, video 313, audio 251, selected 313, session 0 (first)", 1_000);

        assertNull(SabrRefusedRequest.of(serverError));
    }

    private static InvalidResponseCodeException refusal(int code) {
        return new InvalidResponseCodeException(code, null, Collections.emptyMap(),
                new DataSpec(Uri.parse("https://rr1---sn-test.googlevideo.com/videoplayback")));
    }
}
