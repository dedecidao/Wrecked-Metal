# DOCS_DEV_SETUP.md — Guia Mínimo de Ambiente, Build, Deploy e Debug

Guia prático para subir uma máquina Windows nova (e/ou Linux/WSL) para desenvolver, compilar, fazer deploy via ADB e depurar o **Wrecked Metal** sem depender de memória tribal.

---

## 1. Visão geral dos dois repositórios

O projeto é dividido em dois repositórios principais:

| Repositório | Caminho local | Papel | Branch principal |
|---|---|---|---|
| `dedecidao/Wrecked-Metal` | Raiz do projeto | App Android (Launcher Java, overlay touch, `GameActivity`, glue nativo CMake em `app/src/main/cpp/android/`, CI/CD) | `main` |
| `dedecidao/Destruction-Decomp-64` | `external/dd64` (submódulo) | Engine: descompilação em C (`src/`) + port de PC e runtime SDL3 / Fast3D (`port/`) | `port-pc` |
| `libsdl-org/SDL` | `external/SDL` (submódulo) | SDL 3.4.x (janela, Vulkan/SDL_gpu, AAudio, gamepads, lado Java `SDLActivity`) | tag `release-3.4.20` |

### Fluxo de trabalho entre repos

1. **Mudança só de Android** (Launcher Java, `TouchControlsView`, `android_main.c`, `touch.c`, `haptics.c`, Gradle, CI): edite direto em `Wrecked-Metal`.
2. **Mudança na engine ou port compartilhado** (`external/dd64/src/`, `port/src/`, `port/fast3d/`, `port/game/`):
   - Abra PR contra a branch **`port-pc`** de `dedecidao/Destruction-Decomp-64`.
   - Código exclusivo do Android deve ficar protegido por `#ifdef __ANDROID__` (ou `TARGET_ANDROID`) para **nunca quebrar o build de PC**.
   - Código específico do port (não-N64) deve ficar sob `#ifdef TARGET_PC` para não alterar o layout/match da ROM original.
   - Depois do merge na `port-pc`, atualize o ponteiro do submódulo `external/dd64` em `Wrecked-Metal` (`git submodule update --remote` ou checkout do commit mergeado e commit no repo principal).
   - *Nota*: `.gitmodules` referencia `branch = android-port` por histórico, mas todos os PRs ativos da engine entram em `port-pc`.

---

## 2. Setup mínimo (Fast Path: codar + build via CI + debug via ADB)

Se você não precisa compilar o APK localmente (a CI do GitHub já gera tudo a cada push/PR), o mínimo para codar, baixar o APK e testar no celular no Windows é:

1. **Git** (com suporte a submódulos)
2. **GitHub CLI (`gh`)** autenticado (`gh auth login` com escopos `repo` e `workflow`)
3. **Python 3** (para scripts em `tools/`)
4. **Android Platform-Tools (`adb`)**:
   - Baixe o ZIP oficial (`platform-tools-latest-windows.zip`) de `https://dl.google.com/android/repository/platform-tools-latest-windows.zip`.
   - Extraia em `C:\Users\<SEU_USUARIO>\Android\platform-tools` e adicione ao `PATH` do usuário.
   - Valide no PowerShell:
     ```powershell
     adb version
     adb devices -l
     ```

### Clonar o repositório completo

```powershell
git clone --recursive https://github.com/dedecidao/Wrecked-Metal
cd Wrecked-Metal
```

Se já tiver clonado sem `--recursive`:

```powershell
git submodule update --init --recursive
```

### Baixar APKs prontos da CI

A CI assina tanto o APK **release** quanto o APK **debug** com a mesma chave de release (quando os secrets `WM_KEYSTORE_*` estão configurados). Isso significa que você pode baixar o debug APK da CI e instalar direto por cima do app de debug no aparelho **sem perder a ROM importada nem os saves**.

```powershell
# Baixa o último debug APK da release rolling "nightly"
gh release download nightly --repo dedecidao/Wrecked-Metal --pattern "*debug*.apk" --dir "$HOME\Android\apks" --clobber
```

Para baixar o artifact de um PR ou branch específico antes do merge:

```powershell
gh run list --repo dedecidao/Wrecked-Metal --limit 5
gh run download <RUN_ID> --repo dedecidao/Wrecked-Metal -n dd64-android-debug-apk --dir "$HOME\Android\apks"
```

---

## 3. Setup completo para build local

