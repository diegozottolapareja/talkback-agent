# Technical Review — Android AI TalkBack Accessibility Agent

> Revisión técnica independiente de la propuesta inicial (ChatGPT) para un agente on-device que abre una URL en Chrome, navega con TalkBack hasta un elemento y devuelve **lo que TalkBack realmente anuncia**.
> Estado: análisis previo a implementación. No contiene código.
> Convención de confianza: **[Doc]** = respaldado por documentación oficial / API pública; **[Conocimiento]** = comportamiento conocido de Android/TalkBack, no garantizado por contrato; **[Hipótesis]** = requiere PoC.

---

## 1. Executive Summary

**Veredicto general: Partially feasible — viable con una arquitectura distinta a la propuesta, condicionado a 3 PoCs.**

1. **El punto crítico (capturar lo que TalkBack dice) NO se resuelve con `AccessibilityService` + `AccessibilityEvent` + `AccessibilityNodeInfo`**, que es lo que propone la arquitectura original. Esos mecanismos muestran *los datos de entrada* de TalkBack (el nodo enfocado, su texto, rol, estado), no *su salida*. El texto hablado lo compone TalkBack internamente (rol, estado, posición en lista, hints de uso, contexto de contenedor, verbosidad, idioma) y no se publica como evento. Si se implementa la propuesta original tal cual, se obtiene una **reconstrucción aproximada**, no el anuncio real — exactamente lo que el usuario quiere evitar.

2. **Existe un canal técnicamente sólido y on-device para capturar el anuncio real: un motor TTS propio ("TTS proxy/spy engine").** TalkBack no sintetiza voz por sí mismo: envía cada enunciado como texto al motor Text-to-Speech configurado en el sistema. Una app de terceros puede implementar `TextToSpeechService` (API pública) y ser seleccionada como motor predeterminado; entonces recibe en `onSynthesizeText(SynthesisRequest)` **el texto exacto que TalkBack pide pronunciar**. Puede reenviarlo a Google TTS para que el usuario siga escuchando la voz normal. Es la única vía que captura la *salida* de TalkBack sin root, sin computadora y sin modificar TalkBack. **Clasificación: Feasible en teoría (API pública), Requires PoC para confirmar fidelidad con la versión de TalkBack instalada.**

3. **Captura de audio interno (MediaProjection / AudioPlaybackCapture): Not feasible.** Android solo permite capturar audio con usos `USAGE_MEDIA`, `USAGE_GAME` y `USAGE_UNKNOWN`; la voz de TalkBack usa `USAGE_ASSISTANCE_ACCESSIBILITY` y queda excluida. Grabar por micrófono + speech-to-text es posible pero con pérdida: sirve solo como evidencia secundaria.

4. **Control de Chrome:** un `AccessibilityService` propio es la mejor opción on-device para *localizar* el elemento y *mover el foco*. Pero para reproducir la navegación real de un usuario de TalkBack (swipe lineal) hay que validar que los gestos inyectados con `dispatchGesture` sean interpretados por TalkBack como gestos de navegación (Requires PoC). Se recomienda medir y reportar **dos anuncios**: (a) foco directo sobre el elemento y (b) llegada por navegación lineal desde el elemento anterior, porque pueden diferir.

5. **IA:** las suscripciones Claude Pro/Max y ChatGPT Plus/Pro **no otorgan acceso programático por API** para una app propia. Las opciones reales son: API con pago por uso (costo estimado < USD 0,01 por ejecución con un modelo pequeño), modelo on-device (Gemini Nano, si el teléfono lo soporta), o usar Claude Code/Codex como "cerebro" desde una computadora (contradice el requisito). Además, **la IA no es el riesgo principal**: la interpretación de la instrucción es la parte más fácil del sistema; el núcleo debe ser determinista.

6. **Arquitectura recomendada:** una sola app Android nativa (Kotlin) con cuatro componentes: *Controller AccessibilityService*, *Capture TTS Engine*, *Orchestrator determinista*, *Adaptador LLM intercambiable*; más un setup único opcional vía **Wireless Debugging/Shizuku desde el mismo teléfono** (sin computadora) para conceder `WRITE_SECURE_SETTINGS` y automatizar encender/apagar TalkBack y seleccionar el motor TTS.

7. **Antes de escribir la app completa**, construir un PoC mínimo (≈1–3 días) del motor TTS de captura y compararlo contra la salida de TalkBack ("Display speech output" / logcat). Si ese PoC falla, el producto pierde su requisito central y hay que replantear (ver §3.6).

---

## 2. Critical Findings (problemas de la propuesta original)

