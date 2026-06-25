// ============================================================
// 走り書きメモ — Service Worker
// index.html と同じディレクトリに置いてください
// ============================================================

const CACHE_VERSION = 'v4';
const CACHE_NAME    = 'hashirigaki-memo-' + CACHE_VERSION;

// キャッシュ対象ファイル（相対パス）
const PRECACHE_URLS = [
    './',
    './index.html',
    './about.html',
];

// ============================================================
// install: 起動に必要なファイルを事前キャッシュ
// ============================================================
self.addEventListener('install', function(event) {
    event.waitUntil(
        caches.open(CACHE_NAME).then(function(cache) {
            return Promise.allSettled(
                PRECACHE_URLS.map(function(url) {
                    return fetch(url, { cache: 'no-cache' })
                        .then(function(res) {
                            if (res && res.status === 200) {
                                return cache.put(url, res);
                            }
                        })
                        .catch(function() {
                            // オフライン環境でのinstallは無視
                        });
                })
            );
        }).then(function() {
            return self.skipWaiting();
        })
    );
});

// ============================================================
// activate: 古いキャッシュを削除して即クライアントを掌握
// ============================================================
self.addEventListener('activate', function(event) {
    event.waitUntil(
        caches.keys().then(function(keys) {
            return Promise.all(
                keys
                    .filter(function(k) { return k !== CACHE_NAME; })
                    .map(function(k)    { return caches.delete(k); })
            );
        }).then(function() {
            return self.clients.claim();
        })
    );
});

// ============================================================
// fetch: キャッシュ優先 → ネットワーク → キャッシュフォールバック
// ============================================================
self.addEventListener('fetch', function(event) {
    if (event.request.method !== 'GET') return;

    // chrome-extension など別オリジンは無視
    const url = new URL(event.request.url);
    if (url.origin !== self.location.origin) return;

    event.respondWith(
        caches.match(event.request).then(function(cached) {
            if (cached) {
                // バックグラウンドで更新（Stale-While-Revalidate）
                fetch(event.request, { cache: 'no-cache' })
                    .then(function(fresh) {
                        if (fresh && fresh.status === 200) {
                            caches.open(CACHE_NAME).then(function(cache) {
                                cache.put(event.request, fresh);
                            });
                        }
                    })
                    .catch(function() {});
                return cached;
            }
            // キャッシュ未ヒット → ネットワーク取得してキャッシュ
            return fetch(event.request, { cache: 'no-cache' })
                .then(function(res) {
                    if (res && res.status === 200) {
                        const clone = res.clone();
                        caches.open(CACHE_NAME).then(function(cache) {
                            cache.put(event.request, clone);
                        });
                    }
                    return res;
                })
                .catch(function() {
                    // ナビゲーションならindex.htmlを返す
                    if (event.request.mode === 'navigate') {
                        return caches.match('./index.html');
                    }
                });
        })
    );
});
