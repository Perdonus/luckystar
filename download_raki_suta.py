#!/usr/bin/env python3
"""
Загрузчик манги Raki Suta (Lucky Star) с manga-chan.me
Использование: python download_raki_suta.py
Требования: pip install tqdm requests beautifulsoup4
"""

import time
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import requests
from tqdm import tqdm

# ─── Настройки ───────────────────────────────────────────────────────────────

OUTPUT_DIR   = "raki_suta"       # папка для сохранения
MAX_WORKERS  = 5                 # потоков одновременно (не увеличивайте слишком — бан)
RETRY_COUNT  = 3                 # повторов при ошибке
RETRY_DELAY  = 3                 # секунд между повторами
TIMEOUT      = 60                # секунд ожидания ответа сервера
CHUNK_SIZE   = 1024 * 64         # 64 KB за раз при записи

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Safari/537.36"
    ),
    "Referer": "https://manga-chan.me/",
}

# ─── Ссылки, извлечённые из сохранённой HTML-страницы ────────────────────────

RAW_LINKS = [
    ("https://dl.manga-chan.me/engine/download.php?id=1822982", "9-259.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1822981", "9-258.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1822980", "9-257.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1822979", "9-256.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1809736", "9-255.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1809735", "9-254.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1798899", "9-253.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1279049", "raki-suta-tom-9-glava-252.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1279046", "raki-suta-tom-9-glava-251.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1276499", "raki-suta-tom-9-glava-250.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1276497", "raki-suta-tom-9-glava-249.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1272609", "raki-suta-tom-9-glava-248.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1272608", "raki-suta-tom-9-glava-247.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1271620", "raki-suta-tom-9-glava-246.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1271618", "raki-suta-tom-9-glava-245.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1271615", "raki-suta-tom-9-glava-244.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1271613", "raki-suta-tom-9-glava-243.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1271612", "raki-suta-tom-9-glava-242.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1271610", "raki-suta-tom-9-glava-241.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1263204", "raki-suta-tom-9-glava-240.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1263201", "raki-suta-tom-9-glava-239.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1257196", "raki-suta-tom-9-glava-238.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1254877", "raki-suta-tom-8-glava-237.4.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1254874", "raki-suta-tom-8-glava-237.3.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1254871", "raki-suta-tom-8-glava-237.2.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1245650", "raki-suta-tom-8-glava-236.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1245649", "raki-suta-tom-8-glava-235.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1243263", "raki-suta-tom-8-glava-234.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1694783", "raki_suta_v8_ch233.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1236827", "raki-suta-tom-8-glava-232.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1236824", "raki-suta-tom-8-glava-231.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1234229", "raki-suta-tom-8-glava-230.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1234227", "raki-suta-tom-8-glava-229.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1230650", "raki-suta-tom-8-glava-228.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1230649", "raki-suta-tom-8-glava-227.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1227384", "raki-suta-tom-8-glava-226.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1227383", "raki-suta-tom-8-glava-225.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1225004", "raki-suta-tom-8-glava-224.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1225000", "raki-suta-tom-8-glava-223.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1220278", "raki-suta-tom-8-glava-222.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1220277", "raki-suta-tom-8-glava-221.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1217131", "raki-suta-tom-8-glava-220.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1217130", "raki-suta-tom-8-glava-219.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1217129", "raki-suta-tom-8-glava-218.1.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1209808", "raki-suta-tom-8-glava-218.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1209806", "raki-suta-tom-7-glava-217.2.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1207554", "raki-suta-tom-7-glava-217.1.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1201200", "raki-suta-tom-7-glava-217.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1199132", "raki-suta-tom-7-glava-216.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1196265", "raki-suta-tom-7-glava-215.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1196264", "raki-suta-tom-7-glava-214.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1193400", "raki-suta-tom-7-glava-213.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1193396", "raki-suta-tom-7-glava-212.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1188603", "raki-suta-tom-7-glava-211.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1188600", "raki-suta-tom-7-glava-210.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1183401", "raki-suta-tom-7-glava-209.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1183399", "raki-suta-tom-7-glava-208.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1180170", "raki-suta-tom-7-glava-207.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1180168", "raki-suta-tom-7-glava-206.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1176902", "raki-suta-tom-7-glava-205.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1176900", "raki-suta-tom-7-glava-204.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1167171", "raki-suta-tom-7-glava-203.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1167170", "raki-suta-tom-7-glava-202.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1161713", "raki-suta-tom-7-glava-201.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1161712", "raki-suta-tom-7-glava-200.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1159865", "raki-suta-tom-7-glava-199.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1159864", "raki-suta-tom-7-glava-198.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1153166", "raki-suta-tom-7-glava-197.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1153165", "raki-suta-tom-7-glava-196.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148242", "lucky-star_v7_ch195.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148243", "lucky-star_v7_ch194.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148244", "lucky-star_v7_ch193.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148245", "lucky-star_v7_ch192.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148246", "lucky-star_v7_ch191.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148247", "lucky-star_v7_ch190.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148248", "lucky-star_v7_ch189.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148249", "lucky-star_v7_ch188.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148250", "lucky-star_v7_ch187.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148251", "lucky-star_v7_ch186.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148252", "lucky-star_v7_ch185.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148253", "lucky-star_v6_ch184.2.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148254", "lucky-star_v6_ch184.1.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148255", "lucky-star_v6_ch184.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148256", "lucky-star_v6_ch183.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148257", "lucky-star_v6_ch182.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148258", "lucky-star_v6_ch181.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148259", "lucky-star_v6_ch180.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148260", "lucky-star_v6_ch179.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148261", "lucky-star_v6_ch178.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148262", "lucky-star_v6_ch177.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148263", "lucky-star_v6_ch176.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148264", "lucky-star_v6_ch175.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148265", "lucky-star_v6_ch174.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148266", "lucky-star_v6_ch173.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148267", "lucky-star_v6_ch172.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148268", "lucky-star_v6_ch171.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148269", "lucky-star_v6_ch170.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148270", "lucky-star_v6_ch169.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148271", "lucky-star_v6_ch168.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148272", "lucky-star_v6_ch167.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148273", "lucky-star_v6_ch166.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148274", "lucky-star_v6_ch165.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148275", "lucky-star_v6_ch164.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148276", "lucky-star_v6_ch163.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148277", "lucky-star_v6_ch162.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148278", "lucky-star_v6_ch161.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148279", "lucky-star_v6_ch160.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148280", "lucky-star_v6_ch159.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148281", "lucky-star_v6_ch158.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148282", "lucky-star_v6_ch157.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148283", "lucky-star_v6_ch156.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148284", "lucky-star_v6_ch155.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148285", "lucky-star_v6_ch154.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148286", "lucky-star_v6_ch153.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148287", "lucky-star_v6_ch152.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148288", "lucky-star_v6_ch151.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148289", "lucky-star_v6_ch150.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148290", "lucky-star_v5_ch149.5.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148291", "lucky-star_v5_ch149.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148292", "lucky-star_v5_ch148.3.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148293", "lucky-star_v5_ch148.2.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148294", "lucky-star_v5_ch148.1.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148295", "lucky-star_v5_ch147.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148296", "lucky-star_v5_ch146.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148297", "lucky-star_v5_ch145.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148298", "lucky-star_v5_ch144.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148299", "lucky-star_v5_ch143.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148300", "lucky-star_v5_ch142.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148301", "lucky-star_v5_ch141.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148302", "lucky-star_v5_ch140.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148303", "lucky-star_v5_ch139.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148304", "lucky-star_v5_ch138.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148305", "lucky-star_v5_ch137.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148306", "lucky-star_v5_ch136.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148307", "lucky-star_v5_ch135.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148308", "lucky-star_v5_ch134.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148309", "lucky-star_v5_ch133.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148310", "lucky-star_v5_ch132.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148311", "lucky-star_v5_ch131.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148312", "lucky-star_v5_ch130.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148313", "lucky-star_v5_ch129.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148314", "lucky-star_v5_ch128.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148315", "lucky-star_v5_ch127.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148316", "lucky-star_v5_ch126.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148317", "lucky-star_v5_ch125.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148318", "lucky-star_v5_ch124.2.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148319", "lucky-star_v4_ch124.1.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148320", "lucky-star_v4_ch124.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148321", "lucky-star_v4_ch123.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148322", "lucky-star_v4_ch122.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148323", "lucky-star_v4_ch121.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148324", "lucky-star_v4_ch120.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148325", "lucky-star_v4_ch119.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148326", "lucky-star_v4_ch118.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148327", "lucky-star_v4_ch117.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148328", "lucky-star_v4_ch116.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148329", "lucky-star_v4_ch115.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148330", "lucky-star_v4_ch114.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148331", "lucky-star_v4_ch113.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148332", "lucky-star_v4_ch112.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148333", "lucky-star_v4_ch111.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148334", "lucky-star_v4_ch110.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148335", "lucky-star_v4_ch109.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148336", "lucky-star_v4_ch108.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148337", "lucky-star_v4_ch107.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148338", "lucky-star_v4_ch106.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148339", "lucky-star_v4_ch105.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148340", "lucky-star_v4_ch104.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148341", "lucky-star_v4_ch103.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148342", "lucky-star_v4_ch102.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148343", "lucky-star_v4_ch101.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148344", "lucky-star_v4_ch100.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148345", "lucky-star_v4_ch99.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148346", "lucky-star_v4_ch98.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148347", "lucky-star_v4_ch97.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148348", "lucky-star_v4_ch96.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148349", "lucky-star_v4_ch95.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148350", "lucky-star_v4_ch94.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148351", "lucky-star_v4_ch93.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148352", "lucky-star_v4_ch92.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148353", "lucky-star_v4_ch91.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148354", "lucky-star_v4_ch90.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148355", "lucky-star_v4_ch89.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148356", "lucky-star_v4_ch88.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148357", "lucky-star_v3_ch87.1.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148358", "lucky-star_v3_ch87.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148359", "lucky-star_v3_ch86.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148360", "lucky-star_v3_ch85.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148361", "lucky-star_v3_ch84.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148362", "lucky-star_v3_ch83.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148363", "lucky-star_v3_ch82.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148364", "lucky-star_v3_ch81.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148365", "lucky-star_v3_ch80.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148366", "lucky-star_v3_ch79.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148367", "lucky-star_v3_ch78.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148368", "lucky-star_v3_ch77.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148369", "lucky-star_v3_ch76.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148370", "lucky-star_v3_ch75.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148371", "lucky-star_v3_ch74.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148372", "lucky-star_v3_ch73.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148373", "lucky-star_v3_ch72.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148374", "lucky-star_v3_ch71.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148375", "lucky-star_v3_ch70.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148376", "lucky-star_v3_ch69.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148377", "lucky-star_v3_ch68.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148378", "lucky-star_v3_ch67.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148379", "lucky-star_v3_ch66.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148380", "lucky-star_v3_ch65.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148381", "lucky-star_v3_ch64.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148382", "lucky-star_v3_ch63.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148383", "lucky-star_v3_ch62.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148384", "lucky-star_v3_ch61.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148385", "lucky-star_v3_ch60.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148386", "lucky-star_v3_ch59.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148387", "lucky-star_v3_ch58.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148388", "lucky-star_v3_ch57.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148389", "lucky-star_v2_ch56.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148390", "lucky-star_v2_ch55.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148391", "lucky-star_v2_ch54.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148392", "lucky-star_v2_ch53.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148393", "lucky-star_v2_ch52.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148394", "lucky-star_v2_ch51.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148395", "lucky-star_v2_ch50.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148396", "lucky-star_v2_ch49.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148397", "lucky-star_v2_ch48.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148398", "lucky-star_v2_ch47.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148399", "lucky-star_v2_ch46.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148400", "lucky-star_v2_ch45.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148401", "lucky-star_v2_ch44.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148402", "lucky-star_v2_ch43.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148403", "lucky-star_v2_ch43.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148404", "lucky-star_v2_ch42.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148405", "lucky-star_v2_ch41.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148406", "lucky-star_v2_ch40.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148407", "lucky-star_v2_ch39.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148408", "lucky-star_v2_ch38.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148409", "lucky-star_v2_ch37.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148410", "lucky-star_v2_ch36.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148411", "lucky-star_v2_ch35.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148412", "lucky-star_v2_ch34.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148413", "lucky-star_v2_ch33.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148414", "lucky-star_v2_ch32.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148415", "lucky-star_v2_ch31.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148416", "lucky-star_v2_ch30.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148417", "lucky-star_v2_ch29.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148418", "lucky-star_v2_ch28.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148419", "lucky-star_v2_ch27.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148420", "lucky-star_v2_ch26.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148421", "lucky-star_v1_ch25.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148422", "lucky-star_v1_ch24.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148423", "lucky-star_v1_ch23.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148424", "lucky-star_v1_ch22.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148425", "lucky-star_v1_ch21.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148426", "lucky-star_v1_ch20.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148427", "lucky-star_v1_ch19.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148428", "lucky-star_v1_ch18.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148429", "lucky-star_v1_ch17.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148430", "lucky-star_v1_ch16.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148431", "lucky-star_v1_ch15.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148432", "lucky-star_v1_ch14.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148433", "lucky-star_v1_ch13.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148434", "lucky-star_v1_ch12.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148435", "lucky-star_v1_ch11.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148436", "lucky-star_v1_ch10.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148437", "lucky-star_v1_ch9.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148438", "lucky-star_v1_ch8.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148439", "lucky-star_v1_ch7.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148440", "lucky-star_v1_ch6.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148441", "lucky-star_v1_ch5.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148442", "lucky-star_v1_ch4.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148443", "lucky-star_v1_ch3.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148444", "lucky-star_v1_ch2.zip"),
    ("https://dl.manga-chan.me/engine/download.php?id=1148445", "lucky-star_v1_ch1.zip"),
]

