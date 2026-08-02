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
import androidx.compose.ui.text.style.TextOverflow
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
 * Which half of the server-side settings a [ProfileCard] is drawing.
 *
 * One composable with two faces rather than two composables, because both halves are backed by the
 * same GET and the same PUT — splitting the code would have meant two loads, two dirty flags and
 * two ways for them to disagree about what is saved.
 */
enum class ProfileSection { Assistant, Schedule }

/**
 * When the summaries arrive, which face the orb wears, and the character Halo plays.
 *
 * Loads once and saves the whole form on one button, rather than writing per field: these are
 * settings people adjust together, and a save per keystroke would put a model call (the
 * personality rewrite) behind every character typed.
 *
 * Choosing a *character* is the exception and saves immediately — it is a single tap with an
 * obvious outcome, and burying it behind Save would make picking Deadpool feel like filling in a
 * form. See [PersonalityPicker].
 */
@Composable
fun ProfileCard(section: ProfileSection, modifier: Modifier = Modifier) {
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

    val assistant = section == ProfileSection.Assistant
    val dirty = loaded != null && (
        morning != loaded!!.morningSummaryTime ||
            evening != loaded!!.eveningSummaryTime ||
            avatar != loaded!!.avatar ||
            personality != loaded!!.personality ||
            lat != loaded!!.lat.toString() ||
            lon != loaded!!.lon.toString()
        )

    HaloCard(modifier.fillMaxWidth()) {
        Mono(if (assistant) "WHO HALO IS" else "WHEN AND WHERE")
        Text(
            if (assistant) {
                "The face it wears and the voice it writes in."
            } else {
                "When the daily summaries arrive, and the sky the weather reminders watch."
            },
            Modifier.padding(top = 4.dp, bottom = 10.dp),
            fontSize = 13.sp,
            color = HaloPalette.navy.copy(alpha = 0.75f),
        )

        if (assistant) {
            Mono("AVATAR")
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AVATARS.forEach { (id, label) ->
                    // The face above its name — a chip reading "Halo" says nothing about what you
                    // are about to put on your screen. Stacked rather than side by side because a
                    // row of face-and-chip pairs runs out of card long before the list runs out of
                    // faces.
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AvatarFace(Expression.Idle, Modifier.size(52.dp), avatar = id)
                        HaloChip(if (id == avatar) "● $label" else label) { avatar = id }
                    }
                }
            }

            // Saves on its own, unlike everything else in this card — see the class comment.
            // `loaded` is replaced with what comes back so the free-text field below and the
            // picker cannot disagree about which one is in force.
            PersonalityPicker(
                selectedId = loaded?.personalityId,
                enabled = !busy,
                onChosen = { profile ->
                    loaded = profile
                    personality = profile.personality
                },
            )

            Mono("MY OWN WORDS")
            Text(
                "Used when no character above is chosen. Changes how Halo words things — never " +
                    "what it does.",
                Modifier.padding(top = 2.dp, bottom = 4.dp),
                fontSize = 12.sp,
                color = HaloPalette.navy.copy(alpha = 0.7f),
            )
            HaloField(
                value = personality,
                placeholder = "dry and deadpan; a cheerful golden retriever; …",
                // Capped here as well as on the server, so the limit is visible as you type
                // rather than arriving as a rejection after you have written a paragraph.
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
        } else {
            TimeField("MORNING SUMMARY", morning, !busy) { morning = it }
            TimeField("EVENING SUMMARY", evening, !busy) { evening = it }

            // Both of these already drive real behaviour — the zone decides when everything fires,
            // the coordinates decide what the weather-conditioned reminders check — and neither was
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

/**
 * The characters Halo can play: the ones the server ships with, plus the user's own.
 *
 * Selecting one writes immediately rather than waiting for Save, which is the one place this card
 * departs from its own rule. A character is a single tap with a visible outcome — the next thing
 * Halo says is in that voice — and holding it behind a Save button would make it feel like a form
 * field rather than a choice. Creating and deleting write immediately for the same reason.
 *
 * @param onChosen handed the profile the server returns, so the caller's copy stays the authority
 *   on what is selected rather than this list keeping a second opinion.
 */
@Composable
private fun PersonalityPicker(
    selectedId: String?,
    enabled: Boolean,
    onChosen: (Profile) -> Unit,
) {
    var available by remember { mutableStateOf<List<Personality>>(emptyList()) }
    var adding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newPersona by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        apiCatching { HaloApi(Config.baseUrl, Config.authToken).personalities() }
            .onSuccess { available = it }
            .onFailure { note = "Could not load the characters — ${it.message}" }
    }

    LaunchedEffect(Config.baseUrl, Config.authToken) { reload() }

    /** Every write here goes through one path, so none of them can forget to re-enable the card. */
    fun run(work: suspend () -> Unit) {
        scope.launch {
            busy = true
            note = null
            work()
            busy = false
        }
    }

    Mono("PERSONALITY")
    Text(
        "A character to play. Only wording changes — never what Halo does.",
        Modifier.padding(top = 2.dp, bottom = 6.dp),
        fontSize = 12.sp,
        color = HaloPalette.navy.copy(alpha = 0.7f),
    )

    val ready = enabled && !busy

    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        // "Default" is the absence of a selection rather than a row of its own — the server has no
        // such personality, and inventing one here would mean two ways to say the same nothing.
        PersonalityRow(
            name = "Default",
            persona = "Halo's own voice, or your own words below.",
            selected = selectedId == null,
            enabled = ready,
            onSelect = {
                run {
                    apiCatching { HaloApi(Config.baseUrl, Config.authToken).choosePersonality(null) }
                        .onSuccess(onChosen)
                        .onFailure { note = it.message ?: "Could not change the character." }
                }
            },
            onDelete = null,
        )

        available.forEach { p ->
            PersonalityRow(
                name = p.name,
                persona = p.persona,
                selected = selectedId == p.id,
                enabled = ready,
                onSelect = {
                    run {
                        apiCatching {
                            HaloApi(Config.baseUrl, Config.authToken).choosePersonality(p.id)
                        }
                            .onSuccess(onChosen)
                            .onFailure { note = it.message ?: "Could not change the character." }
                    }
                },
                // Built-ins belong to the server and the delete is refused there too; not drawing
                // it is the honest version of that rather than a second rule.
                onDelete = if (p.builtin) {
                    null
                } else {
                    {
                        run {
                            val api = HaloApi(Config.baseUrl, Config.authToken)
                            apiCatching { api.deletePersonality(p.id) }
                                .onSuccess {
                                    reload()
                                    // The server clears the selection in the same batch when the
                                    // deleted one was chosen, so the profile is re-read rather
                                    // than guessed at.
                                    apiCatching { api.profile() }.onSuccess(onChosen)
                                }
                                .onFailure { note = it.message ?: "Could not delete it." }
                        }
                    }
                },
            )
        }
    }

    if (adding) {
        HaloField(
            value = newName,
            placeholder = "name it — “Pirate”, “My boss”",
            onChange = { if (it.length <= 40) newName = it },
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            enabled = ready,
        )
        HaloField(
            value = newPersona,
            placeholder = "how it should talk",
            onChange = { if (it.length <= PERSONALITY_MAX) newPersona = it },
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            enabled = ready,
            singleLine = false,
        )
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HaloButton(
                label = "Add",
                enabled = ready && newName.isNotBlank() && newPersona.isNotBlank(),
            ) {
                run {
                    apiCatching {
                        HaloApi(Config.baseUrl, Config.authToken)
                            .addPersonality(newName.trim(), newPersona.trim())
                    }
                        .onSuccess {
                            newName = ""
                            newPersona = ""
                            adding = false
                            reload()
                        }
                        // Carries the server's own words, which for the case that actually happens
                        // — a name you already used — says exactly which name.
                        .onFailure { note = it.message ?: "Could not add it." }
                }
            }
            HaloButton(label = "Cancel", filled = false, enabled = ready) {
                adding = false
                newName = ""
                newPersona = ""
            }
        }
    } else {
        Row(Modifier.padding(bottom = 10.dp)) {
            HaloChip("+ New character", enabled = ready) { adding = true }
        }
    }

    note?.let {
        Text(
            it,
            Modifier.padding(bottom = 10.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = HaloPalette.warm,
        )
    }
}

/** One character: what it is called, how it talks, and whether it is the one in force. */
@Composable
private fun PersonalityRow(
    name: String,
    persona: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HaloChip(if (selected) "● $name" else name, enabled = enabled, onClick = onSelect)
        Text(
            persona,
            Modifier.weight(1f),
            fontSize = 11.sp,
            // One line: this is a reminder of what the name means, not the text itself, and a
            // three-line paragraph per row would bury the list it belongs to.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = HaloPalette.navy.copy(alpha = 0.65f),
        )
        onDelete?.let {
            Mono(
                "REMOVE",
                Modifier.clickable(enabled = enabled, onClick = it).padding(4.dp),
                color = HaloPalette.warm,
            )
        }
    }
}
