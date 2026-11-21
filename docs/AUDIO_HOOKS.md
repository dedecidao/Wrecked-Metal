# Vozes do locutor: mute e ganchos (audio hooks)

Mapa de todas as falas narradas do jogo, como elas são disparadas e como usá-las como ganchos de sistema.
Código: `external/dd64/port/src/voices.c` (fork `dedecidao/Destruction-Decomp-64`, branch `feat/voices-mute`).

## Como funciona

- Toda fala passa pela **fila do locutor** (até 15 falas): `Msg_Queue` (0x80008DD0), `Announcer_Request`
  (0x800090D0), `Announcer_QueueRandom` (0x80008BB8, sorteia de uma lista), `Commentary_PickRandom` (9AF0.c) e
  `Announcer_ReplaceQueued` (930C.c). `Announcer_Update` (9418.c, chamado por `Sound_UpdateRace` a cada quadro)
  tira a próxima da fila e chama **`Sound_PlayVoice` (0x80007744, 8344.c)** com o ID da fala.
- `Sound_PlayVoice` é o **único ponto** por onde as vozes tocam. SFX usam o mesmo tipo de pedido (0x3F4) e o mesmo
  pool, mas outros canais (`xC` 0, 2-8) e IDs abaixo de 100 (`Sound_PlayAt`, `Sound_PlayCar`, `Sound_PlayContact`).
  Por isso cortar em `Sound_PlayVoice` não afeta nenhum efeito sonoro nem a música.
- **ID da fala = índice da lista VOICE do protótipo + 100** (`include/ids.h`, enum `VoiceId`). Confirmado pelo
  checkpoint: 0xED = 237 = `FX_CHECKPT1` (137). As tabelas de duração/volume são indexadas por `id - 100`.
  Os nomes do protótipo servem de pista; no retail alguns podem ter mudado (os IDs acima de 0xF4 nem existem lá).
- Com o mute, a fila, as pausas entre falas e a lógica do jogo ficam iguais; só o som não é pedido. Nenhum asset
  é apagado ou alterado (a ROM fica intacta).

## Opções

| Chave (`dd64.ini`) | Padrão | Efeito |
|---|---|---|
| `[audio] announcer_voice` | `0` (Wrecked Metal) | `0` = todas as falas mudas; `1` = como no N64. Menu R > audio > "announcer voice". No perfil **Fidelity** é forçado em `1`. |
| `[audio] voice_keep` | vazio | IDs que continuam tocando com o mute, ex.: `voice_keep = 0xED 0xD2` |
| env `DD64_VOICELOG=1` | — | Loga cada fala pedida em `dd64.log`: `voice: line 0xED (237) muted` |

API para ganchos (C, no port): `int Port_VoiceAddHook(s32 (*fn)(s32 id))`. A função é chamada para **toda** fala,
antes do mute (até 8 ganchos). Retornar `1` suprime a fala (o gancho tocou outra coisa); `0` deixa o mute decidir.

## Mapa das falas

"Nome protótipo" = nome do enum `VoiceId` (pode diferir no retail). Quando a fala ainda não foi ouvida e
confirmada, a coluna "Evento" descreve **onde** o jogo a pede. Use `DD64_VOICELOG=1` para confirmar o que cada uma diz.

### Menus e telas

| ID | Nome protótipo | Onde (função, arquivo) | Evento |
|---|---|---|---|
| 0xD2 | FX_RI02A | `TitleScreen_Init` (448B4.c) | **"Welcome to Destruction Derby"** no Press Start (só na 1ª vez; única fala permitida no modo 0x19) |
| 0xF5, 0xF6 | (além da lista) | `Intro_StepMessages` (D874.c) | Telas de introdução / mensagens iniciais |
| 0xA9 | FX_DD11 | `OptionsMenu_Left/Right` (483D4.c, 48A0C.c) | Mudar valor no menu de opções (teste do locutor/música) |
| lista por pista | — | `CourseIntro_Init` (44A4C.c) | Nome da pista na apresentação do circuito |
| 0xEA, 0xEC, 0xEB | FX_WI08, FX_WI10, FX_WI09 | `Podium_Init` (4C90C.c) | Pódio: 1º, 2º, 3º |
| 0xE9 | FX_WI06 | `Race_ProcessResult` (4DEBC.c) | Resultado do campeonato: passou de fase |
| 0xCB | FX_LS06 | `Race_ProcessResult` (4DEBC.c) | Resultado do campeonato: não passou |
| 0x82, 0x83 | FX_CF05_BLU/YEL | `Results_DrawTeamScores` (4F808.c) | Placar por equipes: azul / amarelo vence |
| 0xE3, 0xE4, 0xE5 | FX_TT05, TT06, TTAL_A | `Results_ShowLapTimes` (4E784.c) | Novo recorde (sorteia 1 de 3) |
| 0xE2 | FX_TT04 | `Results_ShowLapTimes`, `Race_OnLapCompleted` (2B4D8.c) | Perto do recorde / volta rápida |