| # | Hallazgo | Severidad | Detalle |
|---|---|---|---|
| CF-1 | **Confunde árbol de accesibilidad con salida hablada** | Crítica | `AccessibilityEvent`/`AccessibilityNodeInfo` dan el nodo, no el enunciado. TalkBack agrega rol ("cuadro de edición"), estado ("no seleccionado", "contraído"), hints de uso ("presiona dos veces para editar texto"), contexto ("en lista, 3 elementos", "título nivel 2", "landmark principal"), errores ARIA, `aria-describedby`, y lo ordena según sus reglas internas y la verbosidad configurada. Ninguno de esos textos se publica como evento. |
| CF-2 | **No define un mecanismo de captura de la salida** | Crítica | La propuesta menciona "captura de evidencia" pero no explica de dónde sale el texto anunciado. Ese es el requisito central. |
| CF-3 | **Asume que mover el foco ≈ navegación TalkBack** | Alta | `ACTION_ACCESSIBILITY_FOCUS` programático hace que TalkBack anuncie el nodo, pero el anuncio por swipe lineal puede incluir/omitir contexto (entrar/salir de listas, landmarks, tablas). Hay que medir ambos caminos. |
| CF-4 | **Ignora la convivencia operativa con TalkBack** | Alta | Con TalkBack activo, el usuario no puede operar cómodamente la UI de la propia herramienta (tocar = explorar). La app debe activar TalkBack al iniciar la ejecución y desactivarlo al final; eso requiere `WRITE_SECURE_SETTINGS` (no concedible desde la UI normal). |
| CF-5 | **Supone que las suscripciones sirven como API** | Alta | Las suscripciones de consumidor no incluyen API; usar sus credenciales desde apps propias no está soportado y puede violar términos. Hay que presupuestar API (barata) o modelo local. |
| CF-6 | **Sobredimensiona el rol de la IA** | Media | Un "agente" LLM que decide cada acción introduce no-determinismo en una herramienta de *medición*. La IA debe limitarse a: interpretar la instrucción → plan estructurado, y desambiguar el elemento. La ejecución y la captura deben ser deterministas y reproducibles. |
| CF-7 | **PASS/FAIL sin criterio de comparación definido** | Media | Comparar el anuncio con lo "esperado" requiere normalización (puntuación, separadores, hints diferidos, idioma). Sin eso habrá falsos FAIL. |
| CF-8 | **No considera variables que cambian el anuncio** | Media | Versión de TalkBack, versión de Chrome, idioma del sistema, verbosidad, "hints de uso" activados/desactivados, orden de lectura. El resultado solo es válido junto con ese contexto; debe registrarse en cada reporte. |
| CF-9 | **No considera restricciones de distribución** | Baja/Media | Android 13+ bloquea por defecto activar servicios de accesibilidad en apps instaladas por sideload ("Restricted settings": hay que habilitar "Permitir ajustes restringidos" en Info de la app). Google Play restringe `AccessibilityService` para fines no-accesibilidad; para uso personal (sideload) no aplica. |

---

## 3. TalkBack Feasibility

### 3.1 Cómo produce TalkBack su anuncio (modelo mental necesario)

```
App (Chrome) ──AccessibilityEvent / NodeInfo──► TalkBack
                                                 │  compone el enunciado
                                                 │  (reglas internas + verbosidad + idioma)
                                                 ▼
                                   TextToSpeech.speak(texto, ...)  ──►  Motor TTS del sistema ──► audio (USAGE_ASSISTANCE_ACCESSIBILITY)
                                                 └─ earcons / vibración (no son texto)
```

El texto que buscamos existe en un único punto observable desde fuera de TalkBack: **la frontera TalkBack → motor TTS**. Todo lo que está antes es entrada; todo lo que está después es audio.

### 3.2 Evaluación de cada mecanismo

