package dev.infyplus.halo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.infyplus.halo.ui.HaloButton
import dev.infyplus.halo.ui.HaloCard
import dev.infyplus.halo.ui.HaloChip
import dev.infyplus.halo.ui.AVATARS
import dev.infyplus.halo.ui.AvatarFace
import dev.infyplus.halo.ui.Expression
import dev.infyplus.halo.ui.HaloField
import dev.infyplus.halo.ui.HaloPalette
import dev.infyplus.halo.ui.HaloShapes
import dev.infyplus.halo.ui.Mono
import dev.infyplus.halo.ui.apiCatching
import kotlinx.coroutines.launch

/**
 * The settings that live on the server rather than on this device.
 *
 * They are server-side because they are about the *account*, not the installation: the summary
 * times decide what the backend schedules, and the personality colours text the backend writes.
 * Keeping them here means a second device sees the same Halo rather than a differently-behaved one.
 */

/** 'HH:MM' as the picker's two numbers, tolerating anything odd by falling back to [fallback]. */
private fun hhmm(value: String, fallback: Pair<Int, Int>): Pair<Int, Int> {
    val parts = value.split(":")
    val h = parts.getOrNull(0)?.toIntOrNull()
    val m = parts.getOrNull(1)?.toIntOrNull()
    return if (h != null && m != null && h in 0..23 && m in 0..59) h to m else fallback
}

private fun Pair<Int, Int>.asHhmm(): String =
    first.toString().padStart(2, '0') + ":" + second.toString().padStart(2, '0')

/**
 * A tappable field showing a time, backed by Material's own picker.
 *
 * The picker is Material's rather than hand-drawn: it already handles 12/24-hour locales, keyboard
 * entry and accessibility, none of which is worth reimplementing to match a border radius. It is
 * themed through [TimePickerDefaults] so it reads as part of Halo rather than as a system dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(label: String, value: String, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val (hour, minute) = hhmm(value, 7 to 0)

    Mono(label)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 10.dp)
            .clickable(enabled = enabled) { open = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = HaloPalette.ink)
        Mono("CHANGE", color = HaloPalette.warm)
    }

    if (open) {
        val picker = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            shape = HaloShapes.Panel,
            containerColor = HaloPalette.cream,
            title = { Mono(label) },
            text = {
                TimePicker(
                    state = picker,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = HaloPalette.body.copy(alpha = 0.35f),
                        selectorColor = HaloPalette.warm,
                        containerColor = HaloPalette.cream,
                        timeSelectorSelectedContainerColor = HaloPalette.sun.copy(alpha = 0.45f),
                        timeSelectorUnselectedContainerColor = HaloPalette.body.copy(alpha = 0.25f),
                    ),
                )
            },
            confirmButton = {
                HaloButton(label = "Set") {
                    onPick((picker.hour to picker.minute).asHhmm())
                    open = false
                }
            },
            dismissButton = { HaloButton(label = "Cancel", filled = false) { open = false } },
        )
    }
}

/**
 * When the summaries arrive, which face the orb wears, and the character Halo plays.
 *
 * Loads once and saves the whole form on one button, rather than writing per field: these are
 * settings people adjust together, and a save per keystroke would put a model call (the
 * personality rewrite) behind every character typed.
 */
