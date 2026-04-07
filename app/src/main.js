import { unzipSync } from 'fflate';
import './styles.css';

const STORAGE_KEY = 'luckystar-library-state-v5';
const DOWNLOAD_CACHE = 'luckystar-downloads-v5';
const MANGA_BASE = '../raki_suta/';
const app = document.querySelector('#app');

const state = {
  manifest: null,
  chapters: [],
  filtered: [],
  chapter: null,
  pages: [],
  currentPage: 0,
  currentSpread: 0,
  mode: 'book',
  search: '',
  filter: 'all',
  downloading: false,
  downloadProgress: 0,
  downloadTarget: null,
  readState: loadState(),
  zoom: 1,
  pointerDownX: 0,
  pointerDownY: 0,
  pointerDownAt: 0,
  offlineCount: 0,
  touchZoom: null,
  installPrompt: null,
  mobileLibraryOpen: false,
  pageTurnLockedUntil: 0,
  chapterDownloadProgress: {},
  observer: null,
  scrollSyncFrame: null,
  flipDirection: 'forward',
  readerReady: false,
  activeDownloads: 0,
  horizontalWheelAccumulator: 0,
  lastTouchEndAt: 0,
  touchGestureActive: false,
};

function defaultReadState() {
  return {
    chapterProgress: {},
    chapterDone: {},
    lastChapterId: null,
    zoom: 1,
    mode: 'book',
    downloads: {},
    completedIntro: false,
    installDismissed: false,
  };
}

function mergeReadState(raw) {
  const base = defaultReadState();
  return {
    ...base,
    ...(raw || {}),
    chapterProgress: { ...base.chapterProgress, ...(raw?.chapterProgress || {}) },
    chapterDone: { ...base.chapterDone, ...(raw?.chapterDone || {}) },
    downloads: { ...base.downloads, ...(raw?.downloads || {}) },
  };
}

function loadState() {
  try {
    return mergeReadState(JSON.parse(localStorage.getItem(STORAGE_KEY)));
  } catch {
    return defaultReadState();
  }
}

function saveState() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state.readState));
}

async function boot() {
  state.manifest = await fetchJson('./data/chapters.json');
  state.chapters = state.manifest.chapters;
  state.filtered = [...state.chapters];
  state.zoom = state.readState.zoom || 1;
  state.mode = state.readState.mode || (window.innerWidth < 980 ? 'scroll' : 'book');
  state.mobileLibraryOpen = !state.readState.lastChapterId;
  renderShell();
  bindGlobalEvents();
  await registerServiceWorker();
  await refreshOfflineCount();
  restoreLastChapter();
}

