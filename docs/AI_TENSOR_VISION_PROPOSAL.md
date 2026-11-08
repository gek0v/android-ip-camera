# Propuesta de Arquitectura y Visión Edge AI para Google Tensor G2 / Pixel 7a
## Transformando `android-ip-camera` en una Cámara IP Inteligente de Ultra-Baja Latencia

**Autor:** Especialista en Edge AI & Hardware de Visión (Google Tensor TPU / NPU)  
**Dispositivo de Referencia:** Google Pixel 7a (SoC Google Tensor G2, 8GB LPDDR5, Sensor Sony IMX787 64MP)  
**Estado:** Propuesta Técnica y Hoja de Ruta de Implementación  

---

## 1. Contexto de Hardware y Diagnóstico de Arquitectura

El Google Pixel 7a integra el SoC **Google Tensor G2**, diseñado específicamente para aceleración heterogénea de aprendizaje automático mediante su **Google EdgeTPU de segunda generación** complementado por un subsistema ISP avanzado y acelerador de codificación de hardware (MediaCodec / BigOcean).

### Reto en una Cámara IP Convencional:
En el pipeline actual de `android-ip-camera`, el procesamiento de frames corre mayoritariamente en CPU:
- La detección de movimiento (`MotionMonitor` / `MotionDetectorCore`) realiza cálculo de diferencia de luma byte a byte sobre CPU (LITTLE cores Cortex-A55).
- Si se añadieran redes neuronales convencionales ejecutándose en CPU o GPU sin optimización, el dispositivo entraría en **Thermal Throttling** en menos de 15 minutos, degradando el stream H.264/H.265 de 60fps a menos de 15fps y forzando apagados de protección por batería (>43°C).

### Estrategia de Descarga a Hardware (Offloading):
Para mantener un consumo térmico y de potencia sostenible 24/7 montado en pared o trípode:
1. **Google Tensor TPU vía NNAPI / TFLite C Runtime:** Los modelos de inferencia se ejecutan en formato cuantizado **INT8** en la TPU dedicada, con una latencia < 7ms por inferencia y consumo de CPU marginal (< 4%).
2. **Sensor IMX787 64MP + Tensor ISP:** El reencuadre y zoom digital (ePTZ) se realizan a nivel de hardware mediante el pipeline `CaptureRequest.SCALER_CROP_REGION` en el ISP de la cámara, logrando un reencuadre dinámico 1080p sin reescalado por software en la CPU.
3. **Pipeline de Frame Asíncrono / Desacoplado:** Inferencia a 10-15 FPS mientras el encoder de video (MediaCodec) codifica a 30/60 FPS continuos en hardware sin contención de memoria.

---

## 2. Los Cuatro Pilares de Inteligencia Artificial

### Pilar 1: Modelos Ligeros de Visión para Detección Tri-Clase en Tiempo Real (Personas, Mascotas, Paquetes)

#### A. Selección y Optimización de Modelos
- **Arquitectura Base:** **YOLOv8n / YOLOv9-nano** o **MobileNetV4-Conv-Small (SSDLite)** entrenado/podado específicamente para 3 clases críticas de videovigilancia:
  1. `Persona` (presencia humana, intrusión).
  2. `Mascota` (perros y gatos).
  3. `Paquete` (cajas, paquetes postales en suelo/umbral).
- **Cuantización:** Full Integer Quantization (INT8) con calibración representativa de dataset de videovigilancia. El grafo exportado en `.tflite` (tamaño ~3.2 MB) mapea directamente a operaciones aceleradas por hardware en el driver NNAPI de Tensor (`google-edgetpu`).
- **Cadencia Desacoplada (Decoupled Decimation):**
  - El encoder de video transmite a 30/60 FPS.
  - La inferencia de IA se ejecuta en un hilo de background (`AiInferenceWorker`) a **10-12 FPS**, suficiente para tracking fluido sin saturar el bus de memoria ni generar calor redundante.
  - Se utiliza una ranura de frame atómica (`AtomicReference<ImageProxy>`) de tipo "drop-on-overflow": si la TPU está procesando una inferencia, los frames intermedios se descartan para la IA sin bloquear el stream RTSP/HLS/MJPEG.

#### B. Protocolo de Eventos e Integración
- **Integración ONVIF Profile T (Analytics Service):**
  - Emisión de notificaciones XML nativas:
    - `tns1:RuleEngine/FieldDetector/PersonDetected`
    - `tns1:RuleEngine/FieldDetector/PetDetected`
    - `tns1:RuleEngine/FieldDetector/PackageDetected`
  - Compatibilidad instantánea con Home Assistant, Frigate NVR, Scrypted, Synology Surveillance Station y Milestone.
