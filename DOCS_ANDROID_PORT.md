# DOCS_ANDROID_PORT.md — memória do projeto (port Android)

Este arquivo guarda o que o André pediu para "memorizar", as descobertas técnicas do
port e as variáveis importantes do jogo. Atualizar a cada fase. As opções do menu e o
mapa de memória detalhado estão em [DOCS_INTERNALS_AND_OPTIONS.md](DOCS_INTERNALS_AND_OPTIONS.md).

## Tela de referência: Game Mode

O primeiro menu depois do "press start" (world championship / arcade / time trial /
multi-player, "press z-button for options menu", "press r-button for pc options").

| O quê | Onde |
|---|---|
| Modo de jogo | `MODE_MAIN` (`gGame->mode`) |
| Desenha a tela | `external/dd64/src/auto/40338.c` `MainMenu_Show` |
| Cima / baixo | `src/auto/3FD00.c` `MainMenu_Prev`, `src/auto/3FE04.c` `MainMenu_Next` |
| A (escolhe o modo) | `src/auto/3FF30.c` `MainMenu_Select` |
| Z abre as opções do jogo; entrada dos códigos de cheat | `src/auto/40244.c` `MainMenu_CheckOptions` (`Cheat_UpdateInput`) |
| Opções do jogo (music/voice/sfx/playsong/stereo), `MODE_OPTIONS` | `src/auto/47F6C.c` `OptionsMenu_Prev/Next`, `483D4.c` `_Left`, `48A0C.c` `_Right`, `4905C.c` `_Back`, `492A8.c` `_Update`, `49380.c` `_Init` |
| Menu de PC (R) | `external/dd64/port/game/pcmenu.c` (desenho e entrada), `external/dd64/port/src/options.c` (páginas e linhas), `port/src/config.c` (chaves e padrões do dd64.ini) |

## Descobertas (bugs corrigidos no Android)

| Sintoma | Causa | Correção |
|---|---|---|
| "Could not map the game's memory at 0x80000000" | Num processo 32-bit o ART põe o cache do JIT e o shadow map do linear-alloc em 0x7DA00000-0x83A00000 | `wrap.sh` (builds debug) pré-carrega `libdd64reserve.so`, que reserva a faixa antes do ART subir |
| Tela preta | `gen_widths.py` achava 20 valores estáticos em vez de 4842: o clang não gera DWARF para variáveis `extern` | `symbols/dd64_widths.c` vindo do build de PC |
| Tela preta | SDL_gpu com MSAA 4x fica preto na Adreno 740 | MSAA travado em 1x (`android/gpu_caps.c`) |
| Carregamento de minutos | TLS emulado pelo NDK abaixo do minSdk 29 (`__thread` em `port/src/shadow.c`, todo acesso à memória) | minSdk 29 |
| Tela de título nunca vinha | `inflate_codes` (`src/auto/51980.c`) compilado com clang -O2 para ARM entra em loop infinito | Esse arquivo em -O0 (causa no clang ainda aberta) |
| "Could not load ROM" depois de atualizar | Activity recriada sem o extra do intent | `GameActivity` usa `files/rom/dd64.z64` como padrão |
| **Crash com Z + baixo na tela Game Mode / opções** (fase 1) | `D_800BAD10` (tabela do menu de opções) é *definido* em `47F6C.c` e *também* posto em 0x800BAD10 pelo `dd64_syms.ld`. Com `-fvisibility=hidden` o clang acessava relativo ao PC, como se o .so estivesse carregado no endereço 0: `OptionsMenu_Next` lia 0x800BAD10 + endereço de carga (0x5AFC1D10) | Objetos do jogo com `-fvisibility=default`: os 5 globais definidos no jogo e fixados pelos syms (`D_800B7DFC`, `D_800B7F8C`, `D_800B8244`, `D_800BAD10`, `D_800BBD94`) passam pela GOT, que tem o endereço absoluto |

