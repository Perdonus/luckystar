const STORAGE_KEY = 'luckystar-reader-v7';
const app = document.querySelector('#app');

const state = {
  library: null,
  series: [],
  currentSeriesId: null,
  currentReleaseId: null,
  currentChapterId: null,
  currentPage: 0,
  menuOpen: false,
  flipping: false,
  flipDirection: 'next',
  touchStartX: 0,
  touchStartY: 0,
  readState: loadState(),
};

function defaultState() {
  return {
    lastSeriesId: null,
    lastReleaseBySeries: {},
    lastChapterBySeries: {},
    progressByChapter: {},
    chapterDone: {},
  };
}

function loadState() {
  try {
    return { ...defaultState(), ...(JSON.parse(localStorage.getItem(STORAGE_KEY)) || {}) };
  } catch {
    return defaultState();
  }
}

function saveState() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state.readState));
}

async function boot() {
  try {
    const response = await fetch('./data/library.json', { cache: 'no-cache' });
    if (!response.ok) throw new Error('Failed to load library.json');
    state.library = await response.json();
    state.series = Array.isArray(state.library?.series) ? state.library.series : [];
    if (!state.series.length) throw new Error('Library is empty');
    state.currentSeriesId = state.readState.lastSeriesId || state.series[0].id;
    ensureSelections();
    bindGlobalEvents();
    render();
  } catch (error) {
    console.error(error);
    app.innerHTML = '<main class="empty-screen">Не удалось загрузить библиотеку.</main>';
  }
}

function getSeriesById(id) {
  return state.series.find((series) => series.id === id) || null;
}

function getCurrentSeries() {
  return getSeriesById(state.currentSeriesId) || state.series[0] || null;
}

function getSeriesReleases(series) {
  return Array.isArray(series?.releases)
    ? series.releases.filter((release) => Array.isArray(release.chapterIds) && release.chapterIds.length > 0)
    : [];
}

function getCurrentRelease() {
  const series = getCurrentSeries();
  const releases = getSeriesReleases(series);
  return releases.find((release) => release.id === state.currentReleaseId) || releases[0] || null;
}

function getCurrentChapter() {
  return state.currentChapterId ? state.library?.chapterIndex?.[state.currentChapterId] || null : null;
}

function getCurrentPages() {
  return getCurrentChapter()?.pages || [];
}

function getReleaseIndex(series, releaseId) {
  const releases = getSeriesReleases(series);
  const index = releases.findIndex((release) => release.id === releaseId);
  return index >= 0 ? index + 1 : 1;
}

function getDisplayReleaseTitle(series, release) {
  if (!series || !release) return '';
  return `Том ${getReleaseIndex(series, release.id)}`;
}

function getChapterNumber(release, chapterId) {
  const ids = release?.chapterIds || [];
  const index = ids.indexOf(chapterId);
  return index >= 0 ? index + 1 : 1;
}

function ensureSelections() {
  const series = getCurrentSeries();
  if (!series) return;

  const releases = getSeriesReleases(series);
  if (!releases.length) {
    state.currentReleaseId = null;
    state.currentChapterId = null;
    state.currentPage = 0;
    return;
  }

  const rememberedRelease = state.readState.lastReleaseBySeries[series.id];
  state.currentReleaseId = releases.some((release) => release.id === rememberedRelease) ? rememberedRelease : releases[0].id;

  const release = releases.find((item) => item.id === state.currentReleaseId) || releases[0];
  const chapterIds = release.chapterIds || [];
  const rememberedChapter = state.readState.lastChapterBySeries[series.id];
  state.currentChapterId = chapterIds.includes(rememberedChapter) ? rememberedChapter : chapterIds[0] || null;

  const pages = getCurrentPages();
  const rememberedPage = state.currentChapterId ? state.readState.progressByChapter[state.currentChapterId]?.page || 0 : 0;
  state.currentPage = clamp(rememberedPage, 0, Math.max(0, pages.length - 1));
}

function getChapterProgress(chapterId) {
  return state.readState.progressByChapter[chapterId] || null;
}

function isChapterDone(chapterId) {
  return Boolean(state.readState.chapterDone[chapterId] || getChapterProgress(chapterId)?.done);
}

function render() {
  if (!state.currentChapterId) {
    renderHome();
    return;
  }
  renderReader();
}