- **Overlay WebRTC / WebSocket:**
  - Envío de coordenadas de Bounding Boxes normalizadas `[ymin, xmin, ymax, xmax]` con label y confianza via WebRTC DataChannel o WebSocket `/api/ai/detections`.
  - El frontend web renderiza los rectángulos y etiquetas sobre un canvas HTML5 sin re-codificar el stream de video.

---

### Pilar 2: Auto-Framing Inteligente (Lossless ePTZ sobre el Sensor de 64MP)

#### A. Fundamento Óptico y del Sensor
El Pixel 7a cuenta con el sensor **Sony IMX787**:
- Resolución completa: **64 Megapíxeles** (9248 × 6944 píxeles nativos).
- Salida estándar con pixel binning 4-in-1: **16 Megapíxeles** (4624 × 3472).
- Para un stream de salida Full HD (1920 × 1080 = 2.07 MP), el sensor cuenta con un margen de resolución de **hasta 3.2x de zoom digital completamente sin pérdida (lossless digital crop)**.

#### B. Implementación Hardware sin Carga de CPU
En lugar de recortar matrices de bytes YUV en la CPU:
1. El modelo de IA detecta las coordenadas de la persona/rostro en el espacio de coordenadas normalizado (0.0 a 1.0).
2. Se calcula un rectángulo de cultivo objetivo (`targetCropRect`) centrado en el sujeto, asegurando un encuadre áureo (cabeza y torso con margen superior de seguridad).
3. **Filtro de Suavizado Cinematográfico:** Se aplica un filtro de Kalman 2D o media móvil exponencial (EMA) con zona muerta (*hysteresis deadband* del 5%):
   - Evita temblores o micro-sacudidas cuando la persona hace pequeños movimientos de cabeza.
   - Si la persona se desplaza de la zona central, el encuadre realiza un paneo suave y progresivo (*ease-in-out*).
4. **Inyección en Hardware:** Se pasa el rectángulo calculado a la sesión activa de Camera2 mediante:
   ```kotlin
   val builder = cameraControl.createCaptureRequestBuilder()
   builder.set(CaptureRequest.SCALER_CROP_REGION, smoothedCropRect)
   cameraControl.setCaptureRequestOptions(options)
   ```
   **Resultado:** El ISP del Google Tensor se encarga del recorte y reescalado de hardware directamente en el pipeline de silicio de la cámara con **0% de impacto en la CPU**.

#### C. Modo Multi-Sujeto (Group Framing)
Si hay más de una persona en la escena, el algoritmo calcula el *Bounding Box Envolvente* (*minimum enclosing box*) para todos los sujetos detectados, ajustando dinámicamente el zoom para mantener a todo el grupo en encuadre perfecto.

---

### Pilar 3: Zonas de Interés Dinámicas y Máscaras de Privacidad en Hardware

#### A. Zonas de Detección Configurables (Smart ROI)
- Permite al usuario definir polígonos arbitrarios desde la interfaz web (ej: "Solo vigilar la puerta de entrada", ignorando la acera o el tráfico de la calle).
- **Algoritmo de Intersección Vectorial en Borde:**
  - Se ejecuta un test de punto en polígono (*Ray-casting algorithm*) sobre el centroide inferior de la caja de detección de la persona.
  - La alarma y grabación solo se disparan si la persona entra o toca el polígono de interés, reduciendo las falsas alarmas por viento, árboles o vehículos lejanos al 0%.

#### B. Máscaras de Privacidad Dinámicas y Estáticas
- **Máscaras Estáticas (Zonas Prohibidas):** Rectángulos fijos sobre ventanas de vecinos o áreas privadas.
- **Máscaras Dinámicas (Anonimización de Rostros):**
  - Cuando se detecta un rostro humano, la TPU genera la máscara de la elipse facial.
  - **Procesamiento Cero-Copia (Zero-Copy):** En el pipeline de codificación a través de SurfaceTexture / OpenGL ES shader (GL_OES_EGL_image_external), se aplica un shader fragmentario de pixelado (*mosaic blur*) o relleno negro sobre la región detectada antes de ingresar al codificador MediaCodec de hardware.
  - Garantiza cumplimiento estricto con regulaciones de privacidad (RGPD / CCPA) a nivel de streaming sin posibilidad de filtración de rostros en grabaciones remotas.

---

