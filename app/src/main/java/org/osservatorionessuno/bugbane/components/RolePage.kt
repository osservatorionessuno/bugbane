package org.osservatorionessuno.bugbane.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.osservatorionessuno.bugbane.R
import org.osservatorionessuno.bugbane.utils.Role

/** The primary button keeps the default (user); the analyst path is a text link. */
@Composable
fun RolePage(onChoose: (Role) -> Unit) {
    SlideshowPageContent(
        page = SlideshowPageData(
            title = stringResource(R.string.slideshow_role_title),
            description = stringResource(R.string.slideshow_role_description),
            icon = Icons.Filled.PhoneAndroid,
            buttonText = stringResource(R.string.slideshow_role_user_button),
        ),
        onClickContinue = { onChoose(Role.USER) },
    ) {
        TextButton(
            onClick = { onChoose(Role.ANALYST) },
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.slideshow_role_analyst_button),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}