function renderHome() {
  const cards = state.series.map((series) => `
    <button class="series-tile" data-series-id="${series.id}" aria-label="${escapeHtml(series.title)}">
      <img src=".${series.banner}" alt="${escapeHtml(series.title)}" loading="lazy" />
      <span>${escapeHtml(series.title)}</span>
    </button>
  `).join('');

  app.innerHTML = `
    <main class="home-screen">
      <section class="series-grid">${cards}</section>
    </main>
  `;

  document.querySelectorAll('[data-series-id]').forEach((button) => {
    button.addEventListener('click', () => {
      state.currentSeriesId = button.dataset.seriesId;
      state.readState.lastSeriesId = state.currentSeriesId;
      ensureSelections();
      openChapter(state.currentChapterId, { keepMenu: false, resetFlip: true });
    });
  });
}

function renderReader() {
  const series = getCurrentSeries();
  const releases = getSeriesReleases(series);
  const release = getCurrentRelease();
  const chapter = getCurrentChapter();
  const pages = getCurrentPages();
  const page = pages[state.currentPage] || null;
  const prevPage = pages[Math.max(0, state.currentPage - 1)] || page;
  const nextPageRef = pages[Math.min(pages.length - 1, state.currentPage + 1)] || page;
  const chapterIds = release?.chapterIds || [];

  const tomeButtons = releases.map((item) => `
    <button class="tome-pill ${item.id === release?.id ? 'active' : ''}" data-release-id="${item.id}">
      ${escapeHtml(getDisplayReleaseTitle(series, item))}
    </button>
  `).join('');

  const chapterButtons = chapterIds.map((chapterId) => {
    const chapterNumber = getChapterNumber(release, chapterId);
    const done = isChapterDone(chapterId);
    const progress = getChapterProgress(chapterId);
    const mark = done ? '✓' : (typeof progress?.page === 'number' && progress.page > 0 ? `${progress.page + 1}` : '');
    return `
      <button class="chapter-link ${chapterId === chapter?.id ? 'active' : ''} ${done ? 'done' : ''}" data-chapter-id="${chapterId}" aria-label="Глава ${chapterNumber}">
        <span class="chapter-left">Глава ${chapterNumber}</span>
        <span class="chapter-right">${mark}</span>
      </button>
    `;
  }).join('');

  app.innerHTML = `
    <section class="reader-screen">
      <aside class="sidebar ${state.menuOpen ? 'open' : ''}" id="sidebar">
        <div class="sidebar-head">
          <button class="home-link" id="title-home">← Манги</button>
        </div>
        <div class="sidebar-tomes">${tomeButtons}</div>
        <div class="chapter-menu">${chapterButtons}</div>
      </aside>

      <button class="sidebar-backdrop ${state.menuOpen ? 'open' : ''}" id="sidebar-backdrop" aria-label="Закрыть меню"></button>

      <div class="reader-root">
        <header class="topbar">
          <button class="icon-button" id="menu-toggle" aria-label="Меню">☰</button>
          <div class="topbar-center">
            <div class="topbar-title">${escapeHtml(series?.title || '')}</div>
            <div class="topbar-subtitle">${escapeHtml(getDisplayReleaseTitle(series, release))} · Глава ${getChapterNumber(release, chapter?.id)}</div>
          </div>
          <div class="page-indicator">${state.currentPage + 1}/${pages.length || 0}</div>
        </header>

        <main class="reader-fullscreen" id="reader-fullscreen">
          <button class="nav-zone left" id="prev-page" aria-label="Назад"></button>
          <section class="book-stage">
            <div class="book-frame">
              <img class="page-under ${state.flipping && state.flipDirection === 'prev' ? 'show' : ''}" src=".${prevPage?.url || page?.url || ''}" alt="" />
              <img class="page-under ${state.flipping && state.flipDirection === 'next' ? 'show' : ''}" src=".${nextPageRef?.url || page?.url || ''}" alt="" />
              <div class="page-sheet ${state.flipping ? `animate ${state.flipDirection}` : ''}">
                <div class="page-face front">
                  <img class="page-current" src=".${page?.url || ''}" alt="${escapeHtml(chapter?.title || '')}" draggable="false" />
                </div>
                <div class="page-face back"></div>
              </div>
            </div>
          </section>
          <button class="nav-zone right" id="next-page" aria-label="Вперёд"></button>
        </main>
      </div>
    </section>
  `;

  bindReaderEvents();
}