| **Tela preta nas classes Professional/Legend** (19/11) | Essas classes correm espelhadas (`D_800958A1`, `2CF18.c`): `View_SetupSplitScreen` (`633F0.c`) dá largura negativa à vista, `Gfx_SetViewport2` (`19DC0.c`) um viewport com escala X negativa, e o jogo inverte o culling da pista (`19F90.c`). `gfx_calc_and_set_viewport` calculava a borda esquerda como centro − escala: com escala negativa, a área 3D ia para fora da tela (x 320-640), e o culling invertido apagava as faces visíveis | `port/fast3d/gfx_pc.cpp`: área = centro ± \|escala\|, e X espelhado na transformação de cada vértice (e nos quadros interpolados), como o RSP faz antes do teste de winding. Vale também para o PC |
| **Crash ao salvar recorde no Time Trials** (19/11) | `Records_Draw` (`4A128.c`, tela de novos recordes) formata os tempos num local de 8 bytes; `Time_Format` (`EC94.c`) fazia `sprintf("%1d:%2d.%2d")` inteiro ali e só depois cortava: um tempo que não cabe (minutos ≥ 10, recorde não definido) estoura o buffer. No N64, corrupção silenciosa da pilha; no Android (stack protector do NDK) `__stack_chk_fail` → abort | `Time_Format` formata num buffer grande e copia só os `len` bytes, como o N64 deixava |

Regra geral: **um `.so` 32-bit (PIC) não é o exe de endereço fixo do PC**. Todo símbolo
do jogo num endereço N64 absoluto tem de ser acessado pela GOT, nunca relativo ao PC.
No código do port (visibilidade hidden), usar `Port_N64ToHost(endereço)` em vez de
`extern`.

## Menus no Android (fase 3)

O menu de PC (R no menu do jogo) no Android: `external/dd64/port/src/options.c` (páginas
sob `#ifdef __ANDROID__`; as do PC continuam iguais) e `port/src/config.c` (padrões
`DEF(pc, android)` e valores forçados depois de ler o dd64.ini). O submódulo
`external/dd64` agora segue a branch **`android-port`** do fork.

| Página | Linhas no Android |
|---|---|
| video | resolution (720p/1080p de saída), vsync, interpolation, 3d resolution (720p/1080p), anti-aliasing (off/2x), clean cut-outs, hd fonts, seamless sky, draw distance (x8/x16), fps counter, renderer (vulkan / opengl es) |
| sound | master volume, audio delay |
| controls | rumble, rumble strength, stick dead zone, stick range, camera dead zone, analog pedals |
| game | announce every wreck (drones, set all drones to e cpu unlockables removidos: ficam em 1x / default / off) |
| advanced (nova) | screen shape (4:3/widescreen), n64 aa blur, texture filter, hud boxes, anisotropy, skip intros |

Toda página termina em **restore defaults** (`Port_ConfigDefault`).

Padrões do Android e o porquê:

| Opção | Padrão | Motivo |
|---|---|---|
| resolution (saída) | 720p | `GameActivity.applyOutputResolution` dimensiona a surface pela altura (largura segue o formato da tela); 1080p para quem quiser nitidez |
| 3d resolution | 720p | maior custo de GPU/bateria; 1080p disponível |
| anti-aliasing | **2x** | GPUs de celular são *tile-based*: as amostras do MSAA ficam na memória do tile e 2x custa pouco. 4x fica **preto** com SDL_gpu na Adreno 740 (testado), então o máximo no Android é 2x (`android/gpu_caps.c`) |
| n64 aa blur | on (avançado) | o visual do N64; precisa de MSAA ≥ 2x |
| texture filter | bilinear (avançado) | 20/11: configuração ideal do André (fiel, lisa) |
| anisotropy | 4x (avançado) | 20/11: 16x contribuía para quedas a 55 fps |
| draw distance | x8 (x8/x16) | 20/11: x16 contribuía para quedas a 55 fps |
| hud boxes | off (avançado) | pedido |
| screen shape | widescreen (avançado), sem "stretched" | pedido |
| skip intros | on (avançado) | pedido |
| renderer | vulkan | o OpenGL ES funciona (shim de `glDepthRangef` em `gfx_opengl.cpp`) mas roda a ~3 fps na Adreno 740 |
| right stick camera | on, oculta | pedido |
| interpolation | off | decisão do André após a fase 2 |
| clean cut-outs | on | decisão do André após a fase 2 |
| seamless sky | on | decisão do André após a fase 2 |
| drones / set all drones to / cpu unlockables | 1x / default / off, removidos | decisão do André: os drones faziam carros voarem na largada das arenas |
| raphnet | oculto | não é compilado no Android |
| keyboard drives, raphnet, controller paks, páginas keys/pad, display, frame limit | ocultos | não se aplicam ao celular |

