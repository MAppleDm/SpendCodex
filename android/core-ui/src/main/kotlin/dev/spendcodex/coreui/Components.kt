package dev.spendcodex.coreui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Press feedback is a flat grey wash, not a ripple. */
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    background(if (pressed && enabled) AppTheme.colors.pressed else Color.Transparent)
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(AppTheme.colors.border))
}

@Composable
fun IconAction(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    content: @Composable (Color) -> Unit,
) {
    val c = AppTheme.colors
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) c.foreground else Color.Transparent)
            .tap(enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { content(if (active) c.background else if (enabled) c.foreground else c.tertiary) }
}

@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = AppTheme.colors.foreground,
    enabled: Boolean = true,
) {
    Text(
        text,
        style = AppTheme.type.secondary.copy(color = if (enabled) color else AppTheme.colors.tertiary),
        modifier = modifier.tap(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

/** A text field that is just a line underneath. */
@Composable
fun UnderlineField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    singleLine: Boolean = true,
    mono: Boolean = false,
    secret: Boolean = false,
    error: Boolean = false,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    focusRequester: FocusRequester? = null,
    textAlign: TextAlign = TextAlign.Start,
) {
    val t = AppTheme.type
    val style = (if (mono) t.mono.copy(fontSize = 16.sp) else t.body).copy(textAlign = textAlign)
    Column(modifier) {
        Box(Modifier.padding(vertical = 8.dp)) {
            if (value.isEmpty()) {
                Text(placeholder, style = style.copy(color = AppTheme.colors.tertiary), modifier = Modifier.fillMaxWidth())
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = style,
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else 8,
                cursorBrush = SolidColor(AppTheme.colors.foreground),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, capitalization = capitalization),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardActions = KeyboardActions(onAny = { onImeAction() }),
                modifier = Modifier.fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .semantics { if (placeholder.isNotEmpty()) contentDescription = placeholder },
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (error) AppTheme.colors.error else AppTheme.colors.border))
    }
}

/** The top of a secondary page: back, the title, and room for an action on the right. */
@Composable
fun PageHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp)) {
            IconAction(onBack, CoreTexts.back) { BackIcon(it) }
            Text(title, style = AppTheme.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp).weight(1f))
            actions()
        }
        Hairline()
    }
}

/** The small grey title of a group of rows. */
@Composable
fun SectionCaption(text: String, modifier: Modifier = Modifier) {
    Text(text, style = AppTheme.type.caption, modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp))
}

/** A grey explanation under a setting. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        text, style = AppTheme.type.caption.copy(color = if (color == Color.Unspecified) AppTheme.colors.secondary else color),
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
fun StatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    extra: String? = null,
    valueColor: Color = Color.Unspecified,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    val t = AppTheme.type
    Row(
        modifier.fillMaxWidth().tap(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = t.body.copy(color = if (enabled) AppTheme.colors.foreground else AppTheme.colors.tertiary),
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = t.mono.copy(color = if (valueColor == Color.Unspecified) AppTheme.colors.foreground else valueColor),
            maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 220.dp))
        if (extra != null) {
            Spacer(Modifier.width(12.dp))
            Text(extra, style = t.monoSecondary)
        }
    }
}

/** A choice in a list: a dot marks the chosen one. */
@Composable
fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, caption: String? = null) {
    Row(
        modifier.fillMaxWidth().tap(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { this.selected = selected; role = Role.RadioButton },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = AppTheme.type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (caption != null) Text(caption, style = AppTheme.type.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Text(if (selected) "●" else "", style = AppTheme.type.body, modifier = Modifier.width(16.dp), textAlign = TextAlign.End)
    }
}

@Composable
fun DetailLine(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = AppTheme.type.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(12.dp))
        Text(value, style = AppTheme.type.mono.copy(color = if (valueColor == Color.Unspecified) AppTheme.colors.foreground else valueColor))
    }
}

enum class NoteTone { Question, Info, Error }

/** A line from the app: a question with its thin bar, a notice, an error. */
@Composable
fun FeedNote(text: String, tone: NoteTone, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = AppTheme.colors
    val color = if (tone == NoteTone.Error) c.error else c.secondary
    val click = if (onClick != null) Modifier.tap(onClick = onClick) else Modifier
    Row(modifier.fillMaxWidth().then(click).padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (tone == NoteTone.Question) {
            Box(Modifier.width(1.dp).height(20.dp).background(c.foreground))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = AppTheme.type.secondary.copy(color = if (tone == NoteTone.Question) c.foreground else color))
    }
}