| Mecanismo | ¿Da el anuncio real? | Clasificación | Fundamento |
|---|---|---|---|
| `AccessibilityEvent` (TYPE_VIEW_ACCESSIBILITY_FOCUSED, etc.) en un servicio propio | **No.** Da el evento que *dispara* el anuncio y el nodo afectado. | Not feasible (como fuente del anuncio); útil como sincronización | [Doc] Los eventos los emite la app, no TalkBack. TalkBack no publica su salida como eventos. |
| `TYPE_ANNOUNCEMENT` | Solo anuncios explícitos de la app (`announceForAccessibility`, regiones live). | Partially feasible (subconjunto) | [Doc] Es entrada a TalkBack, no salida. |
| `AccessibilityNodeInfo` | **No.** Es el insumo: text, contentDescription, hintText, roleDescription, stateDescription, collectionInfo, error. | Not feasible como anuncio; Feasible para localizar elementos y como "anuncio esperado aproximado" | [Doc] |
| **Motor TTS propio (`TextToSpeechService`) seleccionado como predeterminado** | **Sí: recibe el texto que TalkBack envía a sintetizar.** | **Feasible (API pública) / Requires PoC (fidelidad)** | [Doc] `android.speech.tts.TextToSpeechService`, `onSynthesizeText(SynthesisRequest, SynthesisCallback)`, `SynthesisRequest.getCharSequenceText()`. [Conocimiento] TalkBack usa el motor TTS del sistema. |
| TalkBack → Ajustes para desarrolladores → "Mostrar salida de voz" (Display speech output) | Muestra en pantalla el texto hablado. | Partially feasible / Requires PoC | [Conocimiento] Existe en TalkBack (Configuración avanzada → Configuración para desarrolladores). Leerlo desde otra app requiere screenshot+OCR o leer la ventana vía `getWindows()` (no garantizado). Excelente como **oráculo de validación manual**. |
| TalkBack → "Nivel de registro" (logcat verbose) | El log incluye la salida de voz. | Partially feasible | Leer logcat de otra app requiere `READ_LOGS` (solo concedible vía `adb`/shell, p.ej. Shizuku o Wireless Debugging on-device). En Android 13+ además aparece un diálogo de consentimiento de acceso a logs. Formato de log no es contrato estable. Útil como **segundo oráculo** en el PoC. |
| `AudioPlaybackCapture` (MediaProjection) | No: la voz de TalkBack no es capturable. | **Not feasible** | [Doc] `AudioPlaybackCaptureConfiguration` solo admite `USAGE_MEDIA`, `USAGE_GAME`, `USAGE_UNKNOWN`; el audio de accesibilidad está excluido. |
| Grabación por micrófono + STT | Aproximado, con errores de reconocimiento, ruido, nombres propios. | Partially feasible (solo evidencia secundaria) | No sirve como "texto exacto". |
| Fork/compilación propia de TalkBack (código open source en GitHub) | Sí, pero de *otro* binario. | Not recommended | Rompe la fidelidad: el objetivo es la versión instalada de TalkBack. Útil solo para estudiar las reglas de composición. |
| Root / Xposed / hooking de TalkBack | Sí. | Not feasible bajo las condiciones (sin root) | Fuera de alcance. |

### 3.3 Solución propuesta: Capture TTS Engine

**Funcionamiento:**

1. La app incluye un servicio `TextToSpeechService` ("Capture Engine") declarado como motor TTS.
2. Se configura como motor predeterminado (Ajustes → Accesibilidad → Salida de texto a voz → Motor preferido), manualmente una vez o automáticamente vía `Settings.Secure` `tts_default_synth` si se tiene `WRITE_SECURE_SETTINGS`.
3. Cada vez que TalkBack habla, el engine recibe `SynthesisRequest` con: texto (incluyendo spans como `TtsSpan`/`LocaleSpan` si los hubiera), idioma, velocidad, tono, y parámetros (p.ej. `utteranceId`).
4. El engine registra `{timestamp, texto, idioma, params}` en un log interno (Room/archivo) y además:
   - **Modo silencioso:** devuelve audio vacío/silencio rápido (ejecuciones más rápidas, sin molestar).
   - **Modo proxy audible:** delega la síntesis al motor de Google (cliente `TextToSpeech` interno con `synthesizeToFile` o streaming) y devuelve el PCM por `SynthesisCallback`. Más complejo; opcional.
5. El Orchestrator correlaciona los enunciados recibidos con la acción ejecutada (ventana temporal desde la acción hasta "quiescencia", p.ej. 1,5–3 s sin nuevos enunciados, porque TalkBack emite los hints de uso con retraso y a veces en un enunciado separado).

**Matices que el PoC debe medir:**

- **Interrupciones:** con `QUEUE_FLUSH`, TalkBack puede pedir un texto y cancelarlo antes de terminar de hablarlo (`onStop`). El engine *recibió* el texto completo; el reporte debe distinguir "solicitado" vs "posiblemente interrumpido".
- **Segmentación:** TalkBack puede partir un anuncio en varios enunciados (p.ej. contenido + hint diferido). El reporte debe mostrar la secuencia y una concatenación.
- **Earcons y vibraciones** no son texto: se registran aparte si se desea (TalkBack los reproduce como sonidos propios, no vía TTS) — fuera del alcance del texto.
- **Dependencia del motor:** TalkBack puede consultar idiomas soportados por el motor (`onIsLanguageAvailable`, `onGetLanguage`) para decidir cambios de idioma (`LocaleSpan`). El Capture Engine debe declarar los mismos idiomas que el motor real o el texto podría variar en páginas multilingües. [Hipótesis]
- **Algunas versiones/fabricantes** podrían priorizar un motor concreto; Samsung trae su propio motor y TalkBack integrado ("Voice Assistant" en modelos viejos). [Hipótesis → PoC en el dispositivo real del usuario]

