# Práctica 6 · Telemetría ESP32 en web y móvil

Aplicación con **backend Java**, **frontend HTML/CSS/JavaScript** y **SQLite**, basada en `TelemetriaIoT.java` y `ESP32_Telemetria.ino`. La computadora recibe las mediciones del circuito y ofrece el mismo panel a los navegadores de PC y celular.

## Ejecutar en Windows

Desde PowerShell, en la carpeta del repositorio:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\iniciar.ps1
```

Abre **http://localhost:3000**. También puedes iniciar haciendo doble clic en `iniciar.cmd`. Para probar sin el circuito, selecciona **Demo** y pulsa **Iniciar monitoreo**. Los datos simulados se identifican como `DEMO` y se pueden excluir con el filtro de origen.

El script compila, ejecuta las pruebas y arranca el backend. Si no tienes Java o Maven, descarga herramientas portátiles de sus proveedores oficiales en `.tools/` y verifica sus archivos. La primera ejecución necesita Internet; las posteriores reutilizan esas herramientas. No modifica instalaciones del sistema. La descarga automática del JDK requiere Windows x64.

Pulsa **Ctrl+C** en la terminal para cerrar el servidor. Cerrar una pestaña solo cierra el panel: el backend continúa recibiendo y guardando datos hasta que pulses **Detener** o cierres el servidor.

Con **JDK 17 o posterior y Maven** instalados, también puedes usar Windows, Linux o macOS:

```sh
mvn clean package
java -jar target/telemetria-web.jar
```

El frontend está incluido en el JAR. No requiere Node, React ni un servidor web adicional.

## Conectar el ESP32

Abre `firmware/ESP32_Telemetria/ESP32_Telemetria.ino` en Arduino IDE. Es el programa suministrado, sin cambios en los GPIO ni el protocolo. El servidor Java sustituye a la ventana Swing.

1. Configura `WIFI_SSID` y `WIFI_PASSWORD`. El ESP32, la computadora y el celular deben estar en la misma red local.
2. Para **UDP**, cambia `PC_IP` por la IPv4 de la **computadora que ejecuta Java**. En Windows se obtiene con `ipconfig`. Por ejemplo: `IPAddress PC_IP(192, 168, 1, 90);` si la PC tiene `192.168.1.90`.
3. Carga el programa con las bibliotecas DHT sensor library, Adafruit Unified Sensor, Adafruit GFX Library y Adafruit SSD1306, y el soporte ESP32 de Arduino IDE.
4. En la página, introduce la IPv4 del **ESP32**, mostrada en la OLED o el monitor serie. Es distinta de `PC_IP`: en UDP filtra el emisor; en TCP identifica el dispositivo al que consultar.
5. Selecciona el modo que corresponde al DIP físico e inicia el monitoreo.

| Modo | DIP 1 / GPIO 13 | Comportamiento |
| --- | --- | --- |
| UDP | ON / LOW, conectado a GND | ESP32 transmite a la PC en **4210**, cada **50 ms**. |
| TCP | OFF / HIGH, `INPUT_PULLUP` | Java consulta al ESP32 en **8080**, cada **5 s** después de completar la consulta anterior. |
| Demo | No necesita circuito | Java genera datos simulados cada **2 s**. |

El DHT11 produce una muestra real aproximadamente cada **2 s**. Los envíos UDP de 50 ms repiten la última muestra. El contador distingue paquetes de muestras guardadas; SQLite registra una sola lectura por ID en cada sesión. Solo se aceptan paquetes de la IP configurada.

En TCP se conserva la conversación original, en **el mismo socket**:

```text
Java -> ESP32: GET_TEMP\n
ESP32 -> Java: TEMP=25.4;HUM=58.0;ID=2000\r\n
Java -> ESP32: ALERTA_ON\n o ALERTA_OFF\n
```

Al detener TCP, el backend intenta una consulta adicional para enviar `ALERTA_OFF`. Si falla, el panel informa que hay que revisar el dispositivo o cambiar el DIP a UDP para desactivar el estrobo.

**Cierra la ventana Swing si usa UDP:** ocuparía el mismo puerto 4210. Si Windows solicita acceso, permite Java en **redes privadas**. Para el celular se necesita TCP entrante en el puerto 3000; para las mediciones UDP se necesita UDP entrante en 4210. Una red con aislamiento entre clientes Wi-Fi puede impedir estas conexiones.

## Abrir en el celular

Con el backend en ejecución, abre en el navegador del teléfono:

```text
http://IP_DE_LA_COMPUTADORA:3000
```

La consola muestra la dirección de red local al iniciar. El teléfono debe usar la misma red Wi-Fi. `localhost` en el teléfono apunta al propio teléfono, por lo que allí debes usar la IP de la PC. El diseño adapta tarjetas, controles y gráfica; la tabla permite desplazamiento horizontal.

Todos los navegadores comparten el monitoreo y la configuración. Al iniciar o detener desde el celular, la PC refleja el cambio. Es una web adaptable; está preparada para una práctica en red local de confianza. No incluye autenticación para publicación en Internet.

## Funciones y reglas

- Temperatura en °C, humedad relativa en %, conexión y estado de alerta.
- Umbral de temperatura configurable, por defecto **≥ 32 °C**.
- Aumento entre muestras nuevas configurable, por defecto **≥ 2 °C**.
- Termómetro de ocho segmentos: **20 °C → 1**, **30 °C → 4**, **40 °C → 8**.
- Gráfica de temperatura y humedad de las últimas **240** muestras del filtro.
- Historial paginado, filtros UDP/TCP/Demo y última hora/24 horas/todo el historial.
- Estadísticas de todas las muestras que coinciden con el filtro y CSV de **todos** sus registros, independientemente de la página actual.
- Registro persistente de eventos; se muestran los últimos 30.
- Aviso si no llegan muestras nuevas durante **15 s**, aunque sigan llegando paquetes repetidos.

La línea de umbral de la gráfica corresponde a la configuración actual. Cada sesión conserva los umbrales usados para sus alertas. Como en Java Swing, UDP ignora los duplicados y conserva la alerta hasta la siguiente muestra nueva. TCP responde a cada consulta: si se repite el ID, evalúa únicamente el umbral de temperatura, sin generar otro aumento abrupto. El historial conserva la alerta que tuvo la muestra al registrarse.

Los IDs del firmware proceden de `millis()`. Si el ESP32 se reinicia o el contador se desborda, pulsa **Detener** e inicia otra sesión. Los IDs anteriores se ignoran y generan un aviso. Al reiniciar Java, el historial permanece, pero debes iniciar el monitoreo nuevamente.

## Base de datos

SQLite se crea automáticamente en `data/telemetria.sqlite`; no requiere instalar MySQL ni configurar credenciales. El esquema está en `database/schema.sql`.

**Sí se conserva el historial del sensor.** Cada muestra nueva recibida mientras el monitoreo está activo se inserta en `measurements`, con temperatura, humedad, fecha y hora, ID del sensor, modo UDP/TCP, IP y alertas. Cerrar el navegador, detener el monitoreo o reiniciar Java no elimina esas mediciones. En el panel selecciona **Todo el historial** para ver también registros de días anteriores. Los paquetes repetidos no crean filas adicionales.

`schema.sql` contiene la definición de las tablas; los registros históricos están en el archivo **`telemetria.sqlite`**. Los datos DEMO también se guardan, con su origen separado para poder filtrarlos.

### Preparar otra computadora Windows

1. Copia o descarga el repositorio en una carpeta con permisos de escritura.
2. Conéctala a Internet y haz doble clic en **`preparar-equipo.cmd`**. Descarga Java/Maven si faltan, resuelve la biblioteca SQLite, compila, ejecuta las pruebas y crea la base de datos con sus tablas. No arranca el monitor ni genera mediciones.
3. Cuando termine, ejecuta **`iniciar.cmd`** y abre `http://localhost:3000`.
4. Actualiza `PC_IP` en el firmware para UDP y configura en la página la IP del ESP32. Estas direcciones pueden cambiar al usar otra computadora.