### 3.1 Build local do APK Android

Requisitos (mesmo conjunto usado em [.github/workflows/android.yml](.github/workflows/android.yml)):

- **JDK 17** (Eclipse Temurin recomendado)
- **Gradle 8.14.3** (ou Android Studio)
- **Android SDK** com:
  - `compileSdk 35` / `platforms;android-35`
  - **NDK `27.2.12479018`**
  - **CMake `3.22.1`**
- **Python 3**
- **Ninja**

> [!NOTE]
> Em builds 32-bit (`armeabi-v7a`), o repo já inclui `symbols/dd64.syms` e `symbols/dd64_widths.c` pré-exportados do build de PC, então o CMake usa esses arquivos direto sem precisar de `arm-linux-gnueabihf-objdump`. Se você alterar `SYMBOLS` ou rodar o build sem o `dd64_widths.c` pronto (por exemplo no caminho de conversão `arm64`), instale `binutils-arm-linux-gnueabihf` (Linux/WSL).

Comandos de build na raiz de `Wrecked-Metal`:

```sh
# Debug APK (armeabi-v7a — app com sufixo .debug e GameActivity exportada para adb)
gradle --no-daemon assembleDebug

# Release APK (assinado se ~/.wreckedmetal/signing.properties ou env WM_KEYSTORE_* estiver configurado)
gradle --no-daemon assembleRelease

# Probe de endereço arm64 (verifica mapeamento de 0x80000000 sem compilar o jogo todo)
gradle --no-daemon -Pdd64.abis=arm64-v8a -Pdd64.probe=true assembleDebug

# Checagem informacional de compilação arm64 (atualmente falha até concluir docs/ARM64.md)
gradle --no-daemon -Pdd64.abis=arm64-v8a -Pdd64.probe=false externalNativeBuildDebug
```

> [!WARNING]
> O submódulo `external/SDL/android-project` inclui um `gradle/wrapper/gradle-wrapper.jar` próprio de exemplo. Use o comando `gradle` instalado no sistema (como a CI faz) e não o wrapper dentro de `external/SDL`.

#### Assinatura local (opcional — apenas mantenedor)

O Gradle procura `~/.wreckedmetal/signing.properties` (ou `-Pwm.signing=<arquivo>`) ou as variáveis `WM_KEYSTORE_FILE`, `WM_KEYSTORE_PASSWORD`, `WM_KEY_ALIAS`/`WM_KEY_PASSWORD`. **Nunca** commite `.p12`, `.jks`, `.keystore` ou `signing.properties`.

### 3.2 Build de PC e Build N64 (no `external/dd64`, via Linux / WSL)

Necessário quando você altera a engine em `external/dd64` e quer:
- testar rapidamente no PC sem esperar build Android;
- rodar os verificadores de ABI/protótipos/byte-order antes de abrir PR;
- atualizar `symbols/dd64.syms` e `symbols/dd64_widths.c`.

No Ubuntu / WSL2:

```sh
sudo apt update
sudo apt install -y make curl python3-venv binutils-mips-linux-gnu cmake ninja-build \
  gcc-multilib g++-multilib pkg-config libsdl3-dev:i386
```

1. **Build matching do N64** (gera `build/dd64.elf`, exigido pelo CMake do port de PC):
   - Coloque sua ROM em `external/dd64/baserom.z64` (SHA1 `a2c0799e13566d1b723299592cfcac9387f25fa7`, nunca commitar).
   ```sh
   cd external/dd64
   python3 -m venv .venv
   . .venv/bin/activate
   pip install -r requirements.txt
   tools/setup.sh
   make extract && make
   # Deve imprimir: build/dd64.z64: OK
   ```

2. **Build PC Linux (32-bit)**:
   ```sh
   cmake -B build_linux -G Ninja
   cmake --build build_linux
   ```

3. **Cross-compile Windows (32-bit MinGW-i686 a partir do Linux/WSL)**:
   ```sh
   cmake -B build_win -G Ninja \
     -DCMAKE_TOOLCHAIN_FILE=cmake/mingw-i686.cmake \
     -DSDL3_MINGW_ROOT=/caminho/para/SDL3-3.4.x/i686-w64-mingw32
   cmake --build build_win
   ```

4. **Checkers obrigatórios ao alterar C do jogo (`external/dd64`)**:
   ```sh
   python3 tools/port/check_protos.py
   python3 tools/port/check_protos.py --calls
   python3 tools/port/check_void_returns.py
   python3 tools/port/check_struct_sizes.py @build_linux/gen/game_objects.txt
   ```

