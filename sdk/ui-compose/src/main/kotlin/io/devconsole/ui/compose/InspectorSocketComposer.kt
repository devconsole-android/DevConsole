/**
 * @author Shakib
 * @since 04/11/26
 */
@file:Suppress("FunctionNaming", "MagicNumber")

package io.devconsole.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val MQTT_PROTOCOL = "mqtt"
private const val DEFAULT_QOS = 0
private val qosOptions = listOf("0", "1", "2")
private const val COMPOSER_TEXT_MIN_LINES = 3

/**
 * Everything the frame detail needs to show a send composer for one connection. Null [protocol]
 * (a connection the data source cannot write to) means no composer at all.
 */
internal data class SocketComposerHost(
    val connectionId: String,
    val protocol: String,
    val initialTopic: String?,
    val lastResult: InspectorCommandResult?,
    val onSend: (text: String, topic: String?, qos: Int, retained: Boolean) -> Unit,
)

/**
 * Whether Send may be tapped: a frame needs text, and an MQTT publish additionally needs a topic.
 * A WebSocket frame ignores both topic and QoS, so only the text gate applies to it.
 */
internal fun socketComposerSendEnabled(
    protocol: String,
    text: String,
    topic: String,
): Boolean = text.isNotEmpty() && (protocol != MQTT_PROTOCOL || topic.isNotBlank())

/**
 * In-frame send composer, shown in the socket frame detail for a connection the adapter reports as
 * writable. The draft is local to the open frame; only a [InspectorCommandResult.Success] clears
 * the text, so a rejected frame stays editable while the shared result flash explains why.
 */
@Composable
internal fun SocketSendComposer(
    host: SocketComposerHost,
    modifier: Modifier = Modifier,
) {
    val colors = DevConsoleTheme.colors
    val isMqtt = host.protocol == MQTT_PROTOCOL
    var text by rememberSaveable(host.connectionId) { mutableStateOf("") }
    var topic by rememberSaveable(host.connectionId) { mutableStateOf(host.initialTopic.orEmpty()) }
    var qos by rememberSaveable(host.connectionId) { mutableIntStateOf(DEFAULT_QOS) }
    var retained by rememberSaveable(host.connectionId) { mutableStateOf(false) }
    var awaitingResult by remember(host.connectionId) { mutableStateOf(false) }

    LaunchedEffect(host.lastResult) {
        if (awaitingResult) {
            if (host.lastResult is InspectorCommandResult.Success) text = ""
            awaitingResult = false
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Send a frame", color = colors.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Frame text") },
            minLines = COMPOSER_TEXT_MIN_LINES,
            modifier = Modifier.fillMaxWidth().testTag("socketComposerText"),
        )
        if (isMqtt) {
            TopicField(topic) { topic = it }
            QosSelector(qos) { qos = it }
            RetainedToggle(retained) { retained = it }
        }
        InspectorPillButton(
            label = "Send",
            onClick = {
                awaitingResult = true
                host.onSend(text, topic.takeIf { isMqtt }, qos, retained)
            },
            modifier = Modifier.fillMaxWidth().testTag("socketComposerSend"),
            enabled = socketComposerSendEnabled(host.protocol, text, topic),
        )
    }
}

@Composable
private fun TopicField(
    topic: String,
    onTopicChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = topic,
        onValueChange = onTopicChange,
        label = { Text("Topic") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("socketComposerTopic"),
    )
}

@Composable
private fun QosSelector(
    qos: Int,
    onQosChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("QoS", modifier = Modifier.weight(1f))
        InspectorSegmentedControl(
            options = qosOptions,
            selectedIndex = qos,
            onSelect = onQosChange,
            modifier = Modifier.testTag("socketComposerQos"),
        )
    }
}

@Composable
private fun RetainedToggle(
    retained: Boolean,
    onRetainedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Retained", modifier = Modifier.weight(1f))
        Switch(
            checked = retained,
            onCheckedChange = onRetainedChange,
            modifier = Modifier.testTag("socketComposerRetained"),
        )
    }
}