async function fetchJson(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Failed to load ${url}`);
  return response.json();
}

function renderShell() {
  const shellClass = ['app-shell', state.chapter ? 'has-reader' : 'library-only', state.mobileLibraryOpen ? 'sidebar-open' : ''].filter(Boolean).join(' ');
  const asideClass = ['sidebar', 'glass', state.chapter ? 'reading' : '', state.mobileLibraryOpen ? 'opened' : ''].filter(Boolean).join(' ');
  const heroClass = ['hero', 'glass', state.chapter ? 'compact' : ''].filter(Boolean).join(' ');
  const installVisible = state.installPrompt && !state.readState.installDismissed;
  const currentProgress = state.chapter ? getChapterPercent(state.chapter) : 0;
  app.innerHTML = `
    <div class="${shellClass}">
      <aside class="${asideClass}">
        <div class="brand-block">
          <div>
            <p class="eyebrow">sosiskibot.ru/luckystar</p>
            <h1>Lucky Star Library</h1>
            <p class="brand-subtitle">Все версии манги, красивый 3D-reader, оффлайн на телефоне и точный возврат к чтению.</p>
          </div>
          <div class="header-actions">
            <button class="download-all accent" id="download-all">Скачать всё</button>
            <button id="install-app" ${installVisible ? '' : 'hidden'}>Установить app</button>
          </div>
        </div>
        <div class="stats-row">
          <div><strong>${state.chapters.length}</strong><span>глав</span></div>
          <div><strong>${countRead()}</strong><span>прочитано</span></div>
          <div><strong id="offline-size">${offlineLabel()}</strong><span>оффлайн</span></div>
        </div>
        <div class="controls glass-subtle">
          <input id="search" type="search" placeholder="Поиск по главам / версиям" value="${escapeHtml(state.search)}" />
          <div class="segmented">
            ${filterButton('all', 'Все')}
            ${filterButton('unread', 'Не прочитано')}
            ${filterButton('read', 'Прочитано')}
          </div>
        </div>
        <div class="library-note glass-subtle">
          <strong>Мобила:</strong> ставишь как приложение, жмёшь скачать, потом читаешь вообще без интернета. Позиция страницы сохраняется автоматически.
          <div class="library-actions">
            <button class="accent" id="library-download-current" ${state.chapter ? '' : 'disabled'}>${state.chapter ? (state.readState.downloads[state.chapter.file] ? 'Перекачать текущую' : 'Скачать текущую') : 'Открой главу'}</button>
            <button id="library-open-current" ${state.chapter ? '' : 'disabled'}>${state.chapter ? 'К текущей главе' : 'Нет главы'}</button>
          </div>
        </div>
        <div class="chapter-list" id="chapter-list"></div>
      </aside>
      <main class="main-view">
        <section class="${heroClass}">
          <div>
            <p class="eyebrow">Progressive Web App</p>
            <h2>Оффлайн-читалка Lucky Star</h2>
            <p>ПК: клава, мышь, колесо, трекпад, клики по краям и 3D перелистывание. Телефон: свайпы, edge taps, жесты зума только по странице, режим установленного приложения и оффлайн библиотека.</p>
          </div>
          <div class="hero-actions">
            <button class="accent" id="resume-reading">Продолжить чтение</button>
            <button id="toggle-layout">${state.mode === 'book' ? 'Scroll mode' : 'Book mode'}</button>
            <button id="toggle-library" class="mobile-only">${state.mobileLibraryOpen ? 'Скрыть главы' : 'Показать главы'}</button>
          </div>
        </section>
        ${state.chapter ? `
          <section class="now-reading glass-subtle">
            <div>
              <p class="eyebrow">Сейчас читаешь</p>
              <strong>${escapeHtml(state.chapter.title)}</strong>
              <span>${currentProgress}% · страница ${state.currentPage + 1} / ${state.pages.length || 0}</span>
            </div>
            <div class="now-reading-chips">
              <span class="chip ${state.readState.chapterDone[state.chapter.id] ? 'done' : 'progress'}">${state.readState.chapterDone[state.chapter.id] ? 'прочитано' : `${currentProgress}%`}</span>
              <span class="chip ${state.readState.downloads[state.chapter.file] ? 'offline' : ''}">${state.readState.downloads[state.chapter.file] ? 'offline' : 'online'}</span>
            </div>
          </section>
        ` : ''}
        <section class="reader-section glass" id="reader-section">
          <div class="empty-state">
            <h3>Выбери главу слева</h3>
            <p>Все версии на месте. Есть download, прогресс, статусы прочтения, адаптация под телефон и красивый режим книги.</p>
          </div>
        </section>
      </main>
    </div>
  `;
  renderChapterList();
  attachShellEvents();
}

function filterButton(value, label) {
  return `<button data-filter="${value}" class="${state.filter === value ? 'active' : ''}">${label}</button>`;
}

function renderChapterList() {
  const list = document.querySelector('#chapter-list');
  if (!list) return;
  state.filtered = state.chapters.filter((chapter) => {
    const hay = `${chapter.title} ${chapter.displayTitle} ${chapter.group}`.toLowerCase();
    const matchesText = !state.search || hay.includes(state.search.toLowerCase());
    if (!matchesText) return false;
    if (state.filter === 'read') return Boolean(state.readState.chapterDone[chapter.id]);
    if (state.filter === 'unread') return !state.readState.chapterDone[chapter.id];
    return true;
  });
  list.innerHTML = state.filtered.map((chapter) => chapterCardMarkup(chapter)).join('');
}

function chapterCardMarkup(chapter) {
  const progress = state.readState.chapterProgress[chapter.id];
  const done = Boolean(state.readState.chapterDone[chapter.id]);
  const downloaded = Boolean(state.readState.downloads[chapter.file]);
  const percent = getChapterPercent(chapter);
  const downloadPercent = state.chapterDownloadProgress[chapter.file] || 0;
  const statusChip = done ? '<span class="chip done">✓ прочитано</span>' : (progress ? `<span class="chip progress">${percent}%</span>` : '<span class="chip">новая</span>');
  const progressBar = `
    <div class="mini-progress ${done ? 'done' : ''}">
      <span style="width:${Math.max(percent, downloadPercent > 0 && downloadPercent < 100 ? downloadPercent : percent)}%"></span>
    </div>
  `;
  return `
    <button class="chapter-card glass ${state.chapter?.id === chapter.id ? 'selected' : ''}" data-chapter-id="${chapter.id}">
      <img src="./${chapter.thumb}" alt="${escapeHtml(chapter.title)}" loading="lazy" />
      <div class="chapter-meta">
        <div class="chapter-topline">
          <span class="chip">${chapter.group}</span>
          ${downloaded ? '<span class="chip offline">offline</span>' : ''}
          ${statusChip}
        </div>
        <strong>${escapeHtml(chapter.title)}</strong>
        <span>${chapter.pageCount} стр. · ${formatBytes(chapter.size || 0)}</span>
        ${progressBar}
      </div>
    </button>
  `;
}

function renderReader() {
  const section = document.querySelector('#reader-section');
  if (!section) return;
  if (!state.chapter) {
    section.innerHTML = '<div class="empty-state"><h3>Выбери главу слева</h3><p>Откроется режим книги или длинной ленты, всё зависит от устройства и твоего переключателя.</p></div>';
    return;
  }
  const prev = findRelativeChapter(-1);
  const next = findRelativeChapter(1);
  const doublePage = state.mode === 'book' && window.innerWidth > 980;
  const leftPage = doublePage ? state.pages[state.currentSpread * 2] ?? null : null;
  const rightPage = state.mode === 'book' ? state.pages[doublePage ? state.currentSpread * 2 + 1 : state.currentPage] ?? null : null;
  const progress = Math.round(((state.currentPage + 1) / Math.max(state.pages.length, 1)) * 100);
  const chapterDownloaded = Boolean(state.readState.downloads[state.chapter.file]);
  const chapterProgress = state.chapterDownloadProgress[state.chapter.file] || 0;
  const chapterPercent = getChapterPercent(state.chapter);
  section.innerHTML = `
    <div class="reader-header">
      <div>
        <p class="eyebrow">${state.chapter.group}</p>
        <h3>${escapeHtml(state.chapter.title)}</h3>
        <span>${state.currentPage + 1} / ${state.pages.length} · ${progress}%</span>
      </div>
      <div class="reader-tools">
        <button id="prev-chapter" ${!prev ? 'disabled' : ''}>← Глава</button>
        <button id="next-chapter" ${!next ? 'disabled' : ''}>Глава →</button>
        <button id="toggle-reading-mode">${state.mode === 'book' ? 'Scroll mode' : 'Book mode'}</button>
        <button id="chapter-download">${state.downloading && state.downloadTarget === state.chapter.file ? `Скачивание ${chapterProgress || state.downloadProgress}%` : (chapterDownloaded ? 'Перекачать' : 'Скачать главу')}</button>
        <button id="mark-read">${state.readState.chapterDone[state.chapter.id] ? 'Сбросить статус' : 'Прочитано'}</button>
      </div>
    </div>
    <div class="reader-meta-row">
      <div class="reader-pill">${chapterDownloaded ? 'Оффлайн готово' : 'Чтение из сети / локалки'}</div>
      <div class="reader-pill">${state.mode === 'book' ? '3D flip режим' : 'вертикальная лента'}</div>
      <div class="reader-pill">Zoom: ${Math.round(state.zoom * 100)}%</div>
      <div class="reader-pill">Прогресс: ${chapterPercent}%</div>
    </div>
    <div class="reader-stage ${state.mode}" id="reader-stage">
      <button class="nav-zone left" id="prev-page" aria-label="Назад"></button>
      <div class="book-scene ${state.mode === 'book' ? 'active' : ''}">
        <div class="book-frame ${doublePage ? '' : 'single'} ${state.readerReady ? 'ready' : ''} ${state.flipDirection}">
          <div class="book-depth-shadow"></div>
          ${leftPage ? pageSheetMarkup(leftPage, 'left-sheet', 'left page') : ''}
          ${rightPage ? pageSheetMarkup(rightPage, `right-sheet ${doublePage ? '' : 'solo'}`, 'right page') : ''}
          <div class="page-flip ${state.flipDirection}" id="page-flip"></div>
          <div class="page-glow"></div>
          <div class="book-spine"></div>
          <div class="book-reflection"></div>
        </div>
      </div>
      <div class="scroll-pages ${state.mode === 'scroll' ? 'active' : ''}" id="scroll-pages">
        ${state.pages.map((page, index) => scrollPageMarkup(page, index)).join('')}
      </div>
      <button class="nav-zone right" id="next-page" aria-label="Вперёд"></button>
    </div>
    <div class="reader-footer">
      <input id="page-slider" type="range" min="0" max="${Math.max(state.pages.length - 1, 0)}" value="${state.currentPage}" />
      <div class="zoom-tools">
        <button id="zoom-out">−</button>
        <span>${Math.round(state.zoom * 100)}%</span>
        <button id="zoom-in">+</button>
      </div>
    </div>
    <div class="reader-mobile-bar mobile-reader-only">
      <button id="mobile-prev">←</button>
      <button id="mobile-menu">Главы</button>
      <button id="mobile-download">${chapterDownloaded ? 'Offline ✓' : 'Скачать'}</button>
      <button id="mobile-next">→</button>
    </div>
    <div class="hint-row">
      <span>ПК: ←/→, PgUp/PgDn, Space, Home/End, J/K, wheel, трекпад, клики по краям.</span>
      <span>Телефон: свайпы, тапы по краям, pinch/кнопки zoom, возврат на точную страницу.</span>
    </div>
  `;
  bindReaderEvents();
  applyZoom();
  syncScrollPageMarker();
  if (state.mode === 'scroll') queueScrollMarkerSync();
  state.readerReady = true;
}

function pageSheetMarkup(page, className, alt) {
  return `<div class="page-sheet ${className}"><div class="paper-shadow"></div><div class="page-inner zoom-surface"><img src="${page.src}" alt="${alt}" /></div></div>`;
}

function scrollPageMarkup(page, index) {
  return `<figure class="scroll-page ${index === state.currentPage ? 'current' : ''}" data-page-index="${index}"><div class="scroll-page-num">${index + 1}</div><div class="zoom-surface"><img src="${page.src}" alt="page ${index + 1}" loading="lazy" /></div></figure>`;
}

async function openChapter(chapterId) {
  const chapter = state.chapters.find((item) => item.id === chapterId);
  if (!chapter) return;
  cleanupPageUrls();
  state.chapter = chapter;
  state.readerReady = false;
  try {
    const cached = await getFromCache(chapter.file);
    const bytes = cached || await fetchZip(chapter.file);
    const zip = unzipSync(new Uint8Array(bytes));
    state.pages = Object.entries(zip)
      .filter(([name]) => /\.(png|jpe?g|webp|gif)$/i.test(name))
      .sort(([a], [b]) => a.localeCompare(b, undefined, { numeric: true }))
      .map(([name, data]) => {
        const view = data instanceof Uint8Array ? data : new Uint8Array(data);
        const blob = new Blob([view], { type: inferMime(name) });
        return { name, src: URL.createObjectURL(blob) };
      });
    const saved = state.readState.chapterProgress[chapter.id];
    state.currentPage = Math.min(saved?.page ?? 0, Math.max(state.pages.length - 1, 0));
    state.currentSpread = Math.floor(state.currentPage / 2);
    state.mobileLibraryOpen = false;
    renderShell();
    renderReader();
    persistCurrentProgress(false);
  } catch (error) {
    console.error(error);
    showToast(`Не удалось открыть ${chapter.title}`);
  }
}

function cleanupPageUrls() {
  disconnectObserver();
  for (const page of state.pages) URL.revokeObjectURL(page.src);
  state.pages = [];
}

function inferMime(name) {
  if (/\.png$/i.test(name)) return 'image/png';
  if (/\.webp$/i.test(name)) return 'image/webp';
  if (/\.gif$/i.test(name)) return 'image/gif';
  return 'image/jpeg';
}

async function fetchZip(file) {
  const response = await fetch(`${MANGA_BASE}${file}`);
  if (!response.ok) throw new Error(`Failed to fetch ${file}`);
  return response.arrayBuffer();
}

function bindGlobalEvents() {
  window.addEventListener('keydown', onKeyDown);
  window.addEventListener('resize', onResize);
  window.addEventListener('wheel', onWheel, { passive: false });
  window.addEventListener('beforeinstallprompt', (event) => {
    event.preventDefault();
    state.installPrompt = event;
    renderShell();
    if (state.chapter) renderReader();
  });
  window.addEventListener('pagehide', () => persistCurrentProgress(false));
  window.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') persistCurrentProgress(false);
  });
  let touchStartX = 0;
  let touchStartY = 0;
  document.addEventListener('touchstart', (event) => {
    if (event.touches.length === 2) {
      state.touchZoom = pinchDistance(event.touches);
      state.touchGestureActive = true;
      return;
    }
    state.touchGestureActive = false;
    const touch = event.changedTouches[0];
    touchStartX = touch.clientX;
    touchStartY = touch.clientY;
  }, { passive: true });
  document.addEventListener('touchmove', (event) => {
    if (event.touches.length === 2 && state.chapter) {
      const next = pinchDistance(event.touches);
      if (state.touchZoom) {
        const diff = (next - state.touchZoom) / 280;
        if (Math.abs(diff) > 0.02) {
          changeZoom(diff);
          state.touchZoom = next;
          state.touchGestureActive = true;
        }
      }
    }
  }, { passive: true });
  document.addEventListener('touchend', (event) => {
    state.lastTouchEndAt = performance.now();
    if (event.touches.length < 2) state.touchZoom = null;
    if (!state.chapter || event.changedTouches.length !== 1 || state.touchGestureActive) {
      state.touchGestureActive = false;
      return;
    }
    const touch = event.changedTouches[0];
    const dx = touch.clientX - touchStartX;
    const dy = touch.clientY - touchStartY;
    if (Math.abs(dx) > 40 && Math.abs(dx) > Math.abs(dy)) dx < 0 ? nextPage() : previousPage();
  }, { passive: true });
  document.addEventListener('dblclick', (event) => event.preventDefault());
  document.addEventListener('gesturestart', (event) => event.preventDefault());
  document.addEventListener('gesturechange', (event) => event.preventDefault());
}

function onResize() {
  if (window.innerWidth >= 980) state.mobileLibraryOpen = false;
  if (state.chapter) renderReader();
}

function onKeyDown(event) {
  const tag = event.target?.tagName;
  if (tag === 'INPUT' || tag === 'TEXTAREA' || !state.chapter) return;
  const key = event.key.toLowerCase();
  if (['arrowright', 'pagedown', ' '].includes(key)) {
    event.preventDefault();
    nextPage();
  }
  if (['arrowleft', 'pageup', 'backspace'].includes(key)) {
    event.preventDefault();
    previousPage();
  }
  if (key === 'home') {
    event.preventDefault();
    jumpToPage(0, 'backward');
  }
  if (key === 'end') {
    event.preventDefault();
    jumpToPage(state.pages.length - 1, 'forward');
  }
  if (key === 'j') {
    event.preventDefault();
    nextPage();
  }
  if (key === 'k') {
    event.preventDefault();
    previousPage();
  }
  if (key === 'b') {
    event.preventDefault();
    toggleMode();
  }
  if (key === '+' || key === '=') {
    event.preventDefault();
    changeZoom(0.1);
  }
  if (key === '-') {
    event.preventDefault();
    changeZoom(-0.1);
  }
  if (key === 'o') {
    event.preventDefault();
    if (state.chapter) downloadChapter(state.chapter);
  }
}

function onWheel(event) {
  if (!state.chapter) return;
  const reader = document.querySelector('#reader-stage');
  if (!reader || !reader.contains(event.target)) return;
  if (event.ctrlKey || event.metaKey) {
    event.preventDefault();
    changeZoom((-event.deltaY || event.deltaX) / 900);
    return;
  }
  if (state.mode === 'scroll') return;
  const dominant = Math.abs(event.deltaX) > Math.abs(event.deltaY) ? event.deltaX : event.deltaY;
  if (Math.abs(dominant) < 24) return;
  event.preventDefault();
  state.horizontalWheelAccumulator += dominant;
  if (Math.abs(state.horizontalWheelAccumulator) < 60) return;
  const direction = state.horizontalWheelAccumulator > 0 ? 'forward' : 'backward';
  state.horizontalWheelAccumulator = 0;
  direction === 'forward' ? nextPage() : previousPage();
}

function attachShellEvents() {
  document.querySelector('#search')?.addEventListener('input', (event) => {
    state.search = event.target.value;
    renderChapterList();
  });
  document.querySelectorAll('[data-filter]').forEach((button) => {
    button.addEventListener('click', () => {
      state.filter = button.dataset.filter;
      renderShell();
      if (state.chapter) renderReader();
    });
  });
  document.querySelector('#chapter-list')?.addEventListener('click', (event) => {
    const card = event.target.closest('[data-chapter-id]');
    if (card) openChapter(card.dataset.chapterId);
  });
  document.querySelector('#resume-reading')?.addEventListener('click', restoreLastChapter);
  document.querySelector('#toggle-layout')?.addEventListener('click', toggleMode);
  document.querySelector('#download-all')?.addEventListener('click', downloadAllChapters);
  document.querySelector('#install-app')?.addEventListener('click', installApp);
  document.querySelector('#library-download-current')?.addEventListener('click', () => {
    if (state.chapter) downloadChapter(state.chapter);
  });
  document.querySelector('#library-open-current')?.addEventListener('click', () => {
    if (!state.chapter) {
      restoreLastChapter();
      return;
    }
    state.mobileLibraryOpen = false;
    renderShell();
    renderReader();
  });
  document.querySelector('#toggle-library')?.addEventListener('click', () => {
    state.mobileLibraryOpen = !state.mobileLibraryOpen;
    renderShell();
    if (state.chapter) renderReader();
  });
}

function bindReaderEvents() {
  document.querySelector('#prev-page')?.addEventListener('click', previousPage);
  document.querySelector('#next-page')?.addEventListener('click', nextPage);
  document.querySelector('#toggle-reading-mode')?.addEventListener('click', toggleMode);
  document.querySelector('#page-slider')?.addEventListener('input', (event) => jumpToPage(Number(event.target.value)));
  document.querySelector('#prev-chapter')?.addEventListener('click', () => {
    const prev = findRelativeChapter(-1);
    if (prev) openChapter(prev.id);
  });
  document.querySelector('#next-chapter')?.addEventListener('click', () => {
    const next = findRelativeChapter(1);
    if (next) openChapter(next.id);
  });
  document.querySelector('#zoom-in')?.addEventListener('click', () => changeZoom(0.12));
  document.querySelector('#zoom-out')?.addEventListener('click', () => changeZoom(-0.12));
  document.querySelector('#mark-read')?.addEventListener('click', toggleReadState);
  document.querySelector('#chapter-download')?.addEventListener('click', () => downloadChapter(state.chapter));
  document.querySelector('#mobile-download')?.addEventListener('click', () => downloadChapter(state.chapter));
  document.querySelector('#mobile-menu')?.addEventListener('click', () => {
    state.mobileLibraryOpen = !state.mobileLibraryOpen;
    renderShell();
    renderReader();
  });
  document.querySelector('#mobile-prev')?.addEventListener('click', previousPage);
  document.querySelector('#mobile-next')?.addEventListener('click', nextPage);
  const stage = document.querySelector('#reader-stage');
  stage?.addEventListener('pointerdown', onPointerDown, { passive: true });
  stage?.addEventListener('pointerup', onPointerUp, { passive: true });
  const scrollPages = document.querySelector('#scroll-pages');
  scrollPages?.addEventListener('scroll', queueScrollMarkerSync, { passive: true });
  setupScrollObserver();
}

function onPointerDown(event) {
  state.pointerDownX = event.clientX;
  state.pointerDownY = event.clientY;
  state.pointerDownAt = performance.now();
}

function onPointerUp(event) {
  if (!state.chapter) return;
  if (performance.now() - state.lastTouchEndAt < 420) return;
  const dx = event.clientX - state.pointerDownX;
  const dy = event.clientY - state.pointerDownY;
  const hold = performance.now() - state.pointerDownAt;
  if (event.target.closest('.zoom-surface') && Math.abs(dx) < 8 && Math.abs(dy) < 8 && hold > 140) return;
  if (Math.abs(dx) > 35 && Math.abs(dx) > Math.abs(dy)) {
    dx < 0 ? nextPage() : previousPage();
    return;
  }
  const bounds = event.currentTarget.getBoundingClientRect();
  const x = event.clientX - bounds.left;
  if (x < bounds.width * 0.28) previousPage();
  else if (x > bounds.width * 0.72) nextPage();
}

function getStep() {
  return state.mode === 'book' ? (window.innerWidth > 980 ? 2 : 1) : 1;
}

function nextPage() {
  if (!state.chapter || pageTurnLocked()) return;
  jumpToPage(Math.min(state.pages.length - 1, state.currentPage + getStep()), 'forward');
}

function previousPage() {
  if (!state.chapter || pageTurnLocked()) return;
  jumpToPage(Math.max(0, state.currentPage - getStep()), 'backward');
}

function jumpToPage(page, direction = 'forward') {
  const bounded = Math.max(0, Math.min(page, Math.max(state.pages.length - 1, 0)));
  state.currentPage = bounded;
  state.currentSpread = Math.floor(bounded / 2);
  state.flipDirection = direction;
  persistCurrentProgress(false);
  renderReader();
  animateFlip(direction);
  if (state.mode === 'scroll') {
    document.querySelector(`.scroll-page[data-page-index="${bounded}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }
  syncReaderHeader();
}

function animateFlip(direction) {
  const flip = document.querySelector('#page-flip');
  const frame = document.querySelector('.book-frame');
  if (!flip || state.mode !== 'book') return;
  flip.className = `page-flip ${direction}`;
  frame?.classList.remove('forward-burst', 'backward-burst');
  state.pageTurnLockedUntil = performance.now() + 620;
  requestAnimationFrame(() => {
    flip.classList.add('animate');
    frame?.classList.add(direction === 'forward' ? 'forward-burst' : 'backward-burst');
  });
}

function pageTurnLocked() {
  return state.mode === 'book' && performance.now() < state.pageTurnLockedUntil;
}

function toggleMode() {
  state.mode = state.mode === 'book' ? 'scroll' : 'book';
  state.readState.mode = state.mode;
  saveState();
  renderShell();
  if (state.chapter) renderReader();
}

function changeZoom(diff) {
  state.zoom = Math.min(3, Math.max(0.65, state.zoom + diff));
  state.readState.zoom = state.zoom;
  saveState();
  applyZoom();
  const zoomLabel = document.querySelector('.zoom-tools span');
  if (zoomLabel) zoomLabel.textContent = `${Math.round(state.zoom * 100)}%`;
  const zoomPill = document.querySelectorAll('.reader-pill')[2];
  if (zoomPill) zoomPill.textContent = `Zoom: ${Math.round(state.zoom * 100)}%`;
}

function applyZoom() {
  document.querySelectorAll('.zoom-surface img').forEach((img) => {
    img.style.transform = `scale(${state.zoom})`;
  });
}

function syncReaderHeader() {
  if (!state.chapter) return;
  const progress = Math.round(((state.currentPage + 1) / Math.max(state.pages.length, 1)) * 100);
  const chapterProgress = getChapterPercent(state.chapter);
  document.querySelectorAll('.reader-header span, .now-reading span').forEach((el, index) => {
    if (index === 0) el.textContent = `${state.currentPage + 1} / ${state.pages.length} · ${progress}%`;
    if (index === 1) el.textContent = `${chapterProgress}% · страница ${state.currentPage + 1} / ${state.pages.length || 0}`;
  });
  const slider = document.querySelector('#page-slider');
  if (slider) slider.value = String(state.currentPage);
  const pill = document.querySelectorAll('.reader-pill')[3];
  if (pill) pill.textContent = `Прогресс: ${chapterProgress}%`;
}

function toggleReadState() {
  if (!state.chapter) return;
  if (state.readState.chapterDone[state.chapter.id]) delete state.readState.chapterDone[state.chapter.id];
  else state.readState.chapterDone[state.chapter.id] = true;
  saveState();
  renderShell();
  renderReader();
}

function persistCurrentProgress(reRender = true) {
  if (!state.chapter) return;
  state.readState.lastChapterId = state.chapter.id;
  state.readState.chapterProgress[state.chapter.id] = { page: state.currentPage, pageCount: state.pages.length };
  if (state.currentPage >= state.pages.length - 1 && state.pages.length > 0) state.readState.chapterDone[state.chapter.id] = true;
  saveState();
  if (reRender) renderChapterList();
}

function getChapterPercent(chapter) {
  const progress = state.readState.chapterProgress[chapter.id];
  return progress?.pageCount ? Math.min(100, Math.round(((progress.page + 1) / progress.pageCount) * 100)) : 0;
}

function countRead() {
  return Object.keys(state.readState.chapterDone).length;
}

function findRelativeChapter(offset) {
  if (!state.chapter) return null;
  const index = state.chapters.findIndex((item) => item.id === state.chapter.id);
  return state.chapters[index + offset] || null;
}

function restoreLastChapter() {
  const id = state.readState.lastChapterId || state.chapters[0]?.id;
  if (id) openChapter(id);
}

async function registerServiceWorker() {
  if ('serviceWorker' in navigator) await navigator.serviceWorker.register('./sw.js');
}

async function downloadAllChapters() {
  if (state.downloading) return;
  state.downloading = true;
  try {
    for (let i = 0; i < state.chapters.length; i += 1) {
      const chapter = state.chapters[i];
      state.downloadTarget = chapter.file;
      state.downloadProgress = Math.round(((i + 1) / state.chapters.length) * 100);
      updateDownloadButtons();
      await downloadChapter(chapter, false, true);
    }
    showToast('Все главы скачаны в оффлайн-кэш');
  } catch (error) {
    console.error(error);
    showToast('Часть глав не скачалась, смотри console');
  } finally {
    state.downloading = false;
    state.downloadTarget = null;
    state.downloadProgress = 0;
    updateDownloadButtons();
    renderShell();
    if (state.chapter) renderReader();
  }
}

async function downloadChapter(chapter, rerender = true, bulkMode = false) {
  if (!chapter) return;
  if (!bulkMode) {
    state.downloading = true;
    state.downloadTarget = chapter.file;
  }
  state.activeDownloads += 1;
  state.chapterDownloadProgress[chapter.file] = state.chapterDownloadProgress[chapter.file] || 0;
  updateDownloadButtons();
  try {
    const existing = await getFromCache(chapter.file);
    if (!existing) {
      const response = await fetch(`${MANGA_BASE}${chapter.file}`);
      if (!response.ok) throw new Error(`Failed to download ${chapter.file}`);
      const total = Number(response.headers.get('content-length')) || 0;
      const reader = response.body?.getReader();
      if (reader) {
        let received = 0;
        const chunks = [];
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          chunks.push(value);
          received += value.byteLength;
          state.chapterDownloadProgress[chapter.file] = total ? Math.round((received / total) * 100) : 100;
          if (!bulkMode) state.downloadProgress = state.chapterDownloadProgress[chapter.file];
          updateDownloadButtons();
          renderChapterList();
        }
        await putInCache(chapter.file, concatChunks(chunks, received));
      } else {
        const buffer = await response.arrayBuffer();
        state.chapterDownloadProgress[chapter.file] = 100;
        await putInCache(chapter.file, buffer);
      }
    } else {
      state.chapterDownloadProgress[chapter.file] = 100;
    }
    state.readState.downloads[chapter.file] = true;
    saveState();
    await refreshOfflineCount();
    if (!bulkMode) showToast(`Глава ${chapter.title} скачана`);
  } finally {
    state.activeDownloads = Math.max(0, state.activeDownloads - 1);
    state.chapterDownloadProgress[chapter.file] = 100;
    if (!bulkMode) {
      state.downloading = false;
      state.downloadTarget = null;
      state.downloadProgress = 0;
    }
    updateDownloadButtons();
    renderChapterList();
    if (rerender && state.chapter?.id === chapter.id) renderReader();
  }
}

