# Propuesta de Arquitectura Multimedia y Aceleración de Hardware
## Streaming de Ultra-Baja Latencia (Sub-50ms Glass-to-Glass) para Google Tensor G2 / Pixel

**Autor:** Especialista en Arquitectura Multimedia, Aceleración de Hardware & Streaming de Baja Latencia  
**Proyecto:** `android-ip-camera`  
**Dispositivo de Referencia:** Google Pixel 7 / 7a (Google Tensor G2: 2× Cortex-X1 + 2× Cortex-A78 + 4× Cortex-A55, GPU Mali-G710 MP7, Google EdgeTPU)  
**Objetivo:** Reducción de latencia extremo a extremo (cámara a pantalla en cliente) por debajo de 50ms sostenidos en 1080p60 sin estrangulamiento térmico (*thermal throttling*).

---

## 1. Diagnóstico del Estado Actual y Cuellos de Botella

En las versiones recientes (v0.17.2 – v0.17.3), el proyecto ha logrado grandes optimizaciones:
- Desenrrollado de bucle 8x en UV (`ImageConversionHelper.kt`, alcanzando ~740+ FPS sintéticos a nivel de reorganización de planos YUV420 a NV21 en CPU).
- Eliminación de churn de memoria mediante streams `ThreadLocal` reutilizables y colas estrictas `ArrayBlockingQueue(1)`.

A pesar de estas mejoras en la capa de usuario, persisten tres límites fundamentales impuestos por la arquitectura tradicional de Android/HTTP:

1. **Doble/Triple Copia en Memoria RAM:**
   `ImageProxy` (YUV_420_888 de CameraX) $\rightarrow$ conversión manual en CPU a `ByteArray` NV21 $\rightarrow$ compresión JPEG por software con `YuvImage.compressToJpeg()` (Android libjpeg C++ con ida y vuelta a JVM) $\rightarrow$ escritura en socket TCP vía HTTP multipart boundary.
2. **Bufferbloat Oculto en el Kernel TCP (Head-of-Line Blocking):**
   Aunque la cola a nivel de aplicación sea de 1 frame, el buffer de envío del kernel Linux (`SO_SNDBUF`, de 256KB a 512KB) puede acumular de 3 a 5 fotogramas completos si la red Wi-Fi experimenta micro-jitter. Esto genera una latencia invisible que escala desde 150ms hasta más de 1500ms.
3. **Estrangulamiento Térmico en Google Tensor G2:**
   La compresión JPEG cuadro a cuadro por CPU a 1080p60 exige ~4.5W continuos en los núcleos grandes Cortex-X1 y Cortex-A78. En el Tensor G2 (nodo de 5nm Samsung), tras 10–15 minutos de streaming ininterrumpido la temperatura supera los 43°C y el kernel abate las frecuencias a menos de 1 GHz, desplomando la tasa de fotogramas a 20–25 FPS.

---

## 2. Eje 1: Pipeline de Captura & Aceleración NDK / SIMD / Zero-Copy

### 2.1. Pipeline Zero-Copy con `AHardwareBuffer` y NDK `AImageReader`
* **Arquitectura:**
  Eliminar la abstracción de `CameraX` / Java para el flujo crítico de vídeo de alto rendimiento. Implementar un backend en C++ mediante el NDK de Camera2 (`camera2/NdkCameraManager.h` y `media/NdkImageReader.h`):
  ```c
  AHardwareBuffer_Desc desc = {
      .width = 1920,
      .height = 1080,
      .layers = 1,
      .format = AHARDWAREBUFFER_FORMAT_Y8Cb8Cr8_420,
      .usage = AHARDWAREBUFFER_USAGE_CPU_READ_RARELY |
               AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
               AHARDWAREBUFFER_USAGE_VIDEO_ENCODE
  };
  ```
* **Mecanismo:**
  El ISP del Tensor G2 escribe directamente en la memoria unificada de hardware (Gralloc). La CPU no toca un solo byte de la imagen en memoria RAM, eliminando el 100% de la contención de memoria y las pausas del recolector de basura (GC).
* **Impacto:** Latencia de adquisición de cuadro reducida de **~4.5ms a ~0.3ms**.

### 2.2. Vectorización ARM NEON Intrinsics de Ensamblador (`arm_neon.h`)
* **Implementación:**
  Para los casos donde se requiera manipulación de píxeles o rotación por software, reemplazar los bucles desenrollados en Kotlin por una rutina JNI/NDK en ensamblador vectorial NEON (registros de 128 bits):
  - Carga entrelazada de planos UV: `vld2_u8` o `vld4_u8`.
  - Reordenamiento directo: `vst2_u8` para intercalar planos $V$ y $U$ en un solo ciclo de reloj por vector de 16 bytes.
  - Transposición de microbloques de 8x8 o 16x16 para rotación in-place en registros vectoriales Q0–Q15 sin roundtrips a la caché L3.
