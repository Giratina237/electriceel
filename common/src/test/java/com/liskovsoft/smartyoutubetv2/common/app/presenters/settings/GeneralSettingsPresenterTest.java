package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.content.Context;

import com.liskovsoft.sharedutils.okhttp.DnsCheck;
import com.liskovsoft.sharedutils.okhttp.DnsCheck.Status;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The DNS card's rows are the server's: the protocol for a public DNS only, the servers asked as lines of text, the custom server's as
 * buttons of its protocol
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class GeneralSettingsPresenterTest {
    private PlayerTweaksData mData;
    private int mSavedType;
    private int mSavedProtocol;
    private int mSavedDataSource;
    private String mSavedPrimary;
    private String mSavedSecondary;
    private String mSavedDohUrl;

    @Before
    public void setUp() {
        mData = PlayerTweaksData.instance(getContext());
        mSavedType = mData.getPreferredDnsType();
        mSavedProtocol = mData.getPreferredDnsProtocol();
        mSavedDataSource = mData.getPlayerDataSource();
        mSavedPrimary = mData.getCustomDnsPrimary();
        mSavedSecondary = mData.getCustomDnsSecondary();
        mSavedDohUrl = mData.getCustomDohUrl();
    }

    @After
    public void tearDown() {
        mData.setPreferredDnsType(mSavedType);
        mData.setPreferredDnsProtocol(mSavedProtocol);
        mData.setPlayerDataSource(mSavedDataSource);
        mData.setCustomDnsPrimary(mSavedPrimary);
        mData.setCustomDnsSecondary(mSavedSecondary);
        mData.setCustomDohUrl(mSavedDohUrl);
    }

    /**
     * The system's DNS has its own protocol (Private DNS), and its servers are the network's: no protocol, no custom rows
     */
    @Test
    public void theSystemsDnsHasNoProtocolAndShowsItsServers() {
        for (int type : new int[] {PlayerTweaksData.DNS_TYPE_SYSTEM, PlayerTweaksData.DNS_TYPE_IPV4}) {
            showDns(type, PlayerTweaksData.DNS_PROTOCOL_DOH);

            assertEquals(Arrays.asList(str(R.string.dns_server), str(R.string.dns_primary_server), str(R.string.dns_secondary_server),
                    str(R.string.dns_check), str(R.string.dns_fix)), titles());
            assertAddressesAreText();
        }
    }

    /**
     * A public DNS: its protocol, and in place of the custom server's rows its servers, or over HTTPS its URL (the server the check
     * asks: the addresses are only where its host is)
     */
    @Test
    public void aPublicDnsShowsItsProtocolAndItsServers() {
        showDns(PlayerTweaksData.DNS_TYPE_GOOGLE, PlayerTweaksData.DNS_PROTOCOL_PLAIN);

        assertEquals(Arrays.asList(str(R.string.dns_server), str(R.string.dns_protocol),
                str(R.string.dns_primary_server) + ": 8.8.8.8", str(R.string.dns_secondary_server) + ": 8.8.4.4", checkRow(),
                str(R.string.dns_fix)), rows());
        assertAddressesAreText();
        // DNS-over-TLS is Android's Private DNS: not one of the app's
        assertEquals(Arrays.asList(str(R.string.dns_protocol_plain), str(R.string.dns_protocol_doh)),
                optionTitles(AppDialogPresenter.instance(getContext()).getCategories().get(1)));

        showDns(PlayerTweaksData.DNS_TYPE_GOOGLE, PlayerTweaksData.DNS_PROTOCOL_DOH);

        assertEquals(Arrays.asList(str(R.string.dns_server), str(R.string.dns_protocol),
                str(R.string.dns_doh_url) + ": https://dns.google/dns-query", checkRow(), str(R.string.dns_fix)), rows());
        assertTrue(AppDialogPresenter.instance(getContext()).getCategories().get(2).options.get(0).isNote());

        showDns(PlayerTweaksData.DNS_TYPE_QUAD9, PlayerTweaksData.DNS_PROTOCOL_PLAIN);

        assertTrue(rows().contains(str(R.string.dns_primary_server) + ": 9.9.9.9"));
        assertTrue(rows().contains(str(R.string.dns_secondary_server) + ": 149.112.112.112"));
    }

    /**
     * The custom server's addresses for plain DNS: its URL isn't asked
     */
    @Test
    public void theCustomPlainDnsShowsItsAddresses() {
        mData.setCustomDnsPrimary(null);
        mData.setCustomDnsSecondary("192.0.2.2");
        mData.setCustomDohUrl("https://dns.example/dns-query");

        showDns(PlayerTweaksData.DNS_TYPE_CUSTOM, PlayerTweaksData.DNS_PROTOCOL_PLAIN);

        assertEquals(Arrays.asList(str(R.string.dns_server), str(R.string.dns_protocol),
                str(R.string.dns_custom_primary) + ": " + str(R.string.dns_not_set), str(R.string.dns_custom_secondary) + ": 192.0.2.2",
                checkRow(), str(R.string.dns_fix)), rows());
    }

    /**
     * The custom server's URL for DNS-over-HTTPS: its addresses aren't asked
     */
    @Test
    public void theCustomDohShowsItsUrl() {
        mData.setCustomDnsPrimary("192.0.2.1");
        mData.setCustomDohUrl("https://dns.example/dns-query");

        showDns(PlayerTweaksData.DNS_TYPE_CUSTOM, PlayerTweaksData.DNS_PROTOCOL_DOH);

        assertEquals(Arrays.asList(str(R.string.dns_server), str(R.string.dns_protocol),
                str(R.string.dns_custom_doh_url) + ": https://dns.example/dns-query", checkRow(), str(R.string.dns_fix)), rows());
    }

    /**
     * A server chosen: the card is built again in its place, with the server's rows, at the server's row
     */
    @Test
    public void aServerChosenBuildsTheCardAgainInPlace() {
        showDns(PlayerTweaksData.DNS_TYPE_SYSTEM, PlayerTweaksData.DNS_PROTOCOL_PLAIN);
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        OptionItem google = optionOf(dialog.getCategories().get(0), R.string.dns_google);

        google.onSelect(true);

        assertEquals(PlayerTweaksData.DNS_TYPE_GOOGLE, mData.getPreferredDnsType());
        assertTrue(rows().contains(str(R.string.dns_primary_server) + ": 8.8.8.8"));
        assertTrue(dialog.isReplaceEnabled());
        assertTrue(dialog.getCategories().get(0).options.contains(dialog.getFocusedItem()));
        assertEquals(str(R.string.settings_system), dialog.getTitlePrefix());
    }

    /**
     * A protocol chosen for the custom server: its rows are the protocol's, at the protocol's row
     */
    @Test
    public void aProtocolChosenShowsTheCustomServersRowsOfIt() {
        mData.setCustomDohUrl(null);
        showDns(PlayerTweaksData.DNS_TYPE_CUSTOM, PlayerTweaksData.DNS_PROTOCOL_PLAIN);
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        OptionCategory protocols = dialog.getCategories().get(1);

        optionOf(protocols, R.string.dns_protocol_doh).onSelect(true);

        assertEquals(PlayerTweaksData.DNS_PROTOCOL_DOH, mData.getPreferredDnsProtocol());
        assertTrue(rows().contains(str(R.string.dns_custom_doh_url) + ": " + str(R.string.dns_not_set)));
        assertTrue(dialog.isReplaceEnabled());
        assertTrue(dialog.getCategories().get(1).options.contains(dialog.getFocusedItem()));
    }

    /**
     * Each server's line in the button: its name (the system's DNS by its own, the custom server not set says so), then its status,
     * after a green check or a red cross once it's known
     */
    @Test
    public void theCheckHasALineForEachServer() throws Exception {
        mData.setPreferredDnsType(PlayerTweaksData.DNS_TYPE_GOOGLE);
        GeneralSettingsPresenter presenter = GeneralSettingsPresenter.instance(getContext());

        assertEquals("8.8.8.8: " + str(R.string.dns_check_checking), presenter.getDnsCheckLine("8.8.8.8", null).toString());
        assertEquals("✓ 8.8.8.8: 24 ms (142.250.72.14)",
                presenter.getDnsCheckLine("8.8.8.8", result(Status.WORKS, InetAddress.getByName("142.250.72.14"), null)).toString());
        assertEquals("✗ 8.8.4.4: " + str(R.string.dns_check_unreachable),
                presenter.getDnsCheckLine("8.8.4.4", result(Status.UNREACHABLE, null, null)).toString());

        mData.setPreferredDnsType(PlayerTweaksData.DNS_TYPE_SYSTEM);
        assertEquals(str(R.string.dns_system) + ": " + str(R.string.dns_check_checking), presenter.getDnsCheckLine(null, null).toString());

        mData.setPreferredDnsType(PlayerTweaksData.DNS_TYPE_CUSTOM);
        assertEquals(str(R.string.dns_check_custom_not_set) + ": " + str(R.string.dns_check_checking),
                presenter.getDnsCheckLine(null, null).toString());
    }

    /**
     * What each answer, or its absence, says: the address got, how long it took, and the system's DNS waited for longer
     */
    @Test
    public void eachResultIsDescribed() throws Exception {
        GeneralSettingsPresenter presenter = GeneralSettingsPresenter.instance(getContext());
        InetAddress youtube = InetAddress.getByName("142.250.72.14");

        assertEquals("24 ms (142.250.72.14)", presenter.describeDnsCheck(result(Status.WORKS, youtube, null), false));
        assertEquals("blocks YouTube (0.0.0.0)",
                presenter.describeDnsCheck(result(Status.BLOCKED, InetAddress.getByName("0.0.0.0"), null), false));
        assertEquals("blocks YouTube (no address)", presenter.describeDnsCheck(result(Status.BLOCKED, null, "NXDOMAIN"), false));
        assertEquals("no answer in 5 s", presenter.describeDnsCheck(result(Status.TIMEOUT, null, null), false));
        assertEquals("no answer in 10 s", presenter.describeDnsCheck(result(Status.TIMEOUT, null, null), true));
        assertEquals("unreachable from this network", presenter.describeDnsCheck(result(Status.UNREACHABLE, null, null), false));
        assertEquals("failed (SERVFAIL)", presenter.describeDnsCheck(result(Status.FAILED, null, "SERVFAIL"), false));
    }

    private static DnsCheck.Result result(Status status, InetAddress address, String detail) {
        return new DnsCheck.Result(status, address, 24, detail);
    }

    private static String checkRow() {
        return str(R.string.dns_check) + ": " + str(R.string.dns_check_desc, DnsCheck.HOSTNAME);
    }

    private void showDns(int type, int protocol) {
        mData.setPreferredDnsType(type);
        mData.setPreferredDnsProtocol(protocol);
        GeneralSettingsPresenter.instance(getContext()).showDns();
    }

    /**
     * Each row's title
     */
    private List<String> titles() {
        List<String> result = new ArrayList<>();

        for (OptionCategory category : AppDialogPresenter.instance(getContext()).getCategories()) {
            result.add(titleOf(category));
        }

        return result;
    }

    /**
     * Each row's title, and a button's value after it
     */
    private List<String> rows() {
        List<String> result = new ArrayList<>();

        for (OptionCategory category : AppDialogPresenter.instance(getContext()).getCategories()) {
            CharSequence value = category.type == OptionCategory.TYPE_SINGLE_BUTTON ? category.options.get(0).getDescription() : null;
            result.add(titleOf(category) + (value != null ? ": " + value : ""));
        }

        return result;
    }

    /**
     * A server's address is a line of text: it can't be pressed
     */
    private void assertAddressesAreText() {
        for (OptionCategory category : AppDialogPresenter.instance(getContext()).getCategories()) {
            String title = titleOf(category);

            if (title.equals(str(R.string.dns_primary_server)) || title.equals(str(R.string.dns_secondary_server))) {
                assertTrue(title, category.options.get(0).isNote());
            }
        }
    }

    private static String titleOf(OptionCategory category) {
        return String.valueOf(category.title != null ? category.title : category.options.get(0).getTitle());
    }

    private static List<String> optionTitles(OptionCategory category) {
        List<String> result = new ArrayList<>();

        for (OptionItem option : category.options) {
            result.add(String.valueOf(option.getTitle()));
        }

        return result;
    }

    private OptionItem optionOf(OptionCategory category, int titleResId) {
        for (OptionItem option : category.options) {
            if (str(titleResId).contentEquals(option.getTitle())) {
                return option;
            }
        }

        throw new AssertionError("No " + str(titleResId) + " in " + category.title);
    }

    private static String str(int resId, Object... args) {
        // Formatted only with arguments: as the app gets it
        return args.length > 0 ? getContext().getString(resId, args) : getContext().getString(resId);
    }

    private static Context getContext() {
        return RuntimeEnvironment.getApplication();
    }
}
