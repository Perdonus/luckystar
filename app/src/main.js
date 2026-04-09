import '@banegasn/m3-button';
import '@banegasn/m3-card';
import '@banegasn/m3-chip';
import '@banegasn/m3-progress';
import '@banegasn/m3-tabs';
import './styles.css';

const STORAGE_KEY = 'luckystar-reader-state-v7';
const LIBRARY_URL = '/luckystar/data/library.json';
const app = document.querySelector('#app');

const state = {
  loading: true,
  error: null,
  library: null,
  chapterMap: new Map(),
  view: 'home',
  currentSeriesId: null,
  currentReleaseId: null,
  currentChapterId: null,
  currentPage: 0,
  drawerOpen: false,
  mode: window.matchMedia('(max-width: 900px)').matches ? 'scroll' : 'book',
  zoom: 1,
  wheelAccumulator: 0,
  readState: loadReadState(),
  scrollObserver: null,
  scrollRaf: null,
  pointer: {
    x: 0,
    y: 0,
    at: 0,
  },
};

function defaultReadState() {
  return {
    chapterProgress: {},
    chapterDone: {},
    seriesLast: {},
    lastSeriesId: null,
    mode: null,
    zoom: 1,
  };
}

function loadReadState() {
  try {
    const raw = JSON.parse(localStorage.getItem(STORAGE_KEY));
    const base = defaultReadState();
    return {
      ...base,
      ...(raw || {}),
      chapterProgress: { ...base.chapterProgress, ...(raw?.chapterProgress || {}) },
      chapterDone: { ...base.chapterDone, ...(raw?.chapterDone || {}) },
      seriesLast: { ...base.seriesLast, ...(raw?.seriesLast || {}) },
    };
  } catch {
    return defaultReadState();
  }
}

function saveReadState() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state.readState));
}

function normalizeLibrary(data) {
  const series = (data.series || []).map((entry) => {
    const normalized = { ...entry };
    const releases = (entry.releases || []).map((release) => {
      const copy = { ...release };
      if (entry.id === 'lucky-star') {
        copy.title = releaseTomTitle(copy);
      }
      return copy;
    });
    normalized.releases = releases.sort((a, b) => {
      const an = Number.isFinite(a.number) ? a.number : Number.POSITIVE_INFINITY;
      const bn = Number.isFinite(b.number) ? b.number : Number.POSITIVE_INFINITY;
      if (an !== bn) return an - bn;
      return String(a.id).localeCompare(String(b.id), undefined, { numeric: true });
    });
    if (entry.id === 'lucky-star') {
      normalized.releaseLabel = 'Тома';
    }
    return normalized;
  });

  const chapterMap = new Map();
  if (data.chapterIndex && typeof data.chapterIndex === 'object') {
    for (const [id, chapter] of Object.entries(data.chapterIndex)) {
      chapterMap.set(id, chapter);
    }
  }
  if (!chapterMap.size && Array.isArray(data.chapters)) {
    data.chapters.forEach((chapter) => chapterMap.set(chapter.id, chapter));
  }

  return {
    ...data,
    series,
    chapterMap,
  };
}

function releaseTomTitle(release) {
  if (Number.isFinite(release.number)) return `Том ${release.number}`;
  const direct = String(release.title || '').match(/^v\s*(\d+)$/i);
  if (direct) return `Том ${direct[1]}`;
  const fromId = String(release.id || '').match(/(\d+)/);
  if (fromId) return `Том ${Number(fromId[1])}`;
  return String(release.title || release.id || 'Том');
}

async function boot() {
  applyPersistedPrefs();
  bindGlobalEvents();
  render();

  try {
    const response = await fetch(LIBRARY_URL, { cache: 'no-cache' });
    if (!response.ok) throw new Error(`Failed to load library.json (${response.status})`);
    const raw = await response.json();
    const normalized = normalizeLibrary(raw);

    state.library = normalized;
    state.chapterMap = normalized.chapterMap;
    state.loading = false;

    await registerServiceWorker();
    render();
  } catch (error) {
    console.error(error);
    state.loading = false;
    state.error = error instanceof Error ? error.message : 'Unknown error';
    render();
  }
}

function applyPersistedPrefs() {
  state.mode = state.readState.mode || state.mode;
  state.zoom = clamp(Number(state.readState.zoom) || 1, 0.7, 2.4);
  applyZoomVar();
}