function concatChunks(chunks, total) {
  const merged = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    merged.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return merged.buffer;
}

async function getFromCache(file) {
  const cache = await caches.open(DOWNLOAD_CACHE);
  const match = await cache.match(`./manga/${file}`) || await cache.match(`${location.origin}/raki_suta/${file}`);
  return match ? match.arrayBuffer() : null;
}

async function putInCache(file, arrayBuffer) {
  const cache = await caches.open(DOWNLOAD_CACHE);
  await cache.put(`${location.origin}/raki_suta/${file}`, new Response(arrayBuffer));
  await cache.put(`./manga/${file}`, new Response(arrayBuffer));
}

async function refreshOfflineCount() {
  const cache = await caches.open(DOWNLOAD_CACHE);
  const keys = await cache.keys();
  const set = new Set(keys.map((key) => key.url.split('/').pop()).filter(Boolean));
  state.offlineCount = set.size;
  updateOfflineIndicator();
}

function updateOfflineIndicator() {
  const el = document.querySelector('#offline-size');
  if (el) el.textContent = offlineLabel();
}

function updateDownloadButtons() {
  const main = document.querySelector('#download-all');
  if (main) main.textContent = state.downloading ? `Скачать всё (${state.downloadProgress}%)` : 'Скачать всё';
  const chapterBtn = document.querySelector('#chapter-download');
  if (chapterBtn && state.chapter) {
    const progress = state.chapterDownloadProgress[state.chapter.file] || state.downloadProgress;
    const downloaded = Boolean(state.readState.downloads[state.chapter.file]);
    chapterBtn.textContent = state.downloading && state.downloadTarget === state.chapter.file ? `Скачивание ${progress}%` : (downloaded ? 'Перекачать' : 'Скачать главу');
  }
  const mobileBtn = document.querySelector('#mobile-download');
  if (mobileBtn && state.chapter) {
    mobileBtn.textContent = state.downloading && state.downloadTarget === state.chapter.file
      ? `Загрузка ${state.chapterDownloadProgress[state.chapter.file] || state.downloadProgress}%`
      : (state.readState.downloads[state.chapter.file] ? 'Offline ✓' : 'Скачать');
  }
}