### 3.4 Cómo demostrar experimentalmente que funciona (criterio de aceptación)

Tres fuentes independientes sobre el mismo recorrido:

| Fuente | Rol |
|---|---|
| A. Capture Engine (nuestro) | Candidato |
| B. TalkBack "Mostrar salida de voz" (screenshot por paso) | Oráculo visual |
| C. Logcat de TalkBack con nivel verbose (capturado una vez vía `adb` o Wireless Debugging) | Oráculo textual |
| D. Escucha humana (opcional) | Validación de que el modo proxy no altera lo audible |

Página de prueba controlada (HTML propio) con ~40–60 elementos: inputs con `<label>`, `aria-label`, `aria-labelledby`, `aria-describedby`, `placeholder`, `required`, `aria-invalid` + mensaje de error, checkbox/radio/select, botones, links, headings h1–h6, listas, tablas, landmarks, `aria-live`, iframes, contenido traducido a otro idioma (`lang`).

**Criterio:** coincidencia textual A ≡ C (normalizando espacios) en ≥ 98 % de los pasos, y toda diferencia explicada (interrupción, segmentación). Repetir con verbosidad alta/baja y con hints de uso on/off. Si se cumple, el canal queda validado para esa combinación de versiones; registrar `TalkBack versionName`, `Chrome versionName`, Android, idioma.

### 3.5 Conflictos entre múltiples AccessibilityServices

- [Doc] Android permite varios servicios de accesibilidad simultáneos; cada uno recibe los eventos que solicita.
- **Solo uno puede tener "touch exploration"**: nuestro servicio **no** debe solicitar `FLAG_REQUEST_TOUCH_EXPLORATION_MODE` ni filtrar teclas, para no alterar el comportamiento de TalkBack.
- **El foco de accesibilidad es global**: si nuestro servicio llama `ACTION_ACCESSIBILITY_FOCUS`, TalkBack recibe `TYPE_VIEW_ACCESSIBILITY_FOCUSED` y anuncia. Esto es deseable (lo usamos), pero también significa que cualquier acción nuestra fuera de lugar genera anuncios espurios: el Orchestrator debe "limpiar" el buffer de enunciados antes de cada medición.
- `dispatchGesture` (API 24+) con TalkBack activo: [Hipótesis] los gestos inyectados pasan por el input filter de accesibilidad y TalkBack los interpretaría como gestos (swipe derecha = siguiente elemento). Debe validarse (PoC-3). Alternativa si no funciona: inyectar `input swipe` vía shell (Shizuku), o atajos de teclado de TalkBack con teclado físico simulado (también requiere shell).

### 3.6 Plan B si el Capture Engine falla

1. **TalkBack logcat vía `READ_LOGS`** (concedido una vez con Wireless Debugging on-device / Shizuku): captura exacta, pero depende del formato de log y del diálogo de consentimiento de Android 13+.
2. **"Mostrar salida de voz" + `takeScreenshot()` + OCR on-device (ML Kit)**: exacto si el OCR no falla; frágil ante textos largos.
3. **Predicción**: reconstruir el anuncio a partir del nodo replicando las reglas de TalkBack (estudiando su código fuente). Rotular siempre como "PREDICTED, not observed". Es lo que hace la propuesta original, y no cumple el requisito.

---

## 4. Control autónomo de Chrome

### 4.1 Alternativas