function render() {
  if (state.loading) {
    app.className = 'app-loading';
    app.innerHTML = `
      <main class="loading-screen">
        <m3-progress></m3-progress>
      </main>
    `;
    return;
  }

  if (state.error) {
    app.className = 'app-error';
    app.innerHTML = `
      <main class="error-screen">
        <m3-card variant="outlined" class="error-card">
          <h2 slot="header">Ошибка загрузки</h2>
          <p>${escapeHtml(state.error)}</p>
          <m3-button slot="actions" variant="filled" id="retry-load">Повторить</m3-button>
        </m3-card>
      </main>
    `;
    document.querySelector('#retry-load')?.addEventListener('click', () => {
      state.error = null;
      state.loading = true;
      render();
      boot();
    });
    return;
  }

  if (state.view === 'home') {
    renderHome();
    return;
  }

  renderReader();
}

function renderHome() {
  app.className = 'app-home';
  const seriesCards = getSeriesList()
    .map((series) => {
      const { read, total } = getSeriesReadStats(series);
      const progress = total ? (read / total) : 0;
      const cover = series.banner || firstSeriesThumb(series);
      return `
        <m3-card
          class="series-card"
          variant="outlined"
          clickable
          data-series-id="${escapeAttr(series.id)}"
          aria-label="${escapeAttr(series.title)}"
        >
          ${cover ? `<img slot="media" src="${escapeAttr(cover)}" alt="${escapeAttr(series.title)}" loading="lazy" />` : ''}
          <div slot="header" class="series-header">
            <h2>${escapeHtml(series.title)}</h2>
          </div>
          <div class="series-meta">
            <m3-chip variant="filter" selected>${read}/${total}</m3-chip>
            <m3-progress value="${progress.toFixed(4)}"></m3-progress>
          </div>
        </m3-card>
      `;
    })
    .join('');

  app.innerHTML = `
    <main class="home-screen">
      <section class="series-grid" aria-label="Серии манги">
        ${seriesCards}
      </section>
    </main>
  `;

  app.querySelectorAll('[data-series-id]').forEach((card) => {
    card.addEventListener('click', () => openSeries(card.dataset.seriesId));
    card.addEventListener('card-click', () => openSeries(card.dataset.seriesId));
  });
}

function renderReader() {
  const series = getCurrentSeries();
  const chapter = getCurrentChapter();
  if (!series || !chapter) {
    state.view = 'home';
    renderHome();
    return;
  }

  const releases = getReleases(series);
  const currentRelease = getCurrentRelease();
  const releaseChapters = currentRelease ? getReleaseChapters(series, currentRelease.id) : [];
  const pages = chapter.pages || [];
  const pageCount = pages.length;
  const indicator = `${Math.min(state.currentPage + 1, Math.max(pageCount, 1))} / ${Math.max(pageCount, 1)}`;

  app.className = `app-reader mode-${state.mode} ${state.drawerOpen ? 'drawer-open' : ''}`;
  app.innerHTML = `
    <section class="reader-root">
      <header class="reader-topbar">
        <div class="topbar-left">
          <m3-button variant="text" id="go-home">←</m3-button>
          <m3-button variant="text" id="toggle-drawer">☰</m3-button>
        </div>
        <div class="topbar-title">
          <h1>${escapeHtml(series.title)}</h1>
          <p>${escapeHtml(chapter.shortTitle || chapter.title || 'Глава')}</p>
        </div>
        <div class="topbar-right">
          <m3-button variant="outlined" id="toggle-mode">${state.mode === 'book' ? 'Книга' : 'Лента'}</m3-button>
        </div>
      </header>

      <div class="release-strip">
        <m3-tabs id="release-tabs" variant="secondary">
          ${releases.map((release) => `<m3-tab label="${escapeAttr(releaseDisplayName(series, release))}" ${release.id === state.currentReleaseId ? 'active' : ''}></m3-tab>`).join('')}
        </m3-tabs>
      </div>

      <div class="reader-body">
        <aside class="chapter-drawer ${state.drawerOpen ? 'open' : ''}" id="chapter-drawer">
          <div class="drawer-head">
            <strong>${escapeHtml(releaseDisplayName(series, currentRelease))}</strong>
          </div>
          <div class="chapter-list" id="chapter-list">
            ${releaseChapters.map((item) => chapterItemMarkup(item)).join('')}
          </div>
        </aside>
        <button class="drawer-backdrop ${state.drawerOpen ? 'visible' : ''}" id="drawer-backdrop" aria-label="Закрыть меню"></button>

        <main class="reader-stage">
          ${state.mode === 'book' ? bookStageMarkup(chapter) : scrollStageMarkup(chapter)}
        </main>
      </div>

      <footer class="reader-footer">
        <m3-button variant="text" id="prev-page" ${state.currentPage <= 0 ? 'disabled' : ''}>◀</m3-button>
        <input id="page-slider" type="range" min="0" max="${Math.max(pageCount - 1, 0)}" value="${Math.min(state.currentPage, Math.max(pageCount - 1, 0))}" />
        <div class="page-indicator" id="page-indicator">${indicator}</div>
        <div class="zoom-row">
          <m3-button variant="text" id="zoom-out">−</m3-button>
          <span>${Math.round(state.zoom * 100)}%</span>
          <m3-button variant="text" id="zoom-in">+</m3-button>
        </div>
        <m3-button variant="text" id="next-page" ${state.currentPage >= pageCount - 1 ? 'disabled' : ''}>▶</m3-button>
      </footer>
    </section>
  `;

  bindReaderEvents(releases, releaseChapters, chapter);
  if (state.mode === 'scroll') {
    setupScrollTracking();
  } else {
    disconnectScrollTracking();
  }

  preloadNearbyPages(chapter);
}