### Largada e corrida

| ID | Nome protótipo | Onde | Evento |
|---|---|---|---|
| 0xDF | FX_ST01 | `Countdown_Update` (45884.c) | Contagem regressiva |
| 0xF7 | (além da lista) | `Race_ShowGo` (463E4.c) | "GO!" |
| 0xED | **FX_CHECKPT1** | `func_8001F000` (1FC00.c) | **Passou no checkpoint** |
| 0x86, 0x87 | FX_CH04, FX_CH05 | `Race_AddNodeDistance` (20C98.c), `Car_OnTriggerCrossed` (2C3A0.c), 20E8C.c | Gatilhos de pista / volta (sorteia 1 de 2) |
| listas | FX_TT*, FX_LS* | `Racer_UpdateLapTiming` (202F8.c) | Tempo de volta |
| 0xA0, 0xA8, 0x8D | FX_CS06, FX_DD05, FX_CL06A | `Announcer_UpdateRace` (1BF08.c) | Comentários de posição/estado da corrida |
| 0xBD-0xC0 | FX_GG06..GG10 | `Announcer_UpdateRace` | Comentários por situação (contagem de carros etc.) |
| 0xC4, 0xC5 | FX_GGAL_D/E | `Announcer_UpdateRace` | Comentário aleatório |
| listas | — | `Race_UpdateFinish` (464F4.c), `Race_WaitForOthers` (46894.c) | Chegada: colocação final (1º..12º) e fim de prova |

### Batidas e destruição

| ID | Nome protótipo | Onde | Evento |
|---|---|---|---|
| 0xF0 | FX_TIME1 (nome do protótipo) | `Car_ApplyImpactDamage` (2A618.c) | **Batida forte com bônus**: forte candidata à voz feminina "high bonus" (confirmar com `DD64_VOICELOG`) |
| 0xCD | FX_MCAL_A | `Car_ApplyImpactDamage` | Impacto grande |
| 0xB2, 0xB3, 0xB4, 0xB5 | FX_GC13..GC16 | `Car_ApplyImpactDamage` | Impactos por intensidade/tipo |
| lista (300 ms de atraso) | — | `Car_ApplyImpactDamage` (l4) | Comentário pós-batida |
| 0xB8, 0x65 | FX_GCAL_D, FX_ADLIB3 | `Car_OnCollision` (29F78.c) | Colisão entre carros |
| 0x7D, 0x7E, 0x7F | FX_CF02_BLU/YEL, CF03 | `Car_OnCollision` | Colisão por equipe |
| 0xB1, 0x9F | FX_GC12, FX_CS04 | `Car_DamagePart` (A890.c) | Peça do carro destruída (roda, capô) |
| 0x70-0x7A | FX_BT09B_*, FX_BT13_*, FX_BTAL_A | `Car_UpdateWreck` (1C624.c) | Carro destruído (wreck), por equipe |
| `LINE_TEN` etc. | — | `Port_FieldAnnounceWreck` (port/src/field.c) | Port: "carros restantes" com o grid dobrado |

### Modos especiais (Battle / Tag / Capture the Flag)

| ID | Nome protótipo | Onde | Evento |
|---|---|---|---|
| 0x6A-0x6D | FX_BT03B_01..04 | `Tag_PassTo` (265A8.c) | Tag passou para o jogador N |
| 0x6E | FX_BT05 | `Tag_PassTo` | Tag passada |
| 0x69, 0x74, 0x6F | FX_BT02, FX_BT10, FX_BT06 | 26170.c | Eventos do modo Battle |
| 0x7B, 0x7C, 0x80, 0x81 | FX_CF01_*, FX_CF04_* | 26EB8.c | Bandeira (Capture the Flag) por equipe |

## Ideias de ganchos (para depois)

- **Câmera lenta em batida forte**: gancho em 0xF0 / 0xCD / 0xB2-0xB5 dispara ~0,5 s de slow motion (escala de
  tempo do jogo) + vibração forte; quem decide a intensidade é o ID (já classificado pelo jogo).
- **SFX alternativo no checkpoint** (0xED) e na chegada (listas de `Race_UpdateFinish`): tocar um som próprio
  (`.ogg` em `Data/Sfx/`) no lugar da voz, retornando `1` no gancho.
- **Texto na tela no lugar da voz**: mostrar "CHECKPOINT", "WRECKED!", "HIGH BONUS" com a fonte do jogo
  quando a voz estiver muda (acessibilidade).
- **Contadores/estatísticas**: contar wrecks, batidas fortes e checkpoints para conquistas ou um resumo pós-corrida.
- **Voz personalizada**: packs de voz do usuário em `Data/Voices/<id>.ogg` (mesma ideia da música).
- **Vibração temática**: padrões de rumble diferentes para checkpoint, wreck e "GO!".