| Opción | On-device sin PC | Fidelidad vs. usuario real de TalkBack | Capacidad de localizar elemento | Clasificación |
|---|---|---|---|---|
| **AccessibilityService propio** (búsqueda en árbol, `ACTION_ACCESSIBILITY_FOCUS`, `ACTION_SCROLL_*`, `ACTION_NEXT_HTML_ELEMENT`, `dispatchGesture`) | Sí | Alta: opera sobre el mismo árbol que ve TalkBack, en Chrome real | Buena (texto, contentDescription, hint, viewId sintético de Chrome, roles) | **Feasible — recomendada** |
| UIAutomator / UiAutomation | No sin shell (requiere instrumentation lanzada vía adb) | Media; además `UiAutomation` históricamente puede suprimir servicios de accesibilidad salvo flag explícito | Buena | Partially feasible; no aporta sobre la opción anterior |
| Appium / Espresso | Requiere PC | — | — | Not feasible bajo requisitos |
| WebView dentro de nuestra app | Sí | **Baja-media**: no es Chrome (otra versión/config, sin UI de Chrome, sin la misma barra/omnibox); el anuncio puede diferir | Excelente (control total del DOM vía JS) | Not recommended como objetivo; útil para tests internos |
| Chrome DevTools Protocol en el teléfono (socket `chrome_devtools_remote`) | Solo vía shell (Shizuku/Wireless Debugging) y con "Depuración USB" activa | No mueve el foco de TalkBack; sirve para localizar/hacer scroll/leer el AX tree de Blink | Excelente (`Accessibility.getFullAXTree`, `DOM.scrollIntoViewIfNeeded`) | Partially feasible; **complemento opcional**, no reemplazo |
| Custom Tabs / Intent `ACTION_VIEW` | Sí | Alta (es Chrome) | — | Feasible solo para **abrir** la URL |

### 4.2 Recomendación

- **Abrir:** `Intent.ACTION_VIEW` con `setPackage("com.android.chrome")`.
- **Esperar carga:** eventos `TYPE_WINDOW_CONTENT_CHANGED` + presencia del nodo WebView raíz de Chrome + estabilidad del árbol.
- **Localizar:** búsqueda en el árbol de accesibilidad de Chrome (Chrome expone el contenido web como nodos virtuales). Matching por: label accesible, texto, hint, `roleDescription`, proximidad. Si hay contenido fuera de pantalla no expuesto, hacer scroll iterativo. [Hipótesis] Chrome puede no exponer nodos muy alejados del viewport; PoC-4.
- **Medir anuncio "foco directo":** limpiar buffer → `ACTION_ACCESSIBILITY_FOCUS` sobre el nodo → recolectar enunciados hasta quiescencia.
- **Medir anuncio "navegación lineal":** foco en el nodo anterior en orden de lectura → limpiar buffer → gesto swipe derecha inyectado → verificar que el foco quedó en el objetivo → recolectar enunciados. Esto reproduce lo que vive un usuario real.
- **Evidencia:** `AccessibilityService.takeScreenshot()` (API 30+, sin MediaProjection) antes y después; el rectángulo de foco de TalkBack aparece en la captura.

---

## 5. Architecture Alternatives

| Arquitectura | Cumple "solo teléfono" | Captura real TalkBack | Complejidad | Comentario |
|---|---|---|---|---|
| **A. App Android nativa única (Kotlin)** con AccessibilityService + Capture TTS Engine + Orchestrator + LLM adapter | Sí | Sí (vía Capture Engine) | Media | **Recomendada.** Todo lo crítico requiere componentes nativos de todas formas. |
| B. App web local (PWA) + componentes nativos | Sí | Solo a través de los componentes nativos | Mayor (dos stacks + puente) | No aporta: la PWA no puede acceder a nada crítico; agrega un puente. |
| C. Agente Android expuesto como servidor MCP, consumido por Claude/ChatGPT | Parcial | Sí (mismo núcleo nativo) | Alta | Las apps móviles de Claude/ChatGPT no se conectan a servidores MCP locales del teléfono; requerirían un servidor MCP remoto público (túnel HTTPS hacia el teléfono), con riesgos de seguridad y latencia. **Buena extensión futura**, no base. |
| D. Termux + Claude Code/Codex CLI en el teléfono + herramientas vía shell | Parcial (no oficialmente soportado en Android) | Sí, si se combina con el núcleo nativo | Alta y frágil | Permitiría usar la suscripción vía CLI, pero Claude Code/Codex no están soportados oficialmente en Android/Termux; además el CLI no puede ser AccessibilityService: igual se necesita la app nativa. |
| E. PC + adb + Appium/Claude Code | No | Sí (logcat de TalkBack es trivial con adb) | Baja | Viola el requisito principal, pero es la **mejor herramienta para el PoC de validación** (oráculo logcat). |
| F. Fork de TalkBack instrumentado | Sí | Sí, pero de otro binario | Alta | Rompe fidelidad respecto de la versión instalada. |

**Conclusión:** la propuesta original (app nativa) es correcta *en la forma*; lo que falla es *el mecanismo de captura* y el rol excesivo del "agente". Se mantiene app nativa, se agrega Capture TTS Engine y se reduce la IA a un adaptador.

---

## 6. AI Integration

### 6.1 Suscripción vs. API (diferencia clave)

