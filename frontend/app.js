'use strict';

const $ = (id) => document.getElementById(id);
const number = new Intl.NumberFormat('es-MX');
const time = new Intl.DateTimeFormat('es-MX', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
const date = new Intl.DateTimeFormat('es-MX', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit' });
const SVG = 'http://www.w3.org/2000/svg';
let initialized = false;
let pendingAction = false;
let page = 1;
let pages = 1;
let latestState = null;
let requestId = 0;
let controller;
let refreshInFlight = false;
let chartKey = '';
let eventsKey = '';

function selectedMode() { return document.querySelector('input[name="mode"]:checked').value; }
function filters() { return new URLSearchParams({ mode: $('source-filter').value, period: $('period-filter').value, page: String(page) }); }
function formatTemperature(value) { return value == null ? '— °C' : `${Number(value).toFixed(1)} °C`; }
function changed(delta) { return `${delta > 0 ? '+' : ''}${Number(delta).toFixed(1)} °C`; }

function modeHelp() {
  const mode = selectedMode();
  $('mode-help').textContent = {
    UDP: 'DIP 1 en ON (LOW). Escucha en el puerto 4210.',
    TCP: 'DIP 1 en OFF (HIGH). Consulta al ESP32:8080 cada 5 s.',
    DEMO: 'Datos simulados cada 2 s. No requiere un ESP32 conectado.',
  }[mode];
  $('device-ip').disabled = mode === 'DEMO' || Boolean(latestState?.running);
  $('device-ip').required = mode !== 'DEMO';
  const detail = $('protocol-detail');
  detail.replaceChildren();
  const strong = document.createElement('strong');
  detail.append(mode === 'TCP' ? 'TCP cada ' : mode === 'DEMO' ? 'Demo cada ' : 'UDP cada ');
  strong.textContent = mode === 'TCP' ? '5 s' : mode === 'DEMO' ? '2 s' : '50 ms';
  detail.append(strong);
}

function populateConfig(config) {
  document.querySelector(`input[name="mode"][value="${config.mode}"]`).checked = true;
  $('device-ip').value = config.deviceIp;
  $('high-threshold').value = config.highThreshold;
  $('jump-threshold').value = config.jumpThreshold;
  modeHelp();
}

function updateControls(available = true) {
  const running = Boolean(latestState?.running);
  $('config-fields').disabled = !available || pendingAction || running;
  $('start-button').disabled = !available || pendingAction || running;
  $('stop-button').disabled = !available || pendingAction || !running;
  modeHelp();
}

function svgElement(name, attributes, text) {
  const node = document.createElementNS(SVG, name);
  for (const [key, value] of Object.entries(attributes)) node.setAttribute(key, String(value));
  if (text !== undefined) node.textContent = text;
  return node;
}

function renderChart(rows, threshold) {
  const width = Math.max(280, Math.round($('chart').getBoundingClientRect().width));
  const key = JSON.stringify([rows.map((row) => row.id), threshold, width]);
  if (key === chartKey) return;
  chartKey = key;
  const content = $('chart-content');
  content.replaceChildren();
  $('chart-empty').hidden = rows.length > 0;
  $('chart').setAttribute('viewBox', `0 0 ${width} 245`);
  const left = 40, right = width - 42, top = 25, bottom = 209;
  const minTemp = rows.length ? Math.floor((Math.min(...rows.map((r) => r.temperature), threshold) - 2) / 5) * 5 : 15;
  const maxTemp = rows.length ? Math.ceil((Math.max(...rows.map((r) => r.temperature), threshold) + 2) / 5) * 5 : 40;
  const yTemp = (t) => bottom - (t - minTemp) / (maxTemp - minTemp) * (bottom - top);
  const yHum = (h) => bottom - h / 100 * (bottom - top);
  const start = rows.length ? Date.parse(rows[0].receivedAt) : 0;
  const end = rows.length ? Date.parse(rows[rows.length - 1].receivedAt) : 1;
  const x = (row) => end === start ? (left + right) / 2 : left + (Date.parse(row.receivedAt) - start) / (end - start) * (right - left);
  for (let i = 0; i <= 4; i++) {
    const y = top + i / 4 * (bottom - top);
    content.append(svgElement('line', { x1: left, y1: y, x2: right, y2: y, stroke: '#eaf0eb', 'stroke-width': 1 }));
    if (rows.length) {
      content.append(svgElement('text', { x: left - 9, y: y + 4, fill: '#95a699', 'font-size': 10, 'text-anchor': 'end' }, String(Math.round(maxTemp - i / 4 * (maxTemp - minTemp)))));
      content.append(svgElement('text', { x: right + 9, y: y + 4, fill: '#a59ab8', 'font-size': 10 }, `${100 - i * 25}%`));
    }
  }
  if (!rows.length) { $('chart').setAttribute('aria-label', 'Sin mediciones para el filtro seleccionado.'); return; }
  content.append(svgElement('text', { x: left - 9, y: 13, fill: '#95a699', 'font-size': 9, 'text-anchor': 'end' }, '°C'));
  const coordinates = rows.map((row) => `${x(row).toFixed(2)},${yTemp(row.temperature).toFixed(2)}`).join(' ');
  if (rows.length > 1) content.append(svgElement('polygon', { points: `${x(rows[0])},${bottom} ${coordinates} ${x(rows[rows.length - 1])},${bottom}`, fill: '#eaf8f0', opacity: '.8' }));
  content.append(svgElement('line', { x1: left, y1: yTemp(threshold), x2: right, y2: yTemp(threshold), stroke: '#c9b17b', 'stroke-width': 1, 'stroke-dasharray': '4 5' }));
  content.append(svgElement('polyline', { points: rows.map((row) => `${x(row).toFixed(2)},${yHum(row.humidity).toFixed(2)}`).join(' '), fill: 'none', stroke: '#a28bc1', 'stroke-width': 1.5, 'stroke-dasharray': '4 3', 'stroke-linejoin': 'round' }));
  content.append(svgElement('polyline', { points: coordinates, fill: 'none', stroke: '#218b6e', 'stroke-width': 2.3, 'stroke-linejoin': 'round', 'stroke-linecap': 'round' }));
  const markerStride = Math.max(1, Math.ceil(rows.length / ((right - left) / 8)));
  for (const [index, row] of rows.entries()) {
    if (index % markerStride !== 0 && index !== rows.length - 1) continue;
    const dot = svgElement('circle', { cx: x(row), cy: yTemp(row.temperature), r: row.highAlert || row.jumpAlert ? 3.5 : 2.2, fill: row.highAlert || row.jumpAlert ? '#cf8067' : '#218b6e', stroke: '#fff', 'stroke-width': 1 });
    dot.append(svgElement('title', {}, `${date.format(new Date(row.receivedAt))} · ${row.temperature.toFixed(1)} °C · ${row.humidity.toFixed(0)} % · ${row.mode}`));
    content.append(dot);
  }
  const ticks = rows.length === 1 ? [rows[0]] : width < 400 ? [rows[0], rows[rows.length - 1]] : [rows[0], rows[Math.floor((rows.length - 1) / 2)], rows[rows.length - 1]];
  const unique = new Set();
  for (const row of ticks) {
    if (unique.has(row.id)) continue;
    unique.add(row.id);
    content.append(svgElement('text', { x: x(row), y: 233, fill: '#9cac9f', 'font-size': 10, 'text-anchor': 'middle' }, time.format(new Date(row.receivedAt))));
  }
  $('chart').setAttribute('aria-label', `${rows.length} muestras. Temperatura mínima ${Math.min(...rows.map((r) => r.temperature))} °C y máxima ${Math.max(...rows.map((r) => r.temperature))} °C. También se muestra la humedad relativa.`);
}

function renderHistory(history) {
  page = history.page;
  pages = history.pages;
  $('history-count').textContent = number.format(history.stats.count);
  $('stat-min').textContent = formatTemperature(history.stats.min);
  $('stat-average').textContent = formatTemperature(history.stats.average);
  $('stat-max').textContent = formatTemperature(history.stats.max);
  $('stat-alerts').textContent = number.format(history.stats.alerts);
  $('page-label').textContent = `${page} / ${pages}`;
  $('previous-page').disabled = page <= 1;
  $('next-page').disabled = page >= pages;
  const body = $('history-body');
  body.replaceChildren();
  if (!history.rows.length) {
    const row = document.createElement('tr');
    const cell = document.createElement('td');
    cell.colSpan = 7;
    cell.className = 'table-empty';
    cell.textContent = 'No hay mediciones para este filtro. Inicia una sesión o cambia el origen y el periodo.';
    row.append(cell); body.append(row);
  }
  for (const data of history.rows) {
    const row = $('row-template').content.cloneNode(true);
    row.querySelector('.row-date').textContent = date.format(new Date(data.receivedAt));
    const source = row.querySelector('.source-tag');
    source.textContent = data.mode === 'DEMO' ? 'DEMO' : data.mode;
    source.classList.toggle('demo', data.mode === 'DEMO');
    source.title = data.mode === 'DEMO' ? 'Datos simulados' : `ESP32 ${data.deviceIp}`;
    row.querySelector('.row-temperature').textContent = formatTemperature(data.temperature);
    row.querySelector('.row-humidity').textContent = `${Number(data.humidity).toFixed(0)} %`;
    row.querySelector('.row-delta').textContent = changed(data.delta);
    row.querySelector('.row-id').textContent = data.sampleId;
    const state = row.querySelector('.reading-state');
    const alert = Boolean(data.highAlert || data.jumpAlert);
    state.classList.add(alert ? 'alert' : 'normal');
    state.textContent = data.highAlert && data.jumpAlert ? 'Alta + aumento' : data.highAlert ? 'Temperatura alta' : data.jumpAlert ? 'Aumento abrupto' : 'Normal';
    body.append(row);
  }
  $('export-button').href = `/api/export.csv?${filters()}`;
}

function renderEvents(events) {
  const key = events.map((event) => event.id).join(',');
  if (key === eventsKey) return;
  eventsKey = key;
  const list = $('event-list');
  list.replaceChildren();
  for (const event of events) {
    const node = $('event-template').content.cloneNode(true);
    node.querySelector('.event-dot').classList.add(event.level);
    node.querySelector('.event-message').textContent = event.message;
    const timestamp = node.querySelector('time');
    timestamp.dateTime = event.createdAt;
    timestamp.textContent = date.format(new Date(event.createdAt));
    list.append(node);
  }
}

function render(data) {
  latestState = data;
  if (!initialized || data.running) populateConfig(data.config);
  initialized = true;
  $('server-error').hidden = true;
  $('status-text').textContent = data.status;
  $('connection-status').className = `status-pill ${data.running ? data.fresh ? 'connected' : 'waiting' : ''}`;
  $('last-sync').textContent = `Actualizado ${time.format(new Date(data.serverTime))}`;
  $('demo-banner').hidden = !(data.config.mode === 'DEMO' && (data.running || data.latest));
  $('device-warning').hidden = !data.lastError && !(data.running && data.latest && !data.fresh);
  $('device-warning').textContent = data.lastError || 'No llegan muestras nuevas desde hace más de 15 s. Revisa el sensor y la conexión. La última lectura permanece visible.';
  const reading = data.latest;
  $('temperature').textContent = reading ? Number(reading.temperature).toFixed(1) : '—';
  $('humidity').textContent = reading ? Number(reading.humidity).toFixed(0) : '—';
  $('temperature-detail').textContent = reading ? `${data.running && data.fresh ? 'Última muestra' : 'Última lectura guardada'} · ${changed(reading.delta)}` : 'Esperando la primera muestra';
  $('sample-count').textContent = number.format(data.samples);
  $('packet-count').textContent = number.format(data.packets);
  const liveAlert = data.running && data.fresh && data.activeAlert;
  $('alert-card').classList.toggle('alerting', Boolean(liveAlert));
  $('alert-state').textContent = !reading ? 'Sin datos' : !data.running ? 'Detenido' : !data.fresh ? 'Sin datos nuevos' : liveAlert ? 'Alerta activa' : 'Normal';
  $('alert-detail').textContent = liveAlert ? (reading.highAlert ? `Temperatura ≥ ${data.config.highThreshold} °C` : `Aumento ≥ ${data.config.jumpThreshold} °C`) : `Umbral configurado: ${data.config.highThreshold} °C`;
  const segments = reading?.segments || 0;
  $('segments-text').textContent = `${segments} / 8 segmentos`;
  $('segments').setAttribute('aria-label', `${segments} de 8 segmentos encendidos en la última lectura.`);
  [...$('segments').children].forEach((segment, index) => segment.classList.toggle('lit', index < segments));
  $('chart-live').textContent = data.running ? 'Actualiza cada 2 s' : 'Historial';
  $('chart-live').classList.toggle('active', data.running);
  renderHistory(data.history);
  renderChart(data.history.chart, data.config.highThreshold);
  renderEvents(data.events);
  updateControls();
}

async function refresh() {
  const id = ++requestId;
  controller?.abort();
  controller = new AbortController();
  const currentController = controller;
  refreshInFlight = true;
  const timeout = setTimeout(() => currentController.abort(), 7000);
  try {
    const response = await fetch(`/api/dashboard?${filters()}`, { signal: currentController.signal, cache: 'no-store' });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'No se pudo cargar el panel.');
    if (id === requestId) render(data);
  } catch (error) {
    if (id !== requestId) return;
    $('server-error').textContent = `No se pudo conectar al backend Java. Verifica que el servidor siga ejecutándose. Se reintentará automáticamente. ${error.name === 'AbortError' ? '' : error.message}`;
    $('server-error').hidden = false;
    $('status-text').textContent = 'Servidor desconectado';
    $('connection-status').className = 'status-pill disconnected';
    $('last-sync').textContent = 'Los valores visibles son la última actualización';
    $('chart-live').textContent = 'Sin conexión';
    $('chart-live').classList.remove('active');
    updateControls(false);
  } finally { clearTimeout(timeout); if (id === requestId) refreshInFlight = false; }
}

async function control(action, config = {}) {
  if (pendingAction) return;
  pendingAction = true;
  updateControls();
  $('form-message').className = 'form-message';
  $('form-message').textContent = action === 'start' ? 'Iniciando conexión…' : 'Deteniendo monitoreo…';
  try {
    const response = await fetch(`/api/${action}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(config) });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'No se pudo completar la operación.');
    latestState = data;
    $('form-message').textContent = action === 'start' ? 'Monitoreo iniciado. Los datos se guardan automáticamente.' : 'Sesión detenida. El historial se conservó.';
  } catch (error) {
    $('form-message').textContent = error.message;
    $('form-message').classList.add('error');
  } finally { pendingAction = false; await refresh(); }
}

$('config-form').addEventListener('submit', (event) => {
  event.preventDefault();
  void control('start', { mode: selectedMode(), deviceIp: $('device-ip').value.trim() || '192.168.1.101', highThreshold: Number($('high-threshold').value), jumpThreshold: Number($('jump-threshold').value) });
});
$('stop-button').addEventListener('click', () => void control('stop'));
document.querySelectorAll('input[name="mode"]').forEach((input) => input.addEventListener('change', modeHelp));
for (const id of ['source-filter', 'period-filter']) $(id).addEventListener('change', () => { page = 1; void refresh(); });
$('previous-page').addEventListener('click', () => { if (page > 1) { page--; void refresh(); } });
$('next-page').addEventListener('click', () => { if (page < pages) { page++; void refresh(); } });
document.querySelectorAll('.nav-item').forEach((link) => link.addEventListener('click', () => { document.querySelectorAll('.nav-item').forEach((item) => item.classList.toggle('selected', item === link)); }));
void refresh();
setInterval(() => { if (!document.hidden && !pendingAction && !refreshInFlight) void refresh(); }, 2000);
document.addEventListener('visibilitychange', () => { if (!document.hidden) void refresh(); });
window.addEventListener('resize', () => { if (latestState?.history) renderChart(latestState.history.chart, latestState.config.highThreshold); });