function offlineLabel() {
  return `${state.offlineCount}/${state.chapters.length}`;
}

function queueScrollMarkerSync() {
  if (state.scrollSyncFrame) cancelAnimationFrame(state.scrollSyncFrame);
  state.scrollSyncFrame = requestAnimationFrame(() => {
    state.scrollSyncFrame = null;
    syncCurrentPageFromScroll();
  });
}

function syncCurrentPageFromScroll() {
  if (state.mode !== 'scroll') return;
  const container = document.querySelector('#scroll-pages');
  if (!container) return;
  const pages = [...container.querySelectorAll('.scroll-page')];
  const containerCenter = container.getBoundingClientRect().top + container.clientHeight / 2;
  let best = state.currentPage;
  let bestDistance = Number.POSITIVE_INFINITY;
  pages.forEach((page) => {
    const rect = page.getBoundingClientRect();
    const center = rect.top + rect.height / 2;
    const distance = Math.abs(center - containerCenter);
    if (distance < bestDistance) {
      bestDistance = distance;
      best = Number(page.dataset.pageIndex);
    }
  });
  if (best !== state.currentPage) {
    state.currentPage = best;
    persistCurrentProgress();
    syncScrollPageMarker();
  }
}

function syncScrollPageMarker() {
  document.querySelectorAll('.scroll-page').forEach((page, index) => page.classList.toggle('current', index === state.currentPage));
}