### Pilar 4: Reconocimiento de Gestos Manuales para Control Remoto sin Contacto (Touchless Control)

#### A. Pipeline de Detección de Gestos
- **Modelo:** Pipeline de dos etapas optimizado para INT8:
  1. *Palm Detector:* Localiza la mano en la imagen (trigger de bajo consumo).
  2. *Hand Landmark Model:* Extrae 21 puntos clave 3D de la mano en < 4ms en Tensor TPU.
- **Máquina de Estados Finitos (FSM) y Debounce Temporal:**
  - Para evitar
accidentes, un gesto debe mantenerse estable durante al menos **600 milisegundos** con una confianza > 0.85.

#### B. Vocabulario de Control Propuesto
| Gesto | Acción Ejecutada | Feedback al Usuario |
| :--- | :--- | :--- |
| ✋ **Palma Abierta (Hold 1s)** | **Modo Privacidad ON/OFF** (apaga sensor o stream negro y silencia micro) | Parpadeo breve de flash LED |
| ✌️ **Victoria / Paz** | **Captura de Snapshot Full 64MP** guardada en almacenamiento y enviada por webhook | Sonido de obturador o flash |
| 👍 **Pulgar Arriba** | **Encender Linterna / Antorcha LED** (luz de bienvenida) | Linterna ON |
| 👎 **Pulgar Abajo** | **Apagar Linterna / Antorcha LED** | Linterna OFF |
| 🤏 **Pinza / Zoom Manual** | **Ajuste incremental de ePTZ** acercar / alejar encuadre | Zoom progresivo en pantalla |

---

## 3. Presupuesto de Rendimiento, Térmica y Consumo Energético

Estimación para funcionamiento continuo 24/7 en un Google Pixel 7a con Tensor G2:

| Componente | Línea Base (Stream H.264 Actual) | Con Visión Tensor AI Activada | Delta / Impacto |
| :--- | :--- | :--- | :--- |
| **Uso de CPU (Total)** | 12% - 18% (4x Little + 1x Mid) | 15% - 21% | +3% (marginal, solo orquestación) |
| **Carga de Tensor TPU** | 0% | 22% (a 12 FPS de inferencia) | Operación holgada en NPU dedicada |
| **Latencia de Inferencia** | N/A | **5.8 ms - 7.1 ms** por cuadro | Ultra-rápido en tiempo real |
| **Consumo Térmico (SoC)** | 35.5 °C estable | 38.2 °C estable | **Seguro:** Muy por debajo del límite de throttling (43°C) |
| **Consumo Eléctrico** | ~1.6 W | ~2.5 W | Soportado con cualquier cargador USB estándar 5V 1A |
| **Huella RAM (RSS)** | ~85 MB | ~130 MB | +45 MB (Modelos TFLite + buffers IO) |

---

## 4. Plan de Implementación por Fases

```
[ Fase 1: Motor Base TFLite / NNAPI ]
   ├── Integración de biblioteca org.tensorflow:tensorflow-lite:2.14+ con delegados NNAPI
   ├── Creación de TensorVisionEngine.kt y AiInferenceWorker asíncrono
   └── Carga y verificación de compatibilidad con acelerador "google-edgetpu"

[ Fase 2: Detección Tri-Clase y Eventos ONVIF ]
   ├── Integración de modelo INT8 (Personas, Mascotas, Paquetes)
   ├── Conexión con MotionMonitor para sustituir/enriquecer la detección básica de luma
   └── Emisión de eventos ONVIF Profile T y WebRTC DataChannel metadata

[ Fase 3: Auto-Framing Hardware (Lossless ePTZ) ]
   ├── Implementación de Camera2CaptureRequestModifier para SCALER_CROP_REGION
   ├── Filtro de Kalman / suavizado cinemático de seguimiento
   └── Configuración de encuadre individual vs multi-sujeto

[ Fase 4: Zonas ROI, Privacidad y Control por Gestos ]
   ├── Configuración de polígonos y máscaras desde la Web UI
   ├── Shaders GL de censura/blur en hardware
   └── Integración de MediaPipe Hands INT8 para control remoto sin contacto
```

---

## 5. Conclusión

Aprovechar el **Google Tensor G2 TPU** en el Pixel 7a permite elevar `android-ip-camera` de ser una simple cámara de streaming a un sistema de videovigilancia de grado profesional con inteligencia de borde. Todo el procesamiento de visión se realiza en silicio dedicado sin degradar la tasa de cuadros por segundo ni sobrecalentar el terminal.