5. **Atualizar símbolos no repo Android** (só quando o layout de `build/dd64.elf` mudar):
   ```sh
   # Na raiz de Wrecked-Metal
   python3 tools/export_symbols.py external/dd64/build/dd64.elf symbols/dd64.syms
   # Copie também dd64_widths.c gerado pelo GCC do build PC (ex.: build_win/gen/dd64_widths.c) para symbols/dd64_widths.c
   ```

### 3.3 Armadilhas conhecidas de WSL no Windows

- **Nunca edite ou grave arquivos grandes através de `\\wsl.localhost\...`**: já ocorreu truncamento de arquivo (`android_main.c`). Mantenha o código no filesystem nativo ou edite dentro do próprio ambiente correspondente.
- **Evite passar comandos complexos com aspas ou `$` inline do PowerShell para `wsl bash -c "..."`**: o parser duplo quebra variáveis e citações. Escreva um arquivo `.sh` e execute `wsl bash ./script.sh`.

---

## 4. Deploy e Debug via ADB no Windows

### 4.1 Script automatizado `tools/test-run.ps1`

O repositório traz o script oficial [tools/test-run.ps1](tools/test-run.ps1) para automatizar instalação, limpeza de cheats, injeção de `Data/env.txt`, disparo direto de `GameActivity`, captura de tela e coleta de logs (`dd64.log` e `logcat.txt`).

Exemplos de uso no PowerShell (a partir da raiz de `Wrecked-Metal`):

```powershell
# 1. Baixar o último APK debug da nightly, instalar e rodar por 15s
.\tools\test-run.ps1 -Install -FetchNightly

# 2. Instalar um APK local específico
.\tools\test-run.ps1 -Install -ApkPath ".\app\build\outputs\apk\debug\app-debug.apk"

# 3. Pular direto para uma corrida (track 4 = City, carro 12 = Baja Buggy), acelerar e sair em 600 frames
.\tools\test-run.ps1 -Env "DD64_BOOT=track=4,type=1,car=12;DD64_FAKEGAS=200:1.0;DD64_GFXLOG=1;DD64_EXIT_AFTER=600" -Wait 25

# 4. Testar modo Fidelity (N64 fiel) tirando screenshot no frame 300
.\tools\test-run.ps1 -Fidelity -Env "DD64_BOOT=track=5,type=1;DD64_SHOT=300;DD64_EXIT_AFTER=360" -Wait 20
```

O que o `test-run.ps1` faz automaticamente por segurança:
- Procura o `adb.exe` no `PATH` e em `%USERPROFILE%\Android\platform-tools\adb.exe`.
- Faz backup de `dd64.ini` (ou `dd64-fidelity.ini`) e zera os flags de `[cheats]` durante o teste, **restaurando o original no `finally`** (a menos que `-KeepCheats` seja passado).
- Remove `Data/env.txt` ao final para não deixar testes presos em execuções futuras (o próprio app também só aplica o arquivo uma vez e o renomeia para `env.used.txt`).
- Salva `dd64.log`, `logcat.txt`, `screen.png` e quaisquer `shot_*.bmp` em `%USERPROFILE%\Documents\WreckedMetal-logs\<timestamp>\`.
- Toca um som de notificação no Windows ao concluir (use `-NoAlarm` para silenciar).

### 4.2 Pastas e arquivos no dispositivo Android

Para o app de debug (`com.costelinhagames.wreckedmetal.debug`; no release, remova o `.debug`):

| Caminho no aparelho | Conteúdo |
|---|---|
| `/sdcard/Android/data/com.costelinhagames.wreckedmetal.debug/files/dd64.ini` | Configurações do modo **Wrecked Metal** |
| `/sdcard/Android/data/com.costelinhagames.wreckedmetal.debug/files/dd64-fidelity.ini` | Configurações do modo **Fidelity** |
| `/sdcard/Android/data/com.costelinhagames.wreckedmetal.debug/files/Data/Logs/dd64.log` | Log completo da última sessão (ficha do aparelho, GPU/Vulkan, FPS a cada 30s, avisos `shadow:`, crashes) |
| `/sdcard/Android/data/com.costelinhagames.wreckedmetal.debug/files/Data/Saves/` | Save do Controller Pak (`pak1.mpk`, compartilhado entre os dois modos) |
| `/sdcard/Android/data/com.costelinhagames.wreckedmetal.debug/files/Data/Music/` | Faixas substitutas `song_XXXXXXXX.ogg` / `.mp3` |
| `/sdcard/Android/data/com.costelinhagames.wreckedmetal.debug/files/Data/env.txt` | Variáveis `DD64_*` (`CHAVE=VALOR`, uma por linha, `#` comenta) lidas no boot. **Valem uma abertura só**: o app move o arquivo para `env.used.txt` ao ler, e ignora um `env.txt` mais antigo que a instalação/atualização do app |
| `files/rom/dd64.z64` (armazenamento interno privado) | ROM validada importada pelo usuário |