function chapterItemMarkup(chapter) {
  const done = Boolean(state.readState.chapterDone[chapter.id]);
  const progress = getChapterPercent(chapter);
  let badge = '';
  if (done) badge = '✓';
  else if (progress > 0) badge = `${progress}%`;

  return `
    <button class="chapter-item ${chapter.id === state.currentChapterId ? 'active' : ''} ${done ? 'done' : ''}" data-chapter-id="${escapeAttr(chapter.id)}">
      <span class="chapter-name">${escapeHtml(chapter.shortTitle || chapter.title || chapter.id)}</span>
      <span class="chapter-state">${escapeHtml(badge)}</span>
    </button>
  `;
}

function bookStageMarkup(chapter) {
  const pages = chapter.pages || [];
  const spread = getBookSpread(pages, state.currentPage);
  const flipClass = state._flipClass || '';

  return `
    <div class="book-stage" id="book-stage">
      <button class="page-zone left" id="zone-prev" aria-label="Предыдущая страница"></button>
      <div class="book-scene">
        <div class="book-spread ${spread.single ? 'single' : ''} ${flipClass}" id="book-spread">
          ${spread.left ? pageSheetMarkup(spread.left, 'left-sheet') : ''}
          ${spread.right ? pageSheetMarkup(spread.right, spread.single ? 'right-sheet single' : 'right-sheet') : ''}
          <div class="flip-overlay"></div>
        </div>
      </div>
      <button class="page-zone right" id="zone-next" aria-label="Следующая страница"></button>
    </div>
  `;
}

function pageSheetMarkup(page, extraClass = '') {
  return `
    <article class="page-sheet ${extraClass}">
      <div class="page-surface">
        <img src="${escapeAttr(page.url)}" alt="${escapeAttr(page.name || 'page')}" class="manga-image" draggable="false" loading="eager" decoding="async" />
      </div>
    </article>
  `;
}

function scrollStageMarkup(chapter) {
  const pages = chapter.pages || [];
  return `
    <div class="scroll-stage" id="scroll-stage">
      ${pages.map((page, index) => `
        <figure class="scroll-page ${index === state.currentPage ? 'current' : ''}" data-page-index="${index}">
          <div class="scroll-page-num">${index + 1}</div>
          <div class="scroll-surface">
            <img src="${escapeAttr(page.url)}" alt="${escapeAttr(page.name || `page-${index + 1}`)}" class="manga-image" loading="lazy" decoding="async" draggable="false" />
          </div>
        </figure>
      `).join('')}
    </div>
  `;
}

