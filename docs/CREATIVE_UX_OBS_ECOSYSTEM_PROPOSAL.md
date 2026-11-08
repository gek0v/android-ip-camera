# Propuesta de Arquitectura UX, Ecosistema de Streaming y Domotica
## Transformando `android-ip-camera` en una Estacion de Produccion Audiovisual y Nodo IoT

**Autor:** Especialista en Experiencia de Usuario (UI/UX) y Ecosistema de Streaming (OBS Studio, Home Assistant, Frigate, NVR)
**Proyecto:** `android-ip-camera`
**Estado:** Propuesta Tecnica y Especificacion de Diseno
**Fecha:** Noviembre 2024

---

## 1. Vision General y Diagnostico de Experiencia de Usuario

### 1.1 El Salto de Camara Web Pasiva a Nodo de Produccion Inteligente
La mayoria de camaras IP para Android se limitan a exponer un socket MJPEG o RTSP con una pagina de configuracion austera. Sin embargo, en el ecosistema actual de creadores de contenido (Twitch, YouTube, Kick) y smart homes (Home Assistant, Frigate NVR):
- **Los creadores de contenido** requieren senales activas de estado (Tally Light), control de encuadre dinamico (ePTZ) sin tocar el soporte del movil y fuentes Web optimizadas sin filtros redundantes en OBS.
- **La domotica moderna** exige descubrimiento automatico (MQTT Discovery), control del hardware (flash LED, modos de privacidad) y sensores de presencia y telemetria.
- **La monitorizacion web** demanda Picture-in-Picture del sistema para multitarea, matrices multi-camara distribuidas y esteticas de panel adaptadas a entornos de streaming oscuros.

Esta propuesta define la arquitectura, contratos de comunicacion y pautas de diseno para cada una de estas dimensiones sin saturar el procesador ni la gestion termica del smartphone.

---

## 2. Pilar 1: Suite Especializada para OBS Studio y Creadores de Contenido

### 2.1 Sistema Tally Light Bidireccional (En Vivo / Previo)
En un plato o setup de streaming, el creador suele tener el smartphone montado sobre un tripode o brazo articulado fuera de su alcance inmediato. Necesita confirmacion visual inequivoca de su estado sin desviar la mirada al monitor principal.

```
       +------------------------+
       |   OBS Studio / vMix    |
       |   (obs-websocket v5)   |
       +-----------+------------+
                   | Eventos: SceneTransitionStarted, OutputStateChanged
                   v
       +------------------------+
       | android-ip-camera      |  HTTP REST / WebSockets
       | StreamingService       |<-----------------------+
       +-----------+------------+                        |
                   |                                     |
         +---------+---------+                  +--------+--------+
         v                   v                  v                 v
   [Pantalla Movil]     [Flash LED]       [Browser Source]  [Stream Deck]
   Borde OLED 360       Pulsos suaves     Borde indicador   Boton dinamico
   Rojo = En Vivo       Flash frontal     en Canvas /obs    Tally Status
   Verde = Preview      para plato
```

