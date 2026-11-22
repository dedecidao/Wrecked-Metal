# DOCS_INTERNALS_AND_OPTIONS.md — opções do port e mapa de memória do jogo

Fase 2 da missão de melhorias mobile. Tudo aqui foi tirado do código-fonte
(`external/dd64`, branch `port-pc` da decompilação) e conferido no Galaxy S23 Ultra.
Cada opção do menu de PC (botão R no menu do jogo) corresponde a uma chave do
`dd64.ini` (`external/dd64/port/src/config.c`) e a uma linha em
`external/dd64/port/src/options.c`.

---

## 1. As opções e o impacto real no celular

### Interpolation (`[video] interpolation`)

O jogo roda **sempre a 60 quadros por segundo** (a lógica do N64). Quando a tela mostra
quadros num ritmo diferente de 60 (uma tela de 120 Hz, ou um limite de 30/40 fps),
o port pode **desenhar quadros intermediários**:

| Valor | Nome no menu | O que faz | Custo |
|---|---|---|---|
| 0 | off | Mostra só os quadros do jogo. Numa tela de 120 Hz cada quadro aparece 2 vezes | Nenhum |
| 1 | smooth (padrão) | Desenha quadros *entre* os dois últimos do jogo: movimento mais liso, mas **até 1 quadro (~16 ms) de atraso** | GPU desenha 2x por quadro de jogo a 120 Hz |
| 2 | low latency | Desenha quadros *adiante* do último (extrapola): liso e **sem atraso extra**, com pequenos erros em mudanças bruscas | Mesmo custo do 1 |

A interpolação **só liga quando a taxa da tela é diferente de 60** (`video.c`
`updateInterp`: diferença maior que 1,5 Hz). No S23 o jogo apresentou a 60 Hz nos testes
(contador "60"), então hoje ela fica desligada sozinha. Se o Android der 120 Hz ao jogo,
ela passa a dobrar o trabalho da GPU (mais bateria e calor) em troca de movimento mais
liso.
**Recomendação para mobile:** `off` ou `smooth`. Se a tela for 120 Hz e a bateria importar,
`off`; para o visual mais liso, `smooth`. Evitar `low latency` em touch (o ganho de
latência é pequeno perto da latência do próprio touch).

### Clean cut-outs (`[video] edge_fill`)

Texturas "recortadas" (árvores, grades, placas) têm texels transparentes, quase sempre
**pretos**. Com filtro bilinear, a cor preta "vaza" para a borda do desenho e as árvores
ficam com **contorno escuro** (o N64 também mostrava isso). Ligado, o port pinta cada
texel transparente com a média dos vizinhos visíveis, em duas passadas, ao carregar a
textura (`gfx_pc.cpp`), e as bordas ficam limpas. A transparência não muda.
**Impacto no celular:** custo só na hora de carregar cada textura (CPU, uma vez); zero
por quadro. Em tela pequena o contorno quase não aparece.
**Recomendação:** `on` (melhora o visual sem custo por quadro). Desligado = fiel ao N64.

### CRT filter (`[video] crt`, `crt_curvature`)

Off by default, in both profiles (video page: "crt filter", "crt curvature"). The frame is
box-filtered down to the N64's 240 lines, then each line is drawn as a gaussian beam with dark
gaps between them (classic horizontal scanlines), a little wider where bright (light bloom),
plus a soft glow; `crt_curvature = 1` bulges the picture like a tube. Adapted from the
public-domain crt-lottes in libretro's glsl-shaders; engine code in `port/fast3d/shaders/crt*.frag`
and `gfx_sdlgpu.cpp` / `gfx_opengl.cpp`. With `crt = 1` the launcher keeps the surface at the
screen's full resolution (so the lines fall on real pixels); toggling it in game applies at once,
the resolution change at the next start. Testing: `DD64_CRT="scan,pix,bloom,mask,boost"`
(default `-10,-4,0.4,0,1.4`; mask > 0 brings back the aperture grille).

### Seamless sky (`[video] seamless_sky`)

O céu do jogo é montado com **vários pedaços de textura**. Com filtro bilinear, cada
pedaço é filtrado sozinho e aparecem **linhas (costuras) nas nuvens**. Ligado, o port
filtra os pedaços como se fossem uma imagem só, sem costuras.
**Impacto no celular:** praticamente nenhum (o céu é poucos polígonos).
**Recomendação:** `on` (já é o padrão).

### Raphnet adapter (`[input] raphnet`)

Lê **adaptadores USB raphnet-tech de controle de N64 original** em modo bruto:
valores reais do analógico, sem dead zone, e o Rumble Pak do controle de verdade
(`port/src/raphnet.c`, via hidapi/USB).
**No Android:** o arquivo `raphnet.c` **nem é compilado** (substituído por um stub em
`app/src/main/cpp/android/stubs.c`). A opção não faz nada no celular.
**Recomendação:** esconder no Android (feito na fase 3).