## Versão "fiel, lisa e sem bugs" (20/11)

Padrões do Android definidos pelo André: resolution 720p, 3d resolution 720p, vsync on,
anti-aliasing 2x, draw distance x8, anisotropy 4x, texture filter bilinear
(`config_version` 3 os aplica a um dd64.ini antigo).

**Áudio que não voltava depois de uma interrupção (ligação, outro app):** o mesmo bug do
Yakumo. O SDL usa um stream AAudio de baixa latência (MMAP) que o Android pode tomar do app
em segundo plano; ao voltar, o SDL tenta reiniciar o stream roubado, recebe
`AAUDIO_ERROR_DISCONNECTED` e só registra no log (a recuperação dele depende do callback de
erro do AAudio, que não dispara para um stream roubado enquanto pausado). A abertura também
pode falhar assim ("no output device ... running silent"). Correção: watchdog de reprodução
(`port/src/audio.c`, chamado a cada quadro em `events.c`; a música substituta em `music.c`):
em primeiro plano, se o dispositivo não pede som há 500 ms, o stream é fechado e aberto de
novo; se não abriu, nova tentativa em 1 s, dobrando até 30 s. `DD64_NO_AUDIO_RECOVERY`
desliga.

Ajustes dos testes no aparelho (20/11): o watchdog só age com a **janela em foco** (um seletor
de apps sobre o jogo pausa a activity mas não o loop, e ele reabria o áudio à toa); e as
aberturas de stream são **serializadas** (na partida ele via o stream ainda nulo enquanto o jogo
abria o primeiro e abria um segundo: dois streams lendo o mesmo anel, ~110 underruns/s). Ele
só tenta de novo uma abertura que falhou. A música substituta também troca o stream sob lock.

**Engasgos de 60 para ~40 fps (20/11, à tarde):** o próprio watchdog. Ele lia o relógio (`now`)
antes de pegar o lock, e a thread de áudio podia carimbar `sTakenNs` nesse intervalo: `now - last`
em u64 dava a volta para um valor enorme, um "travamento" falso, e o dispositivo era reaberto na
thread principal (dezenas de ms cada vez, dezenas de vezes por sessão, mesmo no menu parado).
Agora o tempo só conta quando `now` passou do último carimbo (o mesmo na música, `sFedNs`).
Teste no aparelho: 95 s no menu e 90 s de corrida sem nenhuma reabertura.

**Direção com setas:** `[input] touch_arrows` no dd64.ini, a mesma chave no **menu R >
controls > touch steering** (vale ao reiniciar o jogo) e na caixa do launcher
("Direção com setas (cruzeta)").

## Wrecked Metal: dois modos (20/11)

O projeto passou a se chamar **Wrecked Metal** (app, launcher, repositório
`dedecidao/Wrecked-Metal`; por algumas horas foi "Wrecked Metal 64 DX", e o nome final é
"Wrecked Metal", by Costelinha Games). O `applicationId` passou a ser `com.costelinhagames.wreckedmetal` (20/11, antes da primeira
release pública; o debug é `com.costelinhagames.wreckedmetal.debug`). O `namespace` e o pacote Java
continuam `io.github.dedecidao.dd64` (nomes JNI). Não mudar o ID de novo: viraria um app novo e
quebraria as atualizações de quem instalou.