| Producto | Qué incluye | ¿Sirve para una app propia en Android? |
|---|---|---|
| Claude Pro/Max (suscripción de consumidor) | Uso en claude.ai, apps de Claude, y **Claude Code** iniciando sesión con la cuenta | **No** como API. Usar las credenciales de la suscripción desde una app propia no está soportado por Anthropic (la API se factura aparte en la Claude Platform/Console). |
| ChatGPT Plus/Pro | Uso en ChatGPT y **Codex** con login de ChatGPT | **No** como API. La OpenAI API se factura aparte (platform.openai.com). |
| Claude API / OpenAI API | Acceso programático pago por tokens | Sí. |

Fuentes a verificar en la segunda revisión: términos de consumidor de Anthropic y documentación de autenticación de Claude Code; página de precios de la API de OpenAI y documentación de Codex sobre login con ChatGPT.

### 6.2 Opciones

| Opción | Costo | On-device | Calidad para la tarea | Clasificación |
|---|---|---|---|---|
| **API Claude con modelo pequeño** (`claude-haiku-5-5`: USD 0,10 / 1M tokens de entrada, USD 0,50 / 1M de salida, precios de la API de Anthropic a oct-2026) | ~2–5 k tokens por ejecución ⇒ **≈ USD 0,001 por ejecución** | No (requiere red) | Sobrada para: instrucción → JSON estructurado y desambiguación de candidatos | **Feasible — recomendada para v1** |
| API Claude con Sonnet (`claude-sonnet-5-5`: USD 2 / USD 10 por 1M) | ≈ USD 0,01–0,05 por ejecución | No | Mayor robustez para instrucciones ambiguas | Feasible (opcional) |
| API OpenAI (modelo pequeño equivalente) | Del mismo orden | No | Equivalente | Feasible (adaptador alternativo) |
| Gemini Nano on-device (ML Kit GenAI / AICore) | Gratis | Sí | Suficiente para extracción estructurada simple; depende del modelo de teléfono soportado | Requires PoC (compatibilidad del dispositivo) |
| Modelo local vía llama.cpp / MediaPipe LLM | Gratis | Sí | Aceptable con modelos 1–3B para extracción; consumo de RAM/batería | Partially feasible |
| Claude Code / Codex en PC orquestando el teléfono vía adb/MCP | Incluido en suscripción | No | Excelente | Viola requisito; útil en desarrollo |
| Claude/ChatGPT app como front-end + MCP remoto del teléfono | Incluido en suscripción (connectors) | Parcial | Excelente | Requires PoC; complejidad y superficie de ataque altas |

### 6.3 Rol correcto de la IA

La IA **no** debe controlar la ejecución paso a paso. Pipeline recomendado:

1. **Parse:** instrucción NL → `RunSpec` JSON validado por esquema:
   `{ url, target: { description: "Primary Address", hints: [...] }, mode: ["direct_focus","linear_nav"], expected?: { contains?/equals?/regex?, role?, state? }, language? }`
2. **Locate (determinista + IA opcional):** el núcleo devuelve los N mejores candidatos del árbol con puntuación; si hay ambigüedad, el LLM elige entre candidatos (lista cerrada, nunca inventa nodos) o la app pregunta al usuario.
3. **Execute & capture:** 100 % determinista.
4. **Evaluate:** comparación normalizada determinista para `equals/contains/regex`; LLM solo para criterios semánticos ("debe indicar que es obligatorio") y siempre mostrando el texto observado crudo. Resultado: PASS / FAIL / INCONCLUSIVE.
5. **Fallback sin IA:** formulario mínimo (URL + "elemento" + "esperado opcional"). Garantiza que la herramienta funciona aunque no haya red ni clave.

Diseñar el LLM como interfaz `InstructionInterpreter` con implementaciones `ClaudeApi`, `OpenAiApi`, `OnDevice`, `Manual`.

---

## 7. Recommended Architecture

```
┌──────────────────────────── App Android (Kotlin, un solo APK) ────────────────────────────┐
│                                                                                           │
│  UI (Compose)  ── instrucción NL / URL ──►  InstructionInterpreter (LLM adapter | manual) │
│                                                    │ RunSpec (JSON)                       │
│                                                    ▼                                      │
│                                  Orchestrator (máquina de estados determinista)           │
│        ┌─────────────────────────┬────────────────┴──────────┬───────────────────────┐    │
│        ▼                         ▼                           ▼                       ▼    │
│  SetupManager              ControllerA11yService        CaptureTtsEngine       EvidenceStore│
│  - TalkBack on/off         - abrir URL en Chrome       - TextToSpeechService   - JSON run   │
│  - motor TTS = Capture     - esperar carga             - log de enunciados     - screenshots│
│  - verificación de estado  - localizar candidatos      - modo silencio/proxy   - node dump  │
│  (WRITE_SECURE_SETTINGS    - foco directo / swipe      - correlación temporal  - versiones  │
│   vía Shizuku/WD, opcional)- takeScreenshot()                                  - reporte    │
└───────────────────────────────────────────────────────────────────────────────────────────┘
          Chrome (real)  ◄── acciones de accesibilidad ──  │      TalkBack (real) ──texto──► CaptureTtsEngine
```

