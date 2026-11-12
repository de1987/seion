package org.seion.playback

import androidx.media3.common.Player
import androidx.media3.session.SessionCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Session-level rules: with float output a speed change would be a silent no-op, so controllers never see it; Seion's
 * own session commands (queue edits, sleep timer) are for its own UI only, and system controllers need none of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SeionSessionTest {
    @Test fun `the session does not advertise playback speed`() {
        val commands = SeionSession.availablePlayerCommands()
        assertFalse(commands.contains(Player.COMMAND_SET_SPEED_AND_PITCH))
        assertTrue(commands.contains(Player.COMMAND_PLAY_PAUSE))
        assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(commands.contains(Player.COMMAND_STOP))
    }

    @Test fun `Seion's own commands are granted to its UI only`() {
        val ui = SeionSession.availableSessionCommands(appUi = true)
        val other = SeionSession.availableSessionCommands(appUi = false)
        for (command in SeionCommands.all) {
            assertTrue(command.customAction, ui.contains(command))
            assertFalse(command.customAction, other.contains(command))
        }
        // The queue edits and the sleep timer are among them.
        val private = listOf(SeionCommands.REMOVE_AT, SeionCommands.RESTORE_ITEM, SeionCommands.SET_ORDER, SeionCommands.SET_SLEEP_TIMER)
        for (action in private) {
            assertTrue(action, SeionSession.isAppCommand(action))
        }
        assertFalse(SeionSession.isAppCommand(SeionCommands.EVENT_SKIPPED))
        assertFalse(SeionSession.isAppCommand("com.example.OTHER"))
    }

    @Test fun `system controllers keep the browse tree and the transport, shuffle and repeat controls`() {
        val other = SeionSession.availableSessionCommands(appUi = false)
        // Android Auto, AVRCP browsing, external search and the resumption card.
        for (code in listOf(
            SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT,
            SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN,
            SessionCommand.COMMAND_CODE_LIBRARY_GET_ITEM,
            SessionCommand.COMMAND_CODE_LIBRARY_SEARCH,
            SessionCommand.COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT
        )) {
            assertTrue("library command $code", other.contains(code))
        }
        assertEquals(SeionSession.availableSessionCommands(appUi = true).commands - SeionCommands.all.toSet(), other.commands)
        // Shuffle, repeat and the transport are player commands, which every controller has.
        val player = SeionSession.availablePlayerCommands()
        for (command in listOf(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_STOP,
            Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_SET_REPEAT_MODE
        )) {
            assertTrue("player command $command", player.contains(command))
        }
    }

    @Test fun `the media panel's buttons send no session command`() {
        val buttons = SeionSession.mediaButtons("Stop")
        assertEquals(1, buttons.size)
        for (button in buttons) {
            assertNull(button.sessionCommand)
            assertEquals(Player.COMMAND_STOP, button.playerCommand)
        }
    }
}
