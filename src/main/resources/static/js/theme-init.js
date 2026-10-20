(function () {
  'use strict';

  var THEME_KEY = 'aloute-theme';
  var theme = 'dark';

  try {
    var savedTheme = localStorage.getItem(THEME_KEY);
    if (savedTheme === 'light' || savedTheme === 'dark') theme = savedTheme;
  } catch (_) {
    // Keep the default theme when browser storage is unavailable.
  }

  document.documentElement.setAttribute('data-bs-theme', theme);
  document.documentElement.style.colorScheme = theme;
})();
