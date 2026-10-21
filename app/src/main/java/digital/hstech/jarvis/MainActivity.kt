package digital.hstech.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.HearingDisabled
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import digital.hstech.jarvis.ui.ArcBlue
import digital.hstech.jarvis.ui.JarvisTheme

class MainActivity : ComponentActivity() {

    private val vm: JarvisViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Avisos no celular sempre ativos: ponte MQTT em 2º plano (independe do microfone).
        BridgeService.start(this)
        garantirIsencaoBateria()
        setContent {
            JarvisTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    JarvisScreen(vm)
                }
            }
        }
    }

    /**
     * Sem a isenção da otimização de bateria, o Android 15 encerra o BridgeService ao
     * fechar o app e os avisos param de chegar. Pedimos a isenção **uma vez** (diálogo
     * do sistema); se já estiver isento, não incomoda.
     */
    private fun garantirIsencaoBateria() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(packageName) == true) return
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        if (prefs.getBoolean("pediu_bateria", false)) return
        prefs.edit().putBoolean("pediu_bateria", true).apply()
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName")),
            )
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        AppState.foreground = true // app na frente: o BridgeService suprime avisos redundantes
    }

    override fun onPause() {
        AppState.foreground = false
        super.onPause()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JarvisScreen(vm: JarvisViewModel) {
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.toggleHandsFree() }

    val context = androidx.compose.ui.platform.LocalContext.current

    // Pede a permissão de notificações no 1º uso, para os avisos do BridgeService
    // (etapa/conclusão/confirmação) aparecerem mesmo com o app fechado.
    val postNotif = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            postNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun onMicClick() {
        if (vm.handsFree || vm.isListening) {
            vm.toggleHandsFree()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.toggleHandsFree()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // ----- Palavra de ativação "Jarvis" (serviço sempre ativo) ------------- #
    var wakeOn by remember { mutableStateOf(WakeWordService.running) }
    // Só leva o Senhor às Configurações de overlay uma vez por sessão, sem insistir.
    var overlayPedido by remember { mutableStateOf(false) }

    // "Aparecer sobre outros apps": necessária para abrir apps com a tela
    // bloqueada / a partir do serviço em segundo plano.
    fun garantirOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Toast.makeText(
                context,
                "Para abrir apps com a tela bloqueada, conceda \"Aparecer sobre outros apps\".",
                Toast.LENGTH_LONG,
            ).show()
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {}
        }
    }

    fun iniciarEscuta() {
        WakeWordService.start(context)
        wakeOn = true
        Toast.makeText(context, "Escuta ativada — diga \"Jarvis\".", Toast.LENGTH_SHORT).show()
        if (!overlayPedido) { overlayPedido = true; garantirOverlay() }
    }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { iniciarEscuta() } // inicia mesmo se negada (a notificação pode ficar oculta)

    fun comGarantiaDeNotificacao() {
        val precisa = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        if (precisa) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else iniciarEscuta()
    }

    val wakeMicPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) comGarantiaDeNotificacao() }

    fun onWakeClick() {
        if (wakeOn) {
            WakeWordService.stop(context)
            wakeOn = false
            return
        }
        val micOk = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (micOk) comGarantiaDeNotificacao()
        else wakeMicPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Rola para a última mensagem sempre que a conversa muda.
    androidx.compose.runtime.LaunchedEffect(vm.messages.size, vm.isThinking) {
        val target = vm.messages.size // +1 conceitual do indicador de "pensando"
        if (target > 0) listState.animateScrollToItem(target)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("J.A.R.V.I.S.") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = ArcBlue,
                ),
                actions = {
                    // Martelo do THOR: aceso = conectado a mim (o PC); apagado = fora do ar.
                    if (vm.pcDisponivel) {
                        IconButton(onClick = {
                            Toast.makeText(
                                context,
                                if (vm.pcConectado) "Conectado ao computador (THOR), Senhor."
                                else "Computador (THOR) fora do ar — preciso dele para responder.",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Gavel,
                                contentDescription = if (vm.pcConectado) "Conectado ao PC (THOR)" else "PC (THOR) desconectado",
                                tint = if (vm.pcConectado) ArcBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { vm.toggleIdioma() }) {
                        Icon(
                            imageVector = Icons.Filled.Translate,
                            contentDescription = if (vm.idiomaIngles) "Responder em português" else "Responder em inglês",
                            tint = if (vm.idiomaIngles) ArcBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onWakeClick() }) {
                        Icon(
                            imageVector = if (wakeOn) Icons.Filled.Hearing else Icons.Filled.HearingDisabled,
                            contentDescription = if (wakeOn) "Desligar escuta \"Jarvis\"" else "Ativar escuta \"Jarvis\"",
                            tint = if (wakeOn) ArcBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { vm.toggleMute() }) {
                        Icon(
                            imageVector = if (vm.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (vm.muted) "Ativar voz" else "Silenciar voz",
                            tint = if (vm.muted) MaterialTheme.colorScheme.onSurfaceVariant else ArcBlue,
                        )
                    }
                    IconButton(onClick = { vm.clearConversation() }) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Limpar conversa",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            vm.status?.let { status ->
                Text(
                    text = status,
                    color = ArcBlue,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(vm.messages) { msg -> MessageBubble(msg) }
                if (vm.isThinking) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = ArcBlue,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "J.A.R.V.I.S. está processando…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            InputBar(
                value = input,
                onValueChange = { input = it },
                onSend = {
                    val text = input
                    input = ""
                    vm.send(text)
                },
                onMic = ::onMicClick,
                listening = vm.isListening || vm.handsFree,
                micAvailable = vm.recognitionAvailable,
            )
        }
    }
}

@Composable
private fun MessageBubble(msg: Message) {
    val fromUser = msg.role == Role.USER
    val bubbleColor = if (fromUser) ArcBlue else MaterialTheme.colorScheme.surface
    val textColor = if (fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    color = bubbleColor,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (fromUser) 16.dp else 4.dp,
                        bottomEnd = if (fromUser) 4.dp else 16.dp,
                    ),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(text = msg.text, color = textColor)
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    listening: Boolean,
    micAvailable: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Diga ou escreva, Senhor…") },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (value.isNotBlank()) onSend() }),
            )
            Spacer(Modifier.width(8.dp))
            if (micAvailable) {
                IconButton(onClick = onMic) {
                    Icon(
                        imageVector = if (listening) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = if (listening) "Parar microfone" else "Falar",
                        tint = if (listening) ArcBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FloatingActionButton(
                onClick = { if (value.isNotBlank()) onSend() },
                modifier = Modifier.size(48.dp),
                containerColor = ArcBlue,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Enviar",
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}
