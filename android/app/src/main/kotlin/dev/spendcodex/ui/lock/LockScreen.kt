package dev.spendcodex.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.spendcodex.Texts
import dev.spendcodex.coreui.AppTheme
import dev.spendcodex.coreui.LockIcon
import dev.spendcodex.coreui.OutlineButton

/**
 * What the app shows while it is closed: nothing of the journal, only a way to open it. The system's prompt comes up by
 * itself; the button asks for it again after it was dismissed.
 */
@Composable
fun LockScreen(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Column(
        modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding().testTag("lock-screen"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LockIcon(c.foreground, Modifier.size(40.dp))
        Text(Texts.APP, style = AppTheme.type.body, modifier = Modifier.padding(top = 16.dp))
        Text(Texts.LOCKED, style = AppTheme.type.secondary, modifier = Modifier.padding(top = 4.dp))
        OutlineButton(Texts.UNLOCK, onOpen, Modifier.padding(top = 32.dp).testTag("unlock"))
    }
}