function bindReaderEvents(releases, releaseChapters, chapter) {
  document.querySelector('#go-home')?.addEventListener('click', () => {
    disconnectScrollTracking();
    state.view = 'home';
    state.drawerOpen = false;
    render();
  });

  document.querySelector('#toggle-drawer')?.addEventListener('click', () => {
    state.drawerOpen = !state.drawerOpen;
    renderReader();
  });

  document.querySelector('#drawer-backdrop')?.addEventListener('click', () => {
    state.drawerOpen = false;
    renderReader();
  });

  document.querySelector('#toggle-mode')?.addEventListener('click', () => {
    state.mode = state.mode === 'book' ? 'scroll' : 'book';
    state.readState.mode = state.mode;
    saveReadState();
    renderReader();
  });

  document.querySelector('#prev-page')?.addEventListener('click', previousPage);
  document.querySelector('#next-page')?.addEventListener('click', nextPage);
  document.querySelector('#zone-prev')?.addEventListener('click', previousPage);
  document.querySelector('#zone-next')?.addEventListener('click', nextPage);

  document.querySelector('#page-slider')?.addEventListener('input', (event) => {
    const next = Number(event.target.value);
    if (!Number.isFinite(next)) return;
    if (state.mode === 'scroll') {
      scrollToPage(next);
      return;
    }
    jumpToPage(next, next >= state.currentPage ? 'forward' : 'backward');
  });

  document.querySelector('#zoom-in')?.addEventListener('click', () => changeZoom(0.1));
  document.querySelector('#zoom-out')?.addEventListener('click', () => changeZoom(-0.1));

  document.querySelectorAll('.chapter-item').forEach((button) => {
    button.addEventListener('click', () => {
      state.drawerOpen = false;
      openChapter(button.dataset.chapterId, { preferSavedPage: true });
    });
  });

  const tabs = document.querySelector('#release-tabs');
  tabs?.addEventListener('tab-change', (event) => {
    const index = Number(event.detail?.index);
    const release = releases[index];
    if (!release || release.id === state.currentReleaseId) return;
    state.currentReleaseId = release.id;
    const nextChapter = pickChapterForRelease(release, releaseChapters);
    if (nextChapter) {
      openChapter(nextChapter.id, { preferSavedPage: true });
    } else {
      renderReader();
    }
  });

  const bookStage = document.querySelector('#book-stage');
  if (bookStage) {
    bookStage.addEventListener('pointerdown', onPointerDown, { passive: true });
    bookStage.addEventListener('pointerup', onPointerUp, { passive: true });
  }

  if (state.mode === 'scroll') {
    requestAnimationFrame(() => {
      const active = document.querySelector(`.scroll-page[data-page-index="${state.currentPage}"]`);
      active?.scrollIntoView({ block: 'center', behavior: 'auto' });
    });
  }

  refreshFooterIndicators(chapter);
}

function pickChapterForRelease(release, previousReleaseChapters = []) {
  const series = getCurrentSeries();
  if (!series || !release) return null;

  const chapters = getReleaseChapters(series, release.id);
  const last = state.readState.seriesLast[series.id];

  if (last?.chapterId) {
    const saved = chapters.find((chapter) => chapter.id === last.chapterId);
    if (saved) return saved;
  }

  const stillCurrent = chapters.find((entry) => entry.id === state.currentChapterId);
  if (stillCurrent) return stillCurrent;

  return chapters[0] || previousReleaseChapters[0] || null;
}

function openSeries(seriesId) {
  const series = getSeriesList().find((item) => item.id === seriesId);
  if (!series) return;

  state.currentSeriesId = series.id;
  state.view = 'reader';
  state.drawerOpen = false;

  const releases = getReleases(series);
  const last = state.readState.seriesLast[series.id];

  let releaseId = last?.releaseId;
  if (!releases.find((item) => item.id === releaseId)) {
    const byChapter = releases.find((item) => item.chapterIds?.includes(last?.chapterId));
    releaseId = byChapter?.id || releases[0]?.id || null;
  }

  state.currentReleaseId = releaseId;

  let chapterId = last?.chapterId;
  const releaseChapters = getReleaseChapters(series, state.currentReleaseId);
  if (!releaseChapters.find((item) => item.id === chapterId)) {
    chapterId = releaseChapters[0]?.id || firstSeriesChapter(series)?.id || null;
  }

  if (!chapterId) {
    state.view = 'home';
    render();
    return;
  }

  openChapter(chapterId, { preferSavedPage: true });
}

