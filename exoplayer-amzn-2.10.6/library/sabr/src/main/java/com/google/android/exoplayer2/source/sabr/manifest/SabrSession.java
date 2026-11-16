package com.google.android.exoplayer2.source.sabr.manifest;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.source.sabr.protos.videostreaming.StreamerContext.ClientInfo;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * What a player response gives a SABR stream to be streamed with: its URL, its ustreamer config and its poToken. The server asks for
 * another one when they expire, about 6 h in (see SabrManifest#requestSessionRefresh).
 */
public final class SabrSession {
    public final String serverAbrStreamingUrl;
    public final String videoPlaybackUstreamerConfig;
    @Nullable public final String poToken;
    /**
     * Of the client the player response was asked of: another client's session doesn't go with the stream's requests
     */
    public final ClientInfo clientInfo;
    /**
     * Of the player response's formats: the stream's are to be among them
     */
    public final Set<Integer> itags;

    public SabrSession(@NonNull String serverAbrStreamingUrl, @NonNull String videoPlaybackUstreamerConfig, @Nullable String poToken,
                       @NonNull ClientInfo clientInfo, @NonNull Set<Integer> itags) {
        this.serverAbrStreamingUrl = serverAbrStreamingUrl;
        this.videoPlaybackUstreamerConfig = videoPlaybackUstreamerConfig;
        this.poToken = poToken;
        this.clientInfo = clientInfo;
        this.itags = Collections.unmodifiableSet(new HashSet<>(itags));
    }
}
