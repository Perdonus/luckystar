# Lucky Star Reader

PWA-читалка Lucky Star для пути `/luckystar/`.

## Что есть
- все главы из локальной коллекции zip
- статусы прочтения и возврат к точной странице
- 3D book mode + scroll mode
- mobile-friendly PWA с оффлайн загрузкой
- GitHub Actions build/deploy workflow

## Локальный запуск
```bash
npm ci
npm run build
npm run dev
```

## GitHub Pages
Workflow `.github/workflows/deploy.yml` собирает `dist/` и публикует сайт через GitHub Pages.

Если хочешь деплоить именно на `sosiskibot.ru/luckystar`, можно:
1. либо проксировать/реверсить этот путь на GitHub Pages,
2. либо забирать `dist/` из GitHub Actions и выкладывать на твой сервер.
