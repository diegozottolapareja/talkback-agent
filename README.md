# TalkBack Agent (Android)

Herramienta on-device que abre una URL en Chrome, ubica un elemento, mueve el foco de TalkBack hasta él y muestra **el texto que TalkBack envió al motor de voz**. La arquitectura está en [`docs/talkback-agent-technical-review.md`](../docs/talkback-agent-technical-review.md).

> Estado: el código compila y los tests unitarios pasan. **Todavía no se probó nada sobre TalkBack en un dispositivo real.** La validación del canal de captura (Milestone 1) la tenés que hacer vos en el Galaxy S23, siguiendo las instrucciones de abajo.

## Módulos

| Módulo | Contenido | Cómo se verificó |
|---|---|---|
| `core/` (Kotlin puro) | Modelos, `RuleBasedInterpreter` (EN/ES), `ElementMatcher`, correlación temporal, veredicto PASS/FAIL/OBSERVED/INCONCLUSIVE | 29 tests unitarios (`./gradlew -PcoreOnly :core:test`) |
| `app/capture` | `CaptureTtsService` (`TextToSpeechService`), `EchoSpeaker`, activities `CHECK_TTS_DATA` / `GET_SAMPLE_TEXT` | Compila. Requiere prueba en el dispositivo |
| `app/a11y` | `ControllerService` (`AccessibilityService`): árbol de Chrome, foco, `dispatchGesture`, `takeScreenshot` | Compila. Requiere prueba en el dispositivo |
| `app/orchestrator` | Abre Chrome, localiza el elemento, Direct Focus + Linear Navigation, ventana de captura, resultado JSON | Compila. Requiere prueba en el dispositivo |
| `app/ui` | Compose: pestañas Run / Diagnóstico / Setup | Compila en CI |

## Qué significa cada estado de un texto capturado

- **solicitado**: TalkBack llamó a `onSynthesizeText` con ese texto. Esta es la evidencia principal: es exactamente lo que TalkBack pidió decir.
- **síntesis completa**: el motor entregó todo el audio y llamó a `done()`. No prueba que se haya reproducido completo: la reproducción ocurre después, en el framework.
- **interrumpido**: el framework llamó a `onStop()` antes de terminar, por ejemplo porque TalkBack vació la cola al moverse el foco.
- **error**: no se pudo iniciar o terminar la síntesis.

El motor devuelve silencio con una duración que imita el habla, para que las interrupciones sigan siendo observables. Con el **eco audible** activado, el mismo texto se reproduce por el motor de Samsung o Google para que lo escuches. El eco no es evidencia de nada.

## Obtener el APK (sin computadora)

Cada push a la rama compila el APK en GitHub Actions y lo publica en la pre-release `talkback-agent-latest`:

`https://github.com/diegozottolapareja/talkback-agent/releases/download/talkback-agent-latest/talkback-agent-debug.apk`

Si el repo es privado, abrí el link en el navegador del teléfono con tu sesión de GitHub iniciada. También lo podés bajar desde Actions → run → Artifacts (viene en un zip).

## Instalación en el Galaxy S23

1. Descargá `talkback-agent-debug.apk` y abrilo. Si Android lo pide, habilitá **Instalar apps desconocidas** para el navegador o para Mis archivos.
2. Abrí **TalkBack Agent** → pestaña **Setup** y seguí los 4 puntos:
   1. **Motor TTS**: `Ajustes → Administración general → Salida de texto a voz → Motor preferido → TalkBack Capture (TTS)`. Si está en otro lugar, usá el botón "Abrir ajustes de texto a voz".
   2. **Servicio de accesibilidad**: `Ajustes → Accesibilidad → Apps instaladas → TalkBack Agent Controller → Activar`. Si aparece **"Ajuste restringido"**: `Ajustes → Aplicaciones → TalkBack Agent → ⋮ (arriba a la derecha) → Permitir ajustes restringidos`, y volvé a intentar.
   3. **TalkBack**: `Ajustes → Accesibilidad → TalkBack`. Conviene configurar el atajo de teclas de volumen para encenderlo y apagarlo rápido.
   4. **Chrome** instalado.
3. Las instalaciones siguientes se hacen encima de la anterior (la clave de firma es fija), así que la configuración se conserva.

**Al terminar las pruebas** volvé a elegir **Samsung TTS** como motor preferido.

## Milestone 1: prueba manual del canal de captura (hacer primero)