### Flujo de una ejecución

1. Usuario (TalkBack apagado) escribe: "Validá https://example.com. Andá al campo Primary Address y decime qué anuncia TalkBack".
2. Interpreter → `RunSpec`.
3. SetupManager: guarda el estado actual, selecciona CaptureTtsEngine, activa TalkBack (si tiene `WRITE_SECURE_SETTINGS`; si no, guía al usuario a usar el atajo de volumen).
4. Controller: abre Chrome con la URL, espera carga, busca candidatos.
5. Si 1 candidato claro → mide "foco directo" y "navegación lineal". Si ambiguo → LLM elige entre candidatos o la app pregunta.
6. CaptureTtsEngine entrega los enunciados correlacionados.
7. SetupManager restaura TalkBack y el motor TTS originales.
8. Reporte: anuncio observado (crudo y segmentado) por modo, nodo objetivo (propiedades), screenshots, versiones (Android, TalkBack, Chrome, idioma, verbosidad), y PASS/FAIL/INCONCLUSIVE si había esperado. Exportable como JSON/Markdown.

### Setup único sin computadora

- Instalar APK (sideload) → habilitar "Permitir ajustes restringidos" (Android 13+) → activar ControllerA11yService.
- Seleccionar CaptureTtsEngine como motor predeterminado (o dejar que lo haga SetupManager).
- Opcional pero recomendado: conceder `WRITE_SECURE_SETTINGS` una vez mediante **Wireless Debugging desde el propio teléfono** (Android 11+, emparejamiento por código) usando Shizuku o un pairing integrado. Habilita encender/apagar TalkBack y cambiar el motor TTS automáticamente. Riesgo: requiere activar Opciones de desarrollador.

---

## 8. Proof of Concept (riesgos críticos, en orden)

| PoC | Pregunta | Método | Criterio de éxito | Si falla |
|---|---|---|---|---|
| **PoC-1 Capture Engine** (bloqueante) | ¿El motor TTS propio recibe exactamente lo que TalkBack habla? | App mínima con `TextToSpeechService` que loguea y responde silencio. Página de prueba controlada. Comparar contra logcat de TalkBack (vía adb una vez) y "Mostrar salida de voz". | ≥ 98 % coincidencia exacta, diferencias explicadas | Plan B §3.6 (logcat con READ_LOGS) |
| **PoC-1b Proxy audible** | ¿Se puede reenviar a Google TTS sin alterar el texto ni romper TalkBack? | Delegar síntesis y devolver PCM | Voz audible, mismo log | Usar modo silencioso durante mediciones |
| **PoC-2 Foco programático** | ¿`ACTION_ACCESSIBILITY_FOCUS` desde nuestro servicio sobre nodos web de Chrome dispara el anuncio de TalkBack? ¿Coincide con tocar el elemento? | Comparar ambos anuncios en 30 elementos | Anuncio presente en 100 %; diferencias documentadas | Usar solo navegación por gestos |
| **PoC-3 Gestos inyectados** | ¿TalkBack interpreta `dispatchGesture` (swipe derecha) como "siguiente elemento"? | Inyectar swipe con TalkBack activo y observar cambio de foco | Foco avanza 1 elemento de forma consistente | `input swipe` vía Shizuku; o atajos de teclado de TalkBack |
| **PoC-4 Localización en Chrome** | ¿Se encuentran elementos fuera del viewport? ¿Labels asociados vía `aria-labelledby`/`<label>` están en el nodo? | Página larga con el objetivo al final | Localización ≥ 95 % con scroll iterativo | CDP vía shell como complemento |
| **PoC-5 Setup automatizado** | ¿Se puede activar/desactivar TalkBack y cambiar motor TTS con `WRITE_SECURE_SETTINGS` concedido on-device? | Wireless Debugging + Shizuku | Ciclo on/off confiable 20/20 | Pasos manuales guiados (atajo de volumen) |
| **PoC-6 Dispositivo real** | ¿Se cumple todo en el modelo/fabricante del usuario (Samsung/One UI, Pixel…)? | Repetir PoC-1..3 | Igual | Documentar limitaciones |