@Composable
fun ProfileCard(modifier: Modifier = Modifier) {
    var loaded by remember { mutableStateOf<Profile?>(null) }
    var morning by remember { mutableStateOf("07:00") }
    var evening by remember { mutableStateOf("20:00") }
    var avatar by remember { mutableStateOf(DEFAULT_AVATAR) }
    var personality by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Config.baseUrl, Config.authToken) {
        apiCatching { HaloApi(Config.baseUrl, Config.authToken).profile() }
            .onSuccess {
                loaded = it
                morning = it.morningSummaryTime
                evening = it.eveningSummaryTime
                // An avatar we cannot draw falls back rather than showing nothing selected.
                avatar = if (AVATARS.any { a -> a.first == it.avatar }) it.avatar else DEFAULT_AVATAR
                personality = it.personality
                lat = it.lat.toString()
                lon = it.lon.toString()
            }
            .onFailure { note = "Could not load your settings — ${it.message}" }
    }

    val dirty = loaded != null && (
        morning != loaded!!.morningSummaryTime ||
            evening != loaded!!.eveningSummaryTime ||
            avatar != loaded!!.avatar ||
            personality != loaded!!.personality ||
            lat != loaded!!.lat.toString() ||
            lon != loaded!!.lon.toString()
        )

    HaloCard(modifier.fillMaxWidth()) {
        Mono("HALO ITSELF")
        Text(
            "When the daily summaries arrive, and who Halo is when it writes them.",
            Modifier.padding(top = 4.dp, bottom = 10.dp),
            fontSize = 13.sp,
            color = HaloPalette.navy.copy(alpha = 0.75f),
        )

        TimeField("MORNING SUMMARY", morning, !busy) { morning = it }
        TimeField("EVENING SUMMARY", evening, !busy) { evening = it }

        Mono("AVATAR")
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AVATARS.forEach { (id, label) ->
                // The face above its name — a chip reading "Halo" says nothing about what you are
                // about to put on your screen. Stacked rather than side by side because a row of
                // face-and-chip pairs runs out of card long before the list runs out of faces.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AvatarFace(Expression.Idle, Modifier.size(52.dp), avatar = id)
                    HaloChip(if (id == avatar) "● $label" else label) { avatar = id }
                }
            }
        }

        Mono("PERSONALITY")
        Text(
            "A character to play. Changes how Halo words things — never what it does.",
            Modifier.padding(top = 2.dp, bottom = 4.dp),
            fontSize = 12.sp,
            color = HaloPalette.navy.copy(alpha = 0.7f),
        )
        HaloField(
            value = personality,
            placeholder = "dry and deadpan; a cheerful golden retriever; …",
            // Capped here as well as on the server, so the limit is visible as you type rather
            // than arriving as a rejection after you have written a paragraph.
            onChange = { if (it.length <= PERSONALITY_MAX) personality = it },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
            enabled = !busy,
            singleLine = false,
        )
        Mono(
            "${personality.length} / $PERSONALITY_MAX",
            Modifier.padding(bottom = 12.dp),
            color = HaloPalette.navy.copy(alpha = 0.55f),
        )

        // Both of these already drive real behaviour — the zone decides when everything fires, the
        // coordinates decide what the weather-conditioned reminders check — and neither was
        // visible anywhere, so a wrong one was invisible and unfixable.
        Mono("TIME ZONE")
        Text(
            loaded?.tz.orEmpty().ifBlank { "not set" },
            Modifier.padding(top = 4.dp, bottom = 2.dp),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = HaloPalette.ink,
        )
        Mono(
            "Learned from this device — sent with every message.",
            Modifier.padding(bottom = 12.dp),
            color = HaloPalette.navy.copy(alpha = 0.6f),
        )

        Mono("LOCATION")
        Text(
            "Used only by reminders that wait on the weather.",
            Modifier.padding(top = 2.dp, bottom = 4.dp),
            fontSize = 12.sp,
            color = HaloPalette.navy.copy(alpha = 0.7f),
        )
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HaloField(
                value = lat,
                placeholder = "latitude",
                onChange = { lat = it },
                modifier = Modifier.weight(1f),
                enabled = !busy,
            )
            HaloField(
                value = lon,
                placeholder = "longitude",
                onChange = { lon = it },
                modifier = Modifier.weight(1f),
                enabled = !busy,
            )
        }

        HaloButton(
            label = if (busy) "Saving…" else "Save",
            enabled = !busy && dirty,
        ) {
            scope.launch {
                busy = true
                note = null
                apiCatching {
                    HaloApi(Config.baseUrl, Config.authToken).saveProfile(
                        ProfilePatch(
                            morningSummaryTime = morning,
                            eveningSummaryTime = evening,
                            avatar = avatar,
                            personality = personality,
                            // Null leaves the field alone, which is what an unparseable box should
                            // do — the server would reject a non-number and lose the whole save.
                            lat = lat.trim().toDoubleOrNull(),
                            lon = lon.trim().toDoubleOrNull(),
                        ),
                    )
                }
                    .onSuccess {
                        loaded = it
                        // Read the server's version back rather than trusting what was typed: the
                        // personality comes home sanitised, and showing the raw text would leave
                        // the field looking unsaved forever.
                        morning = it.morningSummaryTime
                        evening = it.eveningSummaryTime
                        avatar = it.avatar
                        personality = it.personality
                        lat = it.lat.toString()
                        lon = it.lon.toString()
                        note = "Saved."
                    }
                    .onFailure { note = it.message ?: "That didn't save." }
                busy = false
            }
        }

        note?.let {
            Text(
                it,
                Modifier.padding(top = 10.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (it == "Saved.") HaloPalette.ink else HaloPalette.warm,
            )
        }
    }
}
