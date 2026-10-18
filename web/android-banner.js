// Shows a card to Android visitors, asking them to install the VIX app.
// It also shows inside the installed home-screen web app, because a website
// cannot tell whether the APK is installed. "I already have the app" hides it.
// It builds its own look, so it does not touch app.js or style.css.
(function () {
  var APK_URL = "https://github.com/wev2530/HeyVIX/releases/latest/download/VIX.apk";
  var STORE_KEY = "vix_app_prompt_until";
  var DAY = 24 * 60 * 60 * 1000;

  // Only on Android phones and tablets.
  if (!/Android/i.test(navigator.userAgent)) return;

  // Not again until the quiet period is over.
  try {
    var until = Number(localStorage.getItem(STORE_KEY) || 0);
    if (Date.now() < until) return;
  } catch (e) { /* storage blocked: show it anyway */ }

  function quiet(days) {
    try {
      localStorage.setItem(STORE_KEY, String(Date.now() + days * DAY));
    } catch (e) { /* ignore */ }
  }

  function el(tag, css, text) {
    var node = document.createElement(tag);
    if (css) node.style.cssText = css;
    if (text) node.textContent = text;
    return node;
  }

  function show() {
    if (document.getElementById("vix-app-prompt")) return;

    var scrim = el(
      "div",
      "position:fixed;inset:0;z-index:9999;display:flex;align-items:center;" +
        "justify-content:center;padding:24px;background:rgba(0,0,0,0.65);" +
        "padding-top:max(24px,env(safe-area-inset-top));" +
        "padding-bottom:max(24px,env(safe-area-inset-bottom));"
    );
    scrim.id = "vix-app-prompt";

    var card = el(
      "div",
      "width:100%;max-width:340px;box-sizing:border-box;padding:24px;" +
        "border-radius:24px;background:#1c1c24;color:#f5f5f8;text-align:center;" +
        "font-family:system-ui,-apple-system,Roboto,sans-serif;" +
        "box-shadow:0 12px 40px rgba(0,0,0,0.5);"
    );

    var logo = el("img", "width:64px;height:64px;margin:0 auto 12px;display:block;");
    logo.src = "icon.svg";
    logo.alt = "VIX AI";

    var title = el("div", "font-size:20px;font-weight:700;margin-bottom:8px;", "Get the VIX app");
    var body = el(
      "div",
      "font-size:14px;line-height:1.5;color:#a5a5af;margin-bottom:20px;",
      "The Android app runs better on your phone, and adds the Hey VIX voice " +
        "command, phone commands and the power button assistant."
    );

    var install = el(
      "a",
      "display:block;padding:14px;border-radius:26px;background:#ff3741;" +
        "color:#ffffff;font-size:16px;font-weight:700;text-decoration:none;",
      "Get the app"
    );
    install.href = APK_URL;
    install.setAttribute("download", "VIX.apk");

    var later = el(
      "button",
      "display:block;width:100%;margin-top:8px;padding:12px;border:0;" +
        "background:transparent;color:#a5a5af;font-size:14px;cursor:pointer;",
      "Not now"
    );
    later.type = "button";

    var have = el(
      "button",
      "display:block;width:100%;padding:8px;border:0;background:transparent;" +
        "color:#7d7d88;font-size:13px;cursor:pointer;",
      "I already have the app"
    );
    have.type = "button";

    var hint = el(
      "div",
      "margin-top:12px;font-size:11px;line-height:1.4;color:#7d7d88;",
      "When the download finishes, open it and tap Install. Android may ask you " +
        "to allow installs from your browser once."
    );

    function close() {
      scrim.remove();
    }

    install.addEventListener("click", function () {
      quiet(30);
      setTimeout(close, 400);
    });
    later.addEventListener("click", function () {
      quiet(7);
      close();
    });
    have.addEventListener("click", function () {
      quiet(365);
      close();
    });
    scrim.addEventListener("click", function (e) {
      if (e.target === scrim) {
        quiet(7);
        close();
      }
    });

    card.appendChild(logo);
    card.appendChild(title);
    card.appendChild(body);
    card.appendChild(install);
    card.appendChild(later);
    card.appendChild(have);
    card.appendChild(hint);
    scrim.appendChild(card);
    document.body.appendChild(scrim);
  }

  window.addEventListener("load", function () {
    setTimeout(show, 2500);
  });
})();