function openChapter(chapterId, { preferSavedPage = true } = {}) {
  const chapter = state.chapterMap.get(chapterId);
  if (!chapter) return;

  state.currentChapterId = chapter.id;
  state.currentReleaseId = chapter.releaseId || state.currentReleaseId;

  const pageCount = chapter.pages?.length || 0;
  const saved = state.readState.chapterProgress[chapter.id];
  state.currentPage = preferSavedPage ? clamp(saved?.page ?? 0, 0, Math.max(pageCount - 1, 0)) : 0;

  persistProgress();
  renderReader();
}

function previousPage() {
  const chapter = getCurrentChapter();
  if (!chapter) return;

  if (state.mode === 'scroll') {
    scrollToPage(Math.max(0, state.currentPage - 1));
    return;
  }

  jumpToPage(state.currentPage - pageStep(chapter), 'backward');
}

function nextPage() {
  const chapter = getCurrentChapter();
  if (!chapter) return;

  if (state.mode === 'scroll') {
    scrollToPage(Math.min((chapter.pages?.length || 1) - 1, state.currentPage + 1));
    return;
  }

  jumpToPage(state.currentPage + pageStep(chapter), 'forward');
}

function pageStep(chapter) {
  const wide = window.innerWidth >= 1100;
  const pageCount = chapter.pages?.length || 0;
  if (state.mode !== 'book' || !wide || pageCount <= 1) return 1;
  return 2;
}

function jumpToPage(next, direction = 'forward') {
  const chapter = getCurrentChapter();
  if (!chapter) return;

  const pageCount = chapter.pages?.length || 0;
  const bounded = clamp(next, 0, Math.max(pageCount - 1, 0));
  if (bounded === state.currentPage) return;

  state.currentPage = bounded;
  state._flipClass = direction === 'backward' ? 'flip-backward' : 'flip-forward';
  persistProgress();
  renderReader();

  window.clearTimeout(jumpToPage.flipTimer);
  jumpToPage.flipTimer = window.setTimeout(() => {
    state._flipClass = '';
  }, 420);
}
jumpToPage.flipTimer = 0;

function scrollToPage(index) {
  const bounded = clamp(index, 0, Math.max((getCurrentChapter()?.pages?.length || 1) - 1, 0));
  const node = document.querySelector(`.scroll-page[data-page-index="${bounded}"]`);
  if (!node) return;
  node.scrollIntoView({ behavior: 'smooth', block: 'center' });
}

function onPointerDown(event) {
  state.pointer.x = event.clientX;
  state.pointer.y = event.clientY;
  state.pointer.at = performance.now();
}

function onPointerUp(event) {
  const dx = event.clientX - state.pointer.x;
  const dy = event.clientY - state.pointer.y;
  if (Math.abs(dx) > 36 && Math.abs(dx) > Math.abs(dy)) {
    if (dx < 0) nextPage();
    else previousPage();
    return;
  }

  if (Math.abs(dx) < 10 && Math.abs(dy) < 10) {
    const bounds = event.currentTarget.getBoundingClientRect();
    const x = event.clientX - bounds.left;
    if (x < bounds.width * 0.28) previousPage();
    else if (x > bounds.width * 0.72) nextPage();
  }
}

function setupScrollTracking() {
  disconnectScrollTracking();

  const container = document.querySelector('#scroll-stage');
  if (!container) return;

  const entries = [...container.querySelectorAll('.scroll-page')];
  if (!entries.length) return;

  state.scrollObserver = new IntersectionObserver((observed) => {
    let best = null;
    for (const entry of observed) {
      if (!entry.isIntersecting) continue;
      if (!best || entry.intersectionRatio > best.intersectionRatio) best = entry;
    }
    if (!best) return;

    const index = Number(best.target.dataset.pageIndex);
    if (!Number.isFinite(index) || index === state.currentPage) return;

    state.currentPage = index;
    persistProgress(false);
    refreshFooterIndicators(getCurrentChapter());
    entries.forEach((node, nodeIndex) => node.classList.toggle('current', nodeIndex === index));
  }, {
    root: container,
    threshold: [0.35, 0.55, 0.8],
  });

  entries.forEach((entry) => state.scrollObserver.observe(entry));
}

function disconnectScrollTracking() {
  if (state.scrollObserver) {
    state.scrollObserver.disconnect();
    state.scrollObserver = null;
  }
  if (state.scrollRaf) {
    cancelAnimationFrame(state.scrollRaf);
    state.scrollRaf = null;
  }
}