> [!CAUTION]
> **Nunca crie `files/Data` manualmente via `adb shell mkdir` antes de abrir o app pela primeira vez.** Se a pasta for criada pelo usuário `shell`, o app Android não terá permissão de escrita nela e falhará ao gravar `dd64.log` e saves. Sempre abra o app no celular primeiro e importe a ROM pelo botão **Pick ROM**.

### 4.3 Variáveis de teste e diagnóstico (`Data/env.txt` / shell)

Todas as chaves abaixo são lidas pelo port em `android_main.c` (`loadEnvFile()`) ou pelos subsistemas em `external/dd64/port/`:

#### Automação de boot, navegação e input

| Variável | Formato / Exemplo | O que faz |
|---|---|---|
| `DD64_BOOT` | `track=5,type=1,car=0` ou `menu=23` | Pula logos/menus e inicia direto numa corrida (`track` 0–11, `type` 0–3, `sub` 0–3, `cup` 0–3, `car` 0–23, `players` 1–4) ou num modo de menu (`menu=23` seleção de pista, `menu=32` opções) |
| `DD64_GOTO` | `120:0x24` | Android (`touch.c`): no frame `120`, chama `Game_SetNextMode(0x24, 1)` (ex.: `0x22` novos recordes, `0x23` digitar nome, `0x24` recordes, `0x27` resultados TT) |
| `DD64_POKE` | `100:801253F8=1,801253F9=1` | Android (`touch.c`): no frame `100`, escreve palavras de 32 bits (host order) na RAM N64 |
| `DD64_PRESS` | `60:a 120:start` | Script de botões do controle 1 por frame apresentado (`input.c`) |
| `DD64_TAP` | `a:60` | Dá um toque de 4 frames no botão a cada `N` frames (`input.c`) |
| `DD64_FAKEGAS` | `200:1.0` | Segura acelerador do controle 1 na força especificada a partir do frame `N` (`input.c`) |
| `DD64_FAKERSTICK` | `200:80,0` | Segura o analógico direito em `x,y` a partir do frame `N` (`input.c`) |
| `DD64_FAKEKEY` | `120:Return` | Envia pressionamento real de tecla SDL no frame `N` (`events.c`) |
| `DD64_EXIT_AFTER` | `600` | Encerra o processo após `N` frames apresentados (`gfx_pc.cpp`) |

#### Diagnóstico de travamentos, performance e física

| Variável | Formato / Exemplo | O que faz |
|---|---|---|
| `DD64_STACKS` | `500` | Android (`android_main.c`): a cada `N` ms, threads do jogo (`n64*`) em estado `R` (running) logam `pc` e `lr` como offsets `libdd64.so+0x...` no logcat (`DD64`) |
| `DD64_PEEK` | `8011EEE4,800958B4` | Android (`android_main.c`): junto com `DD64_STACKS`, imprime palavras u32 de RAM N64 no logcat para acompanhar contadores |
| `DD64_GFXLOG` | `1` | Loga contadores de draw calls por segundo, timings de retrace/event pump e histograma de FPS (`gfx_pc.cpp`, `video.c`, `rcp.c`) |
| `DD64_CARTRACE` | `1` | *(Presente na branch de debug `debug/car-trace` da engine)* Traça saltos de velocidade, forças de pneu e suspensão por etapa |
| `DD64_PROFILE` | `profile.txt` | Profiler por amostragem (PC Linux/Windows apenas; no Android usar `DD64_STACKS`) |

#### Gráficos, endianness (shadow map), áudio e dumps