SQLite viene incluido mediante `sqlite-jdbc` en el JAR del backend: **no necesitas un instalador de SQLite, un servicio de Windows ni un servidor de base de datos independiente**. La preparación obtiene todo lo necesario. Las herramientas permanecen en `.tools/` y el archivo de datos en `data/`; no modifica instalaciones del sistema. La descarga automática de Java requiere Windows x64.

También puedes preparar el equipo desde PowerShell:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\iniciar.ps1 -Action prepare
```

Para inicializar las tablas con un JAR ya compilado y Java instalado:

```sh
java -jar target/telemetria-web.jar --init-db
```

**Para trasladar el historial:** detén el servidor en ambas computadoras y copia `data/telemetria.sqlite` a la carpeta `data/` del nuevo equipo antes de preparar o iniciar. No sobrescribas una base de datos del destino que contenga registros que quieras conservar. Git no incluye `data/`, así que descargar el repositorio por sí solo no transfiere tus mediciones. El inicializador conserva las tablas y registros existentes.

Si el nuevo equipo no tendrá Internet, copia además `.tools/` y `target/` desde una preparación completa realizada en otra computadora Windows x64. El repositorio debe incluir también el código, `pom.xml` y los iniciadores. Estas carpetas no están incluidas al descargar únicamente el código desde Git.

| Tabla | Contenido |
| --- | --- |
| `settings` | Último modo, IP y umbrales. |
| `sessions` | Inicio/fin, modo, dispositivo y umbrales usados. |
| `measurements` | Fecha UTC, temperatura, humedad, ID, incremento, segmentos y alertas. |
| `events` | Conexiones, avisos y errores. |

Los datos y las herramientas portátiles están excluidos de Git. Las consultas JDBC usan parámetros. Las fechas se almacenan en UTC; el panel las muestra en la zona horaria del navegador y el CSV las exporta en UTC. Para respaldar, detén primero el servidor y copia `data/telemetria.sqlite`.

Para cambiar puerto o archivo de base de datos antes de arrancar:

```powershell
$env:PORT = '3001'
$env:DB_PATH = 'data\otra-telemetria.sqlite'
powershell -NoProfile -ExecutionPolicy Bypass -File .\iniciar.ps1
```

`HOST` cambia la dirección de escucha. Su valor predeterminado es `0.0.0.0` para admitir acceso desde el celular.

## Estructura

```text
frontend/                         HTML, CSS, JavaScript e icono
src/main/java/mx/ipn/telemetria/
  TelemetriaServer.java            Servidor HTTP y API REST
  TelemetryService.java            UDP, TCP, demo y estado en vivo
  Protocol.java                    Formato ESP32, validación y alertas
  Database.java                    SQLite mediante JDBC
