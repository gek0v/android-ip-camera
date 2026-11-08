import subprocess
import time

issues = [
    {
        "title": "⚡ [Pipeline] Erradicación de Bufferbloat mediante TCP_NOTSENT_LOWAT y Frame Dropping Proactivo",
        "body": """### 📌 Descripción
En conexiones Wi-Fi con fluctuaciones o micro-congestión, el búfer de envío del kernel Linux (`SO_SNDBUF`, habitualmente entre 256KB y 512KB) puede acumular de 3 a 5 fotogramas completos antes de que el transmisor de la app sea consciente. Esto genera una latencia invisible que escala desde 150 ms hasta más de 1.5 segundos.

### 💡 Solución Propuesta
1. Configurar la opción de socket `TCP_NOTSENT_LOWAT` (disponible en Linux / Android API 29+) a un valor bajo (ej. 16KB o 32KB).
2. De esta forma, el kernel solo considera el socket listo para escribir (`poll`/`select` o `isReady`) cuando el búfer de datos no enviados está prácticamente vacío.
3. Descarte proactivo: si el fotograma actual no se puede enviar inmediatamente, se descarta antes de entrar al socket, garantizando que el receptor reciba siempre el fotograma en tiempo real sin lag acumulativo.

### 🎯 Impacto Técnico
- **Esfuerzo:** Bajo
- **Impacto:** Elimina de 150 a 1500 ms de latencia acumulativa oculta en el kernel TCP.
- **Carga en Móvil (Tensor G2):** 0% CPU adicional.
- **Documentación de referencia:** `docs/LOW_LATENCY_PIPE_PROPOSAL.md`"""
    },
    {
        "title": "⚡ [Pipeline] Transporte Binario por WebSocket (/ws/video) de Ultra-Baja Latencia",
        "body": """### 📌 Descripción
El estándar actual de streaming HTTP multipart (`Content-Type: multipart/x-mixed-replace; boundary=frame`) exige transmitir encabezados ASCII de texto (`--frame\\r\\nContent-Type...`) con cada fotograma, además de forzar al navegador a procesar flujos continuos en un parser de texto o streams de fetch.

### 💡 Solución Propuesta
1. Exponer un endpoint WebSocket `/ws/video` (y `/ws/obs`).
2. Cada mensaje WebSocket envía directamente el búfer binario JPEG precedido de una cabecera compacta de 8 bytes (Timestamp 64-bit, flags).
3. En el cliente web, el mensaje llega como un `ArrayBuffer` puro a `socket.onmessage`, transfiriéndose a `createImageBitmap(blob)` con cero sobrecarga de parsing multipart.

### 🎯 Impacto Técnico
- **Esfuerzo:** Medio
- **Impacto:** Reduce la sobrecarga de parsing y cabeceras; latencia extremo a extremo client-side < 20 ms.
- **Carga en Móvil (Tensor G2):** Menor uso de CPU que HTTP multipart gracias a sockets bidireccionales persistentes.
- **Documentación de referencia:** `docs/LOW_LATENCY_PIPE_PROPOSAL.md`"""
    },
    {
        "title": "⚡ [Pipeline] Dynamic Thermal Governor Proactivo para Google Tensor G2",
        "body": """### 📌 Descripción
El SoC Google Tensor G2 (Pixel 7a) ofrece alto rendimiento pero posee una disipación pasiva compacta. En transmisiones ininterrumpidas a 1080p, tras 15-20 minutos la temperatura de la batería puede superar los 42°C, provocando que el kernel de Android aplique thermal throttling y reduzca abruptamente los FPS a 15 fps.

### 💡 Solución Propuesta
1. Monitorear proactivamente la temperatura de batería y térmicos del dispositivo (`BatteryManager.EXTRA_TEMPERATURE` y `PowerManager.addThermalStatusListener`).
2. Implementar un gobernador térmico en tres niveles:
   - **Verde (< 39°C):** Modo máximo rendimiento (1080p60/30, máxima calidad).
   - **Amarillo (39°C - 41.5°C):** Reducción adaptativa de calidad JPEG (de 85% a 70%) para reducir tiempo de codificación y fijación de afinidad de hilos de red exclusivamente a los núcleos de alta eficiencia (Cortex-A55).
   - **Naranja (> 42°C):** Ajuste temporal a 24 fps para estabilizar la disipación sin cortes de transmisión.

### 🎯 Impacto Técnico
- **Esfuerzo:** Medio
- **Impacto:** Evita caídas bruscas de FPS por estrangulamiento térmico en sesiones de streaming prolongadas.
- **Carga en Móvil (Tensor G2):** Mantiene el chip frío (< 41°C sostenidos).
- **Documentación de referencia:** `docs/LOW_LATENCY_PIPE_PROPOSAL.md`"""
    },
    {
        "title": "⚡ [Pipeline] Captura Zero-Copy NDK / C++ con Aceleración ARM NEON",
        "body": """### 📌 Descripción
Actualmente la conversión de formatos YUV a NV21 y la compresión JPEG se ejecutan parcialmente a través de las capas de la JVM y llamadas JNI de alto nivel.

### 💡 Solución Propuesta
1. Implementar un módulo C++ nativo vía NDK utilizando `AImageReader` de Android NDK.
2. Procesamiento de planos YUV y compresión JPEG acelerados con instrucciones vectoriales ARM NEON (`libjpeg-turbo` o `libyuv`).
3. Paso directo de descriptores de memoria sin asignación intermedia de arrays en el heap de Java/ART.

### 🎯 Impacto Técnico
- **Esfuerzo:** Alto
- **Impacto:** Reduce ~14 ms de tiempo de CPU por fotograma a 1080p, permitiendo 1080p60 ultra-fluido.
- **Carga en Móvil (Tensor G2):** -60% de consumo de CPU en el pipeline de codificación.
- **Documentación de referencia:** `docs/LOW_LATENCY_PIPE_PROPOSAL.md`"""
    },
    {
        "title": "🧠 [Edge AI] Auto-Framing ePTZ Cinemático Aprovechando el Sensor de 64 MP (IMX787)",
        "body": """### 📌 Descripción
El Pixel 7a dispone de un sensor principal Sony IMX787 de 64 Megapíxeles. Para una transmisión de 1080p (2 Megapíxeles), existe un excedente masivo de resolución óptica que se desaprovecha en encuadres estáticos.

### 💡 Solución Propuesta
1. Implementar un sistema de seguimiento y reencuadre dinámico (*Auto-Framing* / *Center Stage*) que mantenga al sujeto centrado.
2. En lugar de recortar fotogramas por software en CPU, instruir directamente al ISP de Google Tensor mediante `CaptureRequest.SCALER_CROP_REGION`.
3. Interpolación suave de coordenadas mediante curvas LERP a 60 fps para transiciones cinemáticas sin saltos abruptos.

### 🎯 Impacto Técnico
- **Esfuerzo:** Medio
- **Impacto:** Zoom y seguimiento digital sin pérdida de resolución de 1x a 3x.
- **Carga en Móvil (Tensor G2):** 0% CPU adicional (el recorte se realiza en silicio dentro del ISP).
- **Documentación de referencia:** `docs/AI_TENSOR_VISION_PROPOSAL.md`"""
    },
    {
        "title": "🧠 [Edge AI] Detección Tri-Clase INT8 en Google EdgeTPU (Personas, Mascotas, Paquetes)",
        "body": """### 📌 Descripción
Las cámaras IP convencionales dependen de detección de movimiento por cambio de luma (diferencia de píxeles), lo que produce falsas alarmas por sombras, insectos o cambios de luz ambiental.

### 💡 Solución Propuesta
1. Integrar un modelo ligero de visión (ej. MobileNetV4 / YOLO-Fastest) cuantizado en **INT8**.
2. Delegar la inferencia a la NPU/EdgeTPU de Google Tensor mediante TFLite y la API de NNAPI / Google Tensor Delegate.
3. Clasificación y delimitación de bounding boxes en tiempo real para personas, mascotas y paquetes en un hilo desacoplado a 10-15 Hz.
4. Emisión de eventos inteligentes y metadatos sobre el stream.

### 🎯 Impacto Técnico
- **Esfuerzo:** Alto
- **Impacto:** Detección de objetos inteligente con latencia de inferencia < 7 ms por fotograma.
- **Carga en Móvil (Tensor G2):** < 4% de CPU (cálculo delegado 100% en la NPU).
- **Documentación de referencia:** `docs/AI_TENSOR_VISION_PROPOSAL.md`"""
    },
    {
        "title": "🧠 [Edge AI] Control Gestual Remoto sin Contacto (MediaPipe Hands)",
        "body": """### 📌 Descripción
Al utilizar el teléfono como cámara en un trípode o soporte elevado, cambiar de lente, encender la linterna o bloquear el enfoque requiere desplazarse al ordenador o tocar la pantalla física del móvil, moviendo el encuadre.

### 💡 Solución Propuesta
1. Detección de puntos clave de la mano con modelo ultraligero MediaPipe Hands ejecutado en EdgeTPU.
2. Reconocimiento de gestos clave:
   - ✋ **Palma abierta sostenida:** Disparar Auto-Focus y bloquear posición (AF Lock).
   - ✌️ **Signo de victoria:** Toggle linterna / flash LED.
   - ☝️ **Dedo índice arriba:** Conmutar entre cámara frontal y trasera.
   - 👌 **Gesto OK:** Restablecer zoom a 1.0x (Reset ePTZ).

### 🎯 Impacto Técnico
- **Esfuerzo:** Medio
- **Impacto:** Manejo intuitivo manos libres sin tocar el dispositivo ni el ordenador.
- **Carga en Móvil (Tensor G2):** ~5% de carga GPU/TPU.
- **Documentación de referencia:** `docs/AI_TENSOR_VISION_PROPOSAL.md`"""
    },
    {
        "ti