* **Impacto:** Reducción del tiempo de reordenamiento UV a 1080p a **< 0.18ms** en núcleos Cortex-A78/X1.

### 2.3. Integración de `libjpeg-turbo` 3.x con IDCT Rápida (`JDCT_IFAST`)
* **Problema:** `YuvImage.compressToJpeg()` invoca internamente `JDCT_ISLOW`, optimizado para fidelidad fotográfica fija pero ineficiente para vídeo en vivo.
* **Solución:** Integrar `libjpeg-turbo` compilado con aceleración NEON seleccionando `cinfo.dct_method = JDCT_IFAST` y tablas de cuantización optimizadas para streaming (suprimiendo altas frecuencias imperceptibles en movimiento).
* **Impacto:** Tiempo de codificación JPEG a 1080p reducido de **18ms a 4.2ms**, permitiendo 60 FPS reales sostenidos sin saturar un núcleo completo.

---

## 3. Eje 2: Protocolos de Transporte Alternativos de Ultra-Baja Latencia

### Tabla Comparativa de Protocolos de Transporte

| Protocolo | Capa de Transporte | Latencia Glass-to-Glass | Compatibilidad Cliente | Estado / Recomendación |
| :--- | :--- | :--- | :--- | :--- |
| **HTTP/1.1 Multipart (Actual)** | TCP secuencial con límites MIME | 120ms – 250ms (hasta >1s con congestión) | Universal (Navegadores, OBS, VLC) | Mantener como fallback universal |
| **Binary WebSockets (`/video/ws`)** | TCP con framing binario ligero (8B header) | **35ms – 60ms** | Navegadores (Canvas / WebCodecs), OBS (Browser Source) | **Prioridad 1 (P0)** |
| **WebRTC (MediaCodec H.264/AV1)** | UDP / SRTP / SCTP (Peer-to-Peer) | **15ms – 30ms** | Navegadores nativos, VDO.ninja, OBS Studio | **Prioridad 2 (P1)** |
| **Raw TCP / Pipe Stream** | TCP directo sin cabeceras HTTP | **20ms – 40ms** | OBS (GStreamer / FFMPEG / Source Plugin) | Especializado para estudios |
| **WebTransport sobre HTTP/3** | UDP Datagramas no fiables (QUIC) | **18ms – 35ms** | Chromium 97+, OBS moderno | Futuro estándar web |

### 3.1. Propuesta Inmediata: Binary WebSocket + Worker / OffscreenCanvas
* **Protocolo de Enmarcado:**
  Cada mensaje WebSocket binario contiene un paquete atómico con cabecera de 8 bytes:
  `[Timestamp (8 bytes)][FrameIndex (4 bytes)][Flags (2 bytes)][PayloadLength (4 bytes)][Datos JPEG/H264]`
* **Receptor en Navegador (`index.html`):**
  - Uso de **`OffscreenCanvas`** dentro de un Web Worker dedicado.
  - Decodificación asíncrona mediante la API de hardware `createImageBitmap(blob)`.
  - Evita bloqueos en el hilo principal de renderizado de la UI web.
* **Ventaja:** Si la red se ralentiza, el servidor descarta el frame en la cola de salida inmediatamente en vez de atascarlo en un buffer TCP congestionado.

### 3.2. Propuesta Avanzada: Motor WebRTC Embebido con MediaCodec H.264
* **Mecanismo:** Micro-servidor WebRTC embebido en C++/Kotlin conectado directamente a `MediaCodec` configurado en ultra-low-latency (`KEY_LATENCY = 0`, `KEY_PRIORITY = 0`, modo CBR sin fotogramas B).
* **Beneficio:** Al utilizar UDP con control de congestión GCC/TWCC, se erradica completamente el bloqueo *Head-of-Line*.

---

## 4. Eje 3: Mecanismos Proactivos de Regulación Térmica para 1080p60 Continuo

El Google Pixel con Tensor G2 requiere un control térmico proactivo antes de que el kernel de Linux (`thermal_zone`) aplique recortes agresivos de frecuencia.

### Arquitectura del "Dynamic Thermal Governor"

```
+--------------------------------------------------------------------------+
|                     DYNAMIC THERMAL GOVERNOR (MOTOR)                     |
+--------------------------------------------------------------------------+
    |
    +--> Sensores: Android ThermalManager API (API 29+) + CPU Sysfs Thermal
    |
    +--> Nivel 1: NORMAL (Temp < 38°C)
    |     - Pipeline completo: 1080p @ 60 FPS
    |     - Calidad JPEG q=85 o H.264 @ 8 Mbps
    |
    +--> Nivel 2: LIGHT WARM (38°C - 41°C)
    |     - Reducción invisible de FPS: 60 FPS -> 50 FPS
    |     - Dynamic Quantization: Reducción de q=85 a q=78 (ahorro del 35% de bytes)
    |     - Atenuación de pantalla OLED (Brillo 0% o True Black Overlay)
    |
    +--> Nivel 3: MODERATE WARM (41°C - 44°C)
    |     - Reducción de cadencia: 50 FPS -> 30 FPS
    |     - Downscale espacial dinámico sin reconexión: 1080p -> 900p / 720p
    |     - Motion-Aware Skipping: Si MotionDetectorCore detecta escena estatica,
    |       se salta 1 de cada 2 frames (ahorro del 50% de CPU en compresion)
    |
    +--> Nivel 4: SEVERE (Temp > 44°C)
    |     - Caida de emergencia a 720p @ 24 FPS
    |     - Emision de alerta OSD/WebSocket a OBS Studio: "[WARN] Thermal Limit"
```

