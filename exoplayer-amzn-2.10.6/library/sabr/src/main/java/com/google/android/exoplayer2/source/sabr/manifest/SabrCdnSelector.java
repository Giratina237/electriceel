package com.google.android.exoplayer2.source.sabr.manifest;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.source.sabr.GoogleVideoHosts;

import java.util.ArrayList;
import java.util.List;

/**
 * Selects the host of a signed SABR URL: the one given, then the fallback host the YouTube app switches to
 * (see GoogleVideoHosts#toFallbackUrl). Never the redirector: SABR requests are POSTs, which a redirect turns into GETs.
 */
final class SabrCdnSelector {
    private final List<String> candidateUrls = new ArrayList<>();
    private int selectedIndex;

    public SabrCdnSelector(@Nullable String initialUrl) {
        if (initialUrl == null) {
            return;
        }

        candidateUrls.add(initialUrl);

        String fallbackUrl = GoogleVideoHosts.toFallbackUrl(initialUrl);

        if (fallbackUrl != null) {
            candidateUrls.add(fallbackUrl);
        }

        skipFailedNetworks();
    }

    public synchronized @Nullable String getCurrentUrl() {
        return candidateUrls.isEmpty() ? null : candidateUrls.get(selectedIndex);
    }

    /**
     * Advances after a failure on the current network. If another track already
     * advanced, a stale failure from an earlier network remains retryable.
     */
    public synchronized boolean maybeAdvance(@Nullable String failedUrl) {
        int failedIndex = findCandidateIndex(failedUrl);

        if (failedIndex < 0 || failedIndex > selectedIndex) {
            return false;
        }

        if (failedIndex < selectedIndex) {
            return true;
        }

        GoogleVideoHosts.markNetworkFailed(failedUrl);

        if (selectedIndex + 1 >= candidateUrls.size()) {
            // The last one failed too: with every host failed, the next selection starts again from the first (see
            // skipFailedNetworks). Before, the first stayed skipped for half an hour, and the next playbacks went to the one that
            // failed last (AWHPECZK).
            forgetFailuresIfAllFailed();
            return false;
        }

        selectedIndex++;
        return true;
    }

    private void skipFailedNetworks() {
        // Every host failed lately (e.g. the DASH requests' marks): the first one again
        if (forgetFailuresIfAllFailed()) {
            return;
        }

        // Keep the last candidate as a fallback even if it failed too.
        while (selectedIndex + 1 < candidateUrls.size()
                && GoogleVideoHosts.isNetworkFailed(candidateUrls.get(selectedIndex))) {
            selectedIndex++;
        }
    }

    /**
     * @return whether every host had failed: none is skipped then
     */
    private boolean forgetFailuresIfAllFailed() {
        for (String url : candidateUrls) {
            if (!GoogleVideoHosts.isNetworkFailed(url)) {
                return false;
            }
        }

        for (String url : candidateUrls) {
            GoogleVideoHosts.clearNetworkFailed(url);
        }

        return true;
    }

    private int findCandidateIndex(@Nullable String url) {
        String failedHost = GoogleVideoHosts.getHost(url);

        if (failedHost == null) {
            return -1;
        }

        for (int i = 0; i < candidateUrls.size(); i++) {
            String candidateHost = GoogleVideoHosts.getHost(candidateUrls.get(i));

            if (candidateHost != null && candidateHost.equalsIgnoreCase(failedHost)) {
                return i;
            }
        }

        return -1;
    }
}
