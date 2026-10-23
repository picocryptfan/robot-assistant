// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.pref

import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.ui.theme.str
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.heyrobot.ui.theme.RobotTokens

// MAIN PROCESS. The settings dialogs (Material AlertDialogs): a single choice as radio rows that closes on a pick, one
// text field with Save and Cancel, and a confirmation whose button names the action ("Delete", never "OK").

/** Radio rows, 56 dp each, one of them [selected]; a pick closes the dialog ([onPick]); Cancel leaves it as it was. */
@Composable
fun <T> RadioDialog(
    title: String,
    selected: T,
    entries: List<T>,
    label: (T) -> String,
    detail: (T) -> String? = { null },
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                for (e in entries) {
                    val on = e == selected
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = RobotTokens.choiceRowHeight)
                            .clip(MaterialTheme.shapes.small)
                            .selectable(selected = on, role = Role.RadioButton) { onPick(e) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = on, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(label(e), style = MaterialTheme.typography.bodyLarge)
                            detail(e)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(UiStrings.cancel().str()) } },
    )
}

/**
 * One text field ([initial] to start with), its [supporting] line under it (or [hint]'s, which follows the text), or the
 * error [validate] gives, which also keeps [confirm] off, as does [canSave] (an empty field, say, needs no error). At
 * most [maxLength] characters. [onSave] gets the text trimmed.
 */
@Composable
fun EditDialog(
    title: String,
    initial: String,
    fieldLabel: String,
    supporting: String,
    validate: (String) -> String? = { null },
    confirm: String = UiStrings.save().str(),
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    hint: ((String) -> String?)? = null,
    canSave: (String) -> Boolean = { true },
    maxLength: Int = Int.MAX_VALUE,
    /** 1.0: what is typed may be private (a memory item): the dialog is secure whatever its screen is. */
    secure: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val error = validate(text)
    val ok = error == null && canSave(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = if (secure) app.heyrobot.ui.common.SecureDialog else androidx.compose.ui.window.DialogProperties(),
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(maxLength) },
                label = { Text(fieldLabel) },
                supportingText = { Text(error ?: hint?.invoke(text) ?: supporting) },
                isError = error != null,
                singleLine = true,
                keyboardOptions = keyboard.copy(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ok) onSave(text.trim()) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }, enabled = ok) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(UiStrings.cancel().str()) } },
    )
}

/** A question ("Delete all conversations?"), what follows, and a button that names the action ([confirm]). */
@Composable
fun ConfirmDialog(title: String, text: String?, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(UiStrings.cancel().str()) } },
    )
}
