package com.wavault.app.notification

import android.app.Notification
import android.content.Context
import android.net.Uri
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.wavault.app.domain.DeletionEngine
import com.wavault.app.model.ContentType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WhatsAppNotificationParserTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val parser = WhatsAppNotificationParser()
    private val engine = DeletionEngine()

    @Test
    fun sixEntriesReplacedByDeletionMarkersProduceSixCandidates() {
        val t = 1_700_000_000_000L
        val beforeEntries = listOf(
            Msg("Juan", "uno", t + 1),
            Msg("Juan", "dos", t + 2),
            Msg("Juan", "Foto", t + 3, "image/jpeg", "content://wa/image/1"),
            Msg("Juan", "Video", t + 4, "video/mp4", "content://wa/video/1"),
            Msg("Juan", "Nota de voz", t + 5, "audio/ogg", "content://wa/audio/1"),
            Msg("Juan", "trabajo.pdf", t + 6, "application/pdf", "content://wa/doc/1")
        )
        val afterEntries = beforeEntries.mapIndexed { index, old -> Msg(old.sender, "Este mensaje fue eliminado", t + 10_000 + index) }
        val before = parser.parse(notification("UTN", true, beforeEntries, t, "group-utn"))!!
        val after = parser.parse(notification("UTN", true, afterEntries, t + 20_000, "group-utn"))!!
        assertThat(before.messages).hasSize(6)
        assertThat(before.messages.map { it.contentType }).containsExactly(ContentType.TEXT, ContentType.TEXT, ContentType.IMAGE, ContentType.VIDEO, ContentType.VOICE_NOTE, ContentType.DOCUMENT).inOrder()
        assertThat(after.messages.count { it.isDeletionMarker }).isEqualTo(6)
        val batch = engine.compare(before, after)
        assertThat(batch.candidates).hasSize(6)
        assertThat(batch.candidates.map { it.fingerprint }).containsExactlyElementsIn(before.messages.map { it.fingerprint })
    }

    @Test
    fun scalarMarkerWorksWhenMessagingStyleIsStale() {
        val t = 1_700_000_100_000L
        val old = Msg("Juan", "Hola", t)
        val beforeSbn = notification("Juan", false, listOf(old), t, "chat-juan")
        val afterSbn = notification("Juan", false, listOf(old), t + 60_000, "chat-juan")
        afterSbn.notification.extras.putCharSequence(Notification.EXTRA_TEXT, "Este mensaje fue eliminado")
        afterSbn.notification.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "Este mensaje fue eliminado")
        val before = parser.parse(beforeSbn)!!
        val after = parser.parse(afterSbn)!!
        assertThat(after.messages.single().isDeletionMarker).isFalse()
        assertThat(after.surfaces.any { it.isDeletionMarker }).isTrue()
        val batch = engine.compare(before, after)
        assertThat(batch.candidates).hasSize(1)
        assertThat(batch.candidates.single().fingerprint).isEqualTo(before.messages.single().fingerprint)
    }

    @Test
    fun shiftedNotificationWindowRecoversOnlyDeletedMessages() {
        val t = 1_700_000_200_000L
        val a = Msg("Ana", "A", t + 1); val b = Msg("Ana", "B", t + 2); val c = Msg("Ana", "C", t + 3); val d = Msg("Ana", "D", t + 4); val e = Msg("Ana", "E", t + 5)
        val before = parser.parse(notification("Ana", false, listOf(a, b, c, d, e), t, "chat-ana"))!!
        val after = parser.parse(notification("Ana", false, listOf(Msg("Ana", "Este mensaje fue eliminado", t + 50_001), Msg("Ana", "Este mensaje fue eliminado", t + 50_002), d, e), t + 50_000, "chat-ana"))!!
        val batch = engine.compare(before, after)
        assertThat(batch.candidates.map { it.fingerprint }).containsExactly(before.messages[1].fingerprint, before.messages[2].fingerprint)
    }

    @Test
    fun groupsPreserveSendersAndNeverCrossConversation() {
        val t = 1_700_000_300_000L
        val groupBefore = parser.parse(notification("UTN", true, listOf(Msg("Juan", "J", t + 1), Msg("Maria", "M", t + 2)), t, "group-utn"))!!
        val otherBefore = parser.parse(notification("Familia", true, listOf(Msg("Juan", "X", t + 1)), t, "group-family"))!!
        val groupAfter = parser.parse(notification("UTN", true, listOf(Msg("Juan", "Este mensaje fue eliminado", t + 20_000), Msg("Maria", "M", t + 2)), t + 20_000, "group-utn"))!!
        assertThat(groupBefore.messages.map { it.sender }).containsExactly("Juan", "Maria").inOrder()
        assertThat(engine.compare(groupBefore, groupAfter).candidates).hasSize(1)
        assertThat(engine.compare(otherBefore, groupAfter).candidates).isEmpty()
    }

    @Test
    fun normalNotificationContainsNoDeletionEvidence() {
        val t = 1_700_000_400_000L
        val snapshot = parser.parse(notification("Juan", false, listOf(Msg("Juan", "No borrado", t)), t, "chat-clear"))!!
        assertThat(snapshot.messages.any { it.isDeletionMarker }).isFalse()
        assertThat(snapshot.surfaces.any { it.isDeletionMarker }).isFalse()
    }

    private fun notification(title: String, group: Boolean, entries: List<Msg>, postTime: Long, tag: String): StatusBarNotification {
        val me = Person.Builder().setName("Me").build()
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(if (group) title else null).setGroupConversation(group)
        entries.forEach { item ->
            val person = item.sender?.let { Person.Builder().setName(it).build() }
            val message = NotificationCompat.MessagingStyle.Message(item.text, item.timestamp, person)
            if (item.mime != null && item.uri != null) message.setData(item.mime, Uri.parse(item.uri))
            style.addMessage(message)
        }
        val n = NotificationCompat.Builder(context, "wa-vault-test").setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(title).setStyle(style).build()
        if (!group) n.extras.putCharSequence(Notification.EXTRA_TITLE, title)
        return StatusBarNotification(
            WhatsAppNotificationParser.WHATSAPP,
            WhatsAppNotificationParser.WHATSAPP,
            10,
            tag,
            10_000,
            100,
            0,
            n,
            Process.myUserHandle(),
            postTime
        )
    }

    private data class Msg(val sender: String?, val text: String, val timestamp: Long, val mime: String? = null, val uri: String? = null)
}