#### A. Senaletica en la Pantalla del Smartphone
- **Borde Perimetral OLED (Tally Border):** Marco de 8px en los cuatro bordes de la pantalla ejecutado nativamente en MainActivity o como overlay cuando la pantalla esta atenuada.
  - **Rojo (#EF4444):** Program / En Vivo (la escena que incluye esta camara esta en el aire).
  - **Verde (#10B981):** Preview / Preparado (la escena esta seleccionada en previo de OBS en Modo Estudio).
  - **Azul (#3B82F6):** Standby / Conectado pero no activo en la escena de transmision.
  - **Naranja (#F59E0B):** Advertencia de sistema (bateria < 15% o temperatura SoC > 42C).
- **Ahorro Energetico OLED:** El interior del marco permanece en negro puro (#000000), apagando los diodos emisores y reduciendo el consumo a < 0.8% de bateria por hora con Tally activo.

#### B. Protocolo de Comunicacion y Endpoints
- **Conector obs-websocket v5 Nativo:** El servicio Android se conecta como cliente WebSocket a la IP de OBS Studio en el puerto 4455, procesando eventos CurrentProgramSceneChanged y StreamStateChanged.
- **Endpoint REST /api/tally:**
  - Metodo: POST /api/tally
  - Payload JSON:
```json
{
  "state": "program",
  "scene": "Camara Principal",
  "stream_active": true,
  "record_active": false
}
```

### 2.2 OBS Browser Source Avanzada (/obs) con Shaders WebGL
El endpoint /obs se optimiza para ejecutarse dentro de OBS Browser Source (motor Chromium CE) procesando efectos directamente en la GPU del ordenador emisor, con **cero sobrecarga de CPU en el smartphone**.

#### Parametros de URL Disponibles en /obs:
| Parametro | Valores Aceptados | Descripcion |
|---|---|---|
| chroma | none, green, blue, hex | Extraccion de fondo croma por shader WebGL |
| tolerance | 0.10 a 0.90 (def: 0.35) | Umbral de tolerancia de color en espacio YUV |
| spill | 0.0 a 1.0 (def: 0.20) | Supresion de reflejo verde en contornos |
| blur | 0 a 20 px | Desenfoque gaussiano de fondo (simulacion bokeh) |
| mirror | true, false | Espejado horizontal automatico de la toma |
| fit | contain, cover, fill | Ajuste geometrico al lienzo configurado en OBS |
| tally | true, false | Borde sutil de Tally en la esquina de la fuente |
| hud | true, false | Telemetria en pantalla para monitor privado |

#### Fragment Shader WebGL para Chroma Keying en Cliente:
```glsl
precision mediump float;
uniform sampler2D u_frame;
uniform vec3 u_keyColor;
uniform float u_tolerance;
uniform float u_spill;
varying vec2 v_texCoord;

void main() {
    vec4 color = texture2D(u_frame, v_texCoord);
    float diff = distance(color.rgb, u_keyColor);
    if (diff < u_tolerance) {
        discard; // Transparencia pura directa a OBS
    } else {
        if (color.g > max(color.r, color.b)) {
            color.g = mix(color.g, max(color.r, color.b), u_spill);
        }
        gl_FragColor = color;
    }
}
```

---

## 3. Pilar 2: Controles PTZ Virtuales (Pan / Tilt / Zoom Digital Cinemático)

### 3.1 Arquitectura Híbrida: Sensor Crop + Matriz de Render
Para evitar la pérdida de calidad típica de los zooms digitales por software, se implementa una arquitectura en dos escalones:

```
       +---------------------------------------------+
       |   Sensor Alta Resolucion (ej: 64MP / 50MP)  |
       |                                             |
       |      +-------------------------------+      |
       |      |     ePTZ Hardware Window      |      |
       |      |   (SCALER_CROP_REGION Camera2)|      |
       |      |     1920x1080 1:1 Sensor Crop |      |
       |      +-------------------------------+      |
       +---------------------------------------------+
                              |
                              v
                 Pipeline MJPEG / RTSP 1080p
                              |
               +--------------+--------------+
               v                             v
     [Panel Web de Control]         [OBS Browser Source]
     - Interpolacion LERP           - Paneo suave a 60fps
     - Joystick virtual             - Viewport cinematografico
     - Presets 1, 2, 3              - Atajos de teclado
```

1. **Nivel Hardware ISP (Camera2 `SCALER_CROP_REGION`):**
   - El zoom y desplazamiento principal se solicitan al procesador de imagen (ISP) del dispositivo. En sensores de alta densidad (ej. IMX787 del Pixel 7a), los aumentos de 1.0x a 2.5x son recortes 1:1 de fotodiodos reales, sin interpolacion ni degradacion de nitidez.
2. **Nivel Cliente Web (CSS Matrix3D / WebGL Transform):**
   - Durante el movimiento interactivo, la interfaz web aplica una transformacion de viewport acelerada por GPU a 60 FPS, absorbiendo cualquier latencia de red y ofreciendo una sensacion inmediata y tactil.

### 3.2 Curvas de Interpolacion y Presets Cinemáticos
- **Curvas Bezier Suaves (Ease-in-out Cubic):** Las transiciones entre posiciones evitan paradas mecanicas secas, simulando cabezales fluidos de estudio.
- **Banco de Presets de Produccion:**
  - **Preset 1 (Rostro / Facecam):** Encuadre centrado en busto y rostro a 1.4x de zoom.
  - **Preset 2 (Mesa / Top-down Review):** Paneo hacia la superficie de trabajo o teclado a 1.8x.
  - **Preset 3 (Plano General / Wide Room):** Apertura total a 1.0x para interaccion con el entorno.
- **Atajos de Teclado Profesionales:**
  - `Teclas 1, 2, 3`: Conmutacion instantanea con transicion suave al preset.
  - `W / A / S / D` o `Flechas`: Pan y Tilt progresivos.
  - `+` / `-` o `Rueda del Raton`: Zoom in / out.
  - `Espacio` o `Home`: Reestablecer al centro neutro.

### 3.3 API REST para Elgato Stream Deck y Bitfocus Companion
Permite enlazar consolas fisicas de botones para produccion en vivo:
- `POST /api/ptz/move?pan=10&tilt=-5&zoom=1.5&duration=1000`
- `POST /api/ptz/preset?id=1`
- `POST /api/ptz/save_preset?id=1`
- `GET /api/ptz/status`
```json
{
  "pan": 10,
  "tilt": -5,
  "zoom": 1.5,
  "active_preset": 1,
  "is_transitioning": false
}
```

---

## 4. Pilar 3: Integraciones con Domotica (Home Assistant, MQTT, Frigate, NVR)

### 4.1 Home Assistant MQTT Discovery (Zero-Touch Setup)
Al activar el conector MQTT en la aplicacion, esta publica mensajes retained en el broker Mosquitto para auto-registrar el dispositivo en Home Assistant sin requerir configuracion manual en `configuration.yaml`:

```
          +--------------------------------------------------------+
          |                  android-ip-camera                     |
          +-----------+--------------------------------+-----------+
                      |                                |
            MQTT Discovery & Telemetria              Webhooks
                      |                                |
                      v                                v
       +-----------------------------+  +-----------------------------+
       |     Mosquitto Broker        |  |      Frigate / NVR Server   |
       | (homeassistant/#)           |  | (RTSP/MJPEG, Event Webhook) |
       +--------------+--------------+  +-----------------------------+
                      |
                      v
       +-----------------------------+
       |       Home Assistant        |
       |  - Entidad de Camara        |
       |  - Sensores de Movimiento   |
       |  - Switches Linterna / Modo |
       |  - Bateria y Temperatura    |
       +-----------------------------+
```

#### Entidades Expuestas a Home Assistant:
1. `camera.ip_camera_<id>`: Stream MJPEG y capturas de snapshot en tiempo real.
2. `binary_sensor.ip_camera_motion`: Sensor de presencia/movimiento (`device_class: motion`).
3. `sensor.ip_camera_battery_level`: Porcentaje de carga restante (`device_class: battery`).
4. `binary_sensor.ip_camera_charging`: Estado de conexion electrica (`device_class: battery_charging`).
5. `sensor.ip_camera_temperature`: Temperatura interna del SoC (`device_class: temperature`).
6. `switch.ip_camera_torch`: Control remoto de la linterna LED fisica.
7. `switch.ip_camera_privacy`: Desactiva la captura y apaga los sensores al estar en casa.
8. `select.ip_camera_lens`: Selector de lente fisica (`Main`, `Ultra-Wide`, `Frontal`).
9. `button.ip_camera_ptz_preset_1..3`: Disparadores de presets de camara en automatizaciones.

### 4.2 Webhooks Bidireccionales para Automatizaciones Domoticas
- **Webhooks Salientes (Alertas):** Ante eventos de deteccion de movimiento o umbral de bateria critica (<10%), emision de un POST a `http://<ha-ip>:8123/api/webhook/ipcam_alert` con payload de telemetria y URL de snapshot.
- **Webhooks Entrantes (Actuadores de Alarma):**
  - `POST /api/alarm/strobe`: Dispara destellos estroboscopicos con el flash LED para alertar de intrusiones.
  - `POST /api/control/privacy`: Apaga inmediatamente los streams y desactiva el microfono.

### 4.3 Optimizacion para Frigate NVR & Blue Iris
- **Endpoint de Snapshot Ultra-Rapido:** `/video/snapshot?res=640x360` con latencia menor a 40ms sirviendo directamente el bufer de frame activo en RAM sin operaciones de disco.
- **Sincronizacion Temporal:** Cabecera `X-Camera-Timestamp` con timestamp epoch UTC en microsegundos, permitiendo a los NVRs indexar grabaciones sin desfase horario.

---

## 5. Pilar 4: Mejoras de Interactividad en el Panel de Control Web

### 5.1 Picture-in-Picture (PiP) Nativo del Sistema
Permite desanclar la vista de la camara a una ventana flotante del sistema operativo mediante la API HTML5 Picture-in-Picture, permaneciendo visible sobre OBS, Discord o videojuegos:

```javascript
async function togglePictureInPicture() {
    try {
        let pipSink = document.getElementById('pipVideoSink');
        if (!pipSink) {
            pipSink = document.createElement('video');
            pipSink.id = 'pipVideoSink';
            pipSink.muted = true;
            pipSink.autoplay = true;
            pipSink.style.display = 'none';
            document.body.appendChild(pipSink);
            const stream = streamCanvas.captureStream(30);
            pipSink.srcObject = stream;
            await pipSink.play();
        }
        if (document.pictureInPictureElement) {
            await document.exitPictureInPicture();
        } else {
            await pipSink.requestPictureInPicture();
        }
    } catch (err) {
        showToast('Error PiP: ' + err.message);
    }
}
```

### 5.2 Matriz Multi-Camara (Studio Grid / Quad View)
Para creadores y usuarios con multiples telefonos reciclados, el panel web integra un modo de vista en cuadricula (Bento Grid) que monitorea hasta 4 camaras simultaneamente:

```
+------------------------------------------------------------------------+
|  STUDIO MULTI-CAM GRID (4 NODOS)                  [+ Anadir Camara]    |
+-----------------------------------+------------------------------------+
|  CAM 1: Google Pixel 7a (Face)    |  CAM 2: Redmi Note (Unboxing Desk) |
|  [ MJPEG LIVE 1080p @ 30fps ]     |  [ MJPEG LIVE 1080p @ 30fps ]      |
|  * REC   82% Bateria  34 C        |  * REC   95% Bateria  32 C         |
|  [PTZ: Preset 1] [Linterna OFF]   |  [PTZ: Preset 2] [Linterna ON]     |
+-----------------------------------+------------------------------------+
|  CAM 3: Galaxy S10 (Room Wide)    |  CAM 4: Tablet (Side Angle)        |
|  [ MJPEG LIVE 720p @ 30fps ]      |  [ MJPEG LIVE 720p @ 24fps ]       |
|  o IDLE  45% Bateria  30 C        |  o IDLE  100% Carga   29 C         |
|  [PTZ: Preset 3] [Linterna OFF]   |  [PTZ: Centrado] [Linterna OFF]    |
+-----------------------------------+------------------------------------+
|  Acciones Globales: [Grabar Todas] [Linterna Alerta] [Modo Privacidad] |
+------------------------------------------------------------------------+
```

- **Conexiones Directas Peer-to-Peer:** El navegador se conecta de forma paralela e independiente a la IP de cada dispositivo, evitando cuellos de botella en la red.
- **Controles Globales Unificados:** Conmutacion simultanea de grabacion, linternas y balance de blancos.

### 5.3 Temas Visuales Studio Skins para Streamers
Se incorporan paletas tematicas adaptables con un solo click:
1. **OLED Void (#000000):** Fondo negro absoluto de maximo contraste para platos oscuros y pantallas OLED.
2. **Cyberpunk Streamer (#0f0c20):** Degradados neon cian (#06B6D4) y magenta (#EC4899) para streamers de gaming.
3. **Broadcast Blackmagic Slate (#1e222b):** Consola sobria estilo ATEM con tipografia monoespaciada para codigo de tiempo y bitrate.
4. **Clean Studio Light (#F8FAFC):** Modo claro editorial de alta legibilidad para entornos de oficina y produccion diurna.

### 5.4 HUD de Telemetria Dinamico
- **Medidor VU de Audio Estereo en dBFS:** Monitoreo dinamico de picos con alerta visual de saturacion.
- **Monitor de Carga Termica y Throttling:** Semaforo termico que avisa preventivamente antes de que el dispositivo degrade el framerate.
- **RTT y Calidad de Transporte:** Medicion de latencia de red y caida de frames en tiempo real.

---

## 6. Matriz de Impacto, Factibilidad y Rendimiento

| Caracteristica | Impacto UX / Valor Usuario | Complejidad Tecnica | Impacto en CPU Movil | Impacto en Baterica |
|---|:---:|:---:|:---:|:---:|
| **Tally Light en Pantalla Movil** | 5 / 5 (Critico para Streamers) | Baja | Nulo (< 0.1%) | Minimo (< 1%/h con OLED) |
| **OBS WebGL Shaders (/obs)** | 5 / 5 (Elimina plugins en PC) | Media | Cero (procesa GPU del PC) | Nulo |
| **ePTZ Hardware/Sensor Crop** | 4 / 5 (Control cinematico) | Media | Minimo (gestionado por ISP) | Despreciable |
| **Home Assistant MQTT Discovery** | 5 / 5 (Apertura a Domotica) | Media | Marginal (< 0.5%) | Despreciable |
| **Web Picture-in-Picture (PiP)** | 4 / 5 (Multitarea fluida) | Baja | Cero (procesa navegador) | Nulo |
| **Multi-Cam Studio Grid** | 5 / 5 (Escalabilidad de setup) | Media | Cero en el movil emisor | Nulo en el movil emisor |
| **Temas Studio & Audio VU Meter** | 3 / 5 (Estetica y confort visual) | Baja | Cero | Nulo |

---

## 7. Hoja de Ruta de Implementacion Propuesta (Phased Rollout)

### Fase 1: Modulo OBS & Control Cinematico (Quick Wins - Alta Visibilidad)
- Ampliar el endpoint `/obs` con parametros de query (`chroma`, `fit`, `mirror`, `tally`).
- Implementar la logica de interpolacion ePTZ (`CaptureRequest.SCALER_CROP_REGION`) y los atajos de teclado en el panel web (`WASD`, teclas `1-3`).
- Anadir el boton y disparador nativo de Picture-in-Picture (`captureStream()`) en `index.html`.

### Fase 2: Ecosistema Domotico & Tally Light (Conectividad & Automatizacion)
- Integrar cliente MQTT ligero en Kotlin (HiveMQ MQTT Client o socket coroutine sin dependencias externas pesadas).
- Configurar publicacion automatica de entidades MQTT Discovery para Home Assistant.
- Implementar overlay Tally Light en `MainActivity` activable mediante endpoints HTTP `/api/tally`.

### Fase 3: Multi-Camara & Experiencia Pro Studio (Consolidacion)
- Crear la vista de mosaico `/studio` o tab Multi-Cam en la web SPA para gestionar hasta 4 dispositivos simultaneamente.
- Integrar selector de temas CSS (`OLED Void`, `Broadcast Slate`, `Cyberpunk Neon`).
- Anadir soporte para perfiles de exportacion para Elgato Stream Deck y Bitfocus Companion.

---

## 8. Conclusion

Esta propuesta posiciona a `android-ip-camera` como una herramienta de referencia para creadores de contenido y entusiastas de domotica, permitiendo reciclar terminales Android en **camaras de produccion broadcast** y **sensores inteligentes perimetrales**.
El principio rector de **descargar el procesamiento pesado al cliente web** (WebGL shaders, PiP, matrices CSS) garantiza que el smartphone preserve su rendimiento termico y dedique toda su energia a capturar y codificar video impecable.