function bindReaderEvents() {
  document.querySelector('#menu-toggle')?.addEventListener('click', () => {
    state.menuOpen = !state.menuOpen;
    render();
  });

  document.querySelector('#sidebar-backdrop')?.addEventListener('click', () => {
    state.menuOpen = false;
    render();
  });

  document.querySelector('#title-home')?.addEventListener('click', () => {
    state.currentChapterId = null;
    state.menuOpen = false;
    render();
  });

  document.querySelectorAll('[data-release-id]').forEach((button) => {
    button.addEventListener('click', () => {
      state.currentReleaseId = button.dataset.releaseId;
      const series = getCurrentSeries();
      if (series) state.readState.lastReleaseBySeries[series.id] = state.currentReleaseId;
      ensureSelections();
      openChapter(state.currentChapterId, { keepMenu: true, resetFlip: true });
    });
  });

  document.querySelectorAll('[data-chapter-id]').forEach((button) => {
    button.addEventListener('click', () => openChapter(button.dataset.chapterId, { keepMenu: true, resetFlip: true }));
  });

  document.querySelector('#prev-page')?.addEventListener('click', previousPage);
  document.querySelector('#next-page')?.addEventListener('click', nextPage);

  const reader = document.querySelector('#reader-fullscreen');
  if (reader) {
    reader.addEventListener('click', (event) => {
      if (event.target.closest('#prev-page, #next-page, #menu-toggle, #sidebar, #title-home')) return;
      const bounds = reader.getBoundingClientRect();
      const x = event.clientX - bounds.left;
      if (x < bounds.width * 0.33) previousPage();
      else if (x > bounds.width * 0.67) nextPage();
      else {
        state.menuOpen = !state.menuOpen;
        render();
      }
    });

    reader.addEventListener('touchstart', (event) => {
      const touch = event.changedTouches?.[0];
      if (!touch) return;
      state.touchStartX = touch.clientX;
      state.touchStartY = touch.clientY;
    }, { passive: true });

    reader.addEventListener('touchend', (event) => {
      const touch = event.changedTouches?.[0];
      if (!touch) return;
      const dx = touch.clientX - state.touchStartX;
      const dy = touch.clientY - state.touchStartY;
      if (Math.abs(dx) < 48 || Math.abs(dx) < Math.abs(dy)) return;
      if (dx < 0) nextPage();
      else previousPage();
    }, { passive: true });
  }
}

function bindGlobalEvents() {
  window.addEventListener('keydown', (event) => {
    if (!state.currentChapterId) return;

    if (event.key === 'Escape') {
      if (state.menuOpen) {
        state.menuOpen = false;
        render();
      }
      return;
    }

    if (event.key === 'ArrowRight' || event.key === 'PageDown' || event.key === ' ') {
      event.preventDefault();
      nextPage();
      return;
    }

    if (event.key === 'ArrowLeft' || event.key === 'PageUp') {
      event.preventDefault();
      previousPage();
    }
  });
}

function openChapter(chapterId, options = {}) {
  const chapter = chapterId ? state.library?.chapterIndex?.[chapterId] : null;
  if (!chapter) {
    render();
    return;
  }

  state.currentSeriesId = chapter.seriesId;
  state.currentReleaseId = chapter.releaseId;
  state.currentChapterId = chapter.id;
  state.currentPage = clamp(state.readState.progressByChapter[chapter.id]?.page || 0, 0, Math.max(0, chapter.pages.length - 1));
  state.menuOpen = Boolean(options.keepMenu);
  if (options.resetFlip) state.flipping = false;
  saveProgress();
  render();
}

function setPage(page, direction) {
  const pages = getCurrentPages();
  const target = clamp(page, 0, Math.max(0, pages.length - 1));
  if (target === state.currentPage || state.flipping) return;

  state.flipDirection = direction;
  state.flipping = true;
  render();

  window.setTimeout(() => {
    state.currentPage = target;
    saveProgress();
    render();
    window.setTimeout(() => {
      state.flipping = false;
      render();
    }, 35);
  }, 280);
}

function nextPage() {
  const pages = getCurrentPages();
  if (!pages.length) return;
  if (state.currentPage >= pages.length - 1) {
    markCurrentChapterDone();
    return;
  }
  setPage(state.currentPage + 1, 'next');
}

function previousPage() {
  if (state.currentPage <= 0) return;
  setPage(state.currentPage - 1, 'prev');
}

function markCurrentChapterDone() {
  const chapter = getCurrentChapter();
  if (!chapter) return;
  state.readState.chapterDone[chapter.id] = true;
  saveProgress();
  render();
}

function saveProgress() {
  const chapter = getCurrentChapter();
  const pages = getCurrentPages();
  if (!chapter) return;

  state.readState.lastSeriesId = chapter.seriesId;
  state.readState.lastReleaseBySeries[chapter.seriesId] = chapter.releaseId;
  state.readState.lastChapterBySeries[chapter.seriesId] = chapter.id;
  state.readState.progressByChapter[chapter.id] = {
    page: state.currentPage,
    done: pages.length > 0 && state.currentPage >= pages.length - 1,
  };

  if (pages.length > 0 && state.currentPage >= pages.length - 1) {
    state.readState.chapterDone[chapter.id] = true;
  }

  saveState();
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

function escapeHtml(value) {
  return String(value || '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

boot();