Recomendación: PoC-1 a PoC-3 se pueden hacer con una computadora y `adb` **solo para validar** (no viola el requisito de uso final). El PoC-1 debe hacerse antes que cualquier otra cosa.

---

## 9. Implementation Roadmap

| Etapa | Contenido | Salida |
|---|---|---|
| 0. PoCs críticos | PoC-1, 2, 3 (y 6 en el teléfono real) | Informe con matriz de fidelidad y versiones |
| 1. Núcleo determinista | ControllerA11yService + CaptureTtsEngine + Orchestrator; entrada manual (URL + texto del elemento) | Herramienta usable sin IA, reporte JSON + screenshots |
| 2. Setup sin fricción | SetupManager (Shizuku/WD), restauración de estado, verificación previa ("health check") | Ejecución con un solo tap |
| 3. Evaluación | `expected` con normalización, PASS/FAIL/INCONCLUSIVE, ambos modos de navegación | Resultados comparables |
| 4. IA | `InstructionInterpreter` con API (Haiku) + fallback manual; desambiguación entre candidatos cerrados; pregunta al usuario si hay ambigüedad | Interacción conversacional |
| 5. Extras | Historial, exportación Markdown, batch de varios elementos, recorrido completo de la página, servidor MCP opcional | — |

---

## 10. Comparación: propuesta original vs. recomendación

| Aspecto | Propuesta original (ChatGPT) | Evaluación | Recomendación |
|---|---|---|---|
| Plataforma | App Android nativa Kotlin | Correcta | Mantener |
| Captura del anuncio | AccessibilityEvent / NodeInfo (implícito) | **Incorrecta**: da entrada, no salida | **Capture TTS Engine** (`TextToSpeechService`) + oráculos para validación |
| Control de Chrome | AccessibilityService | Correcta pero incompleta | AccessibilityService + medición dual (foco directo y swipe inyectado) + `takeScreenshot()` |
| Coexistencia con TalkBack | No tratada | Riesgo alto | No pedir touch exploration; limpiar buffer; SetupManager que enciende/apaga TalkBack |
| Audio interno | Mencionado como opción | Not feasible para TalkBack | Descartar; micrófono+STT solo evidencia secundaria |
| IA | Agente que interpreta y conduce | Sobredimensionada | LLM solo para parse + desambiguación cerrada; núcleo determinista |
| Uso de suscripciones | "Preferentemente" | No viable como API | API pago por uso (≈ USD 0,001/ejecución con Haiku) u on-device |
| PASS/FAIL | Mencionado | Sin criterio | Normalización + reglas deterministas; LLM solo para criterios semánticos |
| Evidencia | Genérica | Insuficiente | Texto crudo segmentado, screenshots, node dump, versiones y configuración |
| Validación previa | No | Riesgo de construir sobre una suposición falsa | PoC-1 bloqueante antes de implementar |

---

## 11. Open Questions (solo decisiones que necesitan confirmación)

1. **Modelo exacto del teléfono, versión de Android y fabricante** (Pixel vs. Samsung cambia TTS predeterminado, Gemini Nano y posibles diferencias de TalkBack).
2. **¿Aceptás activar Opciones de desarrollador + Wireless Debugging una vez** para automatizar el setup? Si no, el usuario deberá encender/apagar TalkBack manualmente en cada ejecución.
3. **¿Aceptás crear una API key (pago por uso, centavos al mes)** o preferís un modelo on-device / modo sin IA en la v1?
4. **¿Querés escuchar la voz durante las ejecuciones** (modo proxy, más complejo) o alcanza con el modo silencioso?
5. **¿Tenés acceso a una computadora una única vez para los PoCs de validación** (oráculo logcat)? No es necesaria para el uso final, pero acelera mucho la validación del punto crítico.

---

### Notas para la segunda revisión (ChatGPT)

Puntos donde un desacuerdo es más probable y conviene contrastar con documentación:

- Si TalkBack respeta siempre el motor TTS predeterminado del sistema en las versiones actuales (y en Samsung).
- Si `dispatchGesture` desde un segundo servicio es procesado por la exploración táctil de TalkBack.
- Si "Mostrar salida de voz" sigue disponible en la versión actual de TalkBack y qué tan completo es.
- Límites exactos de Chrome para exponer nodos fuera del viewport.
- Términos vigentes de Anthropic y OpenAI sobre uso de suscripciones fuera de sus apps/CLIs oficiales.