# ─── Лог неудачных загрузок ──────────────────────────────────────────────────

_lock = threading.Lock()
failed: list[tuple[str, str, str]] = []   # (url, filename, причина)


def file_is_ready(path: Path) -> bool:
    return path.exists() and path.is_file() and path.stat().st_size > 0


def resolve_final_destination(out_dir: Path, requested_name: str, response: requests.Response) -> tuple[str, Path]:
    filename = requested_name
    cd = response.headers.get("Content-Disposition", "")
    if "filename=" in cd:
        cd_name = cd.split("filename=")[-1].strip().strip('"\'')
        if cd_name:
            filename = Path(cd_name).name
    return filename, out_dir / filename


# ─── Основная функция скачивания одного файла ────────────────────────────────

def download_one(url: str, filename: str, out_dir: Path, progress: tqdm) -> bool:
    requested_dest = out_dir / filename
    part_dest = requested_dest.with_suffix(requested_dest.suffix + ".part")

    if file_is_ready(requested_dest):
        progress.update(1)
        progress.set_postfix_str(f"пропущен: {requested_dest.name}", refresh=True)
        return True

    for attempt in range(1, RETRY_COUNT + 1):
        try:
            resume_from = part_dest.stat().st_size if part_dest.exists() else 0
            headers = dict(HEADERS)
            if resume_from > 0:
                headers["Range"] = f"bytes={resume_from}-"

            with requests.get(
                url,
                headers=headers,
                stream=True,
                timeout=TIMEOUT,
                allow_redirects=True,
            ) as resp:
                resp.raise_for_status()

                filename, dest = resolve_final_destination(out_dir, filename, resp)
                part_dest = dest.with_suffix(dest.suffix + ".part")

                if file_is_ready(dest):
                    progress.update(1)
                    progress.set_postfix_str(f"пропущен: {dest.name}", refresh=True)
                    return True

                if part_dest != requested_dest.with_suffix(requested_dest.suffix + ".part") and part_dest.exists():
                    resume_from = part_dest.stat().st_size
                else:
                    if resp.status_code == 206 and resume_from > 0:
                        pass
                    elif resume_from > 0 and resp.status_code == 200:
                        part_dest.unlink(missing_ok=True)
                        resume_from = 0

                expected_total = None
                content_range = resp.headers.get("Content-Range")
                if content_range and "/" in content_range:
                    total_part = content_range.rsplit("/", 1)[-1]
                    if total_part.isdigit():
                        expected_total = int(total_part)
                elif resp.headers.get("Content-Length", "").isdigit():
                    expected_total = int(resp.headers["Content-Length"]) + resume_from

                mode = "ab" if resp.status_code == 206 and resume_from > 0 else "wb"
                if mode == "wb":
                    resume_from = 0

                with open(part_dest, mode) as f:
                    for chunk in resp.iter_content(CHUNK_SIZE):
                        if chunk:
                            f.write(chunk)

                final_size = part_dest.stat().st_size
                if expected_total is not None and final_size < expected_total:
                    raise IOError(
                        f"файл не докачался: {final_size} из {expected_total} байт"
                    )

                part_dest.replace(dest)

            progress.update(1)
            progress.set_postfix_str(f"OK: {filename}", refresh=True)
            return True

        except Exception as exc:
            if attempt < RETRY_COUNT:
                time.sleep(RETRY_DELAY)
            else:
                with _lock:
                    failed.append((url, filename, str(exc)))
                progress.update(1)
                progress.set_postfix_str(f"ОШИБКА: {filename}", refresh=True)
                return False

    return False


