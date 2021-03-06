package fr.xephi.authme.cache;

import fr.xephi.authme.ReflectionTestUtils;
import fr.xephi.authme.TestHelper;
import fr.xephi.authme.output.MessageKey;
import fr.xephi.authme.output.Messages;
import fr.xephi.authme.settings.Settings;
import fr.xephi.authme.settings.properties.SecuritySettings;
import fr.xephi.authme.util.BukkitService;
import org.bukkit.entity.Player;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.Calendar;
import java.util.Date;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

/**
 * Test for {@link TempbanManager}.
 */
@RunWith(MockitoJUnitRunner.class)
public class TempbanManagerTest {

    private static final long DATE_TOLERANCE_MILLISECONDS = 200L;

    @Mock
    private BukkitService bukkitService;

    @Mock
    private Messages messages;

    @Test
    public void shouldAddCounts() {
        // given
        Settings settings = mockSettings(3, 60);
        TempbanManager manager = new TempbanManager(bukkitService, messages, settings);
        String address = "192.168.1.1";

        // when
        for (int i = 0; i < 2; ++i) {
            manager.increaseCount(address);
        }

        // then
        assertThat(manager.shouldTempban(address), equalTo(false));
        manager.increaseCount(address);
        assertThat(manager.shouldTempban(address), equalTo(true));
        assertThat(manager.shouldTempban("10.0.0.1"), equalTo(false));
    }

    @Test
    public void shouldIncreaseAndResetCount() {
        // given
        String address = "192.168.1.2";
        Settings settings = mockSettings(3, 60);
        TempbanManager manager = new TempbanManager(bukkitService, messages, settings);

        // when
        manager.increaseCount(address);
        manager.increaseCount(address);
        manager.increaseCount(address);

        // then
        assertThat(manager.shouldTempban(address), equalTo(true));
        assertHasCount(manager, address, 3);

        // when 2
        manager.resetCount(address);

        // then 2
        assertThat(manager.shouldTempban(address), equalTo(false));
        assertHasCount(manager, address, null);
    }

    @Test
    public void shouldNotIncreaseCountForDisabledTempban() {
        // given
        String address = "192.168.1.3";
        Settings settings = mockSettings(1, 5);
        given(settings.getProperty(SecuritySettings.TEMPBAN_ON_MAX_LOGINS)).willReturn(false);
        TempbanManager manager = new TempbanManager(bukkitService, messages, settings);

        // when
        manager.increaseCount(address);

        // then
        assertThat(manager.shouldTempban(address), equalTo(false));
        assertHasCount(manager, address, null);
    }

    @Test
    public void shouldNotCheckCountIfTempbanIsDisabled() {
        // given
        String address = "192.168.1.4";
        Settings settings = mockSettings(1, 5);
        TempbanManager manager = new TempbanManager(bukkitService, messages, settings);
        given(settings.getProperty(SecuritySettings.TEMPBAN_ON_MAX_LOGINS)).willReturn(false);

        // when
        manager.increaseCount(address);
        // assumptions
        assertThat(manager.shouldTempban(address), equalTo(true));
        assertHasCount(manager, address, 1);
        // end assumptions
        manager.reload(settings);
        boolean result = manager.shouldTempban(address);

        // then
        assertThat(result, equalTo(false));
    }

    @Test
    public void shouldNotIssueBanIfDisabled() {
        // given
        Settings settings = mockSettings(0, 0);
        given(settings.getProperty(SecuritySettings.TEMPBAN_ON_MAX_LOGINS)).willReturn(false);
        Player player = mock(Player.class);
        TempbanManager manager = new TempbanManager(bukkitService, messages, settings);

        // when
        manager.tempbanPlayer(player);

        // then
        verifyZeroInteractions(player, bukkitService);
    }

    @Test
    public void shouldBanPlayerIp() {
        // given
        Player player = mock(Player.class);
        String ip = "123.45.67.89";
        TestHelper.mockPlayerIp(player, ip);
        String banReason = "IP ban too many logins";
        given(messages.retrieveSingle(MessageKey.TEMPBAN_MAX_LOGINS)).willReturn(banReason);
        Settings settings = mockSettings(2, 100);
        TempbanManager manager = new TempbanManager(bukkitService, messages, settings);

        // when
        manager.tempbanPlayer(player);
        TestHelper.runSyncDelayedTask(bukkitService);

        // then
        verify(player).kickPlayer(banReason);
        ArgumentCaptor<Date> captor = ArgumentCaptor.forClass(Date.class);
        verify(bukkitService).banIp(eq(ip), eq(banReason), captor.capture(), eq("AuthMe"));

        // Compute the expected expiration date and check that the actual date is within the difference tolerance
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MINUTE, 100);
        long expectedExpiration = cal.getTime().getTime();
        assertThat(Math.abs(captor.getValue().getTime() - expectedExpiration), lessThan(DATE_TOLERANCE_MILLISECONDS));
    }

    @Test
    public void shouldResetCountAfterBan() {
        // given
        Player player = mock(Player.class);
        String ip = "22.44.66.88";
        TestHelper.mockPlayerIp(player, ip);
        String banReason = "kick msg";
        given(messages.retrieveSingle(MessageKey.TEMPBAN_MAX_LOGINS)).willReturn(banReason);
        Settings settings = mockSettings(10, 60);
        TempbanManager manager = new TempbanManager(bukkitService, messages, settings);
        manager.increaseCount(ip);
        manager.increaseCount(ip);
        manager.increaseCount(ip);

        // when
        manager.tempbanPlayer(player);
        TestHelper.runSyncDelayedTask(bukkitService);

        // then
        verify(player).kickPlayer(banReason);
        assertHasCount(manager, ip, null);
    }

    private static Settings mockSettings(int maxTries, int tempbanLength) {
        Settings settings = mock(Settings.class);
        given(settings.getProperty(SecuritySettings.TEMPBAN_ON_MAX_LOGINS)).willReturn(true);
        given(settings.getProperty(SecuritySettings.MAX_LOGIN_TEMPBAN)).willReturn(maxTries);
        given(settings.getProperty(SecuritySettings.TEMPBAN_LENGTH)).willReturn(tempbanLength);
        return settings;
    }

    private static void assertHasCount(TempbanManager manager, String address, Integer count) {
        @SuppressWarnings("unchecked")
        Map<String, Integer> playerCounts = (Map<String, Integer>) ReflectionTestUtils
            .getFieldValue(TempbanManager.class, manager, "ipLoginFailureCounts");
        assertThat(playerCounts.get(address), equalTo(count));
    }
}
