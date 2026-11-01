package com.quran.mobile.feature.audiobar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.quran.labs.androidquran.common.ui.core.LocalQuranColors
import com.quran.labs.androidquran.common.ui.core.QuranIcons
import com.quran.labs.androidquran.common.ui.core.QuranTheme
import com.quran.mobile.feature.audiobar.R
import com.quran.mobile.feature.audiobar.state.AudioBarState
import com.quran.mobile.feature.audiobar.state.AudioBarUiEvent
import com.quran.recitation.events.RecitationModePresenter.EngineStatus

/**
 * QuranApp: Leiste im Rezitationsmodus.
 * [Ausblenden] [Status / Ladebalken] [Mikrofon] [Modus verlassen]
 */
@Composable
internal fun RecitationModeAudioBar(
  state: AudioBarState.RecitationMode,
  eventSink: (AudioBarUiEvent.RecitationModeEvent) -> Unit,
  modifier: Modifier = Modifier
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier.height(IntrinsicSize.Min)
  ) {
    IconButton(onClick = { eventSink(AudioBarUiEvent.RecitationModeEvent.ToggleHideAyahs) }) {
      val tint = if (state.isHidingAyahs) MaterialTheme.colorScheme.primary else LocalContentColor.current
      Icon(QuranIcons.MenuBook, contentDescription = stringResource(R.string.recitation_mode_hidden), tint = tint)
    }

    Column(
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 8.dp)
    ) {
      Text(
        text = statusText(state),
        color = LocalQuranColors.current.defaultTextColor,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      val status = state.engineStatus
      if (status is EngineStatus.Loading) {
        if (status.percent >= 0) {
          LinearProgressIndicator(
            progress = { status.percent / 100f },
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 4.dp)
          )
        } else {
          LinearProgressIndicator(
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 4.dp)
          )
        }
      }
    }

    Box(
      modifier = Modifier
        .minimumInteractiveComponentSize()
        .size(40.dp)
        .clip(CircleShape)
        .background(
          color = if (state.isListening) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
        )
        .combinedClickable(
          role = Role.Button,
          onClick = { eventSink(AudioBarUiEvent.RecitationModeEvent.ToggleListening) },
          onLongClick = { eventSink(AudioBarUiEvent.RecitationModeEvent.ToggleCorrection) },
        ),
      contentAlignment = Alignment.Center
    ) {
      if (state.isListening) {
        Icon(QuranIcons.Pause, contentDescription = "", tint = MaterialTheme.colorScheme.primary)
      } else {
        Icon(QuranIcons.Mic, contentDescription = "")
      }
    }

    IconButton(onClick = { eventSink(AudioBarUiEvent.RecitationModeEvent.ExitMode) }) {
      Icon(QuranIcons.Logout, contentDescription = "")
    }
  }
}

@Composable
private fun statusText(state: AudioBarState.RecitationMode): String {
  return when (val status = state.engineStatus) {
    is EngineStatus.Error -> stringResource(R.string.recitation_mode_error, status.message)
    is EngineStatus.Loading -> when {
      state.isListening -> stringResource(R.string.recitation_mode_waiting)
      status.percent >= 0 -> stringResource(R.string.recitation_mode_loading, status.percent)
      else -> stringResource(R.string.recitation_mode_preparing)
    }
    EngineStatus.Idle -> stringResource(R.string.recitation_mode_preparing)
    EngineStatus.Ready -> when {
      state.isListening -> stringResource(R.string.recitation_mode_listening)
      state.hasSession -> stringResource(R.string.recitation_mode_paused)
      else -> stringResource(R.string.recitation_mode_ready)
    }
  }
}

@Preview
@Composable
private fun RecitationModeAudioBarPreview() {
  QuranTheme {
    RecitationModeAudioBar(
      state = AudioBarState.RecitationMode(EngineStatus.Loading(42), false, false, false),
      eventSink = {}
    )
  }
}
