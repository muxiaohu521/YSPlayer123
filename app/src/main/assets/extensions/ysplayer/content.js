(function() {
    'use strict';

    var YS_DONE_ATTR = 'data-ys-enhanced';

    function enhanceVideo(v) {
        if (!v || v.hasAttribute(YS_DONE_ATTR)) return;
        v.setAttribute(YS_DONE_ATTR, '1');
        v.setAttribute('controls', 'controls');
    }

    function scanRoot(root) {
        if (!root || !root.querySelectorAll) return;
        var videos = root.querySelectorAll('video');
        for (var i = 0; i < videos.length; i++) {
            enhanceVideo(videos[i]);
        }
    }

    scanRoot(document);

    new MutationObserver(function(mutations) {
        for (var i = 0; i < mutations.length; i++) {
            var added = mutations[i].addedNodes;
            for (var j = 0; j < added.length; j++) {
                var node = added[j];
                if (node.nodeType !== 1) continue;
                if (node.tagName === 'VIDEO') {
                    enhanceVideo(node);
                }
                scanRoot(node);
            }
        }
    }).observe(document.body || document.documentElement,
        { childList: true, subtree: true });

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function() {
            scanRoot(document);
        });
    }
})();