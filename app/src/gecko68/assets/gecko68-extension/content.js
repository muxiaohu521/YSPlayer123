(function() {
    'use strict';

    function doUnmute(v) {
        try {
            v.muted = false;
            v.defaultMuted = false;
            v.removeAttribute('muted');
            v.volume = 1.0;
        } catch(e) {}
    }

    function doFullscreen(v) {
        try {
            var fn = v.requestFullscreen
                 || v.mozRequestFullScreen
                 || v.webkitEnterFullscreen
                 || v.webkitRequestFullscreen;
            if (fn) {
                fn.call(v).then(function() {
                    console.log('[ysplayer] fullscreen ok');
                }, function(err) {
                    console.log('[ysplayer] fullscreen rejected:', err);
                });
            }
        } catch(e) {
            console.log('[ysplayer] fullscreen err:', e);
        }
    }

    function handleMedia(v) {
        if (v.__ysHandled) return;
        v.__ysHandled = true;

        doUnmute(v);

        v.addEventListener('play', function() {
            doUnmute(this);
            setTimeout(function() { doFullscreen(this); }, 500);
        });

        v.addEventListener('playing', function() {
            doFullscreen(this);
        });

        v.addEventListener('loadedmetadata', function() { doUnmute(this); });
        v.addEventListener('volumechange', function() {
            if (this.muted) doUnmute(this);
        });
    }

    function scan() {
        var all = document.querySelectorAll('video, audio');
        for (var i = 0; i < all.length; i++) {
            handleMedia(all[i]);
        }
    }

    var mo = new MutationObserver(function(mutations) {
        for (var x = 0; x < mutations.length; x++) {
            var added = mutations[x].addedNodes;
            for (var i = 0; i < added.length; i++) {
                var node = added[i];
                if (node.nodeName === 'VIDEO' || node.nodeName === 'AUDIO') {
                    handleMedia(node);
                } else if (node.nodeType === 1 && node.querySelectorAll) {
                    var vids = node.querySelectorAll('video, audio');
                    for (var j = 0; j < vids.length; j++) {
                        handleMedia(vids[j]);
                    }
                }
            }
        }
    });

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function() {
            mo.observe(document.documentElement, { childList: true, subtree: true });
            scan();
        });
    } else {
        mo.observe(document.documentElement, { childList: true, subtree: true });
        scan();
    }
})();