/** The title of a panel that waits for the user, above the input: a thin bar and the question. */
@Composable
fun PanelTitle(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)) {
        Box(Modifier.width(1.dp).height(20.dp).background(AppTheme.colors.foreground))
        Spacer(Modifier.width(10.dp))
        Text(text, style = AppTheme.type.body)
    }
}

/** A day in the journal: "20 октября, пн". */
@Composable
fun DateRow(text: String, modifier: Modifier = Modifier, extra: String? = null) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 2.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, style = AppTheme.type.caption, modifier = Modifier.weight(1f))
        if (extra != null) Text(extra, style = AppTheme.type.caption.copy(color = AppTheme.colors.tertiary, fontFeatureSettings = "tnum"))
    }
}

/**
 * One operation in the journal: what it was on the left, with when and where under it (the time in orange, like the family's
 * diary), and the amount in its own column on the right, with how it touched the account under it when that differs.
 */
@Composable
fun LedgerRow(
    title: String,
    amount: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    time: String? = null,
    caption: String? = null,
    amountColor: Color = AppTheme.colors.foreground,
    amountCaption: String? = null,
    titleColor: Color = AppTheme.colors.foreground,
    glow: Float = 0f,
) {
    val t = AppTheme.type
    val c = AppTheme.colors
    Row(
        modifier.fillMaxWidth().tap(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = t.body.copy(color = titleColor), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (time != null || caption != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (time != null) {
                        Text(time, style = t.monoSecondary.copy(fontSize = 12.sp, lineHeight = 16.sp, color = c.time))
                        if (caption != null) Spacer(Modifier.width(6.dp))
                    }
                    if (caption != null) {
                        Text(caption, style = t.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(amount, style = t.mono.copy(color = androidx.compose.ui.graphics.lerp(amountColor, c.time, glow)), maxLines = 1)
            if (amountCaption != null) {
                Text(amountCaption, style = t.monoSecondary.copy(fontSize = 12.sp, lineHeight = 16.sp), maxLines = 1)
            }
        }
    }
}

/**
 * A word of the parsed line, above the input: "расход", "4,50 €", "Карта", "вчера". A tap opens its choices. [weak] is a
 * value the app filled in itself (a default, not something written); [problem] marks what is missing or doubtful.
 */
@Composable
fun Token(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, weak: Boolean = false, problem: Boolean = false, active: Boolean = false) {
    val c = AppTheme.colors
    val color = when {
        problem -> c.error
        weak -> c.secondary
        else -> c.foreground
    }
    Text(
        text,
        style = AppTheme.type.secondary.copy(color = if (active) c.background else color, fontFeatureSettings = "tnum"),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) c.foreground else Color.Transparent)
            .border(1.dp, if (problem) c.error else if (active) c.foreground else c.border, RoundedCornerShape(6.dp))
            .tap(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * The input of the journal: one line for "кофе 4.50", the full form beside it, and send once there is something to record.
 */
@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onForm: () -> Unit,
    canSend: Boolean,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    sendDescription: String = CoreTexts.send,
    formDescription: String = CoreTexts.form,
    textStyle: TextStyle = AppTheme.type.body,
) {
    val c = AppTheme.colors
    Column(modifier.fillMaxWidth().background(c.background)) {
        Hairline()
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                if (value.isEmpty()) Text(placeholder, style = textStyle.copy(color = c.tertiary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = textStyle,
                    maxLines = 4,
                    cursorBrush = SolidColor(c.foreground),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, capitalization = KeyboardCapitalization.None),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                    modifier = Modifier.fillMaxWidth()
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                        // a hardware keyboard: Enter records, Shift+Enter starts another line (another operation)
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.isShiftPressed) {
                                if (canSend) onSend()
                                true
                            } else false
                        }
                        .semantics { contentDescription = placeholder },
                )
            }
            IconAction(onForm, formDescription) { FormIcon(it) }
            IconAction(onSend, sendDescription, enabled = canSend, active = canSend) { SendIcon(it) }
        }
    }
}

/** A plain bordered button for the one action of an otherwise empty page (the lock, the first start). */
@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = AppTheme.colors
    Text(
        text, style = AppTheme.type.body.copy(color = if (enabled) c.foreground else c.tertiary, textAlign = TextAlign.Center),
        modifier = modifier.border(1.dp, if (enabled) c.foreground else c.border).tap(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 32.dp, vertical = 12.dp),
    )
}