Objetivo: demostrar que TalkBack envía a nuestro motor exactamente lo que anuncia.

1. Setup: motor **TalkBack Capture** seleccionado y punto 1 en ✓.
2. Pestaña **Diagnóstico** → tocá **Self-test**. Tiene que aparecer una fila `esta app · … · "Prueba del motor de captura"`. Esto prueba que el motor funciona, independientemente de TalkBack.
3. Opcional, como oráculo: `TalkBack → Configuración → Configuración avanzada → Configuración para desarrolladores → Mostrar salida de voz` (si tu versión lo tiene). TalkBack va a mostrar en pantalla el texto que dice.
4. Activá TalkBack. Con el eco activado deberías seguir escuchando la voz, quizás con un pequeño desfasaje.
5. Abrí Chrome en una página con formulario (por ejemplo, la que querés validar) y navegá con **swipe a la derecha** por 10 a 20 elementos: campos, botones, links, títulos.
6. Volvé a la app → **Diagnóstico**. Para cada swipe tiene que haber filas `TalkBack · … · "texto"`, intercaladas con eventos `TYPE_VIEW_ACCESSIBILITY_FOCUSED`.
7. Compará cada texto con lo que escuchaste o con lo que mostró "Mostrar salida de voz".
8. Si coincide, activá **"Validé que el texto capturado coincide…"**. Hasta que no lo hagas, las ejecuciones automáticas devuelven INCONCLUSIVE.
9. Tocá **Compartir log** y mandame el JSON; así puedo revisar la evidencia.

Qué reportar si **no** aparecen filas de TalkBack:
- El valor de "Motor TTS preferido" que muestra la app.
- Si el Self-test funciona.
- Si TalkBack sigue hablando con la voz de Samsung (sería señal de que ignora el motor preferido).

## Milestones 2-4: ejecución automática

1. Setup completo (los 4 en ✓) y la captura validada.
2. Pestaña **Run**: URL + instrucción, por ejemplo `Find Primary Address and tell me exactly what TalkBack announces.` o `Buscá el campo Primary Address. Debería anunciar "Primary Address, cuadro de edición"`.
3. Debajo del campo, la app muestra cómo interpretó la instrucción: elemento, rol, estrategias y valor esperado.
4. Tocá **Run** (con TalkBack activo: doble toque). La app:
   - abre Chrome y espera a que la página se estabilice;
   - busca el elemento en el árbol de accesibilidad, haciendo scroll si hace falta. Si hay varios candidatos, te pide elegir uno;
   - **Direct Focus**: ejecuta `ACTION_ACCESSIBILITY_FOCUS` sobre el elemento y captura lo que TalkBack manda al motor hasta que pasan 1,8 s de silencio;
   - **Linear Navigation**: pone el foco en el elemento anterior e inyecta swipes a la derecha con `dispatchGesture` hasta llegar al objetivo (máximo 6). Si el gesto no mueve el foco, lo informa: esa es justamente la pregunta del PoC-3;
   - guarda screenshots y el resultado en JSON, y vuelve a la app. Si Android no lo deja volver solo, volvé manualmente.
5. El resultado muestra, para cada estrategia: estado, anuncio concatenado, fragmentos originales con su estado, pasos, eventos de foco, screenshot, notas y versiones de Android, Chrome y TalkBack. **Compartir JSON** exporta todo.

Las dos estrategias se reportan por separado; no se asume que den el mismo resultado.

## Limitaciones conocidas (honestas)

- La captura depende de que TalkBack use el motor TTS preferido del sistema. Hay que confirmarlo en el S23 (Milestone 1).
- "síntesis completa" ≠ "se escuchó completo" (ver arriba).
- Si TalkBack interpreta o no los gestos de `dispatchGesture` no está verificado (PoC-3). Si no los interpreta, Linear Navigation va a reportar NAVIGATION_FAILED con una nota que lo explica.
- La localización usa propiedades del árbol de accesibilidad de Chrome. **Nunca** se presentan como anuncio: el anuncio es solo lo que llegó al motor desde TalkBack.
- El intérprete es por reglas (inglés y español). La interfaz `InstructionInterpreter` permite enchufar más adelante Claude API, OpenAI API o un modelo local.

## Compilar

- Solo el módulo core, sin Android SDK: `./gradlew -PcoreOnly :core:test`
- APK completo (requiere Android SDK): `./gradlew :core:test :app:assembleDebug`. Es lo que corre en CI (`.github/workflows/apk.yml`).