O launcher tem dois botões:

- **PLAY WRECKED METAL** (o principal): o jogo customizado, como até aqui, com `dd64.ini`.
- **Play Fidelity** (discreto): o jogo como era no N64. `GameActivity` recebe o extra
  `profile=fidelity` e passa `--fidelity` ao nativo (`android_main.c`, e `port/src/main.c` no PC),
  que liga `gPortConfig.fidelity` antes de carregar as configurações:
  - arquivo próprio, `dd64-fidelity.ini` (um modo não muda o outro; os saves são os mesmos);
  - padrões (arquivo novo, upgrade de padrões, "restore defaults"): hud boxes on, sharp text off,
    seamless sky off, clean cut-outs off, synth 22050 Hz (`kFidelity` em `config.c`);
  - forçados (customizações do port, fora do menu): analog pedals off, right stick camera off
    (o analógico direito é os botões C), touch steering off;
  - página controls só com rumble, rumble strength, stick dead zone e stick range
    (`kControlsFidelity`/`kPagesFidelity` em `options.c`);
  - touch: analógico e os quatro botões C em losango (`TouchControlsView`);
  - vibração do celular como o Rumble Pak: cada chamada com a força e a duração do jogo, sem os
    pulsos sutis de superfície e raspão (`haptics.c`).

Testado no S23 Ultra: Fidelity numa corrida a 60 fps com as caixas do HUD, os quatro C e as
bordas escuras do N64; a página controls só com as linhas do N64; o `dd64.ini` do Wrecked Metal
intocado.

## Assinatura de release (20/11)