src/test/java/mx/ipn/telemetria/    Pruebas unitarias y de integración
database/schema.sql               Esquema de base de datos
firmware/ESP32_Telemetria/         Programa original del ESP32
iniciar.ps1                       Preparación, pruebas y ejecución
preparar-equipo.cmd                Preparación de Java, Maven y SQLite en otra PC
pom.xml                           Dependencias y construcción del JAR
```

## API

| Método y ruta | Función |
| --- | --- |
| `GET /api/dashboard` | Estado, historial, estadísticas, gráfica y eventos. |
| `POST /api/start` | Iniciar UDP, TCP o Demo con configuración JSON. |
| `POST /api/stop` | Detener la sesión actual. |
| `GET /api/export.csv` | Descargar todas las mediciones del filtro. |

Filtros: `mode=all|UDP|TCP|DEMO`, `period=1h|24h|all` y `page=1...`. Los controles POST requieren `Content-Type: application/json` y rechazan orígenes distintos al servidor. Ejemplo de inicio:

```json
{
  "mode": "TCP",
  "deviceIp": "192.168.1.101",
  "highThreshold": 32,
  "jumpThreshold": 2
}
```

## Pruebas

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\iniciar.ps1 -Action test
```

O con Maven instalado: `mvn test`.

Se comprueban paquetes inválidos, umbrales, segmentos, UDP real en loopback, duplicados, falta de muestras nuevas, reinicio de sesión, puerto ocupado, TCP con servidor ESP32 simulado, `ALERTA_ON/OFF`, persistencia, API y CSV filtrado. Las pruebas usan bases temporales y no modifican `data/telemetria.sqlite`. La validación del circuito físico requiere conectar el ESP32 y configurar sus direcciones.

Referencias técnicas: [servidor HTTP del JDK](https://docs.oracle.com/en/java/javase/17/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html), [SQLite JDBC](https://github.com/xerial/sqlite-jdbc).