function preloadNearbyPages(chapter) {
  if (!chapter?.pages?.length) return;
  for (const offset of [1, 2]) {
    const next = chapter.pages[state.currentPage + offset];
    const prev = chapter.pages[state.currentPage - offset];
    if (next?.url) {
      const img = new Image();
      img.src = next.url;
    }
    if (prev?.url) {
      const img = new Image();
      img.src = prev.url;
    }
  }
}

function bindGlobalEvents() {
  window.addEventListener('resize', () => {
    if (state.view === 'reader' && state.mode === 'book') renderReader();
  });

  window.addEventListener('keydown', (event) => {
    if (state.view !== 'reader') return;
    const tag = event.target?.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA') return;

    const key = event.key.toLowerCase();
    if (['arrowright', 'pagedown', ' '].includes(key)) {
      event.preventDefault();
      nextPage();
      return;
    }
    if (['arrowleft', 'pageup', 'backspace'].includes(key)) {
      event.preventDefault();
      previousPage();
      return;
    }
    if (key === 'home') {
      event.preventDefault();
      jumpToPage(0, 'backward');
      return;
    }
    if (key === 'end') {
      event.preventDefault();
      jumpToPage((getCurrentChapter()?.pages?.length || 1) - 1, 'forward');
      return;
    }
    if (key === 'escape' && state.drawerOpen) {
      state.drawerOpen = false;
      renderReader();
      return;
    }
    if (key === 'm') {
      state.mode = state.mode === 'book' ? 'scroll' : 'book';
      state.readState.mode = state.mode;
      saveReadState();
      renderReader();
      return;
    }
    if (key === '+' || key === '=') {
      event.preventDefault();
      changeZoom(0.1);
      return;
    }
    if (key === '-') {
      event.preventDefault();
      changeZoom(-0.1);
    }
  });

  window.addEventListener('wheel', (event) => {
    if (state.view !== 'reader') return;

    const stage = document.querySelector('.reader-stage');
    if (!stage || !stage.contains(event.target)) return;

    if (event.ctrlKey || event.metaKey) {
      event.preventDefault();
      changeZoom((-event.deltaY || event.deltaX) / 900);
      return;
    }

    if (state.mode !== 'book') return;

    const delta = Math.abs(event.deltaX) > Math.abs(event.deltaY) ? event.deltaX : event.deltaY;
    if (Math.abs(delta) < 26) return;
    event.preventDefault();

    state.wheelAccumulator += delta;
    if (Math.abs(state.wheelAccumulator) < 64) return;

    if (state.wheelAccumulator > 0) nextPage();
    else previousPage();

    state.wheelAccumulator = 0;
  }, { passive: false });

  window.addEventListener('pagehide', () => persistProgress(false));
}

function refreshFooterIndicators(chapter) {
  if (!chapter) return;
  const pageCount = chapter.pages?.length || 0;
  const slider = document.querySelector('#page-slider');
  const indicator = document.querySelector('#page-indicator');
  const prev = document.querySelector('#prev-page');
  const next = document.querySelector('#next-page');

  if (slider) {
    slider.max = String(Math.max(pageCount - 1, 0));
    slider.value = String(clamp(state.currentPage, 0, Math.max(pageCount - 1, 0)));
  }
  if (indicator) indicator.textContent = `${Math.min(state.currentPage + 1, Math.max(pageCount, 1))} / ${Math.max(pageCount, 1)}`;
  if (prev) prev.disabled = state.currentPage <= 0;
  if (next) next.disabled = state.currentPage >= pageCount - 1;
}

function changeZoom(diff) {
  state.zoom = clamp(state.zoom + diff, 0.7, 2.4);
  state.readState.zoom = state.zoom;
  saveReadState();
  applyZoomVar();
  const label = document.querySelector('.zoom-row span');
  if (label) label.textContent = `${Math.round(state.zoom * 100)}%`;
}

function applyZoomVar() {
  document.documentElement.style.setProperty('--reader-zoom', state.zoom.toFixed(3));
}