Chave da Costelinha Games: `wreckedmetal-release.p12` (PKCS12, RSA 4096, alias `wreckedmetal`,
válida até 2054;


A nightly publica `wreckedmetal-armeabi-v7a.apk` (assinado, o que os jogadores instalam) e
`wreckedmetal-armeabi-v7a-debug.apk`. O release é `com.costelinhagames.wreckedmetal` e o debug
`com.costelinhagames.wreckedmetal.debug`: são dois apps no aparelho.

## Vibração (fase 4)

Caminho do rumble do jogo até o motor:

| Etapa | Onde |
|---|---|
| O jogo pede rumble | `Pad_Rumble(canal, quadros, força)` (`src/auto/59D40.c`), chamado em batidas entre carros (`28C70.c`), destruição/aterrissagem (`1C624.c`, 30 quadros a 0,9 e contínuo pela força do impacto) e `2E9D0.c` (5 quadros a 0,75); zerado nos menus/pausa (`46894.c`, `476B8.c`) |
| Liga/desliga o motor (PWM: mais fraco = pulsos mais espaçados) | `Pad_Update` (`59B08.c`) → `__osMotorAccess` (`port/src/os_hw.c`) |
| Port | `Port_InputRumble`: no PC só gamepads (`port/src/input.c`, compilado no Android como `Port_InputRumbleHost`) |
| Android | `app/src/main/cpp/android/haptics.c`: gamepads como no PC (o motor do Rumble Pak via `Port_InputRumbleHost`); **sem gamepad, o vibrador do celular** (SDL haptic "VIBRATOR_SERVICE" → `VibrationEffect` com amplitude), com **perfil adaptativo**: `Pad_Rumble` do jogo é embrulhado (`59D40.c` compilado com `Pad_Rumble=Pad_RumbleGame`) |

Perfil do vibrador (pedido do André: sair da pista é comum e não pode ser forte/chato;
batidas continuam fortes):

| Evento | Como é detectado | Vibração (× rumble strength) |
|---|---|---|
| Terra/grama/zebra | `Pad_Rumble(ch, 1, 0.5/0.6)` a cada quadro (`1C624.c`) | **sutil**: 12%, pulsos de 150 ms renovados |
| Raspar na parede/carro | contatos leves (`28C70.c`, força < 0,85) repetidos em menos de 200 ms | **suave**: 25%, pulsos de 150 ms |
| Batida | contato isolado ou forte (`28C70.c`), gatilhos (`2E9D0.c`) | **forte**: 55% a 100% pela força, 35 ms por quadro pedido (90-650 ms) |
| Carro destruído | `Pad_Rumble(ch, 30, 0.9)` (`1C624.c`) | 100%, ~0,65 s |

Pulsos sutis nunca cortam uma batida em andamento.

Testado no S23 (45 s de corrida): 10 pulsos de raspão a 25%/150 ms; batidas a 55% (175 ms),
66%, 95% e 100% (350 ms).

## Áudio

Como o jogo toca som: sintetizador libultra (amostras ADPCM da ROM, mixadas pelo microcódigo
de áudio do RSP, emulado no host em `port/src/audio.c`). **A música é sequenciada** (notas
tipo MIDI tocadas pelos instrumentos dos bancos de som do jogo), não é áudio gravado.

| O quê | Onde |
|---|---|
| Taxa do sintetizador | `src/auto/5D430.c` `Audio_LoadBanks` (`cfg.x2C`): 22050 Hz no N64 |
| Início de cada música | `src/auto/90EC.c` `Res_LoadSoundBank`, recurso tipo 13: copia a sequência da ROM (`p->x4`..`p->x8`) e chama `Seq_StartWithBank`; handle em `D_8011EEE0` |
| Volume / fades da música | `Sound_SetVolume` (`66B24.c`) com a chave `D_8011EEE0`; `Sound_FadeOutAll` (`66960.c`) bit 2 = música (vozes com `sfxId == 0`) |
| Pitch das vozes | `AudioVoice_UpdatePitch` (`676B4.c`) → `alSynSetPitch` → `alResampleParam` (`7438C.c`, caso 7): razão pura, assume 22050 Hz |
| Reverb | `alFxNew` (`71F34.c`): atrasos em amostras |

**Synth quality (`[audio] synth_rate`, página sound):** 22050 (N64) ou **44100 (padrão no
Android)**. A 44100 o resampler recebe metade da razão, os atrasos do reverb dobram e o
low-pass mantém o corte: mesmo tom e mesmo reverb, sem o teto de 11 kHz do N64 (agudos
mais limpos, sem aliasing nos instrumentos transpostos para cima). Medido no S23: mesma
fundamental (531 vs 564 cruzamentos/s), mesmo nível (RMS 4471 vs 4541). O heap de áudio do
N64 (0x35B60 em 0x800D3200) só tinha 6 KB livres; a 44100 o sintetizador usa um heap próprio
de 512 KB no topo da RAM do port (`Port_SynthHeapSize`, o heap principal termina abaixo,
`5C480.c`).

**Músicas substitutas (`port/src/music.c`):** se existir
`Android/data/com.costelinhagames.wreckedmetal.debug/files/Data/Music/song_XXXXXXXX.ogg` (ou `.mp3`),
ela toca no lugar da música do jogo, em loop, num stream SDL próprio; a música original
continua rodando em silêncio (o fluxo do jogo espera por ela), e o slider de música e os
fades vão para o arquivo. Decoders: stb_vorbis 1.22 e dr_mp3 (domínio público,
`external/dd64/port/third_party`). Mono ou estéreo, qualquer taxa (o SDL converte).

As 13 músicas (id = onde a sequência começa na ROM; ordem do "playsong" das opções do jogo):

| id | Onde toca |
|---|---|
| `song_00D9A7B0` | tela de título / menus |
| `song_00DAF910`, `song_00D90F00`, `song_00DA6330`, `song_00D89010`, `song_00DB7650`, `song_00DAC320`, `song_00DB3880`, `song_00DBB960`, `song_00DA0180`, `song_00DBEF30`, `song_00D972A0`, `song_00DC4FB0` | playsong (corridas/arenas) |

**Dump para remasterizar:** `DD64_MUSICDUMP=1` no `Data/env.txt` grava cada música tocada em
`Data/MusicDump/song_XXXXXXXX.wav` (a saída do jogo enquanto aquela música é a atual; para a
música pura, use o "playsong" nas opções, onde não há efeitos). Fluxo: dump → remasterizar/
substituir → salvar como `.ogg` (Vorbis q6+, 44,1/48 kHz) com o mesmo nome em `Data/Music`.

## Menu Cheats (fase 5)

Página **cheats** no menu de PC (PC e Android), `external/dd64/port/src/options.c`
(`kCheats`, `applyCheats`), chaves `[cheats]` no dd64.ini. Usa os flags dos códigos
do próprio jogo (DOCS_INTERNALS_AND_OPTIONS.md):

| Linha | Flag | Código original | Quando vale |
|---|---|---|---|
| unlock all cars | `0x801253F8` | DUD | na hora (seleção de carro) |
| unlock all tracks | `0x801253F9` | BRAD | na hora (todas as dificuldades, e com elas as pistas do campeonato) |
| indestructible car | `0x8012540C + 0x18*j` `+0` | BAR | na próxima corrida |
| no time limit | `+1` | DAD | na próxima corrida |
| super ram | `+2` | LRLR | na próxima corrida (zonas de dano em 100: bate forte e sofre pouco) |
| heavy car | `+4` | BALD | na próxima corrida |

Os flags são reaplicados a cada leitura dos controles (`Port_InputNoteMenuPad`), porque
`Cheat_ResetAll` os zera ao trocar de modo. Desligar uma linha zera o flag uma vez; os
códigos digitados no jogo continuam funcionando. Valem para os 4 jogadores.

## Testes no celular (adb)

- `DD64_POKE=frame:end=valor,...` e `DD64_GOTO=frame:modo` (`android/touch.c`): escrever na RAM do jogo e trocar de modo, para chegar a uma tela sem jogar até ela. Tabela de modos (ROM, init de cada modo no fim da entrada anterior): 0x22 novos recordes, 0x23 digitar o nome, 0x24 recordes, 0x27 resultados do time trial.

- Script: `C:\Users\Admin\Documents\claudeProjects\DD64-android-logs\test-run.ps1`
  (`-Install`, `-Env "DD64_PRESS=...;DD64_GFXLOG=1"`, `-Wait`): abre o `GameActivity`
  direto (exportado nos builds debug), tira print e salva os logs.
- `Data/env.txt` no celular: chaves `DD64_*`, como o shell do PC define
  (`DD64_PRESS`, `DD64_BOOT`, `DD64_FAKEGAS`, `DD64_GFXLOG`, `DD64_STACKS=<ms>`,
  `DD64_PEEK=end,...`, `DD64_TEXDUMP`, `DD64_SHOT`). Vale **uma abertura só**: o app
  move o arquivo para `Data/env.used.txt` ao ler, e não aplica um `env.txt` mais antigo
  que a instalação/atualização do app (`--installed=` do `GameActivity`). Para repetir um
  teste, escreva o `env.txt` de novo antes de cada abertura.
- O SDL manda caminhos relativos (dumps) para a pasta interna `files/`: `adb shell run-as
  com.costelinhagames.wreckedmetal.debug ls files`.

## Memorizado (pedido do André)

- **Announce Every Wreck (fase 3):** padrão `OFF` por enquanto. Futuramente será
  forçado para `ON`, com SFX customizados ou efeitos visuais injetados no gancho onde a
  batida é anunciada: `external/dd64/port/src/field.c`, `Port_FieldAnnounceWreck`,
  marcado com `TODO(FUTURE_MOD)`.