### Drones (`[game] drones`) e Set all drones to (`[game] drone_car`)

**Drones** multiplica os carros do computador: `1x` = como o N64 (12 carros); `2x` a
`10x` = até 120 carros, quanto couber na memória do jogo (`port/src/field.c`). Os
carros extras ("drones") usam slots próprios nas tabelas do jogo
(`port/game/field_part*.c`).
**Set all drones to** escolhe o carro que *todos os drones* dirigem: `default` (os dos
próprios carros do computador), `car 1..12` ou `bonus car 1..12` (os carros
desbloqueáveis). Só os drones; nunca o carro de um jogador (aí vai o "gêmeo").

**Sobre os carros voando:** no seu `dd64.ini` estava `drones = 2` (24 carros) e
`drone_car = car 1`. Numa **arena** há só 12 lugares de largada; o port coloca cada
drone **7 unidades atrás** do lugar `n % 12` (`src/auto/27590.c`, `Race_PlaceCarsOnGrid`
sob `TARGET_PC`). Nas arenas em forma de bacia, "atrás" pode cair **dentro do talude ou
de outro carro**, e a física empurra o carro para fora com força: ele **voa**. Com
`drones = 1x` isso não acontece. (Batidas fortes também lançam carros no jogo original,
mas não com a frequência que os drones causam na largada.)
**Impacto no celular:** cada drone custa CPU (física/IA) e GPU (mais um modelo). O S23
aguenta 2x com folga; 4x+ deve derrubar quadros.
**Recomendação:** padrão `1x` (fiel e estável); deixar 2x-4x como opção "divertida".

### CPU unlockables (`[game] cpu_unlockables`)

Ligado, cada carro do computador tem **50% de chance** de usar o seu "gêmeo"
desbloqueável (carro + 12) em vez do normal (`field.c`, `Port_FieldCarType`). Só muda
os adversários; não desbloqueia nada para o jogador.
**Recomendação:** `off` por padrão (fiel ao N64), opção para variedade.

### Outras opções citadas na fase 3 (resumo)

| Opção | O que faz | Nota mobile |
|---|---|---|
| n64 aa blur (`n64_aa`) | Resolve o MSAA como o N64 (mais suave, "borrado") | **Precisa de MSAA > 1x**; no Android o MSAA está travado em 1x (Adreno 740 ficava preto com 4x), então hoje não tem efeito |
| texture filter | nearest (pixelado), bilinear (suave, padrão do PC), n64 3-point (o filtro real do N64, 3 amostras) | 3-point é o mais fiel ao N64 e custa quase o mesmo que bilinear no shader |
| anisotropy | Filtro anisotrópico (texturas do chão nítidas em ângulo) | Barato em GPUs mobile modernas; 8x-16x |
| draw distance | Cenário desenhado N vezes mais longe (e a névoa junto) | Custo baixo (geometria do N64 é leve) |
| 3d resolution | Linhas do desenho 3D (240 = N64, 720, 1080, ou "window" = a tela) | Maior impacto em GPU/bateria |
| hud boxes | Cápsulas escuras atrás do placar | Só visual |
| announce every wreck | Com drones, cada batida acima de 10 carros recebe a frase do locutor ("that's another one down") | Ver `TODO(FUTURE_MOD)` |

---

## 2. Mapa de memória e internals

Endereços são da RAM do N64 (no port ficam no mesmo endereço, a partir de 0x80000000;
no Android, use `Port_N64ToHost(addr)` no código do port, nunca `extern` com
visibilidade hidden — ver DOCS_ANDROID_PORT.md). Tipos/offsets confirmados no código.

### Desbloqueios e cheats

| Endereço / símbolo | Tipo | Significado | Onde |
|---|---|---|---|
| `0x801253F8` `D_801253F8` | u8 | **Todos os carros liberados** (cheat "DUD") | lido na seleção de carro: `3F244.c`, `42A44.c` (`LOCKED`), `43114.c`, `435F4.c`, `43988.c`, `43B2C.c` |
| `0x801253F9` `D_801253F9` | u8 | **Todas as dificuldades (e pistas do campeonato)** liberadas (cheat "BRAD"): força o nível 3 | `3FF30.c` `MainMenu_Select`, `404DC.c`/`405F8.c` `DiffMenu_*`, `40808.c` `DiffMenu_Show` |
| `0x80097A0C` `D_80097A0C[24]` | u8[] | Carro *n* desbloqueado pelo save (12-23 = carros bônus) | mesmos arquivos acima |
| `0x80097A14` `D_80097A14` | s32 | Maior dificuldade/classe liberada no save (0-3) | `3FF30.c`, `404DC.c`, `40808.c` |
| `0x8012540C` `D_8012540C[4]` (0x18 cada) | struct | Cheats por jogador: `x0`, `x1`, `x2`, `x3`, `x4` (u8) | copiados para o carro em `1E308.c` (criação do carro) |
| `0x801253FC` + 0x18*n | u8[11] + s32 | Buffer da sequência de botões digitada por jogador | `2E00C.c` `Cheat_UpdateInput` |