function persistProgress(save = true) {
  const chapter = getCurrentChapter();
  const series = getCurrentSeries();
  if (!chapter || !series) return;

  const pageCount = chapter.pages?.length || 0;
  state.currentPage = clamp(state.currentPage, 0, Math.max(pageCount - 1, 0));

  state.readState.lastSeriesId = series.id;
  state.readState.chapterProgress[chapter.id] = {
    page: state.currentPage,
    pageCount,
  };
  state.readState.seriesLast[series.id] = {
    chapterId: chapter.id,
    releaseId: chapter.releaseId,
    page: state.currentPage,
  };

  if (pageCount > 0 && state.currentPage >= pageCount - 1) {
    state.readState.chapterDone[chapter.id] = true;
  }

  if (save) saveReadState();
}

function getChapterPercent(chapter) {
  const progress = state.readState.chapterProgress[chapter.id];
  if (!progress?.pageCount) return 0;
  return Math.min(100, Math.round(((progress.page + 1) / progress.pageCount) * 100));
}

function getSeriesReadStats(series) {
  const chapterIds = collectSeriesChapterIds(series);
  let read = 0;
  chapterIds.forEach((id) => {
    if (state.readState.chapterDone[id]) read += 1;
  });
  return { read, total: chapterIds.length };
}

function collectSeriesChapterIds(series) {
  const ids = [];
  for (const release of getReleases(series)) {
    for (const chapterId of release.chapterIds || []) {
      if (state.chapterMap.has(chapterId)) ids.push(chapterId);
    }
  }
  return ids;
}

function getSeriesList() {
  return state.library?.series || [];
}

function getCurrentSeries() {
  return getSeriesList().find((entry) => entry.id === state.currentSeriesId) || null;
}

function getReleases(series) {
  return series?.releases || [];
}

function getCurrentRelease() {
  const series = getCurrentSeries();
  if (!series) return null;
  const release = getReleases(series).find((entry) => entry.id === state.currentReleaseId);
  return release || getReleases(series)[0] || null;
}

function getReleaseChapters(series, releaseId) {
  const release = getReleases(series).find((entry) => entry.id === releaseId);
  if (!release) return [];
  return (release.chapterIds || [])
    .map((id) => state.chapterMap.get(id))
    .filter(Boolean)
    .sort((a, b) => {
      const av = Number.isFinite(a.chapterSort) ? a.chapterSort : (Number.isFinite(a.chapter) ? a.chapter : 0);
      const bv = Number.isFinite(b.chapterSort) ? b.chapterSort : (Number.isFinite(b.chapter) ? b.chapter : 0);
      if (av !== bv) return av - bv;
      return String(a.id).localeCompare(String(b.id), undefined, { numeric: true });
    });
}

function firstSeriesChapter(series) {
  const releases = getReleases(series);
  for (const release of releases) {
    const first = getReleaseChapters(series, release.id)[0];
    if (first) return first;
  }
  return null;
}

function getCurrentChapter() {
  return state.chapterMap.get(state.currentChapterId) || null;
}

function firstSeriesThumb(series) {
  const first = firstSeriesChapter(series);
  return first?.thumb || first?.pages?.[0]?.url || null;
}

function getBookSpread(pages, currentPage) {
  const wide = window.innerWidth >= 1100;
  const canDouble = wide && pages.length > 1;

  if (!canDouble) {
    return {
      single: true,
      left: null,
      right: pages[currentPage] || pages[0] || null,
    };
  }

  const start = Math.floor(currentPage / 2) * 2;
  return {
    single: false,
    left: pages[start] || null,
    right: pages[start + 1] || pages[start] || null,
  };
}

function releaseDisplayName(series, release) {
  if (!release) return '';
  if (series?.id === 'lucky-star') return releaseTomTitle(release);
  return release.title || release.id;
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, (char) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;',
  }[char]));
}

function escapeAttr(value) {
  return escapeHtml(value);
}

async function registerServiceWorker() {
  if (!('serviceWorker' in navigator)) return;
  try {
    await navigator.serviceWorker.register('/luckystar/sw.js');
  } catch (error) {
    console.error('SW register failed', error);
  }
}

window.render_game_to_text = () => JSON.stringify({
  view: state.view,
  seriesId: state.currentSeriesId,
  releaseId: state.currentReleaseId,
  chapterId: state.currentChapterId,
  page: state.currentPage,
  mode: state.mode,
  drawerOpen: state.drawerOpen,
  zoom: state.zoom,
});

boot();