| Variável | Formato / Exemplo | O que faz |
|---|---|---|
| `DD64_SHOT` | `180,360` | Salva screenshots `shot_<frame>.bmp` nos frames indicados (em `files/` no Android) |
| `DD64_DLDUMP` | `180` | Loga toda a display list, matrizes e primeiros vértices da tarefa desenhada no frame `N` |
| `DD64_TEXDUMP` | `1` | Salva até 200 texturas enviadas à GPU como `tex_<n>_<w>x<h>.bmp` (com `DD64_DLDUMP`, só as daquele frame) |
| `DD64_UILOG` | `1` | Loga cada elemento 2D desenhado por `Ui_Draw` (`video.c`) |
| `DD64_BECOPIES` | `1` | Reporta cópias de struct com dados ainda big-endian (`shadow.c`) |
| `DD64_WATCH` | `801253F8` | Loga todo código que escreve sobre o endereço N64 em hex (`shadow.c`) |
| `DD64_MUSICDUMP` | `1` | Grava cada faixa sequenciada tocada em `Data/MusicDump/song_XXXXXXXX.wav` |
| `DD64_WAVOUT` | `out.wav` | Grava a saída PCM mixada pelo sintetizador (`audio.c`) |
| `DD64_NO_AUDIO_RECOVERY` | `1` | Desativa o watchdog de recuperação do stream AAudio (`audio.c`) |
| `DD64_DEBUG_GPU` | `1` | Habilita validation layers do Vulkan no `SDL_CreateGPUDevice` (`gfx_sdlgpu.cpp`) |
| `DD64_UBERSHADER` | `1` | Força o ubershader do combiner no OpenGL/SDL_gpu |

### 4.4 Comandos ADB úteis do dia a dia

```powershell
# Ver log ao vivo filtrado do jogo e SDL
adb logcat -s DD64:V SDL:V DEBUG:V AndroidRuntime:E

# Puxar último dd64.log manualmente
adb pull /sdcard/Android/data/com.costelinhagames.wreckedmetal.debug/files/Data/Logs/dd64.log .

# Verificar arquivos internos privados (incluindo dumps relativos ou ROM)
adb shell run-as com.costelinhagames.wreckedmetal.debug ls -la files files/rom

# Reiniciar servidor ADB se o cabo/porta USB falhar (dica: use porta USB traseira da placa-mãe)
adb kill-server; adb start-server; adb devices -l
```

### 4.5 Simbolizando crashes (`libdd64.so+0x...`)

O `crashHandler` e o `DD64_STACKS` em `app/src/main/cpp/android/android_main.c` registram offsets dentro de `libdd64.so` (por exemplo `libdd64.so+1A4F2C`). Como o `build.gradle` mantém `keepDebugSymbols += ['**/*.so']` e flags `-g -gdwarf-4`, você pode traduzir qualquer offset para arquivo e linha com o `llvm-addr2line` do NDK:

```powershell
# Exemplo apontando para o .so compilado ou extraído do APK debug (armeabi-v7a)
& "$env:LOCALAPPDATA\Android\Sdk\ndk\27.2.12479018\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-addr2line.exe" `
  -f -i -e "app\build\intermediates\merged_native_libs\debug\out\lib\armeabi-v7a\libdd64.so" 0x1A4F2C
```

---

## 5. Regras inegociáveis (Checklist rápido)

- **BYOR sempre**: nunca commitar ROMs (`.z64`, `.n64`, `.v64`), `dd64.elf`, texturas/áudio extraídos ou `baserom*`.
- **Zero segredos**: nunca commitar `.p12`, `.jks`, `.keystore`, `signing.properties` ou tokens.
- **`applicationId` intocável**: manter `com.costelinhagames.wreckedmetal` (e pacote Java `io.github.dedecidao.dd64` por causa dos nomes JNI).
- **Build de PC sempre verde**: toda alteração na engine (`external/dd64`) para Android deve ficar sob `#ifdef __ANDROID__` ou macros `TARGET_*`.
- **Testes em device reais (S23 Ultra)** sempre com **cheats desligados** e barra mínima de qualidade: corrida completa sem crash e **60 fps estáveis**.
- **Atualizar memória**: registrar novas decisões, armadilhas e resultados de sessão em [AI_MEMORY.md](AI_MEMORY.md) e mudanças de comportamento em [DOCS_ANDROID_PORT.md](DOCS_ANDROID_PORT.md) / [DOCS_INTERNALS_AND_OPTIONS.md](DOCS_INTERNALS_AND_OPTIONS.md).