# ─── Точка входа ─────────────────────────────────────────────────────────────

def main() -> None:
    out_dir = Path(OUTPUT_DIR)
    out_dir.mkdir(exist_ok=True)

    total = len(RAW_LINKS)
    print(f"Всего глав: {total}")
    print(f"Папка сохранения: {out_dir.resolve()}")
    print(f"Потоков: {MAX_WORKERS}  |  Повторов: {RETRY_COUNT}\n")

    with tqdm(
        total=total,
        unit="гл",
        dynamic_ncols=True,
        bar_format="{l_bar}{bar}| {n_fmt}/{total_fmt} [{elapsed}<{remaining}, {rate_fmt}]",
    ) as progress:
        with ThreadPoolExecutor(max_workers=MAX_WORKERS) as pool:
            futures = {
                pool.submit(download_one, url, fname, out_dir, progress): fname
                for url, fname in RAW_LINKS
            }
            for fut in as_completed(futures):
                fut.result()  # пробрасываем необработанные исключения в лог

    # Итог
    ok = total - len(failed)
    print(f"\n✅ Успешно: {ok}/{total}")

    if failed:
        print(f"❌ Не удалось ({len(failed)}):")
        for url, fname, reason in failed:
            print(f"  • {fname}: {reason}")

        log_path = out_dir / "failed.txt"
        with open(log_path, "w", encoding="utf-8") as f:
            for url, fname, reason in failed:
                f.write(f"{url}\t{fname}\t{reason}\n")
        print(f"\nСписок ошибок сохранён в: {log_path}")


if __name__ == "__main__":
    main()