function setupScrollObserver() {
  disconnectObserver();
  if (state.mode !== 'scroll') return;
  const container = document.querySelector('#scroll-pages');
  if (!container) return;
  state.observer = new IntersectionObserver((entries) => {
    let bestEntry = null;
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      if (!bestEntry || entry.intersectionRatio > bestEntry.intersectionRatio) bestEntry = entry;
    }
    if (bestEntry) {
      const index = Number(bestEntry.target.dataset.pageIndex);
      if (Number.isFinite(index) && index !== state.currentPage) {
        state.currentPage = index;
        persistCurrentProgress();
        syncScrollPageMarker();
      }
    }
  }, { root: container, threshold: [0.25, 0.5, 0.75] });
  container.querySelectorAll('.scroll-page').forEach((page) => state.observer.observe(page));
}

function disconnectObserver() {
  if (state.observer) {
    state.observer.disconnect();
    state.observer = null;
  }
}

function pinchDistance(touches) {
  const [a, b] = touches;
  return Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
}

async function installApp() {
  if (!state.installPrompt) return;
  await state.installPrompt.prompt();
  state.installPrompt = null;
  state.readState.installDismissed = true;
  saveState();
  renderShell();
  if (state.chapter) renderReader();
}

function showToast(message) {
  let toast = document.querySelector('.toast');
  if (!toast) {
    toast = document.createElement('div');
    toast.className = 'toast';
    document.body.appendChild(toast);
  }
  toast.textContent = message;
  toast.classList.add('visible');
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => toast.classList.remove('visible'), 2200);
}
showToast.timer = null;

function formatBytes(bytes) {
  if (!bytes) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  let value = bytes;
  let index = 0;
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index += 1;
  }
  return `${value.toFixed(value >= 10 || index === 0 ? 0 : 1)} ${units[index]}`;
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"]/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[char]));
}

window.render_game_to_text = () => JSON.stringify({
  mode: state.mode,
  chapter: state.chapter?.id || null,
  currentPage: state.currentPage,
  pageCount: state.pages.length,
  filter: state.filter,
  zoom: state.zoom,
  offlineCount: state.offlineCount,
  downloading: state.downloading,
  selectedTitle: state.chapter?.title || null,
  mobileLibraryOpen: state.mobileLibraryOpen,
  activeDownloads: state.activeDownloads,
  flipDirection: state.flipDirection,
});
window.advanceTime = async (ms = 16) => new Promise((resolve) => setTimeout(resolve, ms));

boot();
