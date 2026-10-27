# SmartChart RC — Apramed

Controle remoto do SmartChart pelo celular, via **Wi‑Fi**.
Usa o mesmo protocolo do app Google TV (serviço "Android TV Remote" que já vem no
Android TV), então não precisa instalar nada no SmartChart. O controle original
continua funcionando junto.

## Gerar o APK
1. Abra a pasta `SmartChartRC` no **Android Studio**.
2. **Build > Build App Bundle(s) / APK(s) > Build APK(s)** (ou `./gradlew assembleDebug`).
3. O APK sai em `app/build/outputs/apk/debug/app-debug.apk`.

Requisitos: celular com Android 9+, na **mesma rede Wi‑Fi** do SmartChart.

## Primeiro uso (pareamento — só uma vez)
1. Abra o app e toque na barra de status.
2. Escolha o SmartChart na lista (ou **Digitar IP** se ele não aparecer).
3. A TV mostra um código de 6 caracteres → digite no celular → **Parear**.
4. Pronto: a barra fica verde. Nas próximas vezes o app conecta sozinho ao abrir.

Menu **Mais…** (na mesma janela): parear de novo, esquecer a TV, diagnóstico.
Segurar o dedo na barra de status também abre o diagnóstico.

## Teclas enviadas
Definidas em `app/src/main/java/com/apramed/smartchartrc/KeyMap.java`
(códigos `KeyEvent.KEYCODE_*` do Android).

| Botão      | Tecla  | Botão      | Tecla  |
|------------|--------|------------|--------|
| C          | C      | Ishihara   | I      |
| E          | E      | Red/Green  | R      |
| ETDRS      | T      | Lancaster  | L      |
| Letras     | P      | Fixação    | O      |
| Números    | N      | Galeria    | G      |
| LEA        | H      | SmartBox   | S      |
| Piktotea   | K      | D-pad / OK | Setas / DPAD_CENTER |

Toque curto envia a tecla uma vez; segurar mantém a tecla pressionada (repetição).

## Estrutura
- `atv/AtvRemote.java` — protocolo Android TV Remote v2 (pareamento porta 6467, comandos 6466).
- `atv/ClientCert.java` — certificado do celular (gerado uma vez; a TV lembra dele após parear).
- `atv/Proto.java` — codificação protobuf mínima, sem dependências.
- `TvFinder.java` — encontra TVs na rede (mDNS `_androidtvremote2._tcp`).
- `MainActivity.java` — tela, botões, pareamento e reconexão automática.

Se a TV parar de aceitar o celular (ex.: após reset), use **Mais… → Parear de novo**.