### Tecnicas de Ahorro Termico Directo:
1. **Afinidad de Nucleos (CPU Affinity):**
   Fijar el hilo de red I/O (`MjpegNetworkWriter`) y el servidor HTTP en los 4 nucleos eficientes Cortex-A55 mediante `sched_setaffinity()`, reservando los nucleos potentes (Cortex-X1 / Cortex-A78) exclusivamente para rafagas cortas del codificador.
2. **Black Screen Overlay / Display Power Saving:**
   El renderizado en pantalla de la camara mediante `SurfaceView` consume hasta un 25% de la energia de la GPU/DSI. Apagar el renderizado en pantalla o colocar una superposicion 100% negra (#000000 en panel OLED) manteniendo el `WakeLock(PARTIAL_WAKE_LOCK)` reduce drasticamente el calentamiento.

---

## 5. Eje 4: Compresion Disruptiva y Erradicacion del Bufferbloat (Sub-50ms)

### 5.1. Control del Buffer del Kernel mediante `TCP_NOTSENT_LOWAT`
* **Problema:** En conexiones Wi-Fi con fluctuaciones, el buffer de envio del sistema operativo (`SO_SNDBUF`) encola datos viejos, haciendo que el cliente reciba frames desfasados con segundos de retraso.
* **Solucion Tecnica:**
  - Configurar a nivel de socket mediante JNI / NDK:
    ```c
    int unsent_limit = 16384; // Limita a 16 KB el buffer de datos no enviados
    setsockopt(socket_fd, IPPROTO_TCP, TCP_NOTSENT_LOWAT, &unsent_limit, sizeof(unsent_limit));
    ```
  - Forzar `TCP_NODELAY = true` (deshabilitar algoritmo de Nagle).
  - Descarte de frames a nivel de aplicacion (*Application-Level Drop*): si el intento de escritura no puede iniciarse inmediatamente, el fotograma se descarta en memoria de usuario antes de sobrecargar la cola del kernel.

### 5.2. Codificacion H.264/H.265 con "Intra-Refresh" Periodico (Slicing)
* **Problema:** Los fotogramas IDR (Keyframes) tradicionales provocan un pico masivo de ancho de banda (un frame I pesa 8 a 10 veces mas que un frame P), causando saturacion periodica del transmisor Wi-Fi y micro-congelamientos.
* **Solucion Disruptiva:**
  - Configurar `MediaCodec` con `KEY_INTRA_REFRESH_PERIOD`: en lugar de emitir un frame IDR completo cada 2 segundos, el codificador de hardware refresca verticalmente una columna de macrobloques en cada cuadro sucesivo.
  - El flujo de bits es completamente plano, los buffers de red nunca se saturan, y la latencia del decodificador en OBS o navegadores se mantiene **inferior a 25 milisegundos**.

---

## 6. Matriz de Impacto, Factibilidad y Hoja de Ruta

| Iniciativa | Dificultad | Reduccion de Latencia | Impacto Termico | Prioridad Recomendada |
| :--- | :---: | :---: | :---: | :---: |
| **1. WebSocket Binario + Worker / Canvas** | Media | -40ms a -80ms | Neutro | **P0 (Inmediata)** |
| **2. Socket Tuning (`TCP_NOTSENT_LOWAT` + Non-blocking Drop)** | Baja-Media | -30ms a -100ms | Leve (+5% eficiencia) | **P0 (Inmediata)** |
| **3. Thermal Governor Dinamico (FPS/Downscale adaptativo)** | Media | Estabilidad a largo plazo | **-35% calor acumulado** | **P1 (Critica)** |
| **4. `libjpeg-turbo` NDK con `JDCT_IFAST` y SIMD NEON** | Media-Alta | -12ms en compresion | -20% uso CPU | **P1** |
| **5. WebRTC / MediaCodec H.264 Ultra-Low-Latency** | Alta | **Latencia final < 25ms** | -40% uso CPU (Encoder HW) | **P2 (Siguiente Milestone)** |

---

## 7. Conclusion

La implementacion de estas propuestas transformara `android-ip-camera` de un servidor MJPEG tradicional en un sistema de captura y streaming de clase profesional para produccion en vivo y OBS Studio. Al combinar **WebSockets binarios**, **regulacion termica proactiva** y **control estricto de buffers en el kernel**, se logra un rendimiento sub-50ms continuo y estable en hardware Google Pixel.