Os flags são **zerados por `Cheat_ResetAll`** (`2DF20.c`), chamado em `2DD88.c` (troca
de modo de jogo). Um cheat "permanente" do port precisa reaplicar os flags a cada quadro.

**Códigos originais do jogo** (strings na ROM em `0x800B9248`…; digitados na tela Game
Mode segurando um botão-modificador e apertando A/B/L/R/cima/baixo):

| Código | Flag | Efeito |
|---|---|---|
| `DUD` | `D_801253F8` | Todos os carros |
| `BRAD` | `D_801253F9` | Todas as dificuldades/pistas |
| `BAR` | `D_8012540C[p].x0` → `car->xBE0` | **Carro indestrutível** (dano ignorado: `29CB0.c` `Car_ApplyDamage`, `2A618.c`) |
| `DAD` | `.x1` → `car->xBE1` | **Sem limite de tempo** (a corrida não termina pelo relógio: `1C624.c`) |
| `LRLR` | `.x2` → `car->xBE2` | Zonas de dano todas em 100 (dano padronizado, dado e recebido: `29F78.c`) |
| `LAL` | `.x3` | Lido mas **sem efeito** encontrado no código |
| `BALD` | `.x4` → `car->xBE3` | **Carro pesado** (massa × 8,8 e inércia alterada: `1E308.c`) |
| `DRULDLUR` | — | Abre a tela de desbloqueio de carro (modo `0x2E`) |

### Estado do jogo

| Símbolo | Tipo | Significado |
|---|---|---|
| `gGame` `0x8011EEE4` → `Game` | ponteiro | Estado global. `+0x0C` `mode` (s16: `MODE_MAIN`, `MODE_OPTIONS`, `MODE_RECORDS`…), `+0x10A8` relógio da corrida (`Time_Get`), `+0x10FC` sub-modo (0x1C = corrida em andamento), `+0x1100` `Race *` |
| `Race` | struct | `+0x74` `cars[12]` (`RACE_CARS` = 120 no PC), `+0xA4` `numCars` |
| `gUi` `0x8011EEF4` | ponteiro | Gerenciador de elementos 2D da tela (`Ui_AddElement/RemoveElement`) |
| `D_8011EEF8` → `+0x514` | f32 | Volume da música (0-1) |
| `D_80097350` | s32 | Quantidade de itens do menu atual |
| `D_800958B4[0]` | s32 | Tipo de evento/pista selecionada (2, 11 = arenas especiais) |
| `D_800958A4` | s32 | Modo de jogo escolhido (campeonato, arcade, time trial…) |
| `gControllers` `0x80125C80` | s32 | Controles conectados |
| `D_800BAD10` | s32[5] | IDs de UI dos itens do menu de opções (o causador do crash da fase 1) |

### Carro (`Car`, `include/car.h` e uso no código)

| Offset | Tipo | Significado |
|---|---|---|
| `+0x10` | s32 | **Vida total do carro** (cai com o dano; 0 = destruído) |
| `+0x30` | s32 | 0 = carro de jogador (usado pelo locutor) |
| `+0x34` | s32 | Tipo/modelo do carro (0-23) |
| `+0x3C` | ptr | Jogador dono |
| `+0x50` | u64 | Limite de tempo da corrida para este carro |
| `+0xA6C`… `parts[8]` (0x14 cada) | `DamageZone` | Zonas de dano: `defence` (+0), `offence` (+4), vida da zona (+8). Zonas: frente, dir. frente, dir. lado, dir. trás, esq. frente, esq. lado, esq. trás, traseira |
| `+0xBE0`..`+0xBE3` | u8 | Cheats ativos no carro (indestrutível, sem tempo, dano 100, pesado) |
| `stats` (`CarStats`, 0x2B0) | struct | Física por modelo: tração `+0xC`, freio `+0x10`, freio motor `+0x14`, off-road `+0x18`, resposta da direção `+0x1C`, torque `+0x2C/+0x30`, marchas `+0x40` |
| `AiDriver` | struct | IA: tendência, defesa (0-99), tenacidade, busca… |

### Pontos de gancho úteis (para mods futuros)

| Gancho | Arquivo/função | Uso |
|---|---|---|
| Batida/destruição anunciada | `port/src/field.c` `Port_FieldAnnounceWreck` | `TODO(FUTURE_MOD)`: SFX/efeitos por batida |
| Dano aplicado | `src/auto/29CB0.c` `Car_ApplyDamage`, `29F78.c` | Vibração por impacto, efeitos |
| Rumble do controle | `port/src/input.c` / `os_hw.c` (`osMotorStart/Stop`), chamado pelo jogo em `src/auto/59550.c`, `59778.c`, `59E94.c`, `59F18.c`, `44A4C.c` | Fase 4 |
