package dev.spendcodex.ui.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.spendcodex.Format
import dev.spendcodex.Texts
import dev.spendcodex.coreui.AppTheme
import dev.spendcodex.data.settings.Lang
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
internal fun DayDivider(day: FeedItem.Day, today: LocalDate, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(50)
    Column(
        modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            Format.feedDate(day.date, today),
            style = AppTheme.type.caption.copy(color = colors.foreground),
            modifier = Modifier.clip(shape).background(colors.pressed)
                .clickable(onClickLabel = Texts.JUMP_TO_DATE, role = Role.Button, onClick = onClick)
                .testTag(day.key).padding(horizontal = 14.dp, vertical = 6.dp),
        )
        if (day.empty) {
            Text(
                Texts.NO_ENTRIES_FOR_DAY,
                style = AppTheme.type.caption,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JournalCalendar(
    anchor: LocalDate,
    earliestYear: Int,
    latest: LocalDate,
    onDismiss: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    val colors = AppTheme.colors
    val locale = Lang.current.locale
    val state = remember(anchor, earliestYear, latest, locale) {
        val maxMillis = latest.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        DatePickerState(
            locale = locale,
            initialDisplayedMonthMillis = anchor.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            yearRange = earliestYear..latest.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= maxMillis
            },
        )
    }
    val selected = state.selectedDateMillis
    LaunchedEffect(selected) {
        if (selected != null) onSelect(LocalDate.ofEpochDay(Math.floorDiv(selected, 86_400_000L)))
    }
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 420.dp).testTag("journal-calendar"),
            color = colors.background,
            shape = RoundedCornerShape(24.dp),
        ) {
            DatePicker(
                state = state,
                modifier = Modifier.sizeIn(maxHeight = 620.dp).verticalScroll(rememberScrollState()),
                colors = DatePickerDefaults.colors(containerColor = colors.background),
                title = null,
                headline = {
                    Text(Texts.JUMP_TO_DATE, style = AppTheme.type.body, modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp))
                },
                showModeToggle = false,
            )
        }
    }
